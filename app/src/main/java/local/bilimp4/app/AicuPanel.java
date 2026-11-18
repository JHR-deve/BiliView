package local.bilimp4.app;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.tabs.TabLayout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shows aicu comment and danmaku history for one account. */
final class AicuPanel implements AutoCloseable {
    private static final String[] TITLES = {"评论", "视频弹幕", "直播弹幕"};

    private final MainActivity activity;
    private final LinearLayout page;
    private final TextView title;
    private final TabLayout tabs;
    private final FrameLayout body;
    private final Map<Integer, LinearLayout> tabViews = new LinkedHashMap<>();
    private final Map<Integer, Boolean> tabLoaded = new LinkedHashMap<>();
    private final LinearProgressIndicator progress;

    private AicuBridge bridge;
    private long uid;
    private int requestId;

    AicuPanel(MainActivity activity) {
        this.activity = activity;
        page = activity.column();
        page.setPadding(activity.dp(16), activity.dp(8), activity.dp(16), activity.dp(24));

        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton back = activity.button("返回", com.google.android.material.R.attr.materialButtonOutlinedStyle);
        back.setMinWidth(activity.dp(72));
        back.setOnClickListener(view -> activity.backToProfile());
        bar.addView(back, new LinearLayout.LayoutParams(-2, -2));
        title = activity.text("评论与弹幕", 17, false);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.setMarginStart(activity.dp(12));
        bar.addView(title, titleParams);
        page.addView(bar);

        progress = new LinearProgressIndicator(activity);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        page.addView(progress, activity.spacing(12));

        tabs = new TabLayout(activity);
        tabs.setTabMode(TabLayout.MODE_FIXED);
        tabs.setTabGravity(TabLayout.GRAVITY_FILL);
        for (String name : TITLES) tabs.addTab(tabs.newTab().setText(name));
        page.addView(tabs, activity.spacing(14));

        body = new FrameLayout(activity);
        page.addView(body, activity.spacing(12));
        for (int index = 0; index < TITLES.length; index++) {
            LinearLayout container = activity.column();
            container.setVisibility(View.GONE);
            tabViews.put(index, container);
            tabLoaded.put(index, false);
            body.addView(container, new FrameLayout.LayoutParams(-1, -2));
        }
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { show(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
    }

    View view() { return page; }

    void open(long uid, String name) {
        this.uid = uid;
        this.requestId++;
        title.setText(name == null || name.isEmpty() ? "UID " + uid : name);
        for (Map.Entry<Integer, LinearLayout> entry : tabViews.entrySet()) {
            entry.getValue().removeAllViews();
            entry.getValue().setVisibility(View.GONE);
            entry.getValue().setAlpha(1f);
            tabLoaded.put(entry.getKey(), false);
        }
        if (tabs.getTabCount() > 0) tabs.selectTab(tabs.getTabAt(0));
        page.setAlpha(0f);
        page.animate().alpha(1f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
        show(0);
    }

    private void show(int index) {
        for (Map.Entry<Integer, LinearLayout> entry : tabViews.entrySet()) {
            entry.getValue().setVisibility(entry.getKey() == index ? View.VISIBLE : View.GONE);
        }
        LinearLayout container = tabViews.get(index);
        if (container == null) return;
        container.setAlpha(0f);
        container.animate().alpha(1f).setDuration(170).start();
        if (Boolean.TRUE.equals(tabLoaded.get(index)) || uid <= 0) return;
        tabLoaded.put(index, true);
        load(index, container);
    }

    private void load(int index, LinearLayout container) {
        if (bridge == null) bridge = new AicuBridge(activity);
        long target = uid;
        int ticket = requestId;
        placeholder(container, "正在读取…");
        progress.setVisibility(View.VISIBLE);
        Runnable done = () -> {
            progress.setVisibility(View.GONE);
            container.setAlpha(0f);
            container.animate().alpha(1f).setDuration(180).start();
        };
        if (index == 0) {
            AicuClient.replies(bridge, target, page -> activity.runOnUiThread(() -> {
                if (ticket != requestId || activity.isFinishing()) return;
                renderReplies(container, page);
                done.run();
            }), message -> activity.runOnUiThread(() -> {
                if (ticket != requestId || activity.isFinishing()) return;
                tabLoaded.put(index, false);
                placeholder(container, message);
                done.run();
            }));
        } else {
            boolean live = index == 2;
            AicuClient.danmaku(bridge, target, live, page -> activity.runOnUiThread(() -> {
                if (ticket != requestId || activity.isFinishing()) return;
                renderDanmaku(container, page, live);
                done.run();
            }), message -> activity.runOnUiThread(() -> {
                if (ticket != requestId || activity.isFinishing()) return;
                tabLoaded.put(index, false);
                placeholder(container, message);
                done.run();
            }));
        }
    }

    private void renderReplies(LinearLayout container, AicuClient.ReplyPage page) {
        container.removeAllViews();
        if (page.list().isEmpty()) {
            placeholder(container, "没有查询到评论记录。");
            return;
        }
        container.addView(summary("共 " + (page.total() > 0 ? page.total() : page.list().size()) + " 条 · 显示 "
                + page.list().size() + " 条"));
        for (AicuClient.Reply reply : page.list()) {
            container.addView(item(reply.time(), reply.message(), null), activity.spacing(10));
        }
    }

    private void renderDanmaku(LinearLayout container, AicuClient.DanmakuPage page, boolean live) {
        container.removeAllViews();
        if (page.list().isEmpty()) {
            placeholder(container, "没有查询到" + (live ? "直播" : "视频") + "弹幕。");
            return;
        }
        container.addView(summary("共 " + (page.total() > 0 ? page.total() : page.list().size()) + " 条 · 显示 "
                + page.list().size() + " 条"));
        for (AicuClient.Danmaku danmaku : page.list()) {
            StringBuilder head = new StringBuilder(danmaku.time());
            if (!danmaku.extra().isEmpty()) head.append(" · ").append(danmaku.extra());
            if (!danmaku.user().isEmpty()) head.append(" · ").append(danmaku.user());
            container.addView(item(head.toString(), danmaku.content(), null), activity.spacing(10));
        }
    }

    private MaterialCardView summary(String text) {
        MaterialCardView card = new MaterialCardView(activity);
        card.setCardElevation(0);
        card.setStrokeWidth(0);
        card.setRadius(activity.dp(16));
        card.setCardBackgroundColor(activity.color(com.google.android.material.R.attr.colorSurfaceVariant));
        LinearLayout body = activity.column();
        int pad = activity.dp(14);
        body.setPadding(pad, pad, pad, pad);
        body.addView(activity.text(text, 13, true));
        card.addView(body);
        return card;
    }

    private MaterialCardView item(String head, String content, String ignored) {
        MaterialCardView card = new MaterialCardView(activity);
        card.setCardElevation(0);
        card.setStrokeWidth(0);
        card.setRadius(activity.dp(16));
        card.setCardBackgroundColor(activity.color(com.google.android.material.R.attr.colorSurfaceVariant));
        LinearLayout body = activity.column();
        int pad = activity.dp(14);
        body.setPadding(pad, pad, pad, pad);
        body.addView(activity.text(head, 12, true));
        TextView message = activity.text(content, 14, false);
        message.setLineSpacing(activity.dp(3), 1f);
        body.addView(message, activity.spacing(6));
        card.addView(body);
        return card;
    }

    private void placeholder(LinearLayout container, String message) {
        container.removeAllViews();
        MaterialCardView card = new MaterialCardView(activity);
        card.setCardElevation(0);
        card.setStrokeWidth(0);
        card.setRadius(activity.dp(16));
        card.setCardBackgroundColor(activity.color(com.google.android.material.R.attr.colorSurfaceVariant));
        LinearLayout body = activity.column();
        int pad = activity.dp(16);
        body.setPadding(pad, pad, pad, pad);
        body.addView(activity.text(message, 13, true));
        card.addView(body);
        container.addView(card);
    }

    @Override
    public void close() {
        requestId++;
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
    }
}