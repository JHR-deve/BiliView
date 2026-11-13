package local.bilimp4.app;

import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

final class DanmakuControls {
    private final MainActivity activity;
    private final LinearLayout controls;
    private final MaterialCheckBox comments;
    private final MaterialButton settings;

    DanmakuControls(MainActivity activity, LinearLayout parent, boolean checkedByDefault) {
        this.activity = activity;
        controls = activity.column();
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        comments = new MaterialCheckBox(activity);
        comments.setText("将弹幕嵌入 MP4");
        comments.setChecked(checkedByDefault);
        comments.setMinHeight(activity.dp(44));
        row.addView(comments, new LinearLayout.LayoutParams(0, -2, 1));
        settings = activity.button("弹幕设置", androidx.appcompat.R.attr.borderlessButtonStyle);
        settings.setOnClickListener(view -> showSettings(activity));
        row.addView(settings, new LinearLayout.LayoutParams(-2, -2));
        controls.addView(row);
        TextView hint = activity.text("烧录会重新编码视频，弹幕会固定在画面里。", 12, true);
        controls.addView(hint, activity.spacing(2));
        hint.setVisibility(checkedByDefault ? View.VISIBLE : View.GONE);
        comments.setOnCheckedChangeListener((button, checked) -> {
            settings.setEnabled(checked && comments.isEnabled());
            hint.setVisibility(checked ? View.VISIBLE : View.GONE);
        });
        parent.addView(controls, activity.spacing(4));
        setBusy(false);
    }

    void setVisible(boolean visible) {
        controls.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    DanmakuStyle.Options options() {
        return comments.isChecked() ? loadOptions(activity) : null;
    }

    void setBusy(boolean busy) {
        comments.setEnabled(!busy);
        settings.setEnabled(!busy && comments.isChecked());
    }

    private static DanmakuStyle.Options loadOptions(MainActivity activity) {
        SharedPreferences preferences = activity.getPreferences(0);
        DanmakuStyle defaults = DanmakuStyle.DEFAULT;
        DanmakuStyle local = new DanmakuStyle(preferences.getInt("danmakuSize", Math.round(defaults.scale() * 100)) / 100f,
                preferences.getInt("danmakuOpacity", Math.round(defaults.opacity() * 100)) / 100f,
                preferences.getBoolean("danmakuColors", true), defaults.speed(), defaults.area());
        return new DanmakuStyle.Options(preferences.getBoolean("danmakuFollowBili", true), local);
    }

    static void showSettings(MainActivity activity) {
        DanmakuStyle.Options options = loadOptions(activity);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout panel = activity.column();
        panel.setPadding(activity.dp(24), activity.dp(8), activity.dp(24), activity.dp(8));
        scroll.addView(panel);

        MaterialSwitch follow = new MaterialSwitch(activity);
        follow.setText("跟随 B 站弹幕设置");
        follow.setChecked(options.followBili());
        follow.setMinHeight(activity.dp(48));
        panel.addView(follow, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(activity.text("登录后会读取 B 站提供的字号比例、不透明度、速度和显示范围。"
                + "未返回的项目使用下方设置。", 13, true), activity.spacing(6));

        Slider size = addSlider(activity, panel, "本机字号", 60, 160, Math.round(options.local().scale() * 100));
        Slider opacity = addSlider(activity, panel, "本机不透明度", 20, 100, Math.round(options.local().opacity() * 100));
        MaterialSwitch colors = new MaterialSwitch(activity);
        colors.setText("保留弹幕原色");
        colors.setChecked(options.local().originalColors());
        colors.setMinHeight(activity.dp(48));
        panel.addView(colors, activity.spacing(6));
        panel.addView(activity.text("关闭原色时使用白字黑边。字号会随视频尺寸缩放，密集弹幕自动避让。", 13, true),
                activity.spacing(4));

        new MaterialAlertDialogBuilder(activity).setTitle("弹幕设置").setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("保存", (dialog, which) -> {
                    int font = Math.round(size.getValue());
                    int alpha = Math.round(opacity.getValue());
                    activity.getPreferences(0).edit()
                            .putBoolean("danmakuFollowBili", follow.isChecked())
                            .putInt("danmakuSize", font)
                            .putInt("danmakuOpacity", alpha)
                            .putBoolean("danmakuColors", colors.isChecked())
                            .apply();
                }).show();
    }

    private static Slider addSlider(MainActivity activity, LinearLayout panel, String name, int min, int max, int value) {
        TextView label = activity.text(name + "  " + value + "%", 14, false);
        panel.addView(label, activity.spacing(12));
        Slider slider = new Slider(activity);
        slider.setContentDescription(name);
        slider.setValueFrom(min);
        slider.setValueTo(max);
        slider.setStepSize(5);
        slider.setValue(value);
        slider.setLabelFormatter(item -> Math.round(item) + "%");
        slider.addOnChangeListener((control, change, user) -> label.setText(name + "  " + Math.round(change) + "%"));
        panel.addView(slider, new LinearLayout.LayoutParams(-1, -2));
        return slider;
    }

}
