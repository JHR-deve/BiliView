package local.bilimp4.app;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.shape.ShapeAppearanceModel;

import java.util.Locale;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DownloadPreview implements AutoCloseable {
    interface Download {
        void start(BiliClient.Streams streams, BiliClient.Quality quality, DanmakuStyle.Options options);
    }

    interface BatchDownload {
        void start(BiliClient.Collection collection, List<BiliClient.Video> videos,
                   int quality, DanmakuStyle.Options options);
    }

    private final MainActivity activity;
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final MaterialCardView card;
    private final ShapeableImageView cover;
    private final TextView title;
    private final TextView duration;
    private final TextView notice;
    private final MaterialButton choose;
    private final DanmakuControls danmaku;
    private final MaterialButton download;
    private final LinearLayout collections;
    private final LinearLayout summary;
    private final BatchDownload onBatch;
    private BiliClient.Streams streams;
    private int selected;
    private boolean closed;

    DownloadPreview(MainActivity activity, LinearLayout panel, Download onDownload, BatchDownload onBatch) {
        this.activity = activity;
        this.onBatch = onBatch;
        card = new MaterialCardView(activity);
        card.setRadius(activity.dp(24));
        card.setCardElevation(0);
        card.setStrokeWidth(activity.dp(1));
        card.setStrokeColor(activity.color(com.google.android.material.R.attr.colorOutlineVariant));
        LinearLayout body = activity.column();
        body.setPadding(activity.dp(16), activity.dp(16), activity.dp(16), activity.dp(12));

        summary = new LinearLayout(activity);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        cover = new ShapeableImageView(activity);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setShapeAppearanceModel(ShapeAppearanceModel.builder().setAllCornerSizes(activity.dp(12)).build());
        LinearLayout.LayoutParams coverSize = new LinearLayout.LayoutParams(activity.dp(104), activity.dp(64));
        coverSize.setMarginEnd(activity.dp(12));
        summary.addView(cover, coverSize);
        LinearLayout labels = activity.column();
        title = activity.text("", 16, false);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(title);
        duration = activity.text("", 13, true);
        labels.addView(duration, activity.spacing(3));
        summary.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(summary);
        notice = activity.text("", 14, true);
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(notice);

        choose = activity.button("画质", com.google.android.material.R.attr.materialButtonOutlinedStyle);
        choose.setOnClickListener(view -> chooseQuality());
        body.addView(choose, activity.spacing(12));

        danmaku = new DanmakuControls(activity, body, true);

        download = activity.button("下载 MP4", com.google.android.material.R.attr.materialButtonStyle);
        download.setOnClickListener(view -> {
            if (streams != null) {
                onDownload.start(streams, streams.video().get(selected), danmaku.options());
            }
        });
        body.addView(download, activity.spacing(10));
        collections = activity.column();
        body.addView(collections, activity.spacing(8));
        card.addView(body);
        card.setVisibility(View.GONE);
        panel.addView(card, activity.spacing(16));
    }

    void show(BiliClient.Inspection inspection) {
        BiliClient.Streams data = inspection.streams();
        streams = data;
        selected = 0;
        notice.setText(inspection.notice());
        notice.setVisibility(inspection.notice() == null ? View.GONE : View.VISIBLE);
        summary.setVisibility(data == null ? View.GONE : View.VISIBLE);
        choose.setVisibility(data == null ? View.GONE : View.VISIBLE);
        download.setVisibility(data == null ? View.GONE : View.VISIBLE);
        collections.removeAllViews();
        for (BiliClient.Collection collection : inspection.collections()) {
            String label = collection.key().startsWith("parts:") ? "视频分 P" : collection.title();
            MaterialButton button = activity.button(label + " · " + collection.videos().size() + " 项",
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            button.setOnClickListener(view -> openCollection(collection));
            collections.addView(button);
        }
        setBusy(false);
        card.setVisibility(View.VISIBLE);
        if (data == null) return;
        cover.setImageDrawable(null);
        cover.setVisibility(View.GONE);
        title.setText(data.title());
        duration.setText(time(data.seconds()));
        duration.setVisibility(data.seconds() > 0 ? View.VISIBLE : View.GONE);
        updateQuality();
        images.execute(() -> {
            Bitmap image = BiliImage.load(data.cover(), 360);
            activity.runOnUiThread(() -> {
                if (closed || streams != data || image == null) return;
                cover.setImageBitmap(image);
                cover.setVisibility(View.VISIBLE);
            });
        });
    }

    private void openCollection(BiliClient.Collection collection) {
        int current = -1;
        for (int i = 0; i < collection.videos().size(); i++) {
            if (collection.videos().get(i).matches(streams)) {
                current = i;
                break;
            }
        }
        BatchPicker.show(activity, collection.title(), collection.videos(), BiliClient.Video::title, current, items -> {
            int quality = streams == null ? -1 : streams.video().get(selected).id();
            onBatch.start(collection, items, quality, danmaku.options());
        });
    }

    void clear() {
        streams = null;
        card.setVisibility(View.GONE);
    }

    void setBusy(boolean busy) {
        choose.setEnabled(!busy && streams != null && streams.video().size() > 1);
        danmaku.setBusy(busy);
        download.setEnabled(!busy && streams != null);
        for (int i = 0; i < collections.getChildCount(); i++) collections.getChildAt(i).setEnabled(!busy);
    }

    private void chooseQuality() {
        BiliClient.Streams data = streams;
        if (data == null) return;
        String[] labels = data.video().stream().map(DownloadPreview::label).toArray(String[]::new);
        new MaterialAlertDialogBuilder(activity).setTitle("选择下载画质")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    if (streams == data) {
                        selected = which;
                        updateQuality();
                    }
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void updateQuality() {
        String label = label(streams.video().get(selected));
        choose.setText("画质  ·  " + label + (streams.video().size() > 1 ? "  ▾" : ""));
    }

    private static String label(BiliClient.Quality quality) {
        return quality.label() + "  ·  " + quality.codec();
    }

    private static String time(int seconds) {
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    @Override public void close() {
        closed = true;
        images.shutdownNow();
    }
}
