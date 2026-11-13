package local.bilimp4.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public final class ExportService extends Service {
    static final String SHOW_HISTORY = "local.bilimp4.app.SHOW_HISTORY";
    private static final String CANCEL = "local.bilimp4.app.CANCEL_EXPORT";
    private static final String CHANNEL = "exports";
    private static final int RUNNING = 1;
    private static final int COMPLETE = 2;
    private ExportQueue queue;
    private NotificationManager notifications;
    private PowerManager.WakeLock wakeLock;
    private boolean foreground;

    @Override public void onCreate() {
        super.onCreate();
        queue = ExportQueue.get(this);
        notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "后台下载与转换",
                NotificationManager.IMPORTANCE_LOW));
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && CANCEL.equals(intent.getAction())) {
            if (foreground) queue.cancel(intent.getLongExtra("id", -1));
            else stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!foreground) {
            try {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING;
                }
                startForeground(RUNNING, notification("正在准备", "下载与转换", true)
                        .setProgress(100, 0, true).build(), type);
                foreground = true;
            } catch (IllegalStateException | SecurityException error) {
                queue.startFailed();
                stopSelf();
                return START_NOT_STICKY;
            }
            holdCpu();
            queue.attach(this);
        }
        return START_NOT_STICKY;
    }

    @SuppressLint("WakelockTimeout")
    private void holdCpu() {
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BiliView:export");
        wakeLock.acquire();
    }

    void progress(long id, String title, String stage, int percent, boolean cancelling) {
        Notification.Builder update = notification(title, stage + (percent >= 0 ? " · " + percent + "%" : ""), true)
                .setProgress(100, Math.max(0, percent), percent < 0);
        if (!cancelling) {
            Intent cancel = new Intent(this, ExportService.class).setAction(CANCEL).putExtra("id", id);
            PendingIntent action = PendingIntent.getService(this, (int) id, cancel,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            update.addAction(new Notification.Action.Builder(null, "取消任务", action).build());
        }
        notifications.notify(RUNNING, update.build());
    }

    void completed(ExportTask.Result result, String failure, int remaining) {
        if (remaining > 0 || "已取消".equals(failure)) return;
        String title = result == null ? "视频处理失败" : "已保存 MP4";
        String text = result == null ? failure : result.name();
        notifications.notify(COMPLETE, notification(title, text, false).build());
    }

    private Notification.Builder notification(String title, String text, boolean ongoing) {
        Intent history = new Intent(this, MainActivity.class).setAction(SHOW_HISTORY)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 0, history,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing);
    }

    void end() {
        foreground = false;
        releaseCpu();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) {
        queue.detach(this);
        end();
    }

    @Override public void onDestroy() {
        queue.detach(this);
        releaseCpu();
        super.onDestroy();
    }

    private void releaseCpu() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }
}
