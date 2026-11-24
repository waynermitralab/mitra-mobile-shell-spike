package __PACKAGE__;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

// Keeps the microphone alive while a page holds a live voice conversation: Android silences the
// capture of an app that is not in the foreground unless it runs a foreground service of type
// microphone. ShellVoiceCapture starts it when the page starts capturing and stops it a few seconds
// after the capture ends (ShellVoiceCapture.IDLE_GRACE_MS). It does no audio work itself.
public class ShellVoiceService extends Service {

    private static final String TAG = "ShellVoice";
    static final String CHANNEL_ID = "shell_voice";
    static final int NOTIFICATION_ID = 7301;
    static final long CHECK_MS = 30_000;
    // Two checks in a row without a recording: one alone can land between two tracks of a page that
    // reopens its microphone.
    static final int IDLE_CHECKS_BEFORE_STOP = 2;
    // Longer than two check periods, so a check that runs late does not let the lock lapse, and
    // short enough that a service nobody renews lets go of the device by itself.
    static final long WAKE_LOCK_MS = 2 * CHECK_MS + 15_000;

    // Both live on the main thread only. wanted: the capture asked for the service and has not
    // withdrawn it. foreground: onStartCommand reached startForeground.
    static boolean wanted = false;
    static boolean foreground = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable check = this::check;
    private PowerManager.WakeLock wakeLock;
    private int idleChecks = 0;

    // False when the system refused (a background start restriction, a missing permission), which
    // leaves the conversation as it was without the service.
    static boolean start(Context context) {
        wanted = true;
        try {
            ContextCompat.startForegroundService(context, new Intent(context, ShellVoiceService.class));
            return true;
        } catch (RuntimeException e) {
            wanted = false;
            return false;
        }
    }

    // A service that was asked to start in the foreground and is stopped before it gets there takes
    // the app down, so until onStartCommand has run the stop is only recorded in wanted. foreground
    // is cleared here, not only in onDestroy, so a start right after this stop (the activity
    // starting again) does not see a service that is already on its way out.
    static void stop(Context context) {
        boolean requested = wanted;
        wanted = false;
        if (!requested || !foreground) return;
        foreground = false;
        try {
            context.stopService(new Intent(context, ShellVoiceService.class));
        } catch (RuntimeException e) {
            // Nothing running to stop.
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (RuntimeException e) {
            // Refused (the permission was revoked, or the system no longer sees the app as allowed to
            // start a microphone service). A service that goes on without ever reaching startForeground
            // is what crashes the app, and stopping it is what ends that obligation, so it stops at
            // once instead of retrying with another type, and wanted is cleared so the capture can ask
            // again the next time the activity starts.
            wanted = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        foreground = true;
        // The request may have been withdrawn while the start was in flight; the foreground state
        // above is still owed, and only after it is it safe to stop.
        if (!wanted) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // The voice conversation also has to keep running when the screen is off and the device
        // would otherwise sleep.
        if (wakeLock == null) {
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":voice");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(WAKE_LOCK_MS);
        // The page can report "capturing" without recording, so the service verifies it.
        idleChecks = 0;
        handler.removeCallbacks(check);
        handler.postDelayed(check, CHECK_MS);
        // A conversation cannot be resumed from a restarted service: the page has to start it again.
        return START_NOT_STICKY;
    }

    // Assumes the capture of a WebView, which runs in the app's own process, shows up in
    // AudioManager.getActiveRecordingConfigurations. The list cannot be narrowed to this app: an
    // unprivileged app gets the recordings of other apps with their client details hidden, so no
    // field tells whose recording it is. The check therefore bounds a page that fakes "capturing"
    // only while no other app is recording; while another one is, it cannot stop the service.
    // That is also what renews the wake lock: a service that nobody verifies stops holding the device.
    void check() {
        boolean recording = hasActiveRecording();
        idleChecks = nextIdleChecks(recording, idleChecks);
        Log.d(TAG, "recording check: " + (recording ? "recording seen" : "no recording") + ", consecutive misses " + idleChecks);
        if (idleChecks >= IDLE_CHECKS_BEFORE_STOP) {
            wanted = false;
            stopSelf();
            return;
        }
        if (wakeLock != null) wakeLock.acquire(WAKE_LOCK_MS);
        handler.postDelayed(check, CHECK_MS);
    }

    static int nextIdleChecks(boolean recording, int idleChecks) {
        return recording ? 0 : idleChecks + 1;
    }

    private boolean hasActiveRecording() {
        AudioManager audio = getSystemService(AudioManager.class);
        return audio != null && !audio.getActiveRecordingConfigurations().isEmpty();
    }

    // startForeground posted the notification while the permission was still missing and the system
    // dropped it; nothing posts it again once the user allows it. Notifying with the foreground id
    // updates the foreground notification in place, without a second startForeground.
    static void repostNotification(Context context) {
        if (!foreground) return;
        try {
            context.getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, build(context));
        } catch (RuntimeException e) {
            Log.w(TAG, "the voice notification was not posted again", e);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        wanted = false;
        stopSelf();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(check);
        foreground = false;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    Notification notification() {
        return build(this);
    }

    private static Notification build(Context context) {
        // Low importance: the notification is a requirement of the system, not something to announce.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Conversa por voz", NotificationManager.IMPORTANCE_LOW);
            context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_voice)
            .setContentTitle(context.getApplicationInfo().loadLabel(context.getPackageManager()))
            .setContentText("Conversa por voz em andamento")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Without it Android 12+ holds the notification back for about 10 seconds.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW);
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch != null) {
            builder.setContentIntent(PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        return builder.build();
    }
}
