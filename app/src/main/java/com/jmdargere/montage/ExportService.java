package com.jmdargere.montage;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.media3.common.util.UnstableApi;

/**
 * Service au premier plan : l'export continue meme si tu quittes l'appli ou que l'ecran s'eteint.
 * Une notification montre l'avancement.
 */
@UnstableApi
public class ExportService extends Service {
    static final String CH = "export";
    static final int ID = 7;
    static volatile String pendingJson = "";

    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean running = false;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    private Notification build(String title, String text, int pct, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true);
        if (ongoing) {
            b.setProgress(100, pct, false);
        }
        return b.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH, "Export du montage",
                    NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = build("MONTAGE", "Export en cours… 0 %", 0, true);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID, n);
        }
        if (!running) {
            running = true;
            String json = pendingJson;
            pendingJson = "";
            Exporter.start(getApplicationContext(), json);
            tick(nm);
        }
        return START_NOT_STICKY;
    }

    private void tick(final NotificationManager nm) {
        int st = Exporter.state();
        if (st == 1) {
            int p = Exporter.progress();
            nm.notify(ID, build("MONTAGE", "Export en cours… " + p + " %", p, true));
            h.postDelayed(() -> tick(nm), 700);
            return;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        String msg = st == 2 ? "✔ Montage terminé : il est dans Films / MONTAGE"
                : ("annulé".equals(Exporter.error()) ? "Export arrêté" : "L'export a échoué");
        nm.notify(ID + 1, build("MONTAGE", msg, 100, false));
        running = false;
        stopSelf();
    }
}
