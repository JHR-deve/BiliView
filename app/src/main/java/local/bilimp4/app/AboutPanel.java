package local.bilimp4.app;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

final class AboutPanel {
    private static final String REPOSITORY = "https://github.com/JHR-deve/BiliMP4-Android";

    static LinearLayout create(MainActivity activity) {
        LinearLayout page = activity.column();
        MaterialCardView hero = new MaterialCardView(activity);
        hero.setCardElevation(0);
        hero.setStrokeWidth(0);
        hero.setRadius(activity.dp(24));
        hero.setCardBackgroundColor(activity.color(com.google.android.material.R.attr.colorSecondaryContainer));
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(activity.dp(18), activity.dp(18), activity.dp(18), activity.dp(18));
        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.ic_launcher);
        row.addView(icon, new LinearLayout.LayoutParams(activity.dp(58), activity.dp(58)));
        LinearLayout labels = activity.column();
        var name = activity.text(activity.getString(R.string.app_name), 26, false);
        var version = activity.text(activity.getString(R.string.about_version, BuildConfig.VERSION_NAME), 14, true);
        int foreground = activity.color(com.google.android.material.R.attr.colorOnSecondaryContainer);
        name.setTextColor(foreground);
        version.setTextColor(foreground);
        labels.addView(name);
        labels.addView(version, activity.spacing(2));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
        size.setMarginStart(activity.dp(16));
        row.addView(labels, size);
        hero.addView(row);
        page.addView(hero);

        section(activity, page, R.string.about_project_title, R.string.about_project);
        section(activity, page, R.string.about_access_title, R.string.about_access);
        addLink(activity, page, R.string.shizuku_guide_title, "https://shizuku.rikka.app/zh-hans/guide/setup/");
        section(activity, page, R.string.about_source_title, R.string.about_source);
        addLink(activity, page, R.string.about_github, REPOSITORY);
        addLink(activity, page, R.string.about_license, REPOSITORY + "/blob/main/LICENSE");
        return page;
    }

    private static void section(MainActivity activity, LinearLayout page, int title, int description) {
        page.addView(activity.text(activity.getString(title), 19, false), activity.spacing(20));
        page.addView(activity.text(activity.getString(description), 14, true), activity.spacing(6));
    }

    private static void addLink(MainActivity activity, LinearLayout page, int label, String url) {
        MaterialButton link = activity.button(activity.getString(label),
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        link.setOnClickListener(view -> {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException error) {
                Toast.makeText(activity, "没有可打开链接的浏览器", Toast.LENGTH_SHORT).show();
            }
        });
        page.addView(link, activity.spacing(8));
    }
}
