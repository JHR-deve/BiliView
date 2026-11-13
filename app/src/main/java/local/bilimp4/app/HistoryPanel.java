package local.bilimp4.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

final class HistoryPanel {
    private final MainActivity activity;
    private final LinearLayout root;
    private final LinearLayout list;
    private final TextView summary;
    private final BiConsumer<android.net.Uri, Boolean> open;
    private final LongConsumer cancel;
    private final Consumer<VideoHistory.Entry> delete;
    private final Consumer<VideoHistory.Entry> retry;
    private final Map<Long, Row> active = new HashMap<>();
    private final Set<String> expandedFolders = new HashSet<>();
    private List<VideoHistory.Entry> entries = List.of();
    private String query = "";

    HistoryPanel(MainActivity activity, LinearLayout root, BiConsumer<android.net.Uri, Boolean> open,
                 LongConsumer cancel, Consumer<VideoHistory.Entry> delete, Consumer<VideoHistory.Entry> retry) {
        this.activity = activity;
        this.root = root;
        this.open = open;
        this.cancel = cancel;
        this.delete = delete;
        this.retry = retry;
        TextInputLayout search = new TextInputLayout(activity, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        search.setHint("搜索视频或合集");
        search.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
        TextInputEditText field = new TextInputEditText(search.getContext());
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setSingleLine(true);
        search.addView(field, new LinearLayout.LayoutParams(-1, -2));
        root.addView(search);
        summary = activity.text("", 14, true);
        root.addView(summary, activity.spacing(12));
        list = activity.column();
        root.addView(list, activity.spacing(8));
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                query = value.toString().strip().toLowerCase(Locale.ROOT);
                render();
            }
            @Override public void afterTextChanged(Editable value) {}
        });
    }

    void show(List<VideoHistory.Entry> entries) {
        this.entries = entries;
        render();
    }

    void expandRunningFolder(long id) {
        for (VideoHistory.Entry entry : entries) {
            if (entry.id() == id && entry.folder() != null && expandedFolders.add(entry.folder().key())) {
                render();
                return;
            }
        }
    }

    private void render() {
        active.clear();
        list.removeAllViews();
        List<VideoHistory.Entry> matches = entries.stream().filter(this::matches).collect(Collectors.toList());
        long ongoing = matches.stream().filter(HistoryPanel::pending).count();
        if (entries.isEmpty()) summary.setText("下载或转换的视频会显示在这里。");
        else if (matches.isEmpty()) summary.setText("没有匹配的视频或合集。");
        else summary.setText("共 " + matches.size() + " 条记录" + (ongoing > 0 ? " · " + ongoing + " 项待完成" : ""));
        Map<String, List<VideoHistory.Entry>> groups = new LinkedHashMap<>();
        for (VideoHistory.Entry entry : matches) {
            if (entry.folder() != null) {
                groups.computeIfAbsent(entry.folder().key(), key -> new ArrayList<>()).add(entry);
            }
        }
        for (VideoHistory.Entry entry : matches) {
            if (entry.folder() == null) {
                list.addView(row(entry), activity.spacing(10));
            } else {
                List<VideoHistory.Entry> group = groups.remove(entry.folder().key());
                if (group != null) list.addView(folder(entry.folder(), group), activity.spacing(10));
            }
        }
    }

    private boolean matches(VideoHistory.Entry entry) {
        return query.isEmpty() || entry.title().toLowerCase(Locale.ROOT).contains(query)
                || (entry.folder() != null && entry.folder().title().toLowerCase(Locale.ROOT).contains(query));
    }

    private View folder(VideoHistory.Folder folder, List<VideoHistory.Entry> entries) {
        entries.sort(Comparator.comparingLong(VideoHistory.Entry::id));
        LinearLayout section = activity.column();
        MaterialCardView card = new MaterialCardView(activity);
        card.setRadius(activity.dp(20));
        card.setCardElevation(0);
        card.setStrokeWidth(0);
        card.setCardBackgroundColor(activity.color(com.google.android.material.R.attr.colorSecondaryContainer));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(activity.dp(16), activity.dp(13), activity.dp(12), activity.dp(13));
        int foreground = activity.color(com.google.android.material.R.attr.colorOnSecondaryContainer);
        header.addView(icon(R.drawable.ic_folder, foreground), new LinearLayout.LayoutParams(activity.dp(24), activity.dp(24)));
        LinearLayout labels = activity.column();
        TextView title = activity.text(folder.title(), 16, false);
        title.setTextColor(foreground);
        labels.addView(title);
        long done = entries.stream().filter(entry -> entry.state() == VideoHistory.State.DONE).count();
        long ongoing = entries.stream().filter(HistoryPanel::pending).count();
        long stopped = entries.size() - done - ongoing;
        String status = done + "/" + entries.size() + " 已完成";
        if (ongoing > 0) status += " · " + ongoing + " 项待完成";
        if (stopped > 0) status += " · " + stopped + " 项未完成";
        TextView detail = activity.text(status, 13, true);
        detail.setTextColor(foreground);
        labels.addView(detail, activity.spacing(3));
        LinearLayout.LayoutParams labelSize = new LinearLayout.LayoutParams(0, -2, 1);
        labelSize.setMarginStart(activity.dp(12));
        labelSize.setMarginEnd(activity.dp(8));
        header.addView(labels, labelSize);
        ImageView arrow = icon(R.drawable.ic_expand, foreground);
        header.addView(arrow, new LinearLayout.LayoutParams(activity.dp(24), activity.dp(24)));
        card.addView(header);
        section.addView(card);

        LinearLayout children = activity.column();
        children.setPadding(activity.dp(12), 0, activity.dp(12), activity.dp(12));
        GradientDrawable backing = new GradientDrawable();
        backing.setColor(activity.color(com.google.android.material.R.attr.colorSurfaceVariant));
        float corner = activity.dp(18);
        backing.setCornerRadii(new float[]{0, 0, 0, 0, corner, corner, corner, corner});
        children.setBackground(backing);
        LinearLayout.LayoutParams inset = activity.spacing(0);
        inset.setMarginStart(activity.dp(10));
        inset.setMarginEnd(activity.dp(10));
        FrameLayout pocket = new FrameLayout(activity);
        pocket.addView(children, new FrameLayout.LayoutParams(-1, -2));
        section.addView(pocket, inset);
        boolean expanded = expandedFolders.contains(folder.key());
        FolderMotion motion = new FolderMotion(pocket, children, arrow, expanded);
        if (expanded) addFolderRows(children, entries);
        ViewCompat.setStateDescription(card, expanded ? "已展开" : "已折叠");
        card.setOnClickListener(view -> {
            boolean open = expandedFolders.add(folder.key());
            if (!open) expandedFolders.remove(folder.key());
            addFolderRows(children, entries);
            ViewCompat.setStateDescription(card, open ? "已展开" : "已折叠");
            motion.animate(open);
        });
        return section;
    }

    private void addFolderRows(LinearLayout children, List<VideoHistory.Entry> entries) {
        if (children.getChildCount() > 0) return;
        for (VideoHistory.Entry entry : entries) children.addView(row(entry), activity.spacing(8));
    }

    private static final class FolderMotion {
        private final FrameLayout pocket;
        private final LinearLayout content;
        private final ImageView arrow;
        private ValueAnimator animation;
        private float fraction;

        FolderMotion(FrameLayout pocket, LinearLayout content, ImageView arrow, boolean expanded) {
            this.pocket = pocket;
            this.content = content;
            this.arrow = arrow;
            finish(expanded);
            pocket.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {}
                @Override public void onViewDetachedFromWindow(View view) {
                    if (animation != null) animation.cancel();
                }
            });
        }

        void animate(boolean expanded) {
            if (animation != null) animation.cancel();
            int width = pocket.getWidth();
            if (width == 0 && pocket.getParent() instanceof View parent) {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) pocket.getLayoutParams();
                width = parent.getWidth() - params.getMarginStart() - params.getMarginEnd();
            }
            if (!ValueAnimator.areAnimatorsEnabled() || width <= 0) {
                finish(expanded);
                return;
            }
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int height = content.getMeasuredHeight();
            setHeight(content, height);
            apply(height);
            pocket.setVisibility(View.VISIBLE);
            pocket.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            animation = ValueAnimator.ofFloat(fraction, expanded ? 1 : 0);
            animation.setDuration(expanded ? 280 : 220);
            animation.setInterpolator(new PathInterpolator(0.2f, 0, 0, 1));
            animation.addUpdateListener(value -> {
                fraction = (float) value.getAnimatedValue();
                apply(height);
            });
            animation.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;
                @Override public void onAnimationCancel(Animator animator) { cancelled = true; }
                @Override public void onAnimationEnd(Animator animator) {
                    animation = null;
                    if (!cancelled) finish(expanded);
                }
            });
            animation.start();
        }

        private void apply(int height) {
            arrow.setRotation(fraction * 180);
            content.setAlpha(0.68f + 0.32f * fraction);
            setHeight(pocket, Math.round(height * fraction));
        }

        private void finish(boolean expanded) {
            fraction = expanded ? 1 : 0;
            arrow.setRotation(fraction * 180);
            content.setAlpha(1);
            pocket.setVisibility(expanded ? View.VISIBLE : View.GONE);
            pocket.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            setHeight(content, -2);
            setHeight(pocket, -2);
        }

        private static void setHeight(View view, int height) {
            var params = view.getLayoutParams();
            if (params.height == height) return;
            params.height = height;
            view.setLayoutParams(params);
        }
    }

    private ImageView icon(int id, int color) {
        ImageView icon = new ImageView(activity);
        icon.setImageResource(id);
        icon.setImageTintList(ColorStateList.valueOf(color));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private static boolean pending(VideoHistory.Entry entry) {
        return entry.state() == VideoHistory.State.RUNNING || entry.state() == VideoHistory.State.QUEUED;
    }

    void updateProgress(long id, String stage, int percent) {
        Row row = active.get(id);
        if (root.getVisibility() == View.VISIBLE && row != null) setProgress(row, stage, percent);
    }

    private MaterialCardView row(VideoHistory.Entry entry) {
        MaterialCardView card = new MaterialCardView(activity);
        card.setRadius(activity.dp(20));
        card.setCardElevation(0);
        card.setStrokeWidth(activity.dp(1));
        card.setStrokeColor(activity.color(com.google.android.material.R.attr.colorOutlineVariant));
        LinearLayout body = activity.column();
        body.setPadding(activity.dp(16), activity.dp(14), activity.dp(16), activity.dp(9));
        body.addView(activity.text(entry.title(), 16, false));
        String date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(entry.updated()));
        body.addView(activity.text(entry.source() + " · " + date + (entry.burned() ? " · 已嵌入弹幕" : ""),
                12, true), activity.spacing(4));
        boolean playable = entry.state() == VideoHistory.State.DONE && VideoHistory.available(activity, entry.uri());
        TextView status = activity.text(label(entry.state(), playable), 14, true);
        body.addView(status, activity.spacing(8));
        if (entry.note() != null && !entry.note().isBlank()) {
            body.addView(activity.text(entry.note(), 13, true), activity.spacing(4));
        }
        LinearLayout actions = new LinearLayout(activity);
        if (playable) {
            actions.addView(action("打开", () -> open.accept(entry.uri(), false)));
            actions.addView(action("分享", () -> open.accept(entry.uri(), true)));
        } else if (entry.state() == VideoHistory.State.QUEUED || entry.state() == VideoHistory.State.RUNNING) {
            actions.addView(action("取消", () -> cancel.accept(entry.id())));
        }
        if (entry.state() == VideoHistory.State.DONE && entry.uri() != null) {
            MaterialButton remove = action(activity.getString(R.string.history_delete), () -> delete.accept(entry));
            remove.setTextColor(activity.color(com.google.android.material.R.attr.colorError));
            actions.addView(remove);
        }
        if (entry.canRetry()) actions.addView(action("重试", () -> retry.accept(entry)));
        if (entry.state() == VideoHistory.State.RUNNING) {
            LinearProgressIndicator indicator = new LinearProgressIndicator(activity);
            body.addView(indicator, activity.spacing(8));
            Row row = new Row(status, indicator);
            active.put(entry.id(), row);
            setProgress(row, entry.stage(), entry.percent());
        }
        if (actions.getChildCount() > 0) body.addView(actions);
        card.addView(body);
        return card;
    }

    private MaterialButton action(String title, Runnable click) {
        MaterialButton button = activity.button(title, androidx.appcompat.R.attr.borderlessButtonStyle);
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private static void setProgress(Row row, String stage, int percent) {
        String text = stage == null ? "正在处理" : stage;
        row.status().setText(percent < 0 ? text : text + " · " + percent + "%");
        if (row.progress().isIndeterminate() != (percent < 0)) {
            row.progress().setVisibility(View.INVISIBLE);
            row.progress().setIndeterminate(percent < 0);
            row.progress().setVisibility(View.VISIBLE);
        }
        if (percent >= 0) row.progress().setProgressCompat(percent, false);
    }

    private static String label(VideoHistory.State state, boolean playable) {
        return switch (state) {
            case QUEUED -> "排队中";
            case RUNNING -> "正在处理";
            case DONE -> playable ? "已完成" : "文件已移除或无法访问";
            case FAILED -> "失败";
            case CANCELLED -> "已取消";
            case INTERRUPTED -> "已中断";
        };
    }

    private record Row(TextView status, LinearProgressIndicator progress) {}
}
