package local.bilimp4.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class BiliFavorites {
    private static final String REFERER = BiliClient.SITE + "/";
    private static final int PAGE_SIZE = 20;
    private static final int MAX_ITEMS = 500;

    record Folder(long id, String title, int count, boolean secret) {}

    record Bangumi(long seasonId, String title, String cover, String progress) {
        String link() {
            return BiliClient.SITE + "/bangumi/play/ss" + seasonId;
        }
    }

    record Entry(String title, String author, String link, String cover, int seconds) {}

    record Media(List<Entry> entries, int total) {}

    static long mid(String cookie) throws IOException {
        if (cookie == null) throw new IOException("登录后可读取收藏夹。");
        for (String part : cookie.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2 || !"DedeUserID".equals(pair[0])) continue;
            try {
                return Long.parseLong(pair[1].trim());
            } catch (NumberFormatException ignored) {
                break;
            }
        }
        throw new IOException("登录状态不完整，请重新扫码登录。");
    }

    static List<Folder> folders(String cookie, ExportTask task) throws IOException, JSONException {
        long mid = mid(cookie);
        JSONObject data = data(BiliClient.json(BiliClient.API
                + "/x/v3/fav/folder/created/list-all?up_mid=" + mid, REFERER, cookie, task), "读取收藏夹");
        JSONArray list = data == null ? null : data.optJSONArray("list");
        List<Folder> folders = new ArrayList<>();
        if (list == null) return folders;
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null || item.optLong("id") <= 0) continue;
            folders.add(new Folder(item.optLong("id"), item.optString("title", "未命名收藏夹"),
                    item.optInt("media_count"), (item.optInt("attr") & 1) == 1));
        }
        return folders;
    }

    static Media media(long id, String keyword, String cookie, ExportTask task)
            throws IOException, JSONException {
        List<Entry> entries = new ArrayList<>();
        int total = 0;
        boolean more = true;
        for (int page = 1; more && page <= MAX_ITEMS / PAGE_SIZE; page++) {
            if (task != null) {
                task.progress(entries.isEmpty() ? "正在读取收藏夹内容" : "已读取 " + entries.size() + " 项", -1);
            }
            JSONObject data = data(BiliClient.json(BiliClient.API + "/x/v3/fav/resource/list?media_id=" + id
                    + "&pn=" + page + "&ps=" + PAGE_SIZE + "&platform=web"
                    + (keyword == null || keyword.isEmpty() ? "" : "&keyword=" + android.net.Uri.encode(keyword)),
                    REFERER, cookie, task), "读取收藏夹内容");
            more = data != null && data.optBoolean("has_more");
            JSONObject info = data == null ? null : data.optJSONObject("info");
            if (info != null) total = info.optInt("media_count");
            JSONArray medias = data == null ? null : data.optJSONArray("medias");
            if (medias == null || medias.length() == 0) break;
            for (int i = 0; i < medias.length(); i++) {
                JSONObject item = medias.optJSONObject(i);
                if (item == null || item.optInt("type") != 2) continue;
                String bvid = item.optString("bvid", item.optString("bv_id"));
                if (bvid.isEmpty()) continue;
                JSONObject upper = item.optJSONObject("upper");
                entries.add(new Entry(item.optString("title", "收藏的视频"),
                        upper == null ? "" : upper.optString("name"), BiliClient.SITE + "/video/" + bvid,
                        item.optString("cover"), item.optInt("duration")));
            }
        }
        if (total < entries.size()) total = entries.size();
        return new Media(entries, total);
    }

    static List<Bangumi> bangumi(int type, String cookie, ExportTask task) throws IOException, JSONException {
        long mid = mid(cookie);
        JSONObject data = data(BiliClient.json(BiliClient.API + "/x/space/bangumi/follow/list?type=" + type
                + "&follow_status=0&pn=1&ps=30&vmid=" + mid, REFERER, cookie, task),
                type == 1 ? "读取追番" : "读取追剧");
        JSONArray list = data == null ? null : data.optJSONArray("list");
        List<Bangumi> items = new ArrayList<>();
        if (list == null) return items;
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null || item.optLong("season_id") <= 0) continue;
            JSONObject episode = item.optJSONObject("new_ep");
            items.add(new Bangumi(item.optLong("season_id"), item.optString("title", "未命名"),
                    item.optString("cover"), episode == null ? "" : episode.optString("index_show")));
        }
        return items;
    }

    private static JSONObject data(JSONObject response, String action) throws IOException, BiliAccount.Expired {
        int code = response.optInt("code", -1);
        if (code == -101) throw new BiliAccount.Expired();
        String message = response.optString("message", "接口未返回数据");
        if (code == -352 || code == -412) message = "请求被 B 站风控拦截";
        if (code != 0) throw new IOException(action + "失败，" + message + "（" + code + "）。");
        return response.optJSONObject("data");
    }
}