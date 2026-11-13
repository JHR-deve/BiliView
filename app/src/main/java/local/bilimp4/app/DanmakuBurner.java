package local.bilimp4.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

@OptIn(markerClass = UnstableApi.class)
final class DanmakuBurner implements Transformer.Listener, Runnable {
    private final Handler handler;
    private final ExportTask task;
    private final CountDownLatch done = new CountDownLatch(1);
    private final ProgressHolder progress = new ProgressHolder();
    private Transformer transformer;
    private IOException failure;
    private boolean finished;

    private DanmakuBurner(Handler handler, ExportTask task) {
        this.handler = handler;
        this.task = task;
    }

    static void export(Context context, File source, File destination, List<Danmaku.Comment> comments,
                       DanmakuStyle style, ExportTask task) throws IOException {
        task.check();
        task.progress("正在烧录弹幕", -1);
        HandlerThread thread = new HandlerThread("BiliMP4-encoder");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        DanmakuBurner job = new DanmakuBurner(handler, task);
        if (!handler.post(() -> job.start(context, source, destination, comments, style))) {
            thread.quitSafely();
            throw new IOException("无法启动视频编码器。");
        }
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    job.done.await();
                    break;
                } catch (InterruptedException error) {
                    interrupted = true;
                    task.cancel();
                }
            }
        } finally {
            thread.quitSafely();
            if (interrupted) Thread.currentThread().interrupt();
        }
        task.check();
        if (job.failure != null) throw job.failure;
        if (destination.length() == 0) throw new IOException("编码器没有写入视频。");
    }

    private void start(Context context, File source, File destination,
                       List<Danmaku.Comment> comments, DanmakuStyle style) {
        try {
            task.check();
            DanmakuOverlay overlay = new DanmakuOverlay(comments, style);
            Effects effects = new Effects(Collections.emptyList(),
                    Collections.singletonList(new OverlayEffect(Collections.singletonList(overlay))));
            EditedMediaItem input = new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                    .setEffects(effects).build();
            Composition composition = new Composition.Builder(new EditedMediaItemSequence.Builder(input).build())
                    .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                    .build();
            transformer = new Transformer.Builder(context)
                    .setLooper(handler.getLooper())
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(this)
                    .build();
            transformer.start(composition, destination.getAbsolutePath());
            handler.postDelayed(this, 300);
        } catch (ExportTask.Cancelled cancelled) {
            finish(cancelled);
        } catch (RuntimeException error) {
            finish(new IOException("无法启动视频编码器。", error));
        }
    }

    @Override public void run() {
        if (finished) return;
        try {
            task.check();
            if (transformer.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) {
                task.progress("正在烧录弹幕", Math.min(progress.progress, 99));
            }
            handler.postDelayed(this, 300);
        } catch (ExportTask.Cancelled cancelled) {
            stop(cancelled);
        } catch (RuntimeException error) {
            stop(new IOException("无法读取视频编码进度。", error));
        }
    }

    private void stop(IOException reason) {
        try {
            transformer.cancel();
        } catch (RuntimeException ignored) {
            // Preserve the error that led to stopping the export.
        } finally {
            finish(reason);
        }
    }

    @Override public void onCompleted(Composition composition, ExportResult result) {
        finish(null);
    }

    @Override public void onError(Composition composition, ExportResult result, ExportException error) {
        finish(new IOException("设备编码失败（" + error.errorCode + "）。", error));
    }

    private void finish(IOException error) {
        if (finished) return;
        finished = true;
        failure = error;
        handler.removeCallbacks(this);
        done.countDown();
    }
}
