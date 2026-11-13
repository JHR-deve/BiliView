package local.bilimp4.app;

import android.content.res.AssetFileDescriptor;
import android.util.Xml;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

final class Danmaku {
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    record Comment(long timeMs, int mode, int fontSize, int color, String text) {}

    static List<Comment> fetch(long cid, String referer, ExportTask task) throws IOException {
        HttpURLConnection connection = BiliClient.connect("https://comment.bilibili.com/" + cid + ".xml", referer);
        connection.setInstanceFollowRedirects(false);
        try {
            task.check();
            if (connection.getResponseCode() != 200) throw new IOException("弹幕接口暂时不可用。");
            List<Comment> comments = read(new ByteArrayInputStream(readBody(connection, task)));
            if (comments.isEmpty()) throw new IOException("该视频没有可显示的弹幕。");
            return comments;
        } finally {
            connection.disconnect();
        }
    }

    static DanmakuStyle.Resolved resolveStyle(long aid, long cid, String referer, String cookie,
                                              DanmakuStyle.Options options, ExportTask task) throws ExportTask.Cancelled {
        if (!options.followBili() || cookie == null || aid <= 0 || cid <= 0) {
            return new DanmakuStyle.Resolved(options.local(), false);
        }
        HttpURLConnection connection = null;
        try {
            task.check();
            connection = BiliClient.connect(BiliClient.API + "/x/v2/dm/web/view?type=1&oid=" + cid
                    + "&pid=" + aid, referer);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Cookie", cookie);
            if (connection.getResponseCode() == 200) {
                return applySettings(readBody(connection, task), options.local());
            }
        } catch (ExportTask.Cancelled cancelled) {
            throw cancelled;
        } catch (IOException ignored) {
            // The export can continue with the local style when Bilibili has no accessible configuration.
        } finally {
            if (connection != null) connection.disconnect();
        }
        return new DanmakuStyle.Resolved(options.local(), false);
    }

    private static DanmakuStyle.Resolved applySettings(byte[] response, DanmakuStyle local) throws IOException {
        CodedInputStream view = CodedInputStream.newInstance(response);
        int tag;
        while ((tag = view.readTag()) != 0) {
            // DmWebViewReply.dmSetting is an optional DanmuWebPlayerConfig message.
            if (WireFormat.getTagFieldNumber(tag) == 10
                    && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                return readSettings(CodedInputStream.newInstance(view.readByteArray()), local);
            }
            if (!view.skipField(tag)) break;
        }
        return new DanmakuStyle.Resolved(local, false);
    }

    private static DanmakuStyle.Resolved readSettings(CodedInputStream settings, DanmakuStyle local)
            throws IOException {
        float opacity = local.opacity();
        float scale = local.scale();
        float speed = local.speed();
        float area = local.area();
        boolean synced = false;
        int tag;
        while ((tag = settings.readTag()) != 0) {
            switch (tag) {
                case (11 << 3) | WireFormat.WIRETYPE_FIXED32 -> {
                    float value = settings.readFloat();
                    if (value > 0f && value <= 1f) {
                        opacity = Math.max(0.2f, value);
                        synced = true;
                    }
                }
                case (12 << 3) | WireFormat.WIRETYPE_VARINT -> {
                    int value = settings.readInt32();
                    if (value == 25 || value == 50 || value == 75) {
                        area = value / 100f;
                        synced = true;
                    }
                }
                case (13 << 3) | WireFormat.WIRETYPE_FIXED32 -> {
                    float value = settings.readFloat();
                    if (value >= 0.4f && value <= 1.6f) {
                        speed = value;
                        synced = true;
                    }
                }
                case (14 << 3) | WireFormat.WIRETYPE_FIXED32 -> {
                    float value = settings.readFloat();
                    if (value >= 0.4f && value <= 1.6f) {
                        scale = value;
                        synced = true;
                    }
                }
                default -> {
                    if (!settings.skipField(tag)) throw new IOException("弹幕设置格式无效。");
                }
            }
        }
        return new DanmakuStyle.Resolved(new DanmakuStyle(scale, opacity, local.originalColors(), speed, area), synced);
    }

    private static byte[] readBody(HttpURLConnection connection, ExportTask task) throws IOException {
        try (InputStream body = connection.getInputStream();
             InputStream input = decode(body, connection.getContentEncoding())) {
            return readBytes(input, task);
        }
    }

    static List<Comment> readCache(CacheEntry entry, String referer, ExportTask task) throws IOException {
        for (boolean protobuf : new boolean[]{false, true}) {
            ExportTask.Source source = protobuf ? entry.protobuf() : entry.xml();
            if (source == null) continue;
            try (AssetFileDescriptor descriptor = source.open()) {
                if (descriptor == null) continue;
                try (InputStream input = descriptor.createInputStream()) {
                    byte[] data = readBytes(input, task);
                    List<Comment> comments = protobuf ? readProtobuf(data) : read(new ByteArrayInputStream(data));
                    if (!comments.isEmpty()) return comments;
                }
            } catch (ExportTask.Cancelled cancelled) {
                throw cancelled;
            } catch (IOException | IllegalStateException | SecurityException ignored) {
                // Try the other cache format or the matching Bilibili partition.
            }
        }
        if (entry.cid() > 0) return fetch(entry.cid(), referer, task);
        throw new IOException(entry.hasDanmakuSource() ? "缓存中没有可读取的弹幕。" : "未找到匹配的弹幕文件或 B 站分集。");
    }

    private static byte[] readBytes(InputStream input, ExportTask task) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) != -1) {
                task.check();
                if (output.size() + count > MAX_BYTES) throw new IOException("弹幕响应过大。");
                output.write(chunk, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static List<Comment> readProtobuf(byte[] data) throws IOException {
        // The cache uses the same repeated DanmakuElem wire format as a Bilibili segment.
        CodedInputStream segment = CodedInputStream.newInstance(data);
        List<Comment> comments = new ArrayList<>();
        int tag;
        while (comments.size() < 12000 && (tag = segment.readTag()) != 0) {
            if (tag == 10) {
                Comment comment = readElement(CodedInputStream.newInstance(segment.readByteArray()));
                if (comment != null) comments.add(comment);
            } else if (!segment.skipField(tag)) {
                throw new IOException("弹幕缓存格式无效。");
            }
        }
        comments.sort(Comparator.comparingLong(Comment::timeMs));
        return comments;
    }

    private static Comment readElement(CodedInputStream element) throws IOException {
        int time = 0;
        int mode = 1;
        int size = 25;
        int color = 0xffffff;
        String text = "";
        int tag;
        while ((tag = element.readTag()) != 0) {
            switch (tag) {
                case 16 -> time = element.readInt32();
                case 24 -> mode = element.readInt32();
                case 32 -> size = element.readInt32();
                case 40 -> color = element.readUInt32();
                case 58 -> text = element.readStringRequireUtf8();
                default -> {
                    if (!element.skipField(tag)) throw new IOException("弹幕缓存格式无效。");
                }
            }
        }
        return comment(time, mode, size, color, text);
    }

    private static InputStream decode(InputStream body, String encoding) throws IOException {
        if ("gzip".equalsIgnoreCase(encoding)) return new GZIPInputStream(body);
        if (!"deflate".equalsIgnoreCase(encoding)) return body;
        PushbackInputStream input = new PushbackInputStream(body, 2);
        int first = input.read();
        int second = input.read();
        if (second >= 0) input.unread(second);
        if (first >= 0) input.unread(first);
        // Bilibili may use raw DEFLATE instead of a zlib wrapper.
        boolean zlib = first >= 0 && second >= 0 && (first & 0x0f) == 8
                && (first >>> 4) <= 7 && ((first << 8) | second) % 31 == 0;
        Inflater inflater = new Inflater(!zlib);
        return new InflaterInputStream(input, inflater) {
            @Override public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    inflater.end();
                }
            }
        };
    }

    static List<Comment> read(InputStream input) throws IOException {
        if (input == null) throw new IOException("无法打开弹幕文件。");
        List<Comment> comments = new ArrayList<>();
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            parser.setInput(input, "UTF-8");
            boolean xml = false;
            for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event != XmlPullParser.START_TAG) continue;
                if (parser.getDepth() == 1) {
                    xml = "i".equals(parser.getName());
                    if (!xml) throw new IOException("无法读取弹幕格式。");
                }
                if (!xml || !"d".equals(parser.getName())) continue;
                String fields = parser.getAttributeValue(null, "p");
                String text = parser.nextText();
                Comment comment = parse(fields, text);
                if (comment != null) comments.add(comment);
                if (comments.size() >= 12000) break;
            }
        } catch (XmlPullParserException error) {
            throw new IOException("弹幕文件不完整。", error);
        }
        comments.sort(Comparator.comparingLong(Comment::timeMs));
        return comments;
    }

    private static Comment parse(String fields, String text) {
        if (fields == null) return null;
        String[] values = fields.split(",", 5);
        if (values.length < 4) return null;
        try {
            double time = Double.parseDouble(values[0]);
            if (!Double.isFinite(time) || time < 0) return null;
            return comment(Math.round(time * 1000), Integer.parseInt(values[1]),
                    Integer.parseInt(values[2]), Integer.parseInt(values[3]), text);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static Comment comment(long time, int mode, int size, int color, String text) {
        text = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (time < 0 || mode < 1 || mode > 6 || text.isEmpty()) return null;
        if (text.length() > 120) {
            int end = Character.isHighSurrogate(text.charAt(119)) ? 119 : 120;
            text = text.substring(0, end);
        }
        return new Comment(time, mode, Math.max(16, Math.min(36, size)), color & 0xffffff, text);
    }
}
