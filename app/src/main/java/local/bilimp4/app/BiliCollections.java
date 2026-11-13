package local.bilimp4.app;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BiliCollections {
    private static final Pattern SPACE = Pattern.compile("/([1-9][0-9]*)/(?:lists/([1-9][0-9]*)|channel/(collectiondetail|seriesdetail))/?");
    private static final int PAGE_SIZE = 30;

    static boolean matches(Uri uri) {
        return uri.getPath() != null && SPACE.matcher(uri.getPath()).matches();
    }

    static BiliClient.Collection load(Uri uri, String cookie, ExportTask task) throws IOException, JSONException {
        String path = uri.getPath();
        Matcher match = SPACE.matcher(path == null ? "" : path);
        if (!match.matches()) throw new IOException("请输入有效的 B 站合集或系列链接。");
        String sid = match.group(2) == null ? uri.getQueryParameter("sid") : match.group(2);
        if (sid == null || !sid.matches("[1-9][0-9]*")) throw new IOException("合集链接缺少有效的编号。");
        String mid = match.group(1);
        boolean series = "seriesdetail".equals(match.group(3)) || "series".equals(uri.getQueryParameter("type"));
        String endpoint = series ? "/x/series/archives?mid=" + mid + "&series_id=" + sid + "&only_normal=true&sort=desc"
                : "/x/polymer/web-space/seasons_archives_list?mid=" + mid + "&season_id=" + sid + "&sort_reverse=false";
        Map<String, BiliClient.Video> videos = new LinkedHashMap<>();
        String title = series ? "B 站系列" : "B 站合集";
        for (int number = 1; ; number++) {
            task.check();
            String pagination = series ? "&pn=" + number + "&ps=" + PAGE_SIZE
                    : "&page_num=" + number + "&page_size=" + PAGE_SIZE;
            JSONObject data = BiliClient.requireData(BiliClient.json(BiliClient.API + endpoint + pagination,
                    uri.toString(), cookie, task), "获取合集");
            JSONObject meta = data.optJSONObject("meta");
            if (meta != null) {
                if (!series && meta.optLong("mid") > 0 && !mid.equals(meta.optString("mid"))) {
                    throw new IOException("合集与链接中的 UP 主不匹配。");
                }
                title = meta.optString("name", title);
            }
            JSONArray archives = data.optJSONArray("archives");
            if (archives == null || archives.length() == 0) break;
            int previous = videos.size();
            for (int i = 0; i < archives.length(); i++) {
                JSONObject archive = archives.optJSONObject(i);
                if (archive != null) add(videos, archive, archive.optString("title"), 1, archive.optLong("cid"));
            }
            JSONObject page = data.optJSONObject("page");
            int total = page == null ? 0 : page.optInt("total");
            int size = page == null ? PAGE_SIZE : page.optInt(series ? "size" : "page_size", PAGE_SIZE);
            if (total > 0 ? (long) number * Math.max(1, size) >= total : archives.length() < PAGE_SIZE) break;
            if (videos.size() == previous && number > 1) throw new IOException("合集分页重复，暂时无法读取完整列表。");
        }
        if (videos.isEmpty()) throw new IOException("没有找到可查看的合集视频。");
        return new BiliClient.Collection((series ? "series:" : "season:") + sid, title,
                new ArrayList<>(videos.values()));
    }

    static void addRelated(JSONObject info, JSONArray pages, List<BiliClient.Collection> collections) {
        if (pages.length() > 1) {
            Map<String, BiliClient.Video> parts = new LinkedHashMap<>();
            for (int i = 0; i < pages.length(); i++) {
                JSONObject page = pages.optJSONObject(i);
                if (page == null) continue;
                int number = i + 1;
                add(parts, info, "P" + number + " " + page.optString("part"), number, page.optLong("cid"));
            }
            if (!parts.isEmpty()) {
                collections.add(new BiliClient.Collection("parts:" + videoId(info),
                        info.optString("title", "视频分 P"), new ArrayList<>(parts.values())));
            }
        }
        JSONObject season = info.optJSONObject("ugc_season");
        JSONArray sections = season == null ? null : season.optJSONArray("sections");
        if (sections == null) return;
        Map<String, BiliClient.Video> episodes = new LinkedHashMap<>();
        for (int i = 0; i < sections.length(); i++) {
            JSONObject section = sections.optJSONObject(i);
            JSONArray list = section == null ? null : section.optJSONArray("episodes");
            if (list == null) continue;
            for (int j = 0; j < list.length(); j++) {
                JSONObject episode = list.optJSONObject(j);
                if (episode == null) continue;
                JSONObject page = episode.optJSONObject("page");
                add(episodes, episode, episode.optString("title"), page == null ? 1 : page.optInt("page", 1),
                        page == null ? episode.optLong("cid") : page.optLong("cid", episode.optLong("cid")));
            }
        }
        if (!episodes.isEmpty()) {
            String key = season.optLong("id") > 0 ? "season:" + season.optLong("id") : "related:" + videoId(info);
            collections.add(new BiliClient.Collection(key, season.optString("title", "投稿合集"),
                    new ArrayList<>(episodes.values())));
        }
    }

    private static void add(Map<String, BiliClient.Video> videos, JSONObject item, String title, int part, long cid) {
        String id = videoId(item);
        if (id == null) return;
        String link = BiliClient.SITE + "/video/" + id + "/?p=" + Math.max(1, part);
        videos.putIfAbsent(link, new BiliClient.Video(title.isBlank() ? id : title, link, cid));
    }

    private static String videoId(JSONObject item) {
        String id = item.optString("bvid");
        if (id.matches("BV[A-Za-z0-9]{10}")) return id;
        long aid = item.optLong("aid");
        return aid > 0 ? "av" + aid : null;
    }
}
