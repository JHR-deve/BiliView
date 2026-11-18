package local.bilimp4.app;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.checkbox.MaterialCheckBox;

final class AudioOnlyControl {
    private static final String KEY = "audioOnly";
    private final MainActivity activity;
    private final MaterialCheckBox box;
    private final TextView hint;
    private Runnable listener;

    AudioOnlyControl(MainActivity activity, LinearLayout parent) {
        this.activity = activity;
        box = new MaterialCheckBox(activity);
        box.setText("仅下载音频（M4A）");
        box.setMinHeight(activity.dp(44));
        box.setChecked(saved(activity));
        hint = activity.text("只保存音轨为 M4A，不会下载画面，也不能嵌入弹幕。", 12, true);
        box.setOnCheckedChangeListener((view, checked) -> {
            save(activity, checked);
            showHint();
            if (listener != null) listener.run();
        });
        parent.addView(box, activity.spacing(4));
        parent.addView(hint, activity.spacing(2));
        showHint();
    }

    static boolean saved(MainActivity activity) {
        return activity.getPreferences(0).getBoolean(KEY, false);
    }

    static void save(MainActivity activity, boolean value) {
        activity.getPreferences(0).edit().putBoolean(KEY, value).apply();
    }

    void onChange(Runnable listener) {
        this.listener = listener;
    }

    boolean checked() {
        return box.isChecked();
    }

    void setBusy(boolean busy) {
        box.setEnabled(!busy);
    }

    void sync() {
        if (box.isChecked() != saved(activity)) box.setChecked(saved(activity));
        else showHint();
    }

    private void showHint() {
        hint.setVisibility(box.isChecked() ? View.VISIBLE : View.GONE);
    }
}