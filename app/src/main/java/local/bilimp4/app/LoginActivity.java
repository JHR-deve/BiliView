package local.bilimp4.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LoginActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollAgain = this::poll;
    private TextView status;
    private ImageView image;
    private MaterialButton refresh;
    private MaterialButton save;
    private BiliAccount.Qr qr;
    private Bitmap bitmap;
    private boolean foreground;
    private boolean polling;
    private volatile int generation;

    @Override
    protected void onCreate(Bundle state) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        createScreen();
        requestQr();
    }

    private void createScreen() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        boolean light = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES;
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(light);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(light);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, "login"));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("登录 B 站");
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setNavigationContentDescription("返回");
        toolbar.setNavigationOnClickListener(view -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(64)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        Configuration configuration = getResources().getConfiguration();
        boolean wide = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        body.setGravity(wide ? Gravity.CENTER_VERTICAL : Gravity.CENTER_HORIZONTAL);
        body.setPadding(dp(24), dp(wide ? 16 : 20), dp(24), dp(wide ? 16 : 24));
        LinearLayout instructions = wide ? new LinearLayout(this) : body;
        if (wide) {
            instructions.setOrientation(LinearLayout.VERTICAL);
            instructions.setGravity(Gravity.CENTER_HORIZONTAL);
        }
        instructions.addView(label("使用哔哩哔哩扫一扫", 22));
        instructions.addView(label("扫码后，在哔哩哔哩 App 确认登录。", 14), spacing(8));
        image = new ImageView(this);
        image.setBackgroundColor(Color.WHITE);
        image.setContentDescription("B 站登录二维码");
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int size = dp(wide ? Math.max(112, Math.min(196, configuration.screenHeightDp - 175)) : 224);
        LinearLayout.LayoutParams imageSize = new LinearLayout.LayoutParams(size, size);
        if (!wide) imageSize.topMargin = dp(24);
        body.addView(image, imageSize);
        if (wide) {
            LinearLayout.LayoutParams column = new LinearLayout.LayoutParams(0, -2, 1);
            column.setMarginStart(dp(24));
            body.addView(instructions, column);
        }
        status = label("", 15);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        instructions.addView(status, spacing(14));
        instructions.addView(label("在同一部手机登录时，可保存二维码，再从哔哩哔哩扫一扫的相册入口选取。", 13), spacing(14));

        save = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle);
        save.setText("保存到相册");
        save.setOnClickListener(view -> saveQr());
        refresh = new MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle);
        refresh.setText("刷新二维码");
        refresh.setOnClickListener(view -> requestQr());
        if (wide) {
            LinearLayout actions = new LinearLayout(this);
            actions.setGravity(Gravity.CENTER);
            actions.addView(save);
            actions.addView(refresh);
            instructions.addView(actions, spacing(14));
        } else {
            instructions.addView(save, spacing(14));
            instructions.addView(refresh, spacing(4));
        }
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
    }

    private void requestQr() {
        int request = ++generation;
        handler.removeCallbacks(pollAgain);
        qr = null;
        bitmap = null;
        polling = false;
        image.setImageDrawable(null);
        refresh.setEnabled(false);
        save.setEnabled(false);
        status.setText("正在获取二维码");
        worker.execute(() -> {
            try {
                BiliAccount.Qr fresh = BiliAccount.generate();
                Bitmap code = makeQr(fresh.url());
                runOnUiThread(() -> {
                    if (!active(request)) return;
                    qr = fresh;
                    bitmap = code;
                    image.setImageBitmap(code);
                    refresh.setEnabled(true);
                    save.setEnabled(true);
                    status.setText("等待扫码");
                    schedulePoll();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!active(request)) return;
                    status.setText("无法获取二维码，请检查网络后刷新。");
                    refresh.setEnabled(true);
                });
            }
        });
    }

    private void poll() {
        BiliAccount.Qr code = qr;
        if (code == null || polling || !foreground) return;
        int request = generation;
        polling = true;
        worker.execute(() -> {
            try {
                BiliAccount.Poll result = BiliAccount.poll(code);
                runOnUiThread(() -> handlePoll(request, result));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!active(request)) return;
                    polling = false;
                    status.setText("登录连接暂时不可用，正在重试。");
                    schedulePoll();
                });
            }
        });
    }

    private void handlePoll(int request, BiliAccount.Poll result) {
        if (!active(request)) return;
        polling = false;
        int code = result.code;
        switch (code) {
            case 0 -> saveSession(request, result.cookie);
            case 86090 -> {
                status.setText("已扫码，请在哔哩哔哩确认登录。");
                schedulePoll();
            }
            case 86101 -> {
                status.setText("等待扫码");
                schedulePoll();
            }
            default -> {
                qr = null;
                save.setEnabled(false);
                status.setText(code == 86038 ? "二维码已过期，请刷新。" : "扫码状态已失效，请刷新。");
            }
        }
    }

    private void saveSession(int request, String cookie) {
        qr = null;
        save.setEnabled(false);
        refresh.setEnabled(false);
        status.setText("正在保存登录状态");
        worker.execute(() -> {
            try {
                if (request != generation) return;
                BiliAccount.save(this, cookie);
                runOnUiThread(() -> {
                    if (!active(request)) return;
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!active(request)) return;
                    status.setText("无法保存登录状态，请刷新后重试。");
                    refresh.setEnabled(true);
                });
            }
        });
    }

    private void schedulePoll() {
        handler.removeCallbacks(pollAgain);
        if (foreground && qr != null && !polling) handler.postDelayed(pollAgain, 2000);
    }

    private boolean active(int request) {
        return request == generation && !isFinishing() && !isDestroyed();
    }

    private void saveQr() {
        Bitmap code = bitmap;
        if (code == null || qr == null) return;
        int request = generation;
        save.setEnabled(false);
        worker.execute(() -> {
            boolean saved;
            try {
                writeQr(code);
                saved = true;
            } catch (Exception error) {
                saved = false;
            }
            boolean success = saved;
            runOnUiThread(() -> {
                if (!active(request)) return;
                save.setEnabled(qr != null);
                Toast.makeText(this, success ? "二维码已保存到相册" : "无法保存二维码", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void writeQr(Bitmap code) throws IOException {
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "B站登录二维码.png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BiliMP4");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("无法创建二维码图片。");
        boolean published = false;
        try {
            try (OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null || !code.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IOException("无法写入二维码图片。");
                }
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            published = resolver.update(uri, values, null, null) == 1;
            if (!published) throw new IOException("无法保存二维码图片。");
        } finally {
            if (!published) resolver.delete(uri, null, null);
        }
    }

    private static Bitmap makeQr(String text) throws Exception {
        int size = 512;
        var matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
    }

    private TextView label(String value, int size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setGravity(Gravity.CENTER);
        return text;
    }

    private LinearLayout.LayoutParams spacing(int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        CharSequence message = status.getText();
        boolean canRefresh = refresh.isEnabled();
        boolean canSave = save.isEnabled();
        createScreen();
        image.setImageBitmap(bitmap);
        status.setText(message);
        refresh.setEnabled(canRefresh);
        save.setEnabled(canSave);
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        schedulePoll();
    }

    @Override protected void onPause() {
        foreground = false;
        handler.removeCallbacks(pollAgain);
        super.onPause();
    }

    @Override protected void onDestroy() {
        generation++;
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }
}
