package local.bilimp4.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ReplacementSpan;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

final class BatchPicker {
    static <T> void show(MainActivity activity, String title, List<T> items, Function<T, String> label,
                         Consumer<List<T>> submit) {
        show(activity, title, items, label, -1, submit);
    }

    static <T> void show(MainActivity activity, String title, List<T> items, Function<T, String> label,
                         int current, Consumer<List<T>> submit) {
        CharSequence[] labels = items.stream().map(label).toArray(CharSequence[]::new);
        if (current >= 0) labels[current] = currentLabel(activity, labels[current]);
        boolean[] selected = new boolean[labels.length];
        var dialog = new MaterialAlertDialogBuilder(activity).setTitle(title)
                .setMultiChoiceItems(labels, selected, (window, which, checked) -> selected[which] = checked)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("全选", null)
                .setPositiveButton("加入队列", (window, which) -> {
                    List<T> batch = new ArrayList<>();
                    for (int i = 0; i < selected.length; i++) {
                        if (selected[i]) batch.add(items.get(i));
                    }
                    if (!batch.isEmpty()) submit.accept(batch);
                }).create();
        dialog.show();
        var add = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        var all = dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL);
        var list = dialog.getListView();
        add.setEnabled(false);
        list.setOnItemClickListener((parent, view, position, id) -> {
            selected[position] = list.isItemChecked(position);
            add.setEnabled(anySelected(selected));
            all.setText(allSelected(selected) ? "取消全选" : "全选");
        });
        all.setOnClickListener(view -> {
            boolean check = !allSelected(selected);
            for (int i = 0; i < selected.length; i++) {
                selected[i] = check;
                list.setItemChecked(i, check);
            }
            add.setEnabled(check);
            all.setText(check ? "取消全选" : "全选");
        });
        if (current >= 0) list.post(() -> list.setSelectionFromTop(current, activity.dp(8)));
    }

    private static CharSequence currentLabel(MainActivity activity, CharSequence title) {
        String tag = "当前视频";
        SpannableString label = new SpannableString(tag + "  " + title);
        int background = activity.color(com.google.android.material.R.attr.colorPrimaryContainer);
        int foreground = activity.color(com.google.android.material.R.attr.colorOnPrimaryContainer);
        label.setSpan(new Badge(background, foreground, activity.dp(7)), 0, tag.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return label;
    }

    private static final class Badge extends ReplacementSpan {
        private final int background;
        private final int foreground;
        private final int padding;

        Badge(int background, int foreground, int padding) {
            this.background = background;
            this.foreground = foreground;
            this.padding = padding;
        }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
            return Math.round(badgePaint(paint).measureText(text, start, end)) + padding * 2;
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                                   int top, int y, int bottom, Paint paint) {
            Paint badge = badgePaint(paint);
            float height = paint.getTextSize() * 1.12f;
            float baseline = y + (paint.ascent() + paint.descent() - badge.ascent() - badge.descent()) / 2;
            float center = baseline + (badge.ascent() + badge.descent()) / 2;
            float right = x + badge.measureText(text, start, end) + padding * 2;
            badge.setColor(background);
            canvas.drawRoundRect(x, center - height / 2, right, center + height / 2, height / 2, height / 2, badge);
            badge.setColor(foreground);
            canvas.drawText(text, start, end, x + padding, baseline, badge);
        }

        private Paint badgePaint(Paint paint) {
            Paint badge = new Paint(paint);
            badge.setAntiAlias(true);
            badge.setTextSize(paint.getTextSize() * 0.79f);
            return badge;
        }
    }

    private static boolean anySelected(boolean[] selected) {
        for (boolean value : selected) if (value) return true;
        return false;
    }

    private static boolean allSelected(boolean[] selected) {
        for (boolean value : selected) if (!value) return false;
        return true;
    }
}
