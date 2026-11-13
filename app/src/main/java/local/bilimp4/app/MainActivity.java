package local.bilimp4.app;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.window.OnBackInvokedDispatcher;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONException;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MainActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private DrawerLayout drawer;
    private NavigationView navigation;
    private MaterialToolbar toolbar;
    private ScrollView scroll;
    private LinearLayout downloadPanel;
    private LinearLayout importPanel;
    private AboutPanel aboutPanel;
    private LinearLayout historyPage;
    private LinearLayout statusArea;
    private int page = R.id.nav_download;
    private TextInputEditText link;
    private MaterialButton paste;
    private MaterialButton download;
    private AccountCard account;
    private DownloadPreview preview;
    private CachePanel caches;
    private MaterialButton cancel;
    private MaterialCardView statusCard;
    private TextView status;
    private TextView detail;
    private LinearProgressIndicator progress;
    private LinearLayout resultActions;
    private ExportTask current;
    private ExportTask.Result lastResult;
    private VideoHistory history;
    private HistoryPanel records;
    private final ArrayDeque<Job> pending = new ArrayDeque<>();
    private Job running;

    @Override
    protected void onCreate(Bundle state) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        history = new VideoHistory(this);
        history.recover();
        history.importPrevious(this);
        createScreen();
        if (state == null) {
            acceptSharedLink(getIntent());
            restoreRecent();
        } else {
            link.setText(state.getString("link"));
            selectPage(state.getInt("page", R.id.nav_download));
            Uri uri = state.getParcelable("resultUri");
            if (uri != null) {
                showResult(new ExportTask.Result(uri, state.getString("resultName"),
                        state.getBoolean("resultBurned"), state.getString("resultNote"), state.getString("resultFolder")));
            }
        }
        updateButtons();
    }

    private void createScreen() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        boolean light = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES;
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(light);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(light);
        LinearLayout root = column();
        root.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface));
        root.setFocusableInTouchMode(true);
        toolbar = new MaterialToolbar(this);
        toolbar.setTitleTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        toolbar.setNavigationIcon(R.drawable.ic_menu);
        toolbar.setNavigationIconTint(color(com.google.android.material.R.attr.colorOnSurface));
        toolbar.setNavigationContentDescription(R.string.nav_open);
        toolbar.setNavigationOnClickListener(view -> {
            hideKeyboard();
            drawer.openDrawer(GravityCompat.START);
        });
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(60)));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        FrameLayout frame = new FrameLayout(this);
        LinearLayout content = column();
        content.setPadding(dp(24), dp(24), dp(24), dp(32));
        frame.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        frame.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = Math.min(right - left, dp(624));
            if (width > 0 && content.getLayoutParams().width != width) {
                content.getLayoutParams().width = width;
                content.requestLayout();
            }
        });
        scroll.addView(frame);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        downloadPanel = createDownloadPanel();
        importPanel = column();
        caches = new CachePanel(this, importPanel, worker, history, (entries, options) -> {
            String cookie = account.cookie();
            List<Request> requests = new ArrayList<>();
            for (CacheEntry entry : entries) {
                requests.add(new Request(entry.title(), VideoHistory.CONVERSION, null, entry.key(), null,
                        task -> task.fromFiles(entry, options, cookie)));
            }
            enqueue(requests, requests.size() > 1);
        }, uri -> openVideo(uri, false));
        content.addView(downloadPanel);
        content.addView(importPanel);
        historyPage = column();
        records = new HistoryPanel(this, historyPage, this::openVideo, this::cancelJob, this::confirmDeleteVideo,
                this::retryDownload);
        content.addView(historyPage);
        aboutPanel = new AboutPanel(this);
        content.addView(aboutPanel.view());
        statusArea = column();
        statusArea.addView(createStatus(), spacing(20));
        content.addView(statusArea);

        drawer = new DrawerLayout(this);
        drawer.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface));
        drawer.addView(root, new DrawerLayout.LayoutParams(-1, -1));
        navigation = createNavigation();
        drawer.addView(navigation, new DrawerLayout.LayoutParams(dp(308), -1, GravityCompat.START));
        drawer.setDrawerTitle(GravityCompat.START, getString(R.string.nav_open));
        ViewCompat.setOnApplyWindowInsetsListener(drawer, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            root.setPadding(safe.left, safe.top, safe.right, Math.max(safe.bottom, ime.bottom));
            navigation.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                navigateBack();
            }
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::navigateBack);
        }
        selectPage(R.id.nav_download);
        setContentView(drawer);
        root.requestFocus();
        ViewCompat.requestApplyInsets(drawer);
    }

    private void navigateBack() {
        if (drawer.isDrawerVisible(GravityCompat.START)) drawer.closeDrawer(GravityCompat.START);
        else if (page != R.id.nav_download) selectPage(R.id.nav_download);
        else finish();
    }

    private NavigationView createNavigation() {
        NavigationView menu = new NavigationView(this);
        menu.setBackgroundTintList(ColorStateList.valueOf(color(com.google.android.material.R.attr.colorSurface)));
        menu.inflateMenu(R.menu.navigation);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(24), dp(22), dp(20), dp(20));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher);
        header.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout.LayoutParams titleSize = new LinearLayout.LayoutParams(-2, -2);
        titleSize.setMarginStart(dp(14));
        header.addView(text(getString(R.string.app_name), 22, false), titleSize);
        menu.addHeaderView(header);

        menu.setNavigationItemSelectedListener(item -> {
            drawer.closeDrawer(GravityCompat.START);
            if (item.getItemId() == R.id.nav_danmaku) {
                DanmakuControls.showSettings(this);
                return false;
            }
            selectPage(item.getItemId());
            return true;
        });
        return menu;
    }

    private LinearLayout createDownloadPanel() {
        LinearLayout panel = column();
        account = new AccountCard(this, panel, () -> {
            if (preview != null) preview.clear();
        });
        TextInputLayout field = new TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        field.setHint("视频、合集链接或 BV 号");
        field.setBoxCornerRadii(dp(16), dp(16), dp(16), dp(16));
        link = new TextInputEditText(field.getContext());
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        link.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_FORCE_ASCII);
        link.setSingleLine(true);
        field.addView(link, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(field, spacing(20));

        LinearLayout actions = new LinearLayout(this);
        paste = button("粘贴链接", com.google.android.material.R.attr.materialButtonOutlinedStyle);
        paste.setOnClickListener(view -> pasteLink());
        actions.addView(paste, new LinearLayout.LayoutParams(0, -2, 1));
        download = button("解析视频", com.google.android.material.R.attr.materialButtonStyle);
        download.setOnClickListener(view -> {
            String input = link.getText().toString().trim();
            if (input.isEmpty()) return;
            String cookie = account.cookie();
            run(task -> task.inspectLink(input, cookie), data -> {
                statusCard.setVisibility(View.GONE);
                preview.show(data);
            });
        });
        LinearLayout.LayoutParams mainAction = new LinearLayout.LayoutParams(0, -2, 1);
        mainAction.setMarginStart(dp(12));
        actions.addView(download, mainAction);
        panel.addView(actions, spacing(12));
        preview = new DownloadPreview(this, panel, (streams, quality, options) -> {
            String cookie = account.cookie();
            var download = new VideoHistory.Download(streams.referer(), quality.id(), options != null);
            Request request = new Request(streams.title(), VideoHistory.DOWNLOAD, null, null, download,
                    task -> task.fromLink(streams, quality, options, cookie));
            enqueue(java.util.Collections.singletonList(request), false);
        }, this::queueVideos);
        link.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                preview.clear();
                updateButtons();
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        return panel;
    }

    private MaterialCardView createStatus() {
        statusCard = new MaterialCardView(this);
        statusCard.setRadius(dp(20));
        statusCard.setCardElevation(0);
        statusCard.setStrokeWidth(0);
        statusCard.setCardBackgroundColor(color(com.google.android.material.R.attr.colorSecondaryContainer));
        LinearLayout body = column();
        body.setPadding(dp(16), dp(16), dp(16), dp(12));
        status = text("", 17, false);
        detail = text("", 14, true);
        int foreground = color(com.google.android.material.R.attr.colorOnSecondaryContainer);
        status.setTextColor(foreground);
        detail.setTextColor(foreground);
        body.addView(status);
        body.addView(detail, spacing(6));
        progress = new LinearProgressIndicator(this);
        progress.setTrackThickness(dp(4));
        progress.setTrackCornerRadius(dp(2));
        body.addView(progress, spacing(12));
        cancel = button("取消", androidx.appcompat.R.attr.borderlessButtonStyle);
        cancel.setOnClickListener(view -> {
            if (current != null) {
                current.cancel();
                cancel.setEnabled(false);
                status.setText("正在取消");
            }
        });
        body.addView(cancel, new LinearLayout.LayoutParams(-2, -2));
        resultActions = new LinearLayout(this);
        MaterialButton openVideo = button("打开视频", androidx.appcompat.R.attr.borderlessButtonStyle);
        openVideo.setOnClickListener(view -> openResult(false));
        MaterialButton share = button("分享", androidx.appcompat.R.attr.borderlessButtonStyle);
        share.setOnClickListener(view -> openResult(true));
        resultActions.addView(openVideo);
        resultActions.addView(share);
        body.addView(resultActions);
        statusCard.addView(body);
        statusCard.setVisibility(View.GONE);
        return statusCard;
    }

    private void selectPage(int destination) {
        if (page != destination) {
            hideKeyboard();
            scroll.scrollTo(0, 0);
        }
        page = destination;
        downloadPanel.setVisibility(page == R.id.nav_download ? View.VISIBLE : View.GONE);
        importPanel.setVisibility(page == R.id.nav_cache ? View.VISIBLE : View.GONE);
        aboutPanel.view().setVisibility(page == R.id.nav_about ? View.VISIBLE : View.GONE);
        historyPage.setVisibility(page == R.id.nav_history ? View.VISIBLE : View.GONE);
        statusArea.setVisibility(page == R.id.nav_about || page == R.id.nav_history ? View.GONE : View.VISIBLE);
        navigation.setCheckedItem(page);
        toolbar.setTitle(navigation.getMenu().findItem(page).getTitle());
        caches.setVisible(page == R.id.nav_cache);
        refreshHistory();
    }

    private void pasteLink() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return;
        }
        link.setText(clip.getItemAt(0).coerceToText(this));
        link.setSelection(link.length());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CachePanel.PICK_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            caches.setFolder(data.getData());
        }
        if (requestCode == AccountCard.LOGIN && resultCode == RESULT_OK) account.refresh();
    }

    private void updateButtons() {
        boolean idle = current == null;
        link.setEnabled(idle);
        paste.setEnabled(idle);
        download.setEnabled(idle && link.getText() != null && !link.getText().toString().trim().isEmpty());
        account.setBusy(!idle);
        preview.setBusy(!idle);
        caches.setBusy(!idle);
    }

    private interface Operation<T> {
        T run(ExportTask task) throws Exception;
    }

    private record Request(String title, String source, VideoHistory.Folder folder, String cacheKey,
                           VideoHistory.Download download, Operation<ExportTask.Result> operation) {}
    private record Job(long id, Request request) {}

    private void queueVideos(BiliClient.Collection collection, List<BiliClient.Video> videos, int preferredQuality,
                             DanmakuStyle.Options options) {
        String cookie = account.cookie();
        VideoHistory.Folder folder = history.folderFor(collection);
        List<Request> requests = new ArrayList<>();
        for (BiliClient.Video video : videos) {
            var download = new VideoHistory.Download(video.link(), preferredQuality, options != null);
            requests.add(downloadRequest(video.title(), folder, download, options, cookie));
        }
        enqueue(requests, true);
    }

    private Request downloadRequest(String title, VideoHistory.Folder folder, VideoHistory.Download download,
                                    DanmakuStyle.Options options, String cookie) {
        return new Request(title, VideoHistory.DOWNLOAD, folder, null, download, task -> {
            BiliClient.Streams streams = task.prepareLink(download.link(), cookie);
            List<BiliClient.Quality> choices = streams.video();
            BiliClient.Quality quality = download.quality() <= 0 ? choices.get(0) : choices.stream()
                    .filter(choice -> choice.id() <= download.quality()).findFirst().orElse(choices.get(choices.size() - 1));
            return task.fromLink(streams, quality, options, cookie);
        });
    }

    private void retryDownload(VideoHistory.Entry entry) {
        if (!history.requeue(entry)) return;
        VideoHistory.Download download = entry.download();
        DanmakuStyle.Options options = download.danmaku() ? DanmakuControls.loadOptions(this) : null;
        Request request = downloadRequest(entry.title(), entry.folder(), download, options, account.cookie());
        pending.add(new Job(entry.id(), request));
        if (current == null) startNext();
        else refreshHistory();
    }

    private void enqueue(List<Request> requests, boolean showQueue) {
        if (current != null || requests.isEmpty()) return;
        for (Request request : requests) {
            pending.add(new Job(history.enqueue(request.title(), request.source(), request.folder(),
                    request.cacheKey(), request.download()), request));
        }
        if (showQueue) selectPage(R.id.nav_history);
        startNext();
    }

    private void startNext() {
        running = pending.poll();
        if (running == null) return;
        Job job = running;
        history.progress(job.id(), "正在准备", -1);
        refreshHistory();
        run(job.request().operation(), result -> {
            history.complete(job.id(), result);
            caches.refreshResults();
            showResult(result);
        });
    }

    private void cancelJob(long id) {
        if (running != null && running.id() == id && current != null) {
            current.cancel();
            cancel.setEnabled(false);
            status.setText("正在取消");
            records.updateProgress(id, "正在取消", -1);
            return;
        }
        for (Iterator<Job> jobs = pending.iterator(); jobs.hasNext(); ) {
            if (jobs.next().id() != id) continue;
            jobs.remove();
            history.end(id, VideoHistory.State.CANCELLED, null);
            refreshHistory();
            break;
        }
    }

    private void refreshHistory() {
        if (page == R.id.nav_history) records.show(history.entries());
    }

    private <T> void run(Operation<T> operation, Consumer<T> onComplete) {
        if (current != null) return;
        hideKeyboard();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        lastResult = null;
        statusCard.setVisibility(View.VISIBLE);
        statusCard.post(() -> statusCard.requestRectangleOnScreen(
                new Rect(0, 0, statusCard.getWidth(), statusCard.getHeight()), false));
        resultActions.setVisibility(View.GONE);
        detail.setVisibility(View.VISIBLE);
        detail.setText("请保持应用打开");
        cancel.setVisibility(View.VISIBLE);
        cancel.setEnabled(true);
        showProgress("正在准备", -1);
        Job job = running;
        VideoHistory.Folder folder = job == null ? null : job.request().folder();
        ExportTask task = new ExportTask(this, folder == null ? null : folder.directory(), (stage, percent) -> runOnUiThread(() -> {
            if (current == null || !cancel.isEnabled()) return;
            showProgress(stage, percent);
            if (job != null) {
                records.updateProgress(job.id(), stage, percent);
                // Persist stages and coarse progress, leaving frame-by-frame updates in the UI.
                if (!stage.equals(historyStage) || Math.floorDiv(percent, 10) != Math.floorDiv(historyPercent, 10)) {
                    history.progress(job.id(), stage, percent);
                    historyStage = stage;
                    historyPercent = percent;
                }
            }
        }));
        historyStage = "";
        historyPercent = -1;
        current = task;
        updateButtons();
        worker.execute(() -> {
            try {
                T result = operation.run(task);
                runOnUiThread(() -> {
                    if (!finishTask(task)) return;
                    onComplete.accept(result);
                    advance(job);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!finishTask(task)) return;
                    showFailure(error);
                    if (job != null) {
                        boolean cancelled = !cancel.isEnabled() || error instanceof ExportTask.Cancelled;
                        history.end(job.id(), cancelled ? VideoHistory.State.CANCELLED : VideoHistory.State.FAILED,
                                cancelled ? null : errorMessage(error));
                    }
                    advance(job);
                });
            }
        });
    }

    private String historyStage;
    private int historyPercent;

    private void advance(Job job) {
        if (job != null) {
            running = null;
            refreshHistory();
        }
        startNext();
    }

    private void showProgress(String stage, int percent) {
        status.setText(percent < 0 ? stage : stage + " · " + percent + "%");
        if (progress.isIndeterminate() != (percent < 0)) {
            progress.setVisibility(View.INVISIBLE);
            progress.setIndeterminate(percent < 0);
        }
        progress.setVisibility(View.VISIBLE);
        if (percent >= 0) progress.setProgressCompat(percent, false);
    }

    private boolean finishTask(ExportTask task) {
        if (current != task || isDestroyed()) return false;
        current = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateButtons();
        return true;
    }

    private void showFailure(Exception error) {
        boolean cancelled = !cancel.isEnabled() || error instanceof ExportTask.Cancelled;
        status.setText(cancelled ? "已取消" : "处理失败");
        progress.setVisibility(View.GONE);
        cancel.setVisibility(View.GONE);
        detail.setVisibility(cancelled ? View.GONE : View.VISIBLE);
        if (!cancelled) detail.setText(errorMessage(error));
    }

    private String errorMessage(Exception error) {
        if (error instanceof UnknownHostException || error instanceof SocketTimeoutException) {
            return "连接 B 站失败，请检查网络后重试。";
        }
        if (error instanceof SecurityException) return "无法访问所选文件，请重新选择。";
        if (error instanceof JSONException) return "B 站接口返回了无法解析的数据，请稍后重试。";
        return error.getMessage() == null ? "文件处理失败，请检查源文件与可用空间。" : error.getMessage();
    }

    private void showResult(ExportTask.Result result) {
        lastResult = result;
        statusCard.setVisibility(View.VISIBLE);
        status.setText(result.burned() ? "已保存弹幕 MP4" : "已保存 MP4");
        detail.setVisibility(View.VISIBLE);
        String location = "下载 / " + ExportTask.FOLDER;
        if (result.folder() != null) location += " / " + result.folder();
        String summary = location + "\n" + result.name();
        if (result.note() != null) summary += "\n" + result.note();
        detail.setText(summary);
        progress.setVisibility(View.GONE);
        cancel.setVisibility(View.GONE);
        resultActions.setVisibility(View.VISIBLE);
        getPreferences(MODE_PRIVATE).edit()
                .putString("recentUri", result.uri().toString())
                .putString("recentName", result.name())
                .putBoolean("recentBurned", result.burned())
                .putString("recentFolder", result.folder())
                .remove("recentComments")
                .apply();
    }

    private void restoreRecent() {
        var preferences = getPreferences(MODE_PRIVATE);
        String saved = preferences.getString("recentUri", null);
        if (saved == null) return;
        Uri uri = Uri.parse(saved);
        try (var cursor = getContentResolver().query(uri, new String[]{"_id"}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return;
        } catch (RuntimeException error) {
            return;
        }
        showResult(new ExportTask.Result(uri, preferences.getString("recentName", "已下载的视频"),
                preferences.getBoolean("recentBurned", false), null, preferences.getString("recentFolder", null)));
    }

    private void openResult(boolean share) {
        if (lastResult == null) return;
        openVideo(lastResult.uri(), share);
    }

    private void confirmDeleteVideo(VideoHistory.Entry entry) {
        var dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.history_delete_title)
                .setMessage(getString(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? R.string.history_delete_message : R.string.history_delete_permanent_message, entry.title()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.history_delete, (window, which) -> deleteVideo(entry))
                .show();
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                .setTextColor(color(com.google.android.material.R.attr.colorError));
    }

    private void deleteVideo(VideoHistory.Entry entry) {
        if (!history.deleteCompleted(entry)) {
            Toast.makeText(this, R.string.history_delete_failed, Toast.LENGTH_SHORT).show();
            refreshHistory();
            return;
        }
        if (lastResult != null && entry.uri().equals(lastResult.uri())) {
            lastResult = null;
            statusCard.setVisibility(View.GONE);
        }
        var preferences = getPreferences(MODE_PRIVATE);
        if (entry.uri().toString().equals(preferences.getString("recentUri", null))) {
            preferences.edit().remove("recentUri").remove("recentName")
                    .remove("recentBurned").remove("recentFolder").apply();
        }
        caches.refreshResults();
        refreshHistory();
        Toast.makeText(this, Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? R.string.history_trashed : R.string.history_deleted, Toast.LENGTH_SHORT).show();
    }

    private void openVideo(Uri uri, boolean share) {
        if (!VideoHistory.available(this, uri)) {
            Toast.makeText(this, "视频文件已移除或无法访问", Toast.LENGTH_SHORT).show();
            refreshHistory();
            return;
        }
        Intent intent = share ? new Intent(Intent.ACTION_SEND).setType("video/mp4")
                .putExtra(Intent.EXTRA_STREAM, uri) : new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4");
        intent.setClipData(ClipData.newRawUri("MP4", uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(share ? Intent.createChooser(intent, "分享 MP4") : intent);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "没有可打开该视频的应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) return;
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        focused.clearFocus();
    }

    private void acceptSharedLink(Intent intent) {
        if (current == null && Intent.ACTION_SEND.equals(intent.getAction())) {
            CharSequence value = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (value != null) {
                drawer.closeDrawer(GravityCompat.START);
                selectPage(R.id.nav_download);
                link.setText(value);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptSharedLink(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("link", link.getText().toString());
        state.putInt("page", page);
        if (lastResult != null) {
            state.putParcelable("resultUri", lastResult.uri());
            state.putString("resultName", lastResult.name());
            state.putBoolean("resultBurned", lastResult.burned());
            state.putString("resultNote", lastResult.note());
            state.putString("resultFolder", lastResult.folder());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (caches != null) {
            caches.refreshAccess();
            caches.refreshResults();
        }
        refreshHistory();
    }

    @Override
    protected void onDestroy() {
        if (current != null) current.cancel();
        current = null;
        if (aboutPanel != null) aboutPanel.close();
        if (caches != null) caches.close();
        if (account != null) account.close();
        if (preview != null) preview.close();
        if (history != null) {
            history.recover();
            history.close();
        }
        worker.shutdown();
        super.onDestroy();
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    TextView text(String value, int size, boolean secondary) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color(secondary ? com.google.android.material.R.attr.colorOnSurfaceVariant
                : com.google.android.material.R.attr.colorOnSurface));
        view.setLineSpacing(dp(2), 1);
        return view;
    }

    MaterialButton button(String label, int style) {
        MaterialButton button = new MaterialButton(this, null, style);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMaxLines(2);
        return button;
    }

    LinearLayout.LayoutParams spacing(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        return params;
    }

    int color(int attr) {
        return MaterialColors.getColor(this, attr, getClass().getSimpleName());
    }

    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
