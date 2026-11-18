package local.bilimp4.app;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class UpdatePrompt {
    private static final long INTERVAL = 24 * 60 * 60 * 1000L;
    private static final String CHECKED_AT = "updateCheckedAt";
    private static final String SKIPPED = "updateSkipped";
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    static void autoCheck(MainActivity activity) {
        SharedPreferences preferences = activity.getPreferences(0);
        long last = preferences.getLong(CHECKED_AT, 0);
        if (System.currentTimeMillis() - last < INTERVAL) return;
        preferences.edit().putLong(CHECKED_AT, System.currentTimeMillis()).apply();
        worker.execute(() -> {
            AppUpdate.Release release;
            try {
                release = AppUpdate.latest();
            } catch (Exception error) {
                return;
            }
            if (!AppUpdate.newer(release.version())) return;
            if (release.version().equals(preferences.getString(SKIPPED, ""))) return;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                show(activity, release);
            });
        });
    }

    static void show(MainActivity activity, AppUpdate.Release release) {
        String notes = release.notes();
        if (notes.length() > 1500) notes = notes.substring(0, 1500).trim() + "…";
        String message = "当前版本 " + BuildConfig.VERSION_NAME + "，最新版本 " + release.version();
        message += notes.isEmpty() ? "\n\n更新说明和下载地址可以在发布页查看。" : "\n\n" + notes;
        new MaterialAlertDialogBuilder(activity)
                .setTitle("发现新版本 " + release.version())
                .setMessage(message)
                .setNegativeButton("稍后", (dialog, which) -> activity.getPreferences(0).edit()
                        .putString(SKIPPED, release.version()).apply())
                .setNeutralButton("发布页", (dialog, which) -> open(activity, release.page()))
                .setPositiveButton("立即更新", (dialog, which) -> start(activity, release))
                .show();
    }

    private static void start(MainActivity activity, AppUpdate.Release release) {
        if (!release.hasApk()) {
            open(activity, release.page());
            return;
        }
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle("需要允许安装应用")
                    .setMessage("Android 要求先允许 BiliView 安装未知来源的应用，才能完成更新。")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("去设置", (dialog, which) -> {
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (ActivityNotFoundException error) {
                            Toast.makeText(activity, "请在系统设置中允许安装应用", Toast.LENGTH_LONG).show();
                        }
                    }).show();
            return;
        }

        LinearLayout body = activity.column();
        body.setPadding(activity.dp(24), activity.dp(8), activity.dp(24), activity.dp(8));
        TextView label = activity.text("正在下载安装包…", 14, true);
        LinearProgressIndicator bar = new LinearProgressIndicator(activity);
        bar.setTrackThickness(activity.dp(4));
        body.addView(label);
        body.addView(bar, activity.spacing(14));

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("正在更新到 " + release.version())
                .setView(body)
                .setCancelable(false)
                .create();
        dialog.show();

        worker.execute(() -> {
            try {
                File apk = AppUpdate.download(activity, release, (percent, done, total) ->
                        activity.runOnUiThread(() -> {
                            if (percent < 0) {
                                if (!bar.isIndeterminate()) {
                                    bar.setVisibility(View.INVISIBLE);
                                    bar.setIndeterminate(true);
                                    bar.setVisibility(View.VISIBLE);
                                }
                                label.setText("正在下载安装包…");
                            } else {
                                bar.setIndeterminate(false);
                                bar.setProgressCompat(percent, true);
                                label.setText(total > 0
                                        ? "正在下载安装包 · " + percent + "%（"
                                        + Math.max(1, done / 1024 / 1024) + " / "
                                        + Math.max(1, total / 1024 / 1024) + " MB）"
                                        : "正在下载安装包 · " + percent + "%");
                            }
                        }));
                activity.runOnUiThread(() -> {
                    dialog.dismiss();
                    install(activity, apk);
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    dialog.dismiss();
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle("更新失败")
                            .setMessage(ExportQueue.errorMessage(error))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton("发布页", (view, which) -> open(activity, release.page()))
                            .show();
                });
            }
        });
    }

    private static void install(MainActivity activity, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (IllegalArgumentException | ActivityNotFoundException error) {
            Toast.makeText(activity, "没有找到可用的安装程序，请到发布页手动下载", Toast.LENGTH_LONG).show();
        }
    }

    private static void open(MainActivity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(activity, "没有可打开链接的浏览器", Toast.LENGTH_SHORT).show();
        }
    }
}