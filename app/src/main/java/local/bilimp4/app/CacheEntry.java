package local.bilimp4.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

record CacheEntry(String key, String title, String location, ExportTask.Source video, ExportTask.Source audio,
                  long aid, long cid, ExportTask.Source xml, ExportTask.Source protobuf) {
    static final String XML = "danmaku.xml";
    static final String PROTOBUF = "danmaku.pb";

    boolean hasDanmakuSource() {
        return xml != null || protobuf != null || cid > 0;
    }

    static long cid(JSONObject metadata) {
        JSONObject page = metadata.optJSONObject("page_data");
        if (page == null) page = metadata.optJSONObject("ep");
        return page == null ? metadata.optLong("cid") : page.optLong("cid", metadata.optLong("cid"));
    }

    static long aid(JSONObject metadata) {
        JSONObject episode = metadata.optJSONObject("ep");
        long fallback = metadata.optLong("aid", metadata.optLong("avid"));
        return episode == null ? fallback : episode.optLong("aid", fallback);
    }
    static JSONObject readMetadata(InputStream input) {
        if (input == null) return new JSONObject();
        try (InputStream stream = input; ByteArrayOutputStream data = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (data.size() + count > 256 * 1024) return new JSONObject();
                data.write(buffer, 0, count);
            }
            return new JSONObject(data.toString(StandardCharsets.UTF_8.name()));
        } catch (IOException | JSONException ignored) {
            return new JSONObject();
        }
    }

    static String title(JSONObject metadata, String fallback) {
        String title = metadata.optString("title", "").trim();
        if (title.isEmpty()) return fallback;
        JSONObject page = metadata.optJSONObject("page_data");
        if (page != null && page.optInt("page") > 1) {
            title += " - P" + page.optInt("page") + " " + page.optString("part");
        }
        return title.trim();
    }
}
