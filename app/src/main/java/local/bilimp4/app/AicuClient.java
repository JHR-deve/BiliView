package local.bilimp4.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Reads comment and danmaku history from aicu.cc through the WebView bridge. */
final class AicuClient {
    private static final String REPLY = "https://api.aicu.cc/api/v3/search/getreply";
    private static final String QUEUE = "https://api.aicu.cc/api/v4/queue/enqueue";
    private static final String VIDEO_DM = "https://api.aicu.cc/api/v4/search/getvideodm";
    private static final String LIVE_DM = "https://api.aicu.cc/api/v4/search/getlivedm";
    // aicu 的 WAF 对 ps=50 直接返回 403，这里固定 30。
    private static final int DANMAKU_SIZE = 30;

    record Reply(String message, String time, int rank) {}

    record ReplyPage(List<Reply> list, long total) {}

    record Danmaku(String time, String content, String extra, String user) {}

    record DanmakuPage(List<Danmaku> list, long total) {}

    static void replies(AicuBridge bridge, long uid, Consumer<ReplyPage> ok, Consumer<String> fail) {
        bridge.get(REPLY + "?uid=" + uid + "&pn=1&ps=100&mode=0&keyword=", body -> {
            try {
                JSONObject root = new JSONObject(body);
                if (root.optInt("code", -1) != 0) {
                    fail.accept(reason(root.optInt("code", 0)));
                    return;
                }
                JSONObject data = root.optJSONObject("data");
                if (data != null && data.optJSONArray("replies") == null) {
                    JSONObject nested = data.optJSONObject("data");
                    if (nested != null) data = nested;
                }
                List<Reply> items = new ArrayList<>();
                long total = 0;
                if (data != null) {
                    JSONObject cursor = data.optJSONObject("cursor");
                    if (cursor != null) total = cursor.optLong("all_count");
                    JSONArray rows = data.optJSONArray("replies");
                    if (rows != null) {
                        for (int i = 0; i < rows.length(); i++) {
                            JSONObject row = rows.optJSONObject(i);
                            if (row == null) continue;
                            String message = row.optString("message");
                            if (message.isEmpty()) continue;
                            items.add(new Reply(message, time(row.optLong("time")), row.optInt("rank")));
                        }
                    }
                }
                ok.accept(new ReplyPage(items, total));
            } catch (Exception error) {
                fail.accept("请求频繁，请稍后重试");
            }
        }, fail);
    }

    static void danmaku(AicuBridge bridge, long uid, boolean live, Consumer<DanmakuPage> ok, Consumer<String> fail) {
        bridge.post(QUEUE, body -> {
            String ticket = "";
            try {
                JSONObject data = new JSONObject(body).optJSONObject("data");
                if (data != null) ticket = data.optString("ticket");
            } catch (Exception ignored) {
                // Fall through to the empty-ticket message.
            }
            if (ticket.isEmpty()) {
                fail.accept("请求频繁，请稍后重试");
                return;
            }
            String url = (live ? LIVE_DM : VIDEO_DM) + "?uid=" + uid + "&pn=1&ps=" + DANMAKU_SIZE
                    + "&mode=0&keyword=&need_count=true&ticket=" + ticket;
            bridge.get(url, payload -> {
                try {
                    onDanmaku(new JSONObject(payload), live, ok, fail);
                } catch (Exception error) {
                    fail.accept("请求频繁，请稍后重试");
                }
            }, fail);
        }, fail);
    }

    private static void onDanmaku(JSONObject root, boolean live, Consumer<DanmakuPage> ok, Consumer<String> fail) {
        if (root.optInt("code", -1) != 0) {
            fail.accept(reason(root.optInt("code", 0)));
            return;
        }
        JSONObject data = root.optJSONObject("data");
        List<Danmaku> items = new ArrayList<>();
        long total = 0;
        if (data != null) {
            JSONObject cursor = data.optJSONObject("cursor");
            if (cursor != null) total = cursor.optLong("all_count");
            if (live) {
                JSONArray rooms = data.optJSONArray("list");
                for (int i = 0; rooms != null && i < rooms.length(); i++) {
                    JSONObject room = rooms.optJSONObject(i);
                    if (room == null) continue;
                    JSONObject info = room.optJSONObject("roominfo");
                    String extra = "";
                    if (info != null) extra = firstNonEmpty(info.optString("roomname"), info.optString("upname"));
                    JSONArray rows = room.optJSONArray("danmu");
                    for (int j = 0; rows != null && j < rows.length(); j++) {
                        JSONObject row = rows.optJSONObject(j);
                        if (row == null) continue;
                        items.add(new Danmaku(time(row.optLong("ts")), row.optString("text"), extra,
                                row.optString("uname")));
                    }
                }
            } else {
                JSONArray rows = data.optJSONArray("videodmlist");
                for (int i = 0; rows != null && i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    long seconds = row.optLong("progress") / 1000;
                    String extra = String.format(Locale.CHINA, "视频进度 %02d:%02d", seconds / 60, seconds % 60);
                    items.add(new Danmaku(time(row.optLong("ctime")), row.optString("content"), extra, ""));
                }
            }
        }
        ok.accept(new DanmakuPage(items, total));
    }

    private static String reason(int code) {
        return "请求频繁，请稍后重试";
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }

    private static String time(long seconds) {
        if (seconds <= 0) return "时间未知";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(seconds * 1000));
    }
}