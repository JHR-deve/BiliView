package local.bilimp4.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Reads a Bilibili member card and recent uploads from the official API. */
final class BiliProfileClient {
    private static volatile String device;
    private static volatile long deviceAt;

    record Profile(String name, String face, String sign, int level, String sex, long fans, long following,
                   String vipLabel, String official, String banner) {}

    record Video(String title, String bvid, String cover, String duration, long play, String created) {}

    static Profile load(long mid, String cookie, ExportTask task) throws Exception {
        JSONObject root = BiliClient.json(BiliClient.API + "/x/web-interface/card?mid=" + mid,
                BiliClient.SITE, cookie, task);
        if (root.optInt("code", -1) != 0) throw new IOException(message(root, "请求频繁，请稍后重试"));
        JSONObject data = root.optJSONObject("data");
        JSONObject card = data == null ? null : data.optJSONObject("card");
        if (card == null) throw new IOException("请求频繁，请稍后重试");

        long fans = card.optLong("fans");
        long following = card.optLong("friend");
        try {
            JSONObject relation = BiliClient.json(BiliClient.API + "/x/relation/stat?vmid=" + mid,
                    BiliClient.SITE, cookie, task);
            JSONObject stat = relation.optJSONObject("data");
            if (relation.optInt("code", -1) == 0 && stat != null) {
                fans = stat.optLong("follower", fans);
                following = stat.optLong("following", following);
            }
        } catch (Exception ignored) {
            // The card values remain usable when the relation endpoint is limited.
        }

        JSONObject level = card.optJSONObject("level_info");
        JSONObject vip = card.optJSONObject("vip");
        JSONObject label = vip == null ? null : vip.optJSONObject("label");
        JSONObject official = card.optJSONObject("Official");
        if (official == null) official = card.optJSONObject("official_verify");
        String banner = "";
        JSONObject space = data.optJSONObject("space");
        if (space != null) banner = firstNonEmpty(space.optString("l_img"), space.optString("s_img"));

        return new Profile(
                fallback(card.optString("name"), "UID " + mid),
                normalize(card.optString("face")),
                card.optString("sign"),
                level == null ? 0 : level.optInt("current_level"),
                fallback(card.optString("sex"), "保密"),
                fans, following,
                label == null ? "" : label.optString("text"),
                official == null ? "" : firstNonEmpty(official.optString("title"), official.optString("desc")),
                normalize(banner));
    }

    static List<Video> videos(long mid, String cookie, ExportTask task, int size) throws Exception {
        Map<String, String> params = new TreeMap<>();
        params.put("mid", Long.toString(mid));
        params.put("pn", "1");
        params.put("ps", Integer.toString(size));
        params.put("order", "pubdate");
        String query = BiliClient.sign(params, merged(cookie, task), task);
        JSONObject root = BiliClient.json(BiliClient.API + "/x/space/wbi/arc/search?" + query,
                BiliClient.SITE, merged(cookie, task), task);
        if (root.optInt("code", -1) != 0) throw new IOException(message(root, "读取投稿列表失败。"));
        JSONObject data = root.optJSONObject("data");
        JSONObject list = data == null ? null : data.optJSONObject("list");
        JSONArray rows = list == null ? null : list.optJSONArray("vlist");
        List<Video> items = new ArrayList<>();
        if (rows == null) return items;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String title = row.optString("title");
            if (title.isEmpty()) continue;
            items.add(new Video(title, row.optString("bvid"), normalize(row.optString("pic")),
                    row.optString("length"), row.optLong("play"), timestamp(row.optLong("created"))));
        }
        return items;
    }

    record Member(long mid, String name, String face, String sign) {}

    static List<Member> relation(String kind, long mid, String cookie, ExportTask task, int page) throws Exception {
        String path = "fans".equals(kind) ? "/x/relation/fans" : "/x/relation/followings";
        // 关系接口同样要求设备指纹 cookie，只带登录态会被判成异常请求。
        String full = merged(cookie, task);
        String query = "vmid=" + mid + "&pn=" + page + "&ps=50&order=desc";
        JSONObject root = BiliClient.json(BiliClient.API + path + "?" + query, BiliClient.SITE, full, task);
        int code = root.optInt("code", -1);
        if (code == -352 || code == -412) {
            // 部分账号的关系接口已经要求 WBI 签名，这里再签一次重试。
            Map<String, String> params = new TreeMap<>();
            params.put("vmid", Long.toString(mid));
            params.put("pn", Integer.toString(page));
            params.put("ps", "50");
            params.put("order", "desc");
            String signed;
            try {
                signed = BiliClient.sign(params, full, task);
            } catch (Exception ignored) {
                throw new IOException(relationFailure(code, kind));
            }
            root = BiliClient.json(BiliClient.API + path + "?" + signed, BiliClient.SITE, full, task);
            code = root.optInt("code", -1);
        }
        if (code != 0) throw new IOException(relationFailure(code, kind));
        JSONObject data = root.optJSONObject("data");
        JSONArray rows = data == null ? null : data.optJSONArray("list");
        List<Member> items = new ArrayList<>();
        if (rows == null) return items;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            long id = row.optLong("mid");
            if (id <= 0) continue;
            items.add(new Member(id, fallback(row.optString("uname"), "UID " + id),
                    normalize(row.optString("face")), row.optString("sign")));
        }
        return items;
    }

    private static String relationFailure(int code, String kind) {
        boolean fans = "fans".equals(kind);
        return "对方开启了隐私设置，" + (fans ? "粉丝" : "关注") + "列表不可见。";
    }

    /** The WBI endpoints require the device fingerprint cookies besides the signature. */
    private static String merged(String cookie, ExportTask task) throws Exception {
        String base = deviceCookie(task);
        if (cookie == null || cookie.isEmpty()) return base;
        return base + "; " + cookie;
    }

    private static String deviceCookie(ExportTask task) throws Exception {
        String cached = device;
        if (cached != null && System.currentTimeMillis() - deviceAt < 12 * 3600_000L) return cached;
        JSONObject root = BiliClient.json(BiliClient.API + "/x/frontend/finger/spi",
                BiliClient.SITE, null, task);
        JSONObject data = root.optJSONObject("data");
        if (data == null || data.optString("b_3").isEmpty()) throw new IOException("请求频繁，请稍后重试");
        String value = "buvid3=" + data.optString("b_3") + "; buvid4=" + data.optString("b_4")
                + "; b_nut=" + (System.currentTimeMillis() / 1000);
        device = value;
        deviceAt = System.currentTimeMillis();
        return value;
    }

    private static String message(JSONObject root, String fallback) {
        String text = root.optString("message");
        return text == null || text.isEmpty() ? fallback : fallback + "（" + root.optInt("code") + "）";
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }

    private static String fallback(String value, String other) {
        return value == null || value.isEmpty() ? other : value;
    }

    private static String normalize(String url) {
        return url == null ? "" : url.replace("http://", "https://");
    }

    private static String timestamp(long seconds) {
        if (seconds <= 0) return "";
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date(seconds * 1000));
    }
}