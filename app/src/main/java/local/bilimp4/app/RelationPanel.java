package local.bilimp4.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lists the followers or followings of a Bilibili member with a local filter. */
final class RelationPanel implements AutoCloseable {
    private final MainActivity activity;
    private final LinearLayout page;
    private final TextView title;
    private final TextInputEditText search;
    private final TextView summary;
    private final LinearLayout list;
    private final MaterialButton more;
    private final LinearProgressIndicator progress;
    private final TextView status;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<BiliProfileClient.Member> members = new ArrayList<>();

    private long mid;
    private String kind = "fans";
    private int pageNum;
    private boolean busy;
    private boolean hasMore;
    private long requestId;

    RelationPanel(MainActivity activity) {
        this.activity = activity;
        page = activity.column();
        page.setPadding(activity.dp(16), activity.dp(8), activity.dp(16), activity.dp(24));

        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton back = activity.button("返回", com.google.android.material.R.attr.materialButtonOutlinedStyle);
        back.setMinWidth(activity.dp(72));
        back.setOnClickListener(view -> activity.backToProfile());
        bar.addView(back, new LinearLayout.LayoutParams(-2, -2));
        title = activity.text("列表", 17, false);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.setMarginStart(activity.dp(12));
        bar.addView(title, titleParams);
        page.addView(bar);

        TextInputLayout field = new TextInputLayout(activity);
        field.setHint("按昵称或 UID 搜索");
        field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        search = new TextInputEditText(activity);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setSingleLine(true);
        field.addView(search);
        page.addView(field, activity.spacing(14));

        summary = activity.text("", 13, true);
        page.addView(summary, activity.spacing(10));

        progress = new LinearProgressIndicator(activity);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        page.addView(progress, activity.spacing(10));

        status = activity.text("", 13, true);
        status.setVisibility(View.GONE);
        page.addView(status, activity.spacing(8));

        list = activity.column();
        page.addView(list, activity.spacing(6));

        more = activity.button("加载更多", com.google.android.material.R.attr.materialButtonOutlinedStyle);
        more.setVisibility(View.GONE);
        more.setOnClickListener(view -> load(true));
        page.addView(more, activity.spacing(12));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { render(); }
        });
    }

    View view() { return page; }

    void open(long mid, String kind, String label) {
        this.mid = mid;
        this.kind = kind;
        this.pageNum = 0;
        this.hasMore = false;
        this.busy = false;
        this.requestId++;
        members.clear();
        list.removeAllViews();
        more.setVisibility(View.GONE);
        search.setText("");
        title.setText(label);
        summary.setText("");
        status.setVisibility(View.GONE);
        load(false);
    }

    private void load(boolean append) {
        if (busy || mid <= 0) return;
        if (append && !hasMore) return;
        long ticket = ++requestId;
        busy = true;
        progress.setVisibility(View.VISIBLE);
        more.setEnabled(false);
        status.setVisibility(View.GONE);
        int next = append ? pageNum + 1 : 1;
        long target = mid;
        String type = kind;
        String cookie = activity.biliCookie();
        worker.execute(() -> {
            try {
                List<BiliProfileClient.Member> loaded = BiliProfileClient.relation(type, target, cookie, null, next);
                activity.runOnUiThread(() -> {
                    if (ticket != requestId || activity.isFinishing()) return;
                    busy = false;
                    progress.setVisibility(View.GONE);
                    more.setEnabled(true);
                    pageNum = next;
                    hasMore = loaded.size() >= 50;
                    if (append) members.addAll(loaded); else {
                        members.clear();
                        members.addAll(loaded);
                    }
                    render();
                    if (!append) {
                        page.setAlpha(0f);
                        page.animate().alpha(1f).setDuration(180)
                                .setInterpolator(new DecelerateInterpolator()).start();
                    }
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (ticket != requestId || activity.isFinishing()) return;
                    busy = false;
                    progress.setVisibility(View.GONE);
                    more.setEnabled(true);
                    showStatus(message(error));
                });
            }
        });
    }

    private void render() {
        list.removeAllViews();
        String keyword = search.getText() == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<BiliProfileClient.Member> filtered = new ArrayList<>();
        for (BiliProfileClient.Member member : members) {
            if (keyword.isEmpty()
                    || member.name().toLowerCase(Locale.ROOT).contains(keyword)
                    || Long.toString(member.mid()).contains(keyword)) {
                filtered.add(member);
            }
        }
        String label = "粉丝".equals(title.getText().toString()) ? "粉丝" : "关注";
        summary.setText(keyword.isEmpty()
                ? "已加载 " + members.size() + " 个" + label + (hasMore ? "，可继续加载" : "")
                : "在已加载的 " + members.size() + " 个" + label + "中找到 " + filtered.size() + " 个");
        more.setVisibility(hasMore ? View.VISIBLE : View.GONE);

        if (filtered.isEmpty()) {
            Block block = new Block(members.isEmpty()
                    ? "对方开启了隐私设置，" + label + "列表不可见。"
                    : "没有匹配的结果");
            list.addView(block.card(), activity.spacing(10));
            return;
        }
        for (BiliProfileClient.Member member : filtered) {
            list.addView(row(member), activity.spacing(8));
        }
    }

    private MaterialCardView row(BiliProfileClient.Member member) {
        MaterialCardView card = new MaterialCardView(activity);
        card.setCardElevation(0);
        card.setStrokeWidth(0);
        card.setRadius(activity.dp(16));
        card.setCardBackgroundColor(activity.color(com.google.android.material.R.attr.colorSurfaceVariant));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> activity.showProfile(member.mid()));

        LinearLayout line = new LinearLayout(activity);
        line.setGravity(Gravity.CENTER_VERTICAL);
        int pad = activity.dp(12);
        line.setPadding(pad, pad, pad, pad);

        MaterialCardView avatarCard = new MaterialCardView(activity);
        avatarCard.setRadius(activity.dp(22));
        avatarCard.setCardElevation(0);
        avatarCard.setStrokeWidth(0);
        ImageView avatar = new ImageView(activity);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarCard.addView(avatar, new FrameLayout.LayoutParams(activity.dp(44), activity.dp(44)));
        line.addView(avatarCard, new LinearLayout.LayoutParams(activity.dp(44), activity.dp(44)));
        loadImage(avatar, member.face());

        LinearLayout labels = activity.column();
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.setMarginStart(activity.dp(12));
        line.addView(labels, labelParams);

        TextView name = activity.text(member.name(), 15, false);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        labels.addView(name);
        String detail = "UID " + member.mid();
        if (!member.sign().isEmpty()) detail = detail + " · " + member.sign();
        TextView meta = activity.text(detail, 12, true);
        meta.setMaxLines(1);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(meta, activity.spacing(3));

        card.addView(line);
        return card;
    }

    private final class Block {
        private final MaterialCardView card;

        Block(String message) {
            card = new MaterialCardView(activity);
            card.setCardElevation(0);
            card.setStrokeWidth(0);
            card.setRadius(activity.dp(16));
            card.setCardBackgroundColor(activity.color(com.google.android.material.R.attr.colorSurfaceVariant));
            LinearLayout body = activity.column();
            int pad = activity.dp(16);
            body.setPadding(pad, pad, pad, pad);
            body.addView(activity.text(message, 13, true));
            card.addView(body);
        }

        MaterialCardView card() { return card; }
    }

    private void showStatus(String message) {
        status.setText(message);
        status.setVisibility(View.VISIBLE);
        status.setAlpha(0f);
        status.animate().alpha(1f).setDuration(160).start();
    }

    private void loadImage(ImageView view, String address) {
        if (address == null || address.isEmpty()) return;
        worker.execute(() -> {
            Bitmap bitmap = BiliImage.load(address, 96);
            if (bitmap == null) return;
            activity.runOnUiThread(() -> view.setImageBitmap(bitmap));
        });
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private String message(Exception error) {
        String text = error.getMessage();
        if (text != null && !text.isEmpty()) return text;
        return "对方开启了隐私设置，" + ("fans".equals(kind) ? "粉丝" : "关注") + "列表不可见。";
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}