package local.bilimp4.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ExportQueue {
    interface Operation<T> {
        T run(ExportTask task) throws Exception;
    }

    record Request(String title, String source, VideoHistory.Folder folder, String cacheKey,
                   VideoHistory.Download download, Operation<ExportTask.Result> operation) {}

    private record Job(long id, Request request) {}

    interface Listener {
        void changed();
        void progress(long id, String stage, int percent);
        void finished(ExportTask.Result result, String failure);
    }

    private static ExportQueue instance;

    static ExportQueue get(Context context) {
        if (instance == null) instance = new ExportQueue(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final VideoHistory history;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Job> pending = new ArrayDeque<>();
    private final ArrayDeque<Runnable> idleCallbacks = new ArrayDeque<>();
    private ExportService service;
    private Listener listener;
    private Job running;
    private ExportTask task;
    private boolean cancelling;
    private boolean interrupted;
    private boolean launching;
    private String stage;
    private int percent = -1;
    private ExportTask.Result latest;
    private String latestFailure;

    private ExportQueue(Context context) {
        this.context = context;
        history = new VideoHistory(context);
        history.recover();
    }

    void observe(Listener observer) {
        listener = observer;
    }

    void unobserve(Listener observer) {
        if (listener == observer) listener = null;
    }

    boolean busy() { return running != null || !pending.isEmpty(); }
    long runningId() { return running == null ? -1 : running.id(); }
    String stage() { return stage == null ? "正在准备" : stage; }
    int percent() { return percent; }
    boolean cancelling() { return cancelling; }
    ExportTask.Result latest() { return latest; }
    String latestFailure() { return latestFailure; }
    void forget(Uri uri) {
        if (latest != null && latest.uri().equals(uri)) latest = null;
    }

    void whenIdle(Runnable callback) {
        if (busy()) idleCallbacks.add(callback);
        else callback.run();
    }

    void enqueue(List<Request> requests) {
        if (requests.isEmpty()) return;
        for (Request request : requests) {
            long id = history.enqueue(request.title(), request.source(), request.folder(), request.cacheKey(),
                    request.download());
            pending.add(new Job(id, request));
        }
        launch();
    }

    void retry(VideoHistory.Entry entry, Request request) {
        if (!history.requeue(entry)) return;
        pending.add(new Job(entry.id(), request));
        launch();
    }

    private void launch() {
        if (service == null) {
            if (launching) {
                changed();
                return;
            }
            try {
                context.startForegroundService(new Intent(context, ExportService.class));
                launching = true;
            } catch (IllegalStateException | SecurityException error) {
                failPending("无法启动后台下载，请回到应用后重试。");
            }
        } else {
            startNext();
        }
        changed();
    }

    void attach(ExportService owner) {
        launching = false;
        service = owner;
        if (!busy() && listener != null) listener.finished(null, "已取消");
        startNext();
    }

    void startFailed() {
        launching = false;
        failPending("无法启动后台下载，请回到应用后重试。");
        changed();
    }

    void detach(ExportService owner) {
        if (service != owner) return;
        service = null;
        if (task != null) {
            interrupted = true;
            task.cancel();
        }
        failPending("后台任务已中断，可从下载记录重试。");
        changed();
    }

    void cancel(long id) {
        if (running != null && running.id() == id && !cancelling) {
            cancelling = true;
            task.cancel();
            stage = "正在取消";
            percent = -1;
            showProgress();
        } else {
            for (var jobs = pending.iterator(); jobs.hasNext(); ) {
                if (jobs.next().id() != id) continue;
                jobs.remove();
                history.end(id, VideoHistory.State.CANCELLED, null);
                changed();
                break;
            }
        }
    }

    private void failPending(String message) {
        Job job;
        while ((job = pending.poll()) != null) history.end(job.id(), VideoHistory.State.INTERRUPTED, message);
        latest = null;
        latestFailure = message;
        if (listener != null) listener.finished(null, message);
    }

    private void startNext() {
        if (running != null || service == null) return;
        Job job = pending.poll();
        if (job == null) {
            ExportService owner = service;
            service = null;
            owner.end();
            changed();
            return;
        }
        running = job;
        cancelling = false;
        interrupted = false;
        stage = "正在准备";
        percent = -1;
        history.progress(job.id(), stage, percent);
        Request request = job.request();
        String folder = request.folder() == null ? null : request.folder().directory();
        ExportTask active = new ExportTask(context, folder,
                (name, value) -> main.post(() -> report(job, name, value)));
        task = active;
        changed();
        showProgress();
        worker.execute(() -> {
            try {
                ExportTask.Result result = request.operation().run(active);
                main.post(() -> finish(job, result, null));
            } catch (Exception error) {
                main.post(() -> finish(job, null, error));
            }
        });
    }

    private void report(Job job, String name, int value) {
        if (running != job || cancelling || interrupted) return;
        if (!name.equals(stage) || Math.floorDiv(value, 10) != Math.floorDiv(percent, 10)) {
            history.progress(job.id(), name, value);
        }
        stage = name;
        percent = value;
        showProgress();
    }

    private void showProgress() {
        if (service != null) service.progress(running.id(), running.request().title(), stage, percent, cancelling);
        if (listener != null) listener.progress(running.id(), stage, percent);
    }

    private void finish(Job job, ExportTask.Result result, Exception error) {
        String failure = null;
        if (result != null) {
            history.complete(job.id(), result);
            latest = result;
            latestFailure = null;
            context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit()
                    .putString("recentUri", result.uri().toString()).putString("recentName", result.name())
                    .putBoolean("recentBurned", result.burned()).putString("recentFolder", result.folder()).apply();
        } else {
            boolean cancelled = cancelling || error instanceof ExportTask.Cancelled;
            if (interrupted) failure = "后台任务已中断，可从下载记录重试。";
            else if (!cancelled) failure = errorMessage(error);
            history.end(job.id(), interrupted ? VideoHistory.State.INTERRUPTED
                    : cancelled ? VideoHistory.State.CANCELLED : VideoHistory.State.FAILED, failure);
            if (failure == null) failure = "已取消";
            latest = null;
            latestFailure = failure;
        }
        if (service != null) service.completed(result, failure, pending.size());
        task = null;
        running = null;
        cancelling = false;
        interrupted = false;
        if (listener != null) listener.finished(result, failure);
        startNext();
        changed();
    }

    private void changed() {
        if (listener != null) listener.changed();
        if (!busy()) {
            Runnable callback;
            while ((callback = idleCallbacks.poll()) != null) callback.run();
        }
    }

    static String errorMessage(Exception error) {
        if (error instanceof UnknownHostException || error instanceof SocketTimeoutException) {
            return "连接 B 站失败，请检查网络后重试。";
        }
        if (error instanceof SecurityException) return "无法访问所选文件，请重新选择。";
        if (error instanceof JSONException) return "B 站接口返回了无法解析的数据，请稍后重试。";
        return error.getMessage() == null ? "文件处理失败，请检查源文件与可用空间。" : error.getMessage();
    }
}
