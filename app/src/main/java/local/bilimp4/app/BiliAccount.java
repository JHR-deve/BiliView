package local.bilimp4.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class BiliAccount {
    private static final String PASSPORT = "https://passport.bilibili.com/x/passport-login/web/qrcode/";
    private static final String KEY_ALIAS = "bili-account-v1";
    private static final String[] SESSION_KEYS = {"SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid"};
    private static final Pattern COOKIE_VALUE = Pattern.compile("[\\x21-\\x7e&&[^;\"\\\\]]{1,4096}");

    record Profile(String name, long uid, String avatar, int level, boolean vip) {}
    record Qr(String url, String key) {}

    static final class Expired extends IOException {
        Expired() {
            super("B 站登录已失效，请重新扫码。");
        }
    }

    static final class Poll {
        final int code;
        final String cookie;

        Poll(int code, String cookie) {
            this.code = code;
            this.cookie = cookie;
        }
    }

    static Qr generate() throws IOException, JSONException {
        JSONObject response = BiliClient.json(PASSPORT + "generate", BiliClient.SITE, null, null);
        JSONObject data = payload(response);
        String url = data.getString("url");
        if (!trustedLoginUrl(new URL(url))) throw new IOException("B 站返回了无效的登录地址。");
        return new Qr(url, data.getString("qrcode_key"));
    }

    static Poll poll(Qr qr) throws IOException, JSONException {
        HttpURLConnection connection = BiliClient.connect(PASSPORT + "poll?qrcode_key=" + Uri.encode(qr.key()),
                BiliClient.SITE);
        connection.setInstanceFollowRedirects(false);
        try {
            JSONObject data = payload(BiliClient.readJson(connection, null));
            int status = data.getInt("code");
            if (status != 0) return new Poll(status, null);
            Map<String, String> cookies = new LinkedHashMap<>();
            collectCookies(connection, cookies);
            String redirect = data.optString("url");
            if (!redirect.isEmpty() && !cookies.containsKey("SESSDATA")) {
                exchange(new URL(redirect), cookies);
            }
            if (!cookies.containsKey("SESSDATA")) throw new IOException("B 站没有返回可用的登录会话。");
            return new Poll(0, cookieHeader(cookies));
        } finally {
            connection.disconnect();
        }
    }

    static Profile profile(String cookie) throws IOException, JSONException {
        JSONObject response = BiliClient.json(BiliClient.API + "/x/web-interface/nav", BiliClient.SITE, cookie, null);
        if (response.optInt("code") == -101) throw new Expired();
        JSONObject data = payload(response);
        if (!data.optBoolean("isLogin")) throw new Expired();
        JSONObject level = data.optJSONObject("level_info");
        JSONObject vip = data.optJSONObject("vip");
        return new Profile(data.getString("uname"), data.getLong("mid"), data.optString("face"),
                level == null ? 0 : level.optInt("current_level"),
                data.optInt("vipStatus") == 1 || (vip != null && vip.optInt("status") == 1));
    }

    private static JSONObject payload(JSONObject response) throws IOException {
        JSONObject data = response.optJSONObject("data");
        if (response.optInt("code", -1) != 0 || data == null) {
            throw new IOException("B 站登录接口暂不可用（" + response.optInt("code", -1) + "）。");
        }
        return data;
    }

    private static void exchange(URL url, Map<String, String> cookies) throws IOException {
        for (int i = 0; i < 6; i++) {
            if (!trustedLoginUrl(url)) throw new IOException("B 站返回了无效的登录跳转。");
            Uri link = Uri.parse(url.toString());
            for (String key : SESSION_KEYS) add(cookies, key, link.getQueryParameter(key));
            if (cookies.containsKey("SESSDATA")) return;
            HttpURLConnection connection = BiliClient.connect(url.toString(), BiliClient.SITE);
            connection.setInstanceFollowRedirects(false);
            try {
                int status = connection.getResponseCode();
                collectCookies(connection, cookies);
                if (cookies.containsKey("SESSDATA")) return;
                String location = connection.getHeaderField("Location");
                if (status < 300 || status >= 400 || location == null) return;
                url = new URL(url, location);
            } finally {
                connection.disconnect();
            }
        }
    }

    private static boolean trustedLoginUrl(URL url) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return "https".equals(url.getProtocol()) && url.getUserInfo() == null
                && (url.getPort() == -1 || url.getPort() == 443)
                && (host.equals("bilibili.com") || host.endsWith(".bilibili.com")
                || host.equals("biligame.com") || host.endsWith(".biligame.com"));
    }

    private static void collectCookies(HttpURLConnection connection, Map<String, String> cookies) {
        for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
            if (!"Set-Cookie".equalsIgnoreCase(header.getKey())) continue;
            for (String value : header.getValue()) {
                int separator = value.indexOf(';');
                String pair = separator < 0 ? value : value.substring(0, separator);
                int equals = pair.indexOf('=');
                if (equals < 1) continue;
                String key = pair.substring(0, equals).trim();
                for (String allowed : SESSION_KEYS) {
                    if (allowed.equals(key)) add(cookies, key, pair.substring(equals + 1).trim());
                }
            }
        }
    }

    private static void add(Map<String, String> cookies, String key, String value) {
        if (value == null || !COOKIE_VALUE.matcher(value).matches()) return;
        cookies.put(key, value.replace(",", "%2C"));
    }

    private static String cookieHeader(Map<String, String> cookies) {
        StringJoiner header = new StringJoiner("; ");
        cookies.forEach((key, value) -> header.add(key + "=" + value));
        return header.toString();
    }

    static synchronized String restore(Context context) throws Exception {
        SharedPreferences preferences = storage(context);
        String encrypted = preferences.getString("data", null);
        String iv = preferences.getString("iv", null);
        if (encrypted == null || iv == null) return null;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    static synchronized void save(Context context, String cookie) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(cookie.getBytes(StandardCharsets.UTF_8));
        if (!storage(context).edit()
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            throw new IOException("无法保存 B 站登录会话。");
        }
    }

    static synchronized void clear(Context context) {
        storage(context).edit().clear().apply();
    }

    private static SharedPreferences storage(Context context) {
        return context.getSharedPreferences("bili_account", Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
