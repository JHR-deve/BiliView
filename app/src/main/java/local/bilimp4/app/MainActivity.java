package local.bilimp4.app;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
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
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONException;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
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
    private LinearLayout aboutPanel;
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

    @Override
    protected void onCreate(Bundle state) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
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
                        state.getBoolean("resultBurned"), state.getString("resultNote")));
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
        caches = new CachePanel(this, importPanel, worker, (entry, options) -> {
            String cookie = account.cookie();
            run(task -> task.fromFiles(entry, options, cookie));
        });
        content.addView(downloadPanel);
        content.addView(importPanel);
        aboutPanel = AboutPanel.create(this);
        content.addView(aboutPanel);
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
                if (drawer.isDrawerVisible(GravityCompat.START)) drawer.closeDrawer(GravityCompat.START);
                else if (page != R.id.nav_download) selectPage(R.id.nav_download);
                else {
                    setEnabled(false);
                    try {
                        getOnBackPressedDispatcher().onBackPressed();
                    } finally {
                        setEnabled(true);
                    }
                }
            }
        });
        selectPage(R.id.nav_download);
        setContentView(drawer);
        root.requestFocus();
        ViewCompat.requestApplyInsets(drawer);
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
        field.setHint("视频链接或 BV 号");
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
            run(task -> task.prepareLink(input, cookie), data -> {
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
            run(task -> task.fromLink(streams, quality, options, cookie));
        });
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
        aboutPanel.setVisibility(page == R.id.nav_about ? View.VISIBLE : View.GONE);
        statusArea.setVisibility(page == R.id.nav_about ? View.GONE : View.VISIBLE);
        navigation.setCheckedItem(page);
        toolbar.setTitle(navigation.getMenu().findItem(page).getTitle());
        caches.setVisible(page == R.id.nav_cache);
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

    private void run(Operation<ExportTask.Result> operation) {
        run(operation, this::showResult);
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
        ExportTask task = new ExportTask(this, (stage, percent) -> runOnUiThread(() -> {
            if (current != null && cancel.isEnabled()) showProgress(stage, percent);
        }));
        current = task;
        updateButtons();
        worker.execute(() -> {
            try {
                T result = operation.run(task);
                runOnUiThread(() -> {
                    if (finishTask(task)) onComplete.accept(result);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (finishTask(task)) showFailure(error);
                });
            }
        });
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
        String summary = "下载 / " + ExportTask.FOLDER + "\n" + result.name();
        if (result.note() != null) summary += "\n" + result.note();
        detail.setText(summary);
        progress.setVisibility(View.GONE);
        cancel.setVisibility(View.GONE);
        resultActions.setVisibility(View.VISIBLE);
        getPreferences(MODE_PRIVATE).edit()
                .putString("recentUri", result.uri().toString())
                .putString("recentName", result.name())
                .putBoolean("recentBurned", result.burned())
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
                preferences.getBoolean("recentBurned", false), null));
    }

    private void openResult(boolean share) {
        if (lastResult == null) return;
        Uri uri = lastResult.uri();
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
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(link.getWindowToken(), 0);
        link.clearFocus();
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
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (caches != null) caches.refreshAccess();
    }

    @Override
    protected void onDestroy() {
        if (current != null) current.cancel();
        if (caches != null) caches.close();
        if (account != null) account.close();
        if (preview != null) preview.close();
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
