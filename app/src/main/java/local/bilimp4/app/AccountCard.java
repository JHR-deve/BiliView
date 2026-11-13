package local.bilimp4.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.shape.ShapeAppearanceModel;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AccountCard implements AutoCloseable {
    static final int LOGIN = 2;
    private final MainActivity activity;
    private final Runnable sessionChanged;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ShapeableImageView avatar;
    private final TextView title;
    private final TextView info;
    private final MaterialButton action;
    private String cookie;
    private BiliAccount.Profile profile;
    private int generation;
    private boolean busy;
    private boolean closed;

    AccountCard(MainActivity activity, LinearLayout panel, Runnable sessionChanged) {
        this.activity = activity;
        this.sessionChanged = sessionChanged;
        MaterialCardView card = new MaterialCardView(activity);
        card.setRadius(activity.dp(24));
        card.setCardElevation(0);
        card.setStrokeWidth(0);
        card.setCardBackgroundColor(activity.color(com.google.android.material.R.attr.colorSecondaryContainer));

        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(activity.dp(16), activity.dp(13), activity.dp(12), activity.dp(13));

        int iconSize = activity.dp(46);
        FrameLayout icon = new FrameLayout(activity);
        TextView initials = activity.text("B", 21, false);
        initials.setGravity(Gravity.CENTER);
        initials.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        initials.setTextColor(activity.color(com.google.android.material.R.attr.colorOnPrimary));
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(activity.color(com.google.android.material.R.attr.colorPrimary));
        initials.setBackground(circle);
        icon.addView(initials, new FrameLayout.LayoutParams(-1, -1));
        avatar = new ShapeableImageView(activity);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setShapeAppearanceModel(ShapeAppearanceModel.builder()
                .setAllCornerSizes(iconSize / 2f).build());
        avatar.setVisibility(View.GONE);
        icon.addView(avatar, new FrameLayout.LayoutParams(-1, -1));
        row.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

        LinearLayout labels = activity.column();
        int foreground = activity.color(com.google.android.material.R.attr.colorOnSecondaryContainer);
        title = activity.text("游客模式", 17, false);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextColor(foreground);
        labels.addView(title);
        info = activity.text(activity.getString(R.string.account_guest_hint), 12, true);
        info.setTextColor(foreground);
        labels.addView(info, activity.spacing(3));
        LinearLayout.LayoutParams labelSize = new LinearLayout.LayoutParams(0, -2, 1);
        labelSize.setMarginStart(activity.dp(12));
        row.addView(labels, labelSize);

        action = activity.button("登录 B 站", androidx.appcompat.R.attr.borderlessButtonStyle);
        action.setTextColor(foreground);
        action.setOnClickListener(view -> accountAction());
        row.addView(action, new LinearLayout.LayoutParams(-2, -2));
        card.addView(row);
        panel.addView(card);
        MaterialButton help = activity.button(activity.getString(R.string.account_help_title),
                androidx.appcompat.R.attr.borderlessButtonStyle);
        help.setOnClickListener(view -> new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.account_help_title)
                .setMessage(R.string.account_help_body)
                .setPositiveButton(R.string.account_help_done, null)
                .show());
        panel.addView(help, new LinearLayout.LayoutParams(-2, -2));
        refresh();
    }

    String cookie() {
        return cookie;
    }

    void setBusy(boolean busy) {
        this.busy = busy;
        action.setEnabled(!busy);
    }

    private void accountAction() {
        if (busy) return;
        if (cookie == null) {
            activity.startActivityForResult(new Intent(activity, LoginActivity.class), LOGIN);
            return;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(profile == null ? "B 站账号" : profile.name())
                .setItems(new String[]{"刷新账号信息", "退出登录"}, (dialog, which) -> {
                    if (which == 0) refresh();
                    else signOut();
                }).show();
    }

    void refresh() {
        int request = ++generation;
        worker.execute(() -> {
            String saved;
            try {
                saved = BiliAccount.restore(activity);
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (active(request)) display(null, null, "本地会话无法读取，请重新登录。");
                });
                return;
            }
            if (saved == null) {
                activity.runOnUiThread(() -> {
                    if (active(request)) display(null, null, null);
                });
                return;
            }
            BiliAccount.Profile account;
            try {
                account = BiliAccount.profile(saved);
            } catch (BiliAccount.Expired expired) {
                activity.runOnUiThread(() -> {
                    if (!active(request)) return;
                    BiliAccount.clear(activity);
                    display(null, null, "登录已过期，请重新扫码。");
                });
                return;
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (active(request)) display(saved, null, "暂时无法获取账号信息");
                });
                return;
            }
            activity.runOnUiThread(() -> {
                if (active(request)) display(saved, account, null);
            });
            Bitmap photo = BiliImage.load(account.avatar(), 144);
            activity.runOnUiThread(() -> {
                if (active(request) && photo != null) {
                    avatar.setImageBitmap(photo);
                    avatar.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private void signOut() {
        generation++;
        BiliAccount.clear(activity);
        display(null, null, null);
    }

    private void display(String next, BiliAccount.Profile account, String message) {
        boolean changed = !Objects.equals(cookie, next);
        cookie = next;
        profile = account;
        avatar.setImageDrawable(null);
        avatar.setVisibility(View.GONE);
        if (account == null) {
            title.setText(next == null ? "游客模式" : "B 站账号");
            info.setText(message == null ? activity.getString(R.string.account_guest_hint) : message);
        } else {
            title.setText(account.name());
            info.setText("UID " + account.uid() + " · Lv." + account.level()
                    + "\n" + (account.vip() ? "大会员" : "普通账号"));
        }
        action.setText(next == null ? "登录 B 站" : "账号");
        if (changed) sessionChanged.run();
    }

    private boolean active(int request) {
        return !closed && request == generation;
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
    }
}
