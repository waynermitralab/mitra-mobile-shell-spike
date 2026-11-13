package __PACKAGE__;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowWebView;

import java.time.Duration;


// Runs the generated MainActivity, which installs ShellVoiceCapture on the real Bridge, and checks
// when the foreground service runs. The page's side is played by calling the message entry point
// with what shell-voice.js sends (a WebMessageListener cannot run in a unit test); the page loads
// go through the bridge's own WebViewClient, so the real listener dispatch is what runs.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = { ShellWebChromeClientTest.NoServiceWorkerProvider.class })
public class ShellVoiceCaptureTest {

    // The app origin as the shell knows it, port included: the CI fixture is served on a port.
    private static final String ORIGIN = ShellVoiceCapture.originRule("__APP_HOST__", __APP_PORT__);
    private static final Uri APP = Uri.parse(ORIGIN);

    private Application application;
    private ShadowApplication shadow;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before
    public void setUp() {
        ShellVoiceService.wanted = false;
        ShellVoiceService.foreground = false;
        ShellVoiceCapture.notificationAsked = false;
        application = ApplicationProvider.getApplicationContext();
        shadow = shadowOf(application);
        PackageInfo webView = new PackageInfo();
        webView.packageName = "com.google.android.webview";
        webView.versionName = "140.0.0.0";
        ShadowWebView.setCurrentWebViewPackage(webView);
        shadow.declareActionUnbindable("android.support.customtabs.action.CustomTabsService");
    }

    private void boot(boolean recordAudio) {
        if (recordAudio) shadow.grantPermissions(Manifest.permission.RECORD_AUDIO);
        controller = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();
        activity = controller.get();
    }

    // The page reports "capturing" and the debounce window passes, as it does with a conversation that stays.
    private void captureAndSettle() {
        voice().onMessage("capturing", APP, true);
        settle();
    }

    private void idleAndSettle() {
        voice().onMessage("idle", APP, true);
        advance(ShellVoiceCapture.IDLE_GRACE_MS + 100);
    }

    private void advance(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private void settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ShellVoiceCapture.START_DELAY_MS + 100));
    }

    // The system runs onStartCommand after the start was accepted; the fake does not.
    private void serviceReachesForeground() {
        ShellVoiceService.foreground = true;
    }

    private ShellVoiceCapture voice() {
        return activity.voice();
    }

    private Intent started() {
        return shadow.getNextStartedService();
    }

    private Intent stopped() {
        return shadow.getNextStoppedService();
    }

    // Anything left means a call that should not have been made.
    private void assertNoServiceCalls() {
        assertNull(shadow.peekNextStartedService());
        assertNull(shadow.getNextStoppedService());
    }

    @Test
    public void capturingStartsTheServiceWhenTheMicrophoneIsGranted() {
        boot(true);
        captureAndSettle();
        Intent intent = started();
        assertNotNull(intent);
        assertEquals(ShellVoiceService.class.getName(), intent.getComponent().getClassName());
    }

    @Test
    public void capturingDoesNotStartTheServiceWithoutTheMicrophone() {
        boot(false);
        captureAndSettle();
        assertNoServiceCalls();
    }

    @Test
    public void capturingInTheBackgroundDoesNotStartTheService() {
        boot(true);
        controller.pause().stop();
        captureAndSettle();
        assertNoServiceCalls();
    }

    @Test
    public void aStartSkippedByTheLifecycleIsTriedAgainWhenTheActivityStarts() {
        boot(true);
        controller.pause().stop();
        captureAndSettle();
        assertNoServiceCalls();
        controller.start();
        assertNotNull("the page is still capturing when the activity starts", started());
    }

    @Test
    public void aRetryDoesNothingOnceThePageWentIdle() {
        boot(true);
        controller.pause().stop();
        captureAndSettle();
        voice().onMessage("idle", APP, true);
        controller.start();
        assertNoServiceCalls();
    }

    @Test
    public void capturingThatEndsWithinTheWindowNeverStartsTheService() {
        boot(true);
        voice().onMessage("capturing", APP, true);
        voice().onMessage("idle", APP, true);
        settle();
        assertNoServiceCalls();
    }

    private ShadowActivity.PermissionsRequest notificationRequest() {
        return shadowOf(activity).getLastRequestedPermission();
    }

    @Test
    public void theFirstCapturingAsksForTheNotificationPermissionWithoutWaitingForTheAnswer() {
        boot(true);
        voice().onMessage("capturing", APP, true);
        ShadowActivity.PermissionsRequest request = notificationRequest();
        assertNotNull(request);
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, request.requestedPermissions[0]);
        assertEquals(ShellVoiceCapture.NOTIFICATION_REQUEST_CODE, request.requestCode);
        settle();
        assertNotNull("the service starts although nobody answered", started());
    }

    @Test
    public void theNotificationPermissionIsAskedOnlyOncePerProcess() {
        boot(true);
        voice().onMessage("capturing", APP, true);
        ShadowActivity.PermissionsRequest first = notificationRequest();
        assertNotNull(first);
        voice().onMessage("idle", APP, true);
        voice().onMessage("capturing", APP, true);
        assertSame("a second capturing does not ask again", first, notificationRequest());
    }

    @Test
    public void theAskIsNotConsumedWhileTheActivityIsNotStarted() {
        shadow.grantPermissions(Manifest.permission.RECORD_AUDIO);
        controller = Robolectric.buildActivity(MainActivity.class).create();
        activity = controller.get();
        voice().onMessage("capturing", APP, true);
        assertNull(notificationRequest());
        assertFalse(ShellVoiceCapture.notificationAsked);
        controller.start().resume().visible();
        assertNotNull("the start of the activity makes the skipped ask, without a new capturing", notificationRequest());
        assertTrue(ShellVoiceCapture.notificationAsked);
        advance(ShellVoiceCapture.START_DELAY_MS + 100);
        assertTrue("the service starts on the same retry", ShellVoiceService.wanted);
    }

    @Test
    public void aGrantedNotificationPermissionIsNotAskedAgain() {
        shadow.grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        boot(true);
        voice().onMessage("capturing", APP, true);
        assertNull(notificationRequest());
    }

    @Test
    @Config(sdk = 32)
    public void belowAndroid13NothingIsAsked() {
        boot(true);
        voice().onMessage("capturing", APP, true);
        assertNull(notificationRequest());
    }

    @Test
    public void nothingIsAskedWithoutCapturingOrOutsideTheMainFrameOfTheApp() {
        boot(true);
        voice().onMessage("idle", APP, true);
        voice().onMessage("capturing", APP, false);
        voice().onMessage("capturing", Uri.parse("https://evil.example"), true);
        assertNull(notificationRequest());
    }

    private static final int[] GRANTED = { PackageManager.PERMISSION_GRANTED };
    private static final int[] DENIED = { PackageManager.PERMISSION_DENIED };
    private static final String[] NOTIFICATIONS = { Manifest.permission.POST_NOTIFICATIONS };

    private Notification posted() {
        NotificationManager manager = application.getSystemService(NotificationManager.class);
        return shadowOf(manager).getNotification(ShellVoiceService.NOTIFICATION_ID);
    }

    @Test
    public void allowingTheNotificationPermissionPostsTheNotificationAgainWhenInForeground() {
        boot(true);
        serviceReachesForeground();
        assertNull(posted());
        activity.onRequestPermissionsResult(ShellVoiceCapture.NOTIFICATION_REQUEST_CODE, NOTIFICATIONS, GRANTED);
        assertNotNull(posted());
    }

    @Test
    public void allowingTheNotificationPermissionDoesNothingWhenTheServiceIsNotInForeground() {
        boot(true);
        activity.onRequestPermissionsResult(ShellVoiceCapture.NOTIFICATION_REQUEST_CODE, NOTIFICATIONS, GRANTED);
        assertNull(posted());
    }

    @Test
    public void denyingTheNotificationPermissionPostsNothing() {
        boot(true);
        serviceReachesForeground();
        activity.onRequestPermissionsResult(ShellVoiceCapture.NOTIFICATION_REQUEST_CODE, NOTIFICATIONS, DENIED);
        activity.onRequestPermissionsResult(ShellVoiceCapture.NOTIFICATION_REQUEST_CODE, NOTIFICATIONS, new int[0]);
        assertNull(posted());
    }

    @Test
    public void anotherRequestCodeIsIgnored() {
        boot(true);
        serviceReachesForeground();
        activity.onRequestPermissionsResult(ShellVoiceCapture.NOTIFICATION_REQUEST_CODE + 1, NOTIFICATIONS, GRANTED);
        assertNull(posted());
    }

    @Test
    public void capturingStartsTheServiceOnlyAfterTheWindow() {
        boot(true);
        voice().onMessage("capturing", APP, true);
        assertNull("not before the window", shadow.peekNextStartedService());
        settle();
        assertNotNull(started());
        assertNull("once", shadow.peekNextStartedService());
        assertTrue(ShellVoiceService.wanted);
    }

    @Test
    public void idleBeforeTheServiceReachedForegroundOnlyWithdrawsTheRequest() {
        boot(true);
        captureAndSettle();
        started();
        voice().onMessage("idle", APP, true);
        advance(ShellVoiceCapture.IDLE_GRACE_MS + 100);
        assertFalse(ShellVoiceService.wanted);
        assertNull("stopping a service that still owes startForeground crashes the app", shadow.getNextStoppedService());
    }

    @Test
    public void aNewPageBeforeForegroundDoesNotStopTheService() {
        boot(true);
        captureAndSettle();
        started();
        WebView webView = activity.getBridge().getWebView();
        webView.getWebViewClient().onPageStarted(webView, ORIGIN + "/next", null);
        assertNull(shadow.getNextStoppedService());
        assertFalse(ShellVoiceService.wanted);
    }

    @Test
    public void theOriginRuleAndTheMatchKeepThePortOfThePublishedUrl() {
        assertEquals("https://app.example.com", ShellVoiceCapture.originRule("app.example.com", -1));
        assertEquals("https://app.example.com:8443", ShellVoiceCapture.originRule("app.example.com", 8443));
        assertTrue(ShellVoiceCapture.matches(Uri.parse("https://app.example.com:8443/x"), "app.example.com", 8443));
        assertFalse("another port is another origin", ShellVoiceCapture.matches(Uri.parse("https://app.example.com/x"), "app.example.com", 8443));
        assertFalse(ShellVoiceCapture.matches(Uri.parse("https://app.example.com:8443/x"), "app.example.com", -1));
        assertTrue(ShellVoiceCapture.matches(Uri.parse("https://app.example.com/x"), "app.example.com", -1));
        assertFalse(ShellVoiceCapture.matches(null, "app.example.com", -1));
    }

    @Test
    public void aMessageThatIsNotAStringIsIgnoredWithoutThrowing() {
        boot(true);
        assertNull(ShellVoiceCapture.textOf(new androidx.webkit.WebMessageCompat(new byte[] { 1, 2 })));
        assertNull(ShellVoiceCapture.textOf(null));
        assertEquals("capturing", ShellVoiceCapture.textOf(new androidx.webkit.WebMessageCompat("capturing")));
        voice().onMessage(new androidx.webkit.WebMessageCompat(new byte[] { 1 }), APP, true);
        settle();
        assertNoServiceCalls();
    }

    @Test
    public void isAppPageMatchesTheAppHostOnly() {
        assertTrue(ShellVoiceCapture.isAppPage(ORIGIN + "/home"));
        assertTrue(ShellVoiceCapture.isAppPage(ORIGIN));
        assertFalse(ShellVoiceCapture.isAppPage("https://__APP_HOST__.evil.example/home"));
        assertFalse(ShellVoiceCapture.isAppPage("https://evil.example/__APP_HOST__"));
        assertFalse(ShellVoiceCapture.isAppPage("https://other.example/home"));
        assertFalse(ShellVoiceCapture.isAppPage(null));
    }

    @Test
    public void idleStopsTheService() {
        boot(true);
        captureAndSettle();
        started();
        serviceReachesForeground();
        voice().onMessage("idle", APP, true);
        assertNull("not before the grace period", shadow.getNextStoppedService());
        advance(ShellVoiceCapture.IDLE_GRACE_MS + 100);
        assertNotNull(stopped());
    }

    @Test
    public void idleThenCapturingWithinTheGraceKeepsTheServiceWithoutASecondStart() {
        boot(true);
        captureAndSettle();
        started();
        serviceReachesForeground();
        voice().onMessage("idle", APP, true);
        advance(ShellVoiceCapture.IDLE_GRACE_MS / 2);
        voice().onMessage("capturing", APP, true);
        advance(ShellVoiceCapture.IDLE_GRACE_MS + ShellVoiceCapture.START_DELAY_MS);
        assertNoServiceCalls();
        assertTrue(ShellVoiceService.wanted);
    }

    @Test
    public void idleThatStaysStopsTheServiceAfterTheGrace() {
        boot(true);
        captureAndSettle();
        started();
        serviceReachesForeground();
        voice().onMessage("idle", APP, true);
        advance(ShellVoiceCapture.IDLE_GRACE_MS - 100);
        assertNull(shadow.getNextStoppedService());
        advance(200);
        assertNotNull(stopped());
        assertFalse(ShellVoiceService.wanted);
    }

    @Test
    public void stoppedByTheRecordingCheckWhileThePageStillCapturesTheActivityStartTurnsItOnAgain() {
        boot(true);
        captureAndSettle();
        started();
        // What the service leaves behind when its recording check stops it.
        ShellVoiceService.wanted = false;
        ShellVoiceService.foreground = false;
        controller.pause().stop().start();
        assertNotNull(started());
    }

    @Test
    public void startingTwiceAndStoppingTwiceAreHarmless() {
        boot(true);
        captureAndSettle();
        captureAndSettle();
        assertNotNull(started());
        assertNull("the second start did not reach the system", shadow.peekNextStartedService());
        serviceReachesForeground();
        voice().onMessage("idle", APP, true);
        voice().onMessage("idle", APP, true);
        advance(ShellVoiceCapture.IDLE_GRACE_MS + 100);
        assertNotNull(stopped());
        assertNull("the second stop did not reach the system", shadow.getNextStoppedService());
    }

    @Test
    public void idleWithNothingRunningDoesNothing() {
        boot(true);
        idleAndSettle();
        assertNoServiceCalls();
    }

    @Test
    public void aConversationCanStartAgainAfterItEnded() {
        boot(true);
        captureAndSettle();
        serviceReachesForeground();
        idleAndSettle();
        captureAndSettle();
        assertNotNull(started());
        assertNotNull(started());
    }

    @Test
    public void aNewPageStopsTheService() {
        boot(true);
        captureAndSettle();
        started();
        serviceReachesForeground();
        WebView webView = activity.getBridge().getWebView();
        webView.getWebViewClient().onPageStarted(webView, ORIGIN + "/next", null);
        assertNotNull(stopped());
    }

    @Test
    public void aDeadRendererStopsTheService() {
        boot(true);
        captureAndSettle();
        started();
        serviceReachesForeground();
        WebView webView = activity.getBridge().getWebView();
        webView.getWebViewClient().onRenderProcessGone(webView, new RenderProcessGoneDetail() {
            @Override public boolean didCrash() { return true; }
            @Override public int rendererPriorityAtExit() { return 0; }
        });
        assertNotNull(stopped());
    }

    @Test
    public void destroyingTheActivityStopsTheService() {
        boot(true);
        captureAndSettle();
        started();
        serviceReachesForeground();
        controller.pause().stop().destroy();
        assertNotNull(stopped());
    }

    @Test
    public void onlyTheMainFrameOfTheAppHostCounts() {
        boot(true);
        voice().onMessage("capturing", APP, false);
        voice().onMessage("capturing", Uri.parse("https://evil.example"), true);
        voice().onMessage("capturing", Uri.parse("https://__APP_HOST__.evil.example"), true);
        voice().onMessage("capturing", null, true);
        voice().onMessage("anything else", APP, true);
        voice().onMessage((String) null, APP, true);
        settle();
        assertNoServiceCalls();
    }

    // The channel exists on the main WebView only: a window the page opens has a client of its own
    // that never reaches the capture, so a child page loading or capturing cannot stop or start it.
    @Test
    public void aChildWindowIsNotTheMainPage() {
        boot(true);
        WebView page = activity.getBridge().getWebView();
        captureAndSettle();
        started();
        serviceReachesForeground();
        WebView.WebViewTransport transport = page.new WebViewTransport();
        Message message = Message.obtain(new Handler(Looper.getMainLooper()), 0, transport);
        assertTrue(page.getWebChromeClient().onCreateWindow(page, false, true, message));
        WebView child = transport.getWebView();
        child.getWebViewClient().onPageStarted(child, "https://accounts.google.com/o/oauth2/v2/auth", null);
        assertNull("a child page starting is not the main page navigating", shadow.getNextStoppedService());
    }
}
