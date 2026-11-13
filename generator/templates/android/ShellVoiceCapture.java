package __PACKAGE__;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.app.Activity;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.getcapacitor.Bridge;
import com.getcapacitor.WebViewListener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

// Runs ShellVoiceService exactly while the page is capturing audio, so a voice conversation keeps
// its microphone with the screen locked (see the service). The page does not know the shell exists:
// shell-voice.js, bundled with the app, watches getUserMedia and reports "capturing" or "idle"
// through the mitraShellVoice channel, which is a WebMessageListener on the main WebView (with the
// bridge's origin rules, and the host and main frame checked again in onMessage). A
// JavascriptInterface would reach every frame of every page, and the child
// windows of ShellPopups never get this channel at all. A WebView too old for WebMessageListener
// leaves the feature off, and the conversation works as it did.
public final class ShellVoiceCapture {

    private static final String APP_HOST = "__APP_HOST__";
    // -1 when the published URL has no explicit port, as Uri.getPort reports it.
    private static final int APP_PORT = __APP_PORT__;
    private static final String SCRIPT_ASSET = "public/shell-voice.js";
    private static final String CHANNEL = "mitraShellVoice";
    private static final String TAG = "ShellVoice";
    // A page often probes the microphone and lets go of it at once; waiting keeps those probes from
    // ever starting a service that would have to be stopped right away.
    static final long START_DELAY_MS = 1000;
    // A page that mutes with track.stop() and reopens, or asks for the microphone again when WebRTC
    // reconnects or the device changes, reports "idle" and then "capturing" within seconds. With the
    // screen locked a stopped service cannot come back (the activity is not started), so the stop
    // waits long enough for those reopenings, and a service already in the foreground keeps the
    // microphone meanwhile. A conversation that really ended costs this much notification time.
    static final long IDLE_GRACE_MS = 8000;

    // Not one of Capacitor's: an unknown code is handed back by the bridge to the activity's default
    // handling, which ignores it.
    static final int NOTIFICATION_REQUEST_CODE = 7302;
    // Once per process: after a denial the shell does not ask again, and the service runs without
    // the notification in the shade.
    static boolean notificationAsked = false;

    private final Activity activity;
    private final Context context;
    private final Lifecycle lifecycle;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable startTask = this::startNow;
    private final Runnable stopTask = this::stopNow;
    // What the page last reported. The service state (ShellVoiceService.wanted) is what the system
    // was asked for, which can lag or be refused, so the two are kept apart.
    private boolean capturing = false;

    private ShellVoiceCapture(Bridge bridge) {
        this.activity = bridge.getActivity();
        this.context = bridge.getContext();
        this.lifecycle = bridge.getActivity().getLifecycle();
    }

    public static ShellVoiceCapture install(final Bridge bridge) {
        final ShellVoiceCapture capture = new ShellVoiceCapture(bridge);
        // The service must not outlive what it serves: a new document has no capture yet, a dead
        // renderer has none at all, and a destroyed activity has no page. A start that was refused
        // for lack of a started activity is tried again when the activity starts.
        bridge.getActivity().getLifecycle().addObserver((LifecycleEventObserver) (source, event) -> {
            if (event == Lifecycle.Event.ON_DESTROY) capture.reset();
            else if (event == Lifecycle.Event.ON_START) capture.retry();
        });
        final String script = readScript(bridge.getContext());
        final boolean listening = script != null && listen(bridge, capture);
        // Before the page's own scripts when the WebView can, because a page that opens the
        // microphone on load would be missed by a script that arrives later.
        final boolean atDocumentStart = listening && injectAtDocumentStart(bridge, script);
        if (listening) {
            Log.i(TAG, atDocumentStart ? "voice script injected at document start" : "voice script injected at page commit (fallback)");
        }
        bridge.addWebViewListener(new WebViewListener() {
            @Override
            public void onPageStarted(WebView webView) {
                capture.reset();
            }

            @Override
            public boolean onRenderProcessGone(WebView webView, RenderProcessGoneDetail detail) {
                capture.reset();
                return false;
            }

            @Override
            public void onPageCommitVisible(WebView webView, String url) {
                if (listening && !atDocumentStart && isAppPage(url)) webView.evaluateJavascript(script, null);
            }
        });
        return capture;
    }

    private static boolean listen(Bridge bridge, final ShellVoiceCapture capture) {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false;
            WebViewCompat.addWebMessageListener(bridge.getWebView(), CHANNEL, bridge.getAllowedOriginRules(),
                (view, message, sourceOrigin, isMainFrame, replyProxy) -> capture.onMessage(message, sourceOrigin, isMainFrame));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "background audio is off: the web message listener was not added", e);
            return false;
        }
    }

    private static boolean injectAtDocumentStart(Bridge bridge, String script) {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false;
            WebViewCompat.addDocumentStartJavaScript(bridge.getWebView(), script, Collections.singleton(originRule(APP_HOST, APP_PORT)));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "document start injection failed, falling back to page commit", e);
            return false;
        }
    }

    void onMessage(WebMessageCompat message, Uri origin, boolean isMainFrame) {
        // Nothing the page sends may throw out of this callback.
        try {
            onMessage(textOf(message), origin, isMainFrame);
        } catch (RuntimeException e) {
            Log.w(TAG, "a voice message was ignored", e);
        }
    }

    // getData throws for an ArrayBuffer message, so the type is read first when the WebView reports
    // it, and anything that is not a string is dropped.
    static String textOf(WebMessageCompat message) {
        try {
            if (message == null) return null;
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)
                && message.getType() != WebMessageCompat.TYPE_STRING) return null;
            return message.getData();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // The origin rules already limit the channel; the frame and the host are checked again because
    // this is the one place a page can make the app run a foreground service.
    void onMessage(String data, Uri origin, boolean isMainFrame) {
        if (!isMainFrame || !matches(origin, APP_HOST, APP_PORT)) return;
        if ("capturing".equals(data)) {
            capturing = true;
            askForNotifications();
            handler.removeCallbacks(stopTask);
            if (!ShellVoiceService.wanted) {
                handler.removeCallbacks(startTask);
                handler.postDelayed(startTask, START_DELAY_MS);
            }
        } else if ("idle".equals(data)) {
            capturing = false;
            handler.removeCallbacks(startTask);
            handler.removeCallbacks(stopTask);
            handler.postDelayed(stopTask, IDLE_GRACE_MS);
        }
    }

    // The service runs without this permission, but on Android 13+ its notification is then only in
    // the Task Manager and not in the shade, and Play expects the user to see it. The start does not
    // wait for the answer. A request made while the activity is not started would be lost, so it is
    // left for the next "capturing".
    private void askForNotifications() {
        if (notificationAsked || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (!lifecycle.getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        notificationAsked = true;
        ActivityCompat.requestPermissions(activity, new String[] { Manifest.permission.POST_NOTIFICATIONS }, NOTIFICATION_REQUEST_CODE);
    }

    // Called by MainActivity after Capacitor's own routing, with every answer the activity gets.
    void onPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != NOTIFICATION_REQUEST_CODE) return;
        if (grantResults == null || grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) return;
        ShellVoiceService.repostNotification(context);
    }

    // A new document, a dead renderer and a destroyed activity have no conversation to wait for.
    private void reset() {
        capturing = false;
        handler.removeCallbacks(startTask);
        handler.removeCallbacks(stopTask);
        ShellVoiceService.stop(context);
    }

    private void stopNow() {
        ShellVoiceService.stop(context);
    }

    // The page does not report "capturing" again while it keeps capturing, so an ask skipped because
    // the activity was not started has to be made here too, or the notification stays hidden.
    private void retry() {
        if (!capturing) return;
        askForNotifications();
        startNow();
    }

    // Android 14 throws when a microphone service starts without RECORD_AUDIO, and a service started
    // while the app is in the background is refused: both are skipped, and retried when the activity
    // starts (the page is still capturing then).
    private void startNow() {
        if (!capturing || ShellVoiceService.wanted) return;
        if (!lifecycle.getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        ShellVoiceService.start(context);
    }

    static boolean isAppPage(String url) {
        return url != null && matches(Uri.parse(url), APP_HOST, APP_PORT);
    }

    // The port is part of the origin: an app served on 8443 is not the page on 443 of the same host.
    static boolean matches(Uri uri, String host, int port) {
        return uri != null && uri.getHost() != null && host.equalsIgnoreCase(uri.getHost()) && uri.getPort() == port;
    }

    static String originRule(String host, int port) {
        return "https://" + host + (port >= 0 ? ":" + port : "");
    }

    // A missing script is a no-op: the conversation then works as it did.
    private static String readScript(Context context) {
        try (InputStream in = context.getAssets().open(SCRIPT_ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return null;
        }
    }
}
