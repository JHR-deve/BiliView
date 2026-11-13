package local.bilimp4.app;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

import androidx.annotation.OptIn;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.CanvasOverlay;
import androidx.media3.effect.StaticOverlaySettings;

import java.util.ArrayList;
import java.util.List;

@OptIn(markerClass = UnstableApi.class)
final class DanmakuOverlay extends CanvasOverlay {
    private static final long FIXED_TIME = 3800;
    private final List<Danmaku.Comment> comments;
    private final DanmakuStyle style;
    private final long scrollTime;
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<Placed> placed = new ArrayList<>();
    private OverlaySettings settings;
    private int width;
    private int height;
    private boolean hasPixels;

    private record Placed(long time, int mode, int color, String text, float size, float width, float baseline) {}

    DanmakuOverlay(List<Danmaku.Comment> comments, DanmakuStyle style) {
        super(false);
        this.comments = comments;
        this.style = style;
        scrollTime = Math.round(8500f / style.speed());
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public void configure(Size video) {
        super.configure(video);
        float scale = Math.min(1f, 720f / Math.min(video.getWidth(), video.getHeight()));
        width = Math.max(1, Math.round(video.getWidth() * scale));
        height = Math.max(1, Math.round(video.getHeight() * scale));
        setCanvasSize(width, height);
        settings = new StaticOverlaySettings.Builder()
                .setScale((float) video.getWidth() / width, (float) video.getHeight() / height)
                .setAlphaScale(style.opacity())
                .build();
        layout();
    }

    @Override public OverlaySettings getOverlaySettings(long presentationTimeUs) {
        return settings;
    }

    private void layout() {
        placed.clear();
        float baseSize = Math.max(10f, Math.min(height * 0.043f, width * 0.066f) * style.scale());
        float row = baseSize * 1.58f;
        float start = height * 0.026f;
        int available = (int) ((height * style.area() - start) / row);
        int fixedRows = available >= 6 ? 2 : 1;
        Placed[] lanes = new Placed[Math.min(9, Math.max(1, available - fixedRows))];
        long[] top = new long[fixedRows];
        long[] bottom = new long[fixedRows];
        for (Danmaku.Comment comment : comments) {
            float font = baseSize * comment.fontSize() / 25f;
            paint.setTextSize(font);
            String text = TextUtils.ellipsize(comment.text(), paint, width * 0.9f, TextUtils.TruncateAt.END).toString();
            if (text.isEmpty()) continue;
            float textWidth = paint.measureText(text);
            int mode = comment.mode();
            int color = style.originalColors() ? comment.color() : 0xffffff;
            if (mode == 4 || mode == 5) {
                long[] rows = mode == 4 ? bottom : top;
                for (int i = 0; i < rows.length; i++) {
                    if (rows[i] > comment.timeMs()) continue;
                    rows[i] = comment.timeMs() + FIXED_TIME;
                    float baseline = mode == 4 ? height * 0.89f - i * row : start + (i + 0.82f) * row;
                    placed.add(new Placed(comment.timeMs(), mode, color, text, font, textWidth, baseline));
                    break;
                }
            } else {
                for (int i = 0; i < lanes.length; i++) {
                    if (!fits(lanes[i], comment, textWidth, row * 0.32f)) continue;
                    float baseline = start + (i + fixedRows + 0.82f) * row;
                    Placed next = new Placed(comment.timeMs(), mode, color, text, font, textWidth, baseline);
                    lanes[i] = next;
                    placed.add(next);
                    break;
                }
            }
        }
    }

    private boolean fits(Placed previous, Danmaku.Comment next, float nextWidth, float gap) {
        if (previous == null) return true;
        long elapsed = next.timeMs() - previous.time();
        if (elapsed >= scrollTime) return true;
        if ((previous.mode() == 6) != (next.mode() == 6)) return false;
        float oldGap = (width + previous.width()) * elapsed / scrollTime - previous.width();
        float newGap = width - (width + nextWidth) * (scrollTime - elapsed) / scrollTime;
        return oldGap >= gap && newGap >= gap;
    }

    @Override public void onDraw(Canvas canvas, long presentationTimeUs) {
        long now = presentationTimeUs / 1000;
        if (hasPixels) canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        hasPixels = false;
        for (int i = firstAt(now - Math.max(scrollTime, FIXED_TIME)); i < placed.size(); i++) {
            Placed item = placed.get(i);
            long age = now - item.time();
            if (age < 0) break;
            float x;
            if (item.mode() == 4 || item.mode() == 5) {
                if (age >= FIXED_TIME) continue;
                x = (width - item.width()) / 2;
            } else {
                if (age >= scrollTime) continue;
                float traveled = (width + item.width()) * age / scrollTime;
                x = item.mode() == 6 ? traveled - item.width() : width - traveled;
            }
            draw(canvas, item, x);
            hasPixels = true;
        }
    }

    private void draw(Canvas canvas, Placed item, float x) {
        int color = 0xff000000 | item.color();
        int luminance = 54 * Color.red(color) + 183 * Color.green(color) + 19 * Color.blue(color);
        paint.setTextSize(item.size());
        paint.setStrokeWidth(Math.max(1.5f, item.size() * 0.13f));
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(luminance < 11500 ? 0xeef5f5f5 : 0xe6000000);
        canvas.drawText(item.text(), x, item.baseline(), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawText(item.text(), x, item.baseline(), paint);
    }

    private int firstAt(long time) {
        int low = 0;
        int high = placed.size();
        while (low < high) {
            int mid = (low + high) / 2;
            if (placed.get(mid).time() < time) low = mid + 1;
            else high = mid;
        }
        return low;
    }
}
