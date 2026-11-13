package local.bilimp4.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.Mp4Muxer;
import androidx.media3.muxer.MuxerException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.List;

@OptIn(markerClass = UnstableApi.class)
final class ExportTask {
    static final String FOLDER = "BiliMP4";
    static final String PATH = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER;
    private static final int BUFFER_SIZE = 128 * 1024;
    private final Context context;
    private final String folder;
    private final Progress listener;
    private volatile boolean cancelled;
    private long lastUpdate;
    private String lastStage;

    record Result(Uri uri, String name, boolean burned, String note, String folder) {}

    interface Progress {
        void update(String stage, int percent);
    }

    interface Source {
        AssetFileDescriptor open() throws IOException;
    }

    private interface VideoWriter {
        void write(FileOutputStream output) throws IOException;
    }

    static final class Cancelled extends IOException {}

    ExportTask(Context context, String folder, Progress listener) {
        this.context = context.getApplicationContext();
        this.folder = folder;
        this.listener = listener;
    }

    void cancel() {
        cancelled = true;
    }

    void check() throws Cancelled {
        if (cancelled) throw new Cancelled();
    }

    void progress(String stage, int percent) {
        long now = SystemClock.elapsedRealtime();
        if (!stage.equals(lastStage) || now - lastUpdate >= 150 || percent == 100) {
            listener.update(stage, percent);
            lastStage = stage;
            lastUpdate = now;
        }
    }

    BiliClient.Streams prepareLink(String link, String cookie) throws Exception {
        progress("正在解析视频链接", -1);
        return BiliClient.resolve(link, cookie, this);
    }

    BiliClient.Inspection inspectLink(String link, String cookie) throws Exception {
        progress("正在解析视频或合集", -1);
        return BiliClient.inspect(link, cookie, this);
    }

    Result fromLink(BiliClient.Streams streams, BiliClient.Quality quality, DanmakuStyle.Options options,
                    String cookie) throws IOException {
        File video = File.createTempFile("bili-video-", ".m4s", context.getCacheDir());
        File audio = null;
        try {
            progress("正在下载视频", -1);
            BiliClient.download(quality.urls(), streams.referer(), video, "正在下载视频", this);
            if (!streams.audio().isEmpty()) {
                audio = File.createTempFile("bili-audio-", ".m4s", context.getCacheDir());
                progress("正在下载音频", -1);
                BiliClient.download(streams.audio(), streams.referer(), audio, "正在下载音频", this);
            }
            File sound = audio == null ? video : audio;
            if (options == null) return export(video, sound, streams.title());

            progress("正在下载弹幕", -1);
            List<Danmaku.Comment> comments;
            try {
                comments = Danmaku.fetch(streams.cid(), streams.referer(), this);
            } catch (Cancelled cancelled) {
                throw cancelled;
            } catch (IOException error) {
                progress("正在合成 MP4", -1);
                return publish(streams.title(), false, "弹幕未嵌入 · " + error.getMessage(),
                        output -> mux(video, sound, output));
            }
            DanmakuStyle.Resolved style = Danmaku.resolveStyle(streams.aid(), streams.cid(), streams.referer(),
                    cookie, options, this);
            return burn(video, sound, streams.title(), comments, style);
        } finally {
            video.delete();
            if (audio != null) audio.delete();
        }
    }

    private Result burn(File video, File audio, String title, List<Danmaku.Comment> comments,
                        DanmakuStyle.Resolved style) throws IOException {
        check();
        File source = File.createTempFile("bili-source-", ".mp4", context.getCacheDir());
        File destination = null;
        try {
            progress("正在合成 MP4", -1);
            try (FileOutputStream output = new FileOutputStream(source)) {
                mux(video, audio, output);
            }
            video.delete();
            if (!audio.equals(video)) audio.delete();
            try {
                destination = File.createTempFile("bili-burn-", ".mp4", context.getCacheDir());
                DanmakuBurner.export(context, source, destination, comments, style.style(), this);
            } catch (Cancelled cancelled) {
                throw cancelled;
            } catch (IOException error) {
                return publishFile(source, title, false, "弹幕未嵌入，已保存原视频 · " + error.getMessage());
            }
            String note = style.synced() ? "已应用 B 站账号弹幕设置" : "使用本机弹幕样式";
            return publishFile(destination, title + " - 弹幕", true, note);
        } finally {
            source.delete();
            if (destination != null) destination.delete();
        }
    }

    private Result publishFile(File file, String title, boolean burned, String note) throws IOException {
        progress("正在保存 MP4", -1);
        return publish(title, burned, note, output -> {
            try (InputStream input = new FileInputStream(file)) {
                copy(input, output, 0, file.length(), "正在保存 MP4", this);
            }
        });
    }

    Result fromFiles(CacheEntry entry, DanmakuStyle.Options options, String cookie) throws IOException {
        File video = stageFile(entry.video(), "正在读取视频文件");
        File audio = null;
        try {
            audio = stageFile(entry.audio(), "正在读取音频文件");
            if (options == null) return export(video, audio, entry.title());

            progress("正在读取缓存弹幕", -1);
            String referer = entry.aid() > 0 ? BiliClient.SITE + "/video/av" + entry.aid() + "/" : BiliClient.SITE;
            List<Danmaku.Comment> comments;
            try {
                comments = Danmaku.readCache(entry, referer, this);
            } catch (Cancelled cancelled) {
                throw cancelled;
            } catch (IOException error) {
                return export(video, audio, entry.title(), "弹幕未嵌入 · " + error.getMessage());
            }
            DanmakuStyle.Resolved style = Danmaku.resolveStyle(entry.aid(), entry.cid(), referer, cookie, options, this);
            return burn(video, audio, entry.title(), comments, style);
        } finally {
            video.delete();
            if (audio != null) audio.delete();
        }
    }

    private File stageFile(Source source, String stage) throws IOException {
        check();
        progress(stage, -1);
        File file = File.createTempFile("bili-import-", ".m4s", context.getCacheDir());
        boolean ready = false;
        try {
            try (AssetFileDescriptor descriptor = source.open()) {
                if (descriptor == null) throw new IOException("无法打开所选文件。");
                try (InputStream input = descriptor.createInputStream();
                     FileOutputStream output = new FileOutputStream(file)) {
                    byte[] prefix = new byte[17];
                    int count = 0;
                    while (count < prefix.length) {
                        int read = input.read(prefix, count, prefix.length - count);
                        if (read == -1) break;
                        count += read;
                    }
                    int skip = cachePrefix(prefix, count);
                    output.write(prefix, skip, count - skip);
                    long length = descriptor.getLength();
                    copy(input, output, count - skip, length < 0 ? -1 : length - skip, stage, this);
                    if (length >= 0 && file.length() != length - skip) {
                        throw new IOException("所选文件读取不完整。");
                    }
                }
            }
            ready = true;
            return file;
        } finally {
            if (!ready) file.delete();
        }
    }

    private static int cachePrefix(byte[] prefix, int count) {
        // Some Bilibili cache files insert nine ASCII zeroes before the first MP4 box.
        if (count < 17) return 0;
        for (int i = 0; i < 9; i++) {
            if (prefix[i] != '0') return 0;
        }
        String box = new String(prefix, 13, 4, StandardCharsets.US_ASCII);
        return box.equals("ftyp") || box.equals("styp") ? 9 : 0;
    }

    static void copy(InputStream input, OutputStream output, long written, long total,
                     String stage, ExportTask task) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (true) {
            task.check();
            int count = input.read(buffer);
            if (count < 0) break;
            output.write(buffer, 0, count);
            written += count;
            task.progress(stage, percent(written, total, 99));
        }
        task.check();
        task.progress(stage, 100);
    }

    private static int percent(long position, long length, int max) {
        return length <= 0 ? -1 : (int) Math.min(max, 100.0 * position / length);
    }

    private Result export(File video, File audio, String title) throws IOException {
        return export(video, audio, title, null);
    }

    private Result export(File video, File audio, String title, String note) throws IOException {
        progress("正在合成 MP4", -1);
        return publish(title, false, note, output -> mux(video, audio, output));
    }

    private Result publish(String title, boolean burned, String note, VideoWriter writer) throws IOException {
        check();
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        String name = fileName(title);
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Downloads.RELATIVE_PATH, folder == null ? PATH : PATH + "/" + folder);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (uri == null) throw new IOException("无法创建下载文件。");
        boolean published = false;
        try {
            ParcelFileDescriptor output = resolver.openFileDescriptor(uri, "rw");
            if (output == null) throw new IOException("无法写入下载文件。");
            try (FileOutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
                writer.write(stream);
            }
            check();
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            if (resolver.update(uri, values, null, null) != 1) throw new IOException("无法发布下载文件。");
            published = true;
            return new Result(uri, displayName(resolver, uri, name), burned, note, folder);
        } finally {
            if (!published) resolver.delete(uri, null, null);
        }
    }

    private void mux(File videoFile, File audioFile, FileOutputStream output) throws IOException {
        try (Mp4Track video = Mp4Track.open(videoFile, C.TRACK_TYPE_VIDEO);
             Mp4Track audio = Mp4Track.open(audioFile, C.TRACK_TYPE_AUDIO)) {
            Mp4Track.Sample videoSample = video.next();
            Mp4Track.Sample audioSample = audio.next();
            if (videoSample == null || audioSample == null || video.format() == null || audio.format() == null) {
                throw new IOException("所选缓存缺少可读取的音频或视频轨。");
            }
            try (Mp4Muxer muxer = new Mp4Muxer.Builder(output).setSampleBatchingEnabled(false).build()) {
                int videoTrack = muxer.addTrack(video.format());
                int audioTrack = muxer.addTrack(audio.format());
                if (video.format().rotationDegrees != 0) {
                    muxer.addMetadataEntry(new Mp4OrientationData(video.format().rotationDegrees));
                }
                writeTracks(muxer, video, videoTrack, videoSample, audio, audioTrack, audioSample);
            }
        } catch (MuxerException | IllegalArgumentException | IllegalStateException error) {
            throw new IOException("无法将这两条音视频轨封装为 MP4。请检查缓存是否完整且可播放。", error);
        }
    }

    private void writeTracks(Mp4Muxer muxer, Mp4Track video, int videoTrack, Mp4Track.Sample videoSample,
                             Mp4Track audio, int audioTrack, Mp4Track.Sample audioSample)
            throws IOException, MuxerException {
        long videoEnd = 0;
        long audioEnd = 0;
        while (videoSample != null || audioSample != null) {
            check();
            if (videoSample != null && (audioSample == null || videoSample.timeUs() <= audioSample.timeUs())) {
                writeSample(muxer, videoTrack, videoSample);
                videoEnd = Math.max(videoEnd, videoSample.timeUs());
                videoSample = video.next();
            } else {
                writeSample(muxer, audioTrack, audioSample);
                audioEnd = Math.max(audioEnd, audioSample.timeUs());
                audioSample = audio.next();
            }
            progress("正在合成 MP4", percent(Math.max(videoEnd, audioEnd),
                    Math.max(video.durationUs(), audio.durationUs()), 99));
        }
        long tolerance = Math.max(3_000_000, Math.max(videoEnd, audioEnd) / 50);
        if (Math.abs(videoEnd - audioEnd) > tolerance
                || video.durationUs() - videoEnd > tolerance || audio.durationUs() - audioEnd > tolerance) {
            throw new IOException("音视频时长对不上。请确认两条轨道来自同一视频且缓存完整。");
        }
        progress("正在合成 MP4", 100);
    }

    private static void writeSample(Mp4Muxer muxer, int track, Mp4Track.Sample sample) throws MuxerException {
        byte[] data = sample.data();
        muxer.writeSampleData(track, ByteBuffer.wrap(data), new BufferInfo(sample.timeUs(), data.length, sample.flags()));
    }

    static String displayName(ContentResolver resolver, Uri uri, String fallback) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException ignored) {
            // Some document providers do not expose a display name.
        }
        return fallback;
    }

    static String fileName(String title) {
        String name = safeName(title);
        if (name.toLowerCase(Locale.ROOT).endsWith(".mp4")) name = name.substring(0, name.length() - 4);
        if (name.codePointCount(0, name.length()) > 60) {
            name = name.substring(0, name.offsetByCodePoints(0, 60));
        }
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "B站视频";
        return name + ".mp4";
    }

    static String folderName(String title) {
        String name = safeName(title).replaceAll("^[. ]+|[. ]+$", "");
        if (name.codePointCount(0, name.length()) > 48) {
            name = name.substring(0, name.offsetByCodePoints(0, 48)).stripTrailing();
        }
        return name.isEmpty() ? "B站合集" : name;
    }

    private static String safeName(String title) {
        return title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
    }
}
