package local.bilimp4.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
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
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shows a Bilibili member card read from the official API. */
final class CompositionPanel implements AutoCloseable {
    private record Block(MaterialCardView card, LinearLayout body) {}

    private final MainActivity activity;
    private final LinearLayout page;
    private final TextInputEditText input;
    private final MaterialButton search;
    private final LinearProgressIndicator progress;
    private final TextView status;
    private final LinearLayout results;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private long requestId;
    private long currentMid;

    CompositionPanel(MainActivity activity) {
        this.activity = activity;
        page = activity.column();
        page.setPadding(activity.dp(16), activity.dp(8), activity.dp(16), activity.dp(24));

        TextInputLayout field = new TextInputLayout(activity);
        field.setHint("B 站 UID");
        field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        input = new TextInputEditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        field.addView(input);
        page.addView(field, activity.spacing(16));

        search = activity.button("查询", com.google.android.material.R.attr.materialButtonStyle);
        page.addView(search, activity.spacing(10));

        progress = new LinearProgressIndicator(activity);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        page.addView(progress, activity.spacing(12));

        status = activity.text("", 13, true);
        status.setVisibility(View.GONE);
        page.addView(status, activity.spacing(10));

        results = activity.column();
        results.setAlpha(0f);
        results.setVisibility(View.GONE);
        page.addView(results, activity.spacing(14));

        search.setOnClickListener(view -> lookup());
        input.setOnEditorActionListener((view, action, event) -> {
            lookup();
            return true;
        });
    }

    View view() { return page; }

    void setVisible(boolean visible) {
        page.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    long currentMid() { return currentMid; }

    void query(long mid) {
        input.setText(Long.toString(mid));
        if (input.length() > 0) input.setSelection(input.length());
        lookup();
    }

    private void lookup() {
        String raw = input.getText() == null ? "" : input.getText().toString().trim();
        if (raw.isEmpty() || !raw.matches("[0-9]{1,18}")) {
            showStatus("请输入纯数字 UID。");
            return;
        }
        long target = Long.parseLong(raw);
        hideKeyboard();
        long ticket = ++requestId;
        setBusy(true);
        showStatus("");
        results.setVisibility(View.GONE);
        results.setAlpha(0f);
        String cookie = activity.biliCookie();
        worker.execute(() -> {
            try {
                BiliProfileClient.Profile profile = BiliProfileClient.load(target, cookie, null);
                activity.runOnUiThread(() -> {
                    if (ticket != requestId || activity.isFinishing()) return;
                    currentMid = target;
                    render(target, profile);
                    setBusy(false);
                    reveal();
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (ticket != requestId || activity.isFinishing()) return;
                    setBusy(false);
                    showStatus(friendly(error));
                });
            }
        });
    }

    private void reveal() {
        results.setVisibility(View.VISIBLE);
        results.setTranslationY(activity.dp(14));
        results.animate().alpha(1f).translationY(0f).setDuration(240)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void render(long mid, BiliProfileClient.Profile profile) {
        results.removeAllViews();
        results.addView(hero(mid, profile), activity.spacing(0));
        MaterialButton history = activity.button("查看评论与弹幕",
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        history.setOnClickListener(view -> activity.openAicu(mid, profile.name()));
        results.addView(history, activity.spacing(14));
    }

    private MaterialCardView hero(long mid, BiliProfileClient.Profile profile) {
        MaterialCardView card = new MaterialCardView(activity);
        card.setCardElevation(0);
        card.setStrokeWidth(0);
        card.setRadius(activity.dp(24));
        card.setCardBackgroundColor(activity.color(com.google.android.material.R.attr.colorSurfaceVariant));

        LinearLayout column = activity.column();
        if (!profile.banner().isEmpty()) {
            FrameLayout header = new FrameLayout(activity);
            ImageView banner = new ImageView(activity);
            banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
            header.addView(banner, new FrameLayout.LayoutParams(-1, activity.dp(104)));
            View scrim = new View(activity);
            scrim.setBackgroundColor(Color.parseColor("#33000000"));
            header.addView(scrim, new FrameLayout.LayoutParams(-1, activity.dp(104)));
            column.addView(header);
            loadImage(banner, profile.banner(), 1400);
        }

        LinearLayout identity = activity.column();
        int pad = activity.dp(18);
        identity.setPadding(pad, pad, pad, pad);
        identity.setGravity(Gravity.CENTER_HORIZONTAL);

        MaterialCardView avatarCard = new MaterialCardView(activity);
        avatarCard.setRadius(activity.dp(32));
        avatarCard.setCardElevation(0);
        avatarCard.setStrokeWidth(0);
        ImageView avatar = new ImageView(activity);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarCard.addView(avatar, new FrameLayout.LayoutParams(activity.dp(64), activity.dp(64)));
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(activity.dp(64), activity.dp(64));
        if (!profile.banner().isEmpty()) avatarParams.topMargin = activity.dp(-34);
        identity.addView(avatarCard, avatarParams);
        loadImage(avatar, profile.face(), 320);

        TextView name = activity.text(profile.name(), 19, false);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setGravity(Gravity.CENTER);
        identity.addView(name, activity.spacing(10));

        TextView meta = activity.text("UID " + mid + " · Lv" + profile.level() + " · " + profile.sex(), 13, true);
        meta.setGravity(Gravity.CENTER);
        identity.addView(meta, activity.spacing(4));

        ChipGroup chips = chipGroup();
        if (!profile.vipLabel().isEmpty()) chips.addView(chip(profile.vipLabel(), false));
        if (!profile.official().isEmpty()) chips.addView(chip(profile.official(), false));
        Chip fans = chip("粉丝 " + format(profile.fans()), true);
        fans.setClickable(true);
        fans.setOnClickListener(view -> activity.openRelation(mid, "fans", "粉丝"));
        chips.addView(fans);
        Chip following = chip("关注 " + format(profile.following()), true);
        following.setClickable(true);
        following.setOnClickListener(view -> activity.openRelation(mid, "followings", "关注"));
        chips.addView(following);
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, -2);
        chipParams.topMargin = activity.dp(10);
        identity.addView(chips, chipParams);

        if (!profile.sign().isEmpty()) {
            TextView sign = activity.text(profile.sign(), 14, true);
            sign.setGravity(Gravity.CENTER);
            identity.addView(sign, activity.spacing(12));
        }
        column.addView(identity);
        card.addView(column);
        return card;
    }

    private Block block(String title) {
        MaterialCardView card = new MaterialCardView(activity);
        card.setCardElevation(0);
        card.setStrokeWidth(0);
        card.setRadius(activity.dp(20));
        card.setCardBackgroundColor(activity.color(com.google.android.material.R.attr.colorSurfaceVariant));
        LinearLayout body = activity.column();
        int pad = activity.dp(16);
        body.setPadding(pad, pad, pad, pad);
        if (title != null && !title.isEmpty()) {
            TextView header = activity.text(title, 15, false);
            header.setTypeface(Typeface.DEFAULT_BOLD);
            body.addView(header);
        }
        card.addView(body);
        return new Block(card, body);
    }

    private Chip chip(String label, boolean subtle) {
        Chip chip = new Chip(activity);
        chip.setText(label);
        chip.setCheckable(false);
        chip.setClickable(false);
        chip.setChipCornerRadius(activity.dp(14));
        if (subtle) {
            chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                    activity.color(com.google.android.material.R.attr.colorSurface)));
        }
        return chip;
    }

    private ChipGroup chipGroup() {
        ChipGroup group = new ChipGroup(activity);
        group.setChipSpacingHorizontal(activity.dp(8));
        group.setChipSpacingVertical(activity.dp(6));
        group.setSingleLine(false);
        return group;
    }

    private void setBusy(boolean busy) {
        search.setEnabled(!busy);
        input.setEnabled(!busy);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void showStatus(String message) {
        if (message == null || message.isEmpty()) {
            status.setVisibility(View.GONE);
            return;
        }
        status.setText(message);
        status.setVisibility(View.VISIBLE);
        status.setAlpha(0f);
        status.animate().alpha(1f).setDuration(160).start();
    }

    private void loadImage(ImageView view, String address, int edge) {
        if (address == null || address.isEmpty()) return;
        worker.execute(() -> {
            Bitmap bitmap = BiliImage.load(address, edge);
            if (bitmap == null) return;
            activity.runOnUiThread(() -> view.setImageBitmap(bitmap));
        });
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private static String format(long value) {
        if (value >= 100000000L) return String.format(Locale.CHINA, "%.1f 亿", value / 100000000f);
        if (value >= 10000) return String.format(Locale.CHINA, "%.1f 万", value / 10000f);
        return Long.toString(value);
    }

    private static String friendly(Exception error) {
        return "请求频繁，请稍后重试";
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}