package local.bilimp4.app;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BiliClient {
    static final String SITE = "https://www.bilibili.com";
    static final String API = "https://api.bilibili.com";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";
    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>\"，。！？、；）】]+");
    private static final Pattern ID = Pattern.compile("(?<![\\w])(?:BV[A-Za-z0-9]{10}|(?i:av)[1-9][0-9]*)(?![\\w])");
    private static final Pattern VIDEO_PATH = Pattern.compile("/video/([^/]+)/?");
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final int[] WBI_ORDER = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52
    };

    record Quality(int id, String label, String codec, List<String> urls) {}
    record Streams(String title, String referer, long aid, long cid, int seconds, String cover,
                   List<Quality> video, List<String> audio) {}
    record Video(String title, String link, long cid) {
        boolean matches(Streams streams) {
            return streams != null && (cid > 0 ? cid == streams.cid() : link.equals(streams.referer()));
        }
    }
    record Collection(String key, String title, List<Video> videos) {}
    record Inspection(Streams streams, List<Collection> collections) {}

    static Streams resolve(String input, String cookie, ExportTask task) throws Exception {
        return resolve(videoLink(input, task), cookie, task, null);
    }

    static Inspection inspect(String input, String cookie, ExportTask task) throws Exception {
        Uri link = videoLink(input, task);
        if ("space.bilibili.com".equalsIgnoreCase(link.getHost())) {
            return new Inspection(null, java.util.Collections.singletonList(BiliCollections.load(link, cookie, task)));
        }
        List<Collection> collections = new ArrayList<>();
        Streams streams = resolve(link, cookie, task, collections);
        return new Inspection(streams, collections);
    }

    private static Streams resolve(Uri link, String cookie, ExportTask task, List<Collection> collections) throws Exception {
        Matcher path = VIDEO_PATH.matcher(link.getPath() == null ? "" : link.getPath());
        if (!path.matches() || !ID.matcher(path.group(1)).matches()) {
            throw new IOException("请输入普通投稿的 BV/AV 号或视频链接。");
        }
        String id = path.group(1);
        String idQuery = id.startsWith("BV") ? "bvid=" + id : "aid=" + id.substring(2);
        int part = 1;
        String partQuery = link.getQueryParameter("p");
        if (partQuery != null) {
            try {
                part = Integer.parseInt(partQuery);
            } catch (NumberFormatException error) {
                throw new IOException("链接中的分集编号无效。");
            }
        }
        JSONObject info = requireData(json(API + "/x/web-interface/view?" + idQuery, SITE, cookie, task), "获取视频信息");
        JSONArray pages = info.optJSONArray("pages");
        JSONObject page = pages == null ? null : pages.optJSONObject(part - 1);
        if (page == null) throw new IOException("没有找到链接指定的分集。");
        String bvid = info.getString("bvid");
        if (collections != null) BiliCollections.addRelated(info, pages, collections);
        String referer = SITE + "/video/" + bvid + "/?p=" + part;
        String title = info.getString("title");
        if (pages.length() > 1) title += " - P" + part + " " + page.optString("part");
        long aid = info.getLong("aid");
        long cid = page.getLong("cid");
        int seconds = page.optInt("duration", info.optInt("duration"));
        String cover = info.optString("pic");
        boolean loggedIn = cookie != null;

        Map<String, String> params = new TreeMap<>();
        params.put("bvid", bvid);
        params.put("cid", Long.toString(cid));
        params.put("qn", loggedIn ? "127" : "64");
        params.put("fnval", loggedIn ? "4048" : "16");
        params.put("fnver", "0");
        params.put("fourk", loggedIn ? "1" : "0");

        JSONObject playback = null;
        Exception failure = null;
        for (boolean signed : new boolean[]{loggedIn, !loggedIn}) {
            try {
                String endpoint = signed ? "/x/player/wbi/playurl?" + sign(params, cookie, task)
                        : "/x/player/playurl?" + query(params);
                playback = json(API + endpoint, referer, cookie, task);
                Streams streams = select(playback.optJSONObject("data"), title, referer, aid, cid, seconds, cover);
                if (streams != null) return streams;
            } catch (ExportTask.Cancelled cancelled) {
                throw cancelled;
            } catch (IOException | JSONException error) {
                failure = error;
            }
        }
        if (playback != null && playback.optInt("code", -1) != 0) requireData(playback, "获取播放地址");
        if (playback == null && failure != null) throw failure;
        throw new IOException("该链接没有可导出的 MP4 视频流。");
    }

    private static Uri videoLink(String input, ExportTask task) throws IOException {
        Matcher match = LINK.matcher(input);
        while (match.find()) {
            Uri link = Uri.parse(match.group()).buildUpon().scheme("https").build();
            String host = link.getHost();
            if ("b23.tv".equalsIgnoreCase(host)) return expand(link, task);
            if (isBiliHost(host) && link.getPath() != null && (link.getPath().startsWith("/video/")
                    || ("space.bilibili.com".equalsIgnoreCase(host) && BiliCollections.matches(link)))) {
                return link;
            }
        }
        Matcher id = ID.matcher(input);
        if (id.find()) return Uri.parse(SITE + "/video/" + id.group());
        throw new IOException("没有识别到 B 站视频链接。");
    }

    private static Uri expand(Uri link, ExportTask task) throws IOException {
        for (int redirects = 0; redirects < 5; redirects++) {
            task.check();
            HttpURLConnection connection = connect(link.toString(), SITE);
            connection.setInstanceFollowRedirects(false);
            String location;
            try {
                int status = connection.getResponseCode();
                location = connection.getHeaderField("Location");
                if (status < 300 || status >= 400 || location == null) break;
                link = Uri.parse(new URL(connection.getURL(), location).toString());
            } finally {
                connection.disconnect();
            }
            if (!"https".equals(link.getScheme())) break;
            if (isBiliHost(link.getHost())) return link;
            if (!"b23.tv".equalsIgnoreCase(link.getHost())) break;
        }
        throw new IOException("短链接无法展开。请粘贴完整的视频播放页链接。");
    }

    private static boolean isBiliHost(String host) {
        if (host == null) return false;
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals("bilibili.com") || lower.endsWith(".bilibili.com");
    }

    private static Streams select(JSONObject data, String title, String referer, long aid, long cid, int seconds, String cover) {
        if (data == null) return null;
        JSONObject dash = data.optJSONObject("dash");
        if (dash != null) {
            List<Quality> qualities = qualities(dash.optJSONArray("video"), data.optJSONArray("support_formats"));
            JSONObject audio = bestAudio(dash.optJSONArray("audio"));
            if (!qualities.isEmpty() && audio != null) {
                return new Streams(title, referer, aid, cid, seconds, cover, qualities, urls(audio));
            }
        }
        JSONArray progressive = data.optJSONArray("durl");
        if (progressive != null && progressive.length() == 1 && progressive.optJSONObject(0) != null) {
            List<String> urls = urls(progressive.optJSONObject(0));
            if (!urls.isEmpty()) {
                Quality quality = new Quality(data.optInt("quality"), "当前可用画质", "MP4", urls);
                return new Streams(title, referer, aid, cid, seconds, cover,
                        java.util.Collections.singletonList(quality), java.util.Collections.emptyList());
            }
        }
        return null;
    }

    private static List<Quality> qualities(JSONArray streams, JSONArray formats) {
        Map<Integer, String> labels = new HashMap<>();
        if (formats != null) {
            for (int i = 0; i < formats.length(); i++) {
                JSONObject format = formats.optJSONObject(i);
                if (format != null) labels.put(format.optInt("quality"), format.optString("new_description"));
            }
        }
        Map<Integer, Quality> byId = new TreeMap<>(Comparator.reverseOrder());
        if (streams != null) {
            for (int i = 0; i < streams.length(); i++) {
                JSONObject stream = streams.optJSONObject(i);
                if (stream == null) continue;
                String codec = videoCodec(stream.optString("codecs"));
                List<String> addresses = urls(stream);
                if (codec == null || addresses.isEmpty()) continue;
                int id = stream.optInt("id");
                Quality previous = byId.get(id);
                if (previous != null && (!"H.264".equals(codec) || "H.264".equals(previous.codec()))) continue;
                String label = labels.get(id);
                if (label == null || label.isEmpty()) label = stream.optInt("height") + "p";
                byId.put(id, new Quality(id, label, codec, addresses));
            }
        }
        return new ArrayList<>(byId.values());
    }

    private static String videoCodec(String codec) {
        if (codec.startsWith("avc")) return "H.264";
        if (codec.startsWith("hev") || codec.startsWith("hvc")) return "HEVC";
        if (codec.startsWith("av01")) return "AV1";
        return null;
    }

    private static JSONObject bestAudio(JSONArray streams) {
        if (streams == null) return null;
        JSONObject selected = null;
        long bestScore = -1;
        for (int i = 0; i < streams.length(); i++) {
            JSONObject stream = streams.optJSONObject(i);
            if (stream == null || !stream.optString("codecs").startsWith("mp4a") || urls(stream).isEmpty()) continue;
            long bandwidth = stream.optLong("bandwidth");
            if (bandwidth > bestScore) {
                selected = stream;
                bestScore = bandwidth;
            }
        }
        return selected;
    }

    private static List<String> urls(JSONObject stream) {
        Set<String> urls = new LinkedHashSet<>();
        addUrl(urls, stream.optString("baseUrl", stream.optString("base_url", stream.optString("url"))));
        JSONArray backups = stream.optJSONArray("backupUrl");
        if (backups == null) backups = stream.optJSONArray("backup_url");
        if (backups != null) {
            for (int i = 0; i < backups.length(); i++) addUrl(urls, backups.optString(i));
        }
        List<String> ranked = new ArrayList<>(urls);
        ranked.sort(Comparator.comparing(BiliClient::isPeerCdn));
        return ranked;
    }

    private static void addUrl(Set<String> urls, String url) {
        if (url.startsWith("http://")) url = "https://" + url.substring(7);
        if (url.startsWith("https://")) urls.add(url);
    }

    private static boolean isPeerCdn(String address) {
        String host = Uri.parse(address).getHost();
        return host != null && (host.endsWith(".mcdn.bilivideo.cn") || host.endsWith(".edge.mountaintoys.cn"));
    }

    static JSONObject json(String url, String referer, String cookie, ExportTask task) throws IOException, JSONException {
        if (task != null) task.check();
        HttpURLConnection connection = connect(url, referer);
        connection.setInstanceFollowRedirects(false);
        if (cookie != null && "api.bilibili.com".equals(connection.getURL().getHost())) {
            connection.setRequestProperty("Cookie", cookie);
        }
        try {
            return readJson(connection, task);
        } finally {
            connection.disconnect();
        }
    }

    static JSONObject readJson(HttpURLConnection connection, ExportTask task) throws IOException, JSONException {
        connection.setRequestProperty("Accept", "application/json");
        int status = connection.getResponseCode();
        if (status != 200) throw new IOException("B 站接口返回 HTTP " + status + "。");
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                if (task != null) task.check();
                if (data.size() + length > 2 * 1024 * 1024) throw new IOException("B 站接口响应过大。");
                data.write(buffer, 0, length);
            }
            return new JSONObject(data.toString(StandardCharsets.UTF_8.name()));
        }
    }

    static JSONObject requireData(JSONObject json, String action) throws IOException {
        JSONObject data = json.optJSONObject("data");
        int code = json.optInt("code", -1);
        if (code == 0 && data != null) return data;
        String message = json.optString("message", "接口未返回数据");
        if (code == -352 || code == -412) message = "请求被 B 站风控拦截";
        if (code == -101) message = "此视频需要登录";
        throw new IOException(action + "失败，" + message + "（" + code + "）。");
    }

    // WBI parameter signing follows the protocol used by Mimi Downloader.
    private static String sign(Map<String, String> params, String cookie, ExportTask task) throws Exception {
        JSONObject nav = json(API + "/x/web-interface/nav", SITE, cookie, task);
        JSONObject data = nav.optJSONObject("data");
        JSONObject wbi = data == null ? null : data.optJSONObject("wbi_img");
        if (wbi == null) throw new IOException("无法获取 WBI 签名参数。");
        String combined = key(wbi.getString("img_url")) + key(wbi.getString("sub_url"));
        if (combined.length() < 64) throw new IOException("WBI 签名参数无效。");
        StringBuilder mix = new StringBuilder();
        for (int i = 0; i < 32; i++) mix.append(combined.charAt(WBI_ORDER[i]));
        Map<String, String> timed = new TreeMap<>(params);
        timed.put("wts", Long.toString(System.currentTimeMillis() / 1000));
        String query = query(timed);
        byte[] hash = MessageDigest.getInstance("MD5").digest((query + mix).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte value : hash) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return query + "&w_rid=" + hex;
    }

    private static String key(String url) {
        String file = Uri.parse(url).getLastPathSegment();
        return file == null ? "" : file.split("\\.", 2)[0];
    }

    private static String query(Map<String, String> params) {
        Uri.Builder builder = new Uri.Builder();
        params.forEach(builder::appendQueryParameter);
        return builder.build().getEncodedQuery();
    }

    static HttpURLConnection connect(String address, String referer) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Referer", referer);
        return connection;
    }

    static void download(List<String> urls, String referer, File file, String stage, ExportTask task)
            throws IOException {
        IOException lastError = new IOException("没有可用的下载地址。");
        for (String url : urls) {
            truncate(file);
            for (int attempt = 0; attempt < 3; attempt++) {
                task.check();
                try {
                    downloadOnce(url, referer, file, stage, task);
                    return;
                } catch (ExportTask.Cancelled cancelled) {
                    throw cancelled;
                } catch (IOException error) {
                    lastError = error;
                    if (file.length() == 0) break;
                }
            }
        }
        throw new IOException(stage + "失败，" + lastError.getMessage(), lastError);
    }

    private static void truncate(File file) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.getChannel().truncate(0);
        }
    }

    private static void downloadOnce(String url, String referer, File file, String stage, ExportTask task)
            throws IOException {
        long offset = file.length();
        HttpURLConnection connection = connect(url, referer);
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Range", "bytes=" + offset + "-");
        try {
            int status = connection.getResponseCode();
            long total = connection.getContentLengthLong();
            if (status == 206) {
                String range = connection.getHeaderField("Content-Range");
                Matcher match = CONTENT_RANGE.matcher(range == null ? "" : range);
                if (!match.matches() || Long.parseLong(match.group(1)) != offset) {
                    throw new IOException("服务器返回的续传位置无效。");
                }
                total = Long.parseLong(match.group(3));
            } else if (status == 200) {
                offset = 0;
            } else {
                throw new IOException("下载服务器返回 HTTP " + status + "。");
            }
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(file, offset > 0)) {
                ExportTask.copy(input, output, offset, total, stage, task);
            }
            if (file.length() == 0 || (total >= 0 && file.length() != total)) {
                throw new IOException("文件传输不完整。");
            }
        } finally {
            connection.disconnect();
        }
    }
}
