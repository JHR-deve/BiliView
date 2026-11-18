package local.bilimp4.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;

final class AppUpdate {
    static final String PAGE = "https://github.com/JHR-deve/BiliView/releases";
    private static final String LATEST = "https://api.github.com/repos/JHR-deve/BiliView/releases/latest";
    private static final Set<String> FEED_HOSTS = Set.of("api.github.com");
    private static final Set<String> DOWNLOAD_HOSTS = Set.of(
            "github.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com");
    private static final int MAX_FEED_BYTES = 256 * 1024;

    record Release(String version, String notes, String apkUrl, long apkSize, String digest, String page) {
        boolean hasApk() {
            return apkUrl != null && !apkUrl.isEmpty();
        }
    }

    interface Progress {
        void update(int percent, long done, long total);
    }

    static Release latest() throws IOException {
        Release api = apiRelease();
        Release tag = tagRelease();
        if (api == null && tag == null) {
            throw new IOException("暂时无法获取发布记录，请检查网络后重试。");
        }
        if (api == null) return tag;
        if (tag == null) return api;
        return compare(tag.version(), api.version()) > 0
                ? new Release(tag.version(), api.notes(), tag.apkUrl(), 0, null, tag.page())
                : api;
    }

    private static Release apiRelease() {
        try {
            JSONObject json = fetch(LATEST);
            String version = version(json.optString("tag_name", json.optString("name")));
            if (version.isEmpty()) return null;
            String page = json.optString("html_url", PAGE);
            String apk = null;
            long size = 0;
            String digest = null;
            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) continue;
                    String name = asset.optString("name");
                    if (!name.toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
                    String url = asset.optString("browser_download_url");
                    if (!secure(url, DOWNLOAD_HOSTS)) continue;
                    apk = url;
                    size = asset.optLong("size");
                    digest = asset.optString("digest", null);
                    break;
                }
            }
            return new Release(version, json.optString("body", "").trim(), apk, size, digest, page);
        } catch (Exception error) {
            return null;
        }
    }

    // GitHub 的接口对未登录请求限流，这里改用发布页的重定向来确定最新版本。
    static Release tagRelease() throws IOException {
        String address = PAGE + "/latest";
        if (!secure(address, DOWNLOAD_HOSTS)) throw new IOException("更新地址无效。");
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("User-Agent", "BiliView/" + BuildConfig.VERSION_NAME);
        try {
            int status = connection.getResponseCode();
            String location = connection.getHeaderField("Location");
            if (status < 300 || status >= 400 || location == null) {
                throw new IOException("暂时无法获取发布记录，请稍后重试。");
            }
            String tag = location.substring(location.lastIndexOf('/') + 1);
            String version = version(tag);
            if (version.isEmpty() || !secure(location, DOWNLOAD_HOSTS)) {
                throw new IOException("发布记录里没有可识别的版本号。");
            }
            return new Release(version, "", apkAddress(tag, version), 0, null, location);
        } finally {
            connection.disconnect();
        }
    }

    private static String apkAddress(String tag, String version) {
        return PAGE + "/download/" + tag + "/BiliView-" + version + "-debug.apk";
    }
    static boolean newer(String remote) {
        return compare(remote, BuildConfig.VERSION_NAME) > 0;
    }

    static File download(Context context, Release release, Progress progress) throws IOException {
        if (!release.hasApk()) throw new IOException("这个版本没有提供安装包。");
        HttpURLConnection connection = (HttpURLConnection) new URL(release.apkUrl()).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept", "application/octet-stream");
        connection.setRequestProperty("User-Agent", "BiliView/" + BuildConfig.VERSION_NAME);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("下载安装包失败，服务器返回 HTTP " + status + "。");
            }
            File directory = new File(context.getCacheDir(), "updates");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建更新目录。");
            File file = new File(directory, "BiliView-" + release.version() + ".apk");
            long total = connection.getContentLengthLong() > 0
                    ? connection.getContentLengthLong() : release.apkSize();
            MessageDigest sha = sha256();
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                long done = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    sha.update(buffer, 0, count);
                    done += count;
                    progress.update(total > 0 ? (int) Math.min(100, done * 100 / total) : -1, done, total);
                }
            }
            String actual = hex(sha.digest());
            String expected = release.digest();
            if (expected != null && expected.startsWith("sha256:")
                    && !actual.equalsIgnoreCase(expected.substring(7))) {
                file.delete();
                throw new IOException("安装包校验失败，请稍后重试。");
            }
            return file;
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject fetch(String address) throws IOException, JSONException {
        if (!secure(address, FEED_HOSTS)) throw new IOException("更新地址无效。");
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "BiliView/" + BuildConfig.VERSION_NAME);
        try {
            int status = connection.getResponseCode();
            if (status == 404) throw new IOException("还没有发布过可用的版本。");
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("暂时无法获取发布记录，服务器返回 HTTP " + status + "。");
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_FEED_BYTES) throw new IOException("发布记录过大。");
                    output.write(buffer, 0, count);
                }
                return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static boolean secure(String address, Set<String> hosts) {
        if (address == null || address.isEmpty()) return false;
        try {
            URI uri = URI.create(address);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getUserInfo() == null
                    && hosts.contains(String.valueOf(uri.getHost()).toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    static String version(String tag) {
        String clean = tag == null ? "" : tag.trim().replaceFirst("^[vV]", "");
        StringBuilder digits = new StringBuilder();
        for (char letter : clean.toCharArray()) {
            if (Character.isDigit(letter) || letter == '.') digits.append(letter);
            else break;
        }
        String result = digits.toString().replaceAll("\\.+$", "");
        return result.matches("\\d+(\\.\\d+)*") ? result : "";
    }

    static int compare(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? number(a[i]) : 0;
            int y = i < b.length ? number(b[i]) : 0;
            if (x != y) return x > y ? 1 : -1;
        }
        return 0;
    }

    private static int number(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IOException("当前系统不支持安装包校验。", error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) text.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return text.toString();
    }
}