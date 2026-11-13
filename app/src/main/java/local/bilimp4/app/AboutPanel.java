package local.bilimp4.app;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONException;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AboutPanel implements AutoCloseable {
    private static final String REPOSITORY = "https://github.com/JHR-deve/BiliView";
    private static final String RELEASES = REPOSITORY + "/releases";
    private final MainActivity activity;
    private final LinearLayout page;
    private final TextView updateStatus;
    private final MaterialButton checkUpdate;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    AboutPanel(MainActivity activity) {
        this.activity = activity;
        page = activity.column();
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
        var version = activity.text(activity.getString(R.string.about_version, BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE), 14, true);
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

        page.addView(activity.text(activity.getString(R.string.about_update_title), 19, false), activity.spacing(20));
        boolean hasFeed = !BuildConfig.UPDATE_FEED_URL.isBlank();
        checkUpdate = activity.button(activity.getString(R.string.about_update_check),
                com.google.android.material.R.attr.materialButtonStyle);
        checkUpdate.setOnClickListener(view -> {
            if (hasFeed) checkForUpdate();
            else openLink(RELEASES);
        });
        page.addView(checkUpdate, activity.spacing(8));
        updateStatus = activity.text("", 14, true);
        updateStatus.setVisibility(View.GONE);
        page.addView(updateStatus, activity.spacing(6));
        if (hasFeed) addLink(R.string.about_update_releases, RELEASES);
        MaterialButton changes = activity.button(activity.getString(R.string.about_changes_title),
                androidx.appcompat.R.attr.borderlessButtonStyle);
        changes.setOnClickListener(view -> new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.about_changes_version, BuildConfig.VERSION_NAME))
                .setMessage(R.string.about_changes_current)
                .setPositiveButton(android.R.string.ok, null)
                .show());
        page.addView(changes);

        section(activity, page, R.string.about_project_title, R.string.about_project);
        section(activity, page, R.string.about_access_title, R.string.about_access);
        addLink(R.string.shizuku_guide_title, "https://shizuku.rikka.app/zh-hans/guide/setup/");
        section(activity, page, R.string.about_source_title, R.string.about_source);
        addLink(R.string.about_github, REPOSITORY);
        addLink(R.string.about_license, REPOSITORY + "/blob/main/LICENSE");
    }

    LinearLayout view() {
        return page;
    }

    private void checkForUpdate() {
        checkUpdate.setEnabled(false);
        updateStatus.setText(R.string.about_update_checking);
        updateStatus.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            AppUpdate.Release release = null;
            try {
                release = AppUpdate.check(BuildConfig.UPDATE_FEED_URL);
            } catch (IOException | JSONException ignored) {
                // Display an unavailable status and keep the release page accessible.
            }
            AppUpdate.Release result = release;
            activity.runOnUiThread(() -> showUpdate(result));
        });
    }

    private void showUpdate(AppUpdate.Release release) {
        if (activity.isDestroyed() || activity.isFinishing()) return;
        checkUpdate.setEnabled(true);
        if (release == null) {
            updateStatus.setText(R.string.about_update_unavailable);
            return;
        }
        if (release.versionCode() <= BuildConfig.VERSION_CODE) {
            updateStatus.setText(R.string.about_update_current);
            return;
        }
        if (release.minSdk() > Build.VERSION.SDK_INT) {
            updateStatus.setText(activity.getString(R.string.about_update_incompatible, release.versionName()));
            return;
        }
        String title = activity.getString(R.string.about_update_found, release.versionName());
        updateStatus.setText(title);
        if (!page.isShown()) return;
        new MaterialAlertDialogBuilder(activity).setTitle(title)
                .setMessage(release.notes().isEmpty() ? activity.getString(R.string.about_update_details) : release.notes())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.about_update_download, (dialog, which) -> openLink(release.page().toString()))
                .show();
    }

    private static void section(MainActivity activity, LinearLayout page, int title, int description) {
        page.addView(activity.text(activity.getString(title), 19, false), activity.spacing(20));
        page.addView(activity.text(activity.getString(description), 14, true), activity.spacing(6));
    }

    private void addLink(int label, String url) {
        MaterialButton link = activity.button(activity.getString(label),
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        link.setOnClickListener(view -> openLink(url));
        page.addView(link, activity.spacing(8));
    }

    private void openLink(String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(activity, "没有可打开链接的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void close() {
        worker.shutdownNow();
    }
}
