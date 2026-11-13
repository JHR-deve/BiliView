package local.bilimp4.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

final class AppUpdate {
    private static final int MAX_FEED_BYTES = 64 * 1024;

    record Release(int versionCode, String versionName, int minSdk, String notes, URI page) {}

    static Release check(String address) throws IOException, JSONException {
        HttpsURLConnection connection = (HttpsURLConnection) https(address).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (connection.getResponseCode() != HttpsURLConnection.HTTP_OK) {
                throw new IOException("Unexpected update feed response");
            }
            try (var input = connection.getInputStream(); var output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_FEED_BYTES) throw new IOException("Update feed is too large");
                    output.write(buffer, 0, count);
                }
                return parse(output.toString(StandardCharsets.UTF_8.name()));
            }
        } finally {
            connection.disconnect();
        }
    }

    static Release parse(String data) throws JSONException, IOException {
        JSONObject feed = new JSONObject(data);
        int code = positiveInteger(feed, "versionCode");
        int minSdk = positiveInteger(feed, "minSdk");
        String version = feed.getString("versionName").trim();
        if (version.isEmpty() || version.length() > 40) throw new IOException("Invalid version name");
        return new Release(code, version, minSdk, feed.optString("notes").trim(),
                https(feed.getString("releaseUrl")));
    }

    private static int positiveInteger(JSONObject feed, String field) throws JSONException, IOException {
        Object value = feed.get(field);
        if (value instanceof Integer number && number > 0) return number;
        throw new IOException("Invalid " + field);
    }

    private static URI https(String address) throws IOException {
        try {
            URI uri = URI.create(address);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null) {
                return uri;
            }
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid update URL", error);
        }
        throw new IOException("Update URL must use HTTPS");
    }
}
