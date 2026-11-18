package local.bilimp4.app;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * Runs aicu.cc requests inside a WebView. The site sits behind Cloudflare, which drops
 * connections whose TLS fingerprint is not a browser, so the page's own origin performs
 * the request and hands the body back through a JavaScript bridge.
 */
final class AicuBridge implements AutoCloseable {
    private static final String ORIGIN = "https://www.aicu.cc/";
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; 23127DN0CC) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36";
    private static final long TIMEOUT_MS = 20000;

    private record Pending(String url, String method, Consumer<String> ok, Consumer<String> fail) {}

    private final MainActivity activity;
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private WebView webView;
    private Pending current;
    private boolean ready;
    private boolean closed;
    private long startedAt;

    AicuBridge(MainActivity activity) {
        this.activity = activity;
    }

    void get(String url, Consumer<String> ok, Consumer<String> fail) {
        submit(new Pending(url, "GET", ok, fail));
    }

    void post(String url, Consumer<String> ok, Consumer<String> fail) {
        submit(new Pending(url, "POST", ok, fail));
    }

    private void submit(Pending pending) {
        if (closed) {
            pending.fail().accept("请求频繁，请稍后重试");
            return;
        }
        queue.add(pending);
        if (webView == null) create();
        else if (ready) drain();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void create() {
        webView = new WebView(activity);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(UA);
        webView.addJavascriptInterface(this, "AicuBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (closed) return;
                ready = true;
                drain();
            }
        });
        ViewGroup decor = activity.findViewById(android.R.id.content);
        if (decor != null) decor.addView(webView, new ViewGroup.LayoutParams(0, 0));
        webView.loadUrl(ORIGIN);
    }

    private void drain() {
        if (closed || current != null || queue.isEmpty() || webView == null) return;
        Pending next = queue.poll();
        current = next;
        startedAt = System.currentTimeMillis();
        String script = "(function(){"
                + "var done=function(t){AicuBridge.post(t)};"
                + "try{fetch(" + JSONObject.quote(next.url()) + ",{method:" + JSONObject.quote(next.method())
                + ",credentials:'include',cache:'no-store'})"
                + ".then(function(r){return r.text()}).then(done)"
                + ".catch(function(e){AicuBridge.post(JSON.stringify({__error:String(e)}))})"
                + "}catch(e){AicuBridge.post(JSON.stringify({__error:String(e)}))}})()";
        webView.evaluateJavascript(script, null);
        handler.postDelayed(this::expire, TIMEOUT_MS);
    }

    private void expire() {
        if (closed || current == null) return;
        if (System.currentTimeMillis() - startedAt < TIMEOUT_MS - 500) return;
        Pending failed = current;
        current = null;
        failed.fail().accept("请求频繁，请稍后重试");
        drain();
    }

    @JavascriptInterface
    public void post(String body) {
        activity.runOnUiThread(() -> deliver(body));
    }

    private void deliver(String body) {
        Pending done = current;
        current = null;
        if (done != null) {
            String text = body == null ? "" : body.trim();
            if (text.contains("\"__error\"")) done.fail().accept("请求频繁，请稍后重试");
            else if (text.startsWith("<")) done.fail().accept("请求频繁，请稍后重试");
            else done.ok().accept(text);
        }
        drain();
    }

    @Override
    public void close() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        queue.clear();
        if (current != null) {
            current.fail().accept("请求频繁，请稍后重试");
            current = null;
        }
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.destroy();
            webView = null;
        }
    }
}