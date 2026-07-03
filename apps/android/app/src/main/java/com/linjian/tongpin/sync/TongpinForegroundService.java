package com.linjian.tongpin.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import com.linjian.tongpin.MainActivity;
import com.linjian.tongpin.R;
import com.linjian.tongpin.data.PlaybackSnapshot;
import com.linjian.tongpin.data.Prefs;
import com.linjian.tongpin.data.RoomCredentials;
import com.linjian.tongpin.media.TongpinNotificationListener;

public final class TongpinForegroundService extends Service {
    public static final String ACTION_START = "com.linjian.tongpin.action.START_BACKGROUND_SYNC";
    public static final String ACTION_STOP = "com.linjian.tongpin.action.STOP_BACKGROUND_SYNC";

    private static final String CHANNEL_ID = "tongpin_background_sync";
    private static final int NOTIFICATION_ID = 524;
    private static final long KEEP_ALIVE_INTERVAL_MS = 2_500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable keepAliveRunnable = new Runnable() {
        @Override
        public void run() {
            if (!Prefs.backgroundSyncEnabled(TongpinForegroundService.this)) {
                stopSelf();
                return;
            }

            RoomCredentials room = Prefs.room(TongpinForegroundService.this);
            if (room.code.isEmpty()) {
                Prefs.saveStatus(TongpinForegroundService.this, "后台待命 · 等待创建房间");
            } else if (!TongpinNotificationListener.isConnected()) {
                Prefs.saveStatus(TongpinForegroundService.this, "后台待命 · 正在重新连接媒体服务");
                NotificationListenerService.requestRebind(new ComponentName(
                        TongpinForegroundService.this,
                        TongpinNotificationListener.class
                ));
            }

            updateNotification();
            handler.postDelayed(this, KEEP_ALIVE_INTERVAL_MS);
        }
    };

    public static void start(Context context) {
        Prefs.saveBackgroundSyncEnabled(context, true);
        Intent intent = new Intent(context, TongpinForegroundService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Prefs.saveBackgroundSyncEnabled(context, false);
        context.stopService(new Intent(context, TongpinForegroundService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startAsForeground(buildNotification());
        handler.post(keepAliveRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Prefs.saveBackgroundSyncEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }

        Prefs.saveBackgroundSyncEnabled(this, true);
        startAsForeground(buildNotification());
        handler.removeCallbacks(keepAliveRunnable);
        handler.post(keepAliveRunnable);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startAsForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "同频后台待命",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("保持房间连接并在后台接收播放控制指令");
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        RoomCredentials room = Prefs.room(this);
        PlaybackSnapshot playback = Prefs.playback(this);

        String title = room.code.isEmpty() ? "同频等待创建房间" : "同频正在后台待命";
        String song = playback.title == null || playback.title.trim().isEmpty()
                ? "等待播放器"
                : playback.title.trim();
        String roomText = room.code.isEmpty() ? "尚未创建房间" : "房间 " + room.code;
        String state = playback.playing ? "播放中" : "已暂停";
        String text = song + " · " + state + " · " + roomText;

        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, TongpinForegroundService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_stat_tongpin)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(
                        text + "\n" + Prefs.status(this)
                ))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(R.drawable.ic_stat_tongpin, "停止后台", stopPendingIntent)
                .build();
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }
}
