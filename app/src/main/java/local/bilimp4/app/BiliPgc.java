package local.bilimp4.app;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BiliPgc {
    private static final Pattern PLAY = Pattern.compile("/bangumi/play/(ep|ss)([1-9][0-9]*)/?");
    private static final Pattern MEDIA = Pattern.compile("/bangumi/media/md([1-9][0-9]*)/?");
    private static final String RESTRICTED =
            "当前账号或地区没有完整播放权限。若账号已具备权限，请登录 B 站后重新解析。";

    static final class Unavailable extends IOException {
        Unavailable(String message) {
            super(message);
        }
    }

    static boolean matches(Uri uri) {
        String path = uri.getPath();
        return BiliClient.isBiliHost(uri.getHost()) && path != null
                && (PLAY.matcher(path).matches() || MEDIA.matcher(path).matches());
    }

    static BiliClient.Streams resolve(Uri uri, String cookie, ExportTask task,
                                      List<BiliClient.Collection> collections) throws IOException, JSONException {
        String path = uri.getPath();
        Matcher play = PLAY.matcher(path);
        String episodeId = null;
        String seasonQuery;
        if (play.matches()) {
            if ("ep".equals(play.group(1))) episodeId = play.group(2);
            seasonQuery = (episodeId == null ? "season_id=" : "ep_id=") + play.group(2);
        } else {
            Matcher media = MEDIA.matcher(path);
            if (!media.matches()) throw new IOException("请输入有效的 B 站番剧或电影链接。");
            JSONObject info = result(BiliClient.json(BiliClient.API + "/pgc/review/user?media_id=" + media.group(1),
                    BiliClient.SITE, cookie, task), "获取作品信息");
            JSONObject mediaInfo = info.optJSONObject("media");
            long id = mediaInfo == null ? 0 : mediaInfo.optLong("season_id");
            if (id <= 0) throw new IOException("没有找到该作品对应的播放页。");
            seasonQuery = "season_id=" + id;
        }

        JSONObject season = result(BiliClient.json(BiliClient.API + "/pgc/view/web/season?" + seasonQuery,
                BiliClient.SITE, cookie, task), "获取影视信息");
        List<JSONObject> episodes = episodes(season.optJSONArray("episodes"));
        JSONObject selected = find(episodes, episodeId);
        JSONArray sections = season.optJSONArray("section");
        if (selected == null && sections != null) {
            for (int i = 0; i < sections.length(); i++) {
                JSONObject section = sections.optJSONObject(i);
                if (section == null) continue;
                selected = find(episodes(section.optJSONArray("episodes")), episodeId);
                if (selected != null) break;
            }
        }
        if (selected == null) throw new IOException("没有找到链接指定的影视分集。");

        String title = season.optString("title", "B 站影视");
        if (collections != null && episodes.size() > 1) {
            long seasonId = season.optLong("season_id");
            String key = seasonId > 0 ? Long.toString(seasonId) : seasonQuery;
            List<BiliClient.Video> videos = new ArrayList<>();
            for (JSONObject episode : episodes) {
                videos.add(new BiliClient.Video(label(episode), episodeUrl(episode), episode.optLong("cid")));
            }
            collections.add(new BiliClient.Collection("pgc:" + key, title, videos));
        }
        return playback(selected, title, season.optString("cover"), cookie, task);
    }

    private static List<JSONObject> episodes(JSONArray list) {
        List<JSONObject> episodes = new ArrayList<>();
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject episode = list.optJSONObject(i);
                if (episode != null && episode.optLong("id") > 0) episodes.add(episode);
            }
        }
        return episodes;
    }

    private static JSONObject find(List<JSONObject> episodes, String id) {
        for (JSONObject episode : episodes) {
            if (id == null || id.equals(episode.optString("id"))) return episode;
        }
        return null;
    }

    private static String label(JSONObject episode) {
        return episode.optString("show_title", episode.optString("title", "ep" + episode.optLong("id")));
    }

    private static String episodeUrl(JSONObject episode) {
        return BiliClient.SITE + "/bangumi/play/ep" + episode.optLong("id");
    }

    private static BiliClient.Streams playback(JSONObject episode, String title, String seasonCover, String cookie,
                                               ExportTask task) throws IOException, JSONException {
        JSONObject rights = episode.optJSONObject("rights");
        if (rights != null && rights.optInt("area_limit") == 1) {
            throw new Unavailable("此影视分集受地区限制。");
        }
        long cid = episode.optLong("cid");
        if (cid <= 0) throw new Unavailable("当前分集尚未提供可用的播放地址。");
        String referer = episodeUrl(episode);
        Map<String, String> params = BiliClient.playbackParameters(cookie != null, cid);
        params.put("ep_id", episode.optString("id"));
        JSONObject reply = BiliClient.json(BiliClient.API + "/pgc/player/web/playurl?" + BiliClient.query(params),
                referer, cookie, task);
        JSONObject data = result(reply, "获取影视播放地址");
        if (flag(data, "is_drm")) throw new Unavailable("该分集使用 DRM，无法导出为 MP4。");
        if (flag(data, "is_preview") || data.optInt("error_code") == -10403 || data.optInt("code") == -10403) {
            throw new Unavailable(RESTRICTED);
        }
        String episodeTitle = title + " - " + label(episode);
        int seconds = (int) (data.optLong("timelength", episode.optLong("duration")) / 1000);
        BiliClient.Streams streams = BiliClient.select(data, episodeTitle, referer, episode.optLong("aid"), cid,
                seconds, episode.optString("cover", seasonCover));
        if (streams == null) throw new Unavailable("B 站没有返回可导出为 MP4 的完整音视频流。");
        return streams;
    }

    private static boolean flag(JSONObject data, String name) {
        return data.optBoolean(name) || data.optInt(name) != 0;
    }

    private static JSONObject result(JSONObject reply, String action) throws IOException {
        int code = reply.optInt("code", -1);
        JSONObject result = reply.optJSONObject("result");
        if (code == 0 && result != null) return result;
        if (code == -101) throw new Unavailable("此影视分集需要登录 B 站后重新解析。");
        if (code == -10403) throw new Unavailable(RESTRICTED);
        return BiliClient.requireData(reply, action);
    }
}
