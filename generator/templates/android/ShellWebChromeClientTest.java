package __PACKAGE__;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.ServiceWorkerWebSettings;
import android.webkit.WebChromeClient;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowWebView;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

// Runs the client MainActivity.onCreate installs, on top of the real BridgeWebChromeClient: its
// permission listener and launcher, and the real BridgeActivity.onRequestPermissionsResult path
// that delivers the dialog result. The system dialog is played by Robolectric: the launch is read
// from the activity shadow and the result is delivered through onRequestPermissionsResult.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = ShellWebChromeClientTest.NoServiceWorkerProvider.class)
public class ShellWebChromeClientTest {

    // Bridge asks the WebView provider for the service worker controller, and Robolectric has no
    // provider. This is the only part of the Bridge start-up that is replaced.
    @Implements(ServiceWorkerController.class)
    public static class NoServiceWorkerProvider {
        @Implementation
        protected static ServiceWorkerController getInstance() {
            return new ServiceWorkerController() {
                @Override public ServiceWorkerWebSettings getServiceWorkerWebSettings() { return null; }
                @Override public void setServiceWorkerClient(ServiceWorkerClient client) { }
            };
        }
    }

    private static final Uri APP = Uri.parse("https://__APP_HOST__");
    private static final String AUDIO = PermissionRequest.RESOURCE_AUDIO_CAPTURE;

    // Answering twice throws, as the real WebView request does.
    private static final class FakeRequest extends PermissionRequest {
        private final Uri origin;
        private final String[] resources;
        int grants = 0;
        int denies = 0;
        boolean resolved = false;

        FakeRequest(Uri origin, String... resources) {
            this.origin = origin;
            this.resources = resources;
        }

        @Override public Uri getOrigin() { return origin; }
        @Override public String[] getResources() { return resources; }

        @Override public void grant(String[] granted) {
            if (resolved) throw new IllegalStateException("Either grant() or deny() has been already called.");
            resolved = true;
            grants++;
        }

        @Override public void deny() {
            if (resolved) throw new IllegalStateException("Either grant() or deny() has been already called.");
            resolved = true;
            denies++;
        }
    }

    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private ShadowActivity shadow;
    private WebChromeClient client;
    private Application application;

    @Before
    public void setUp() {
        application = ApplicationProvider.getApplicationContext();
        // Bridge refuses to start without a WebView package of a recent enough version.
        PackageInfo webView = new PackageInfo();
        webView.packageName = "com.google.android.webview";
        webView.versionName = "130.0.0.0";
        ShadowWebView.setCurrentWebViewPackage(webView);
        // The browser plugin binds the Custom Tabs service; Robolectric would "connect" it to nothing.
        shadowOf(application).declareActionUnbindable("android.support.customtabs.action.CustomTabsService");
        controller = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();
        activity = controller.get();
        shadow = shadowOf(activity);
        client = activity.getBridge().getWebView().getWebChromeClient();
        assertNotNull("the bridge web view has a chrome client", client);
        assertTrue("MainActivity installed the shell client", client instanceof ShellWebChromeClient);
    }

    private FakeRequest audio() { return new FakeRequest(APP, AUDIO); }

    // Counts the permission dialogs launched since the last call.
    private int launches() {
        int n = 0;
        while (shadow.getNextStartedActivityForResult() != null) n++;
        return n;
    }

    // The dialog closes: the last requested permissions come back granted or denied, and the real
    // onRequestPermissionsResult hands them to Capacitor's launcher callback.
    private void dialogAnswers(boolean allow) {
        ShadowActivity.PermissionsRequest last = shadow.getLastRequestedPermission();
        assertNotNull("a permission dialog was launched", last);
        int[] results = new int[last.requestedPermissions.length];
        for (int i = 0; i < results.length; i++) results[i] = allow ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
        if (allow) shadowOf(application).grantPermissions(last.requestedPermissions);
        deliver(last.requestCode, last.requestedPermissions, results);
    }

    // What the system does when a dialog closes: Activity.dispatchRequestPermissionsResult clears the
    // "one request at a time" flag and calls the real onRequestPermissionsResult chain.
    private void deliver(int requestCode, String[] permissions, int[] results) {
        Intent data = new Intent()
            .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES", permissions)
            .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS", results);
        ReflectionHelpers.callInstanceMethod(android.app.Activity.class, activity, "dispatchRequestPermissionsResult",
            ClassParameter.from(int.class, requestCode), ClassParameter.from(Intent.class, data));
    }

    private void dialogShows() { controller.pause(); }

    private void dialogCloses() { controller.resume(); }

    @Test
    public void secondRequestQueuesDuringTheDialogAndBothAreGrantedOnce() {
        FakeRequest a = audio(), b = audio();
        client.onPermissionRequest(a);
        assertEquals(1, launches());
        dialogShows();
        client.onPermissionRequest(b);
        assertEquals("no second dialog", 0, launches());
        dialogAnswers(true);
        dialogCloses();
        assertEquals(1, a.grants);
        assertEquals(1, b.grants);
        assertEquals(0, a.denies + b.denies);
    }

    @Test
    public void secondRequestInTheSameTickQueuesBeforeTheDialogIsShown() {
        FakeRequest a = audio(), b = audio();
        client.onPermissionRequest(a);
        client.onPermissionRequest(b);
        assertEquals("one dialog for both", 1, launches());
        dialogShows();
        dialogAnswers(true);
        dialogCloses();
        assertEquals(1, a.grants);
        assertEquals(1, b.grants);
    }

    @Test
    public void deniedDialogDeniesBothAndALaterRequestLaunchesAgain() {
        FakeRequest a = audio(), b = audio(), c = audio();
        client.onPermissionRequest(a);
        launches();
        dialogShows();
        client.onPermissionRequest(b);
        dialogAnswers(false);
        dialogCloses();
        assertEquals(1, a.denies);
        assertEquals(1, b.denies);
        assertEquals(0, a.grants + b.grants);
        client.onPermissionRequest(c);
        assertEquals("a new dialog after the old one closed", 1, launches());
    }

    @Test
    public void aRequestTheWebViewAlreadyResolvedDoesNotBreakTheResult() {
        FakeRequest a = audio(), b = audio();
        client.onPermissionRequest(a);
        dialogShows();
        client.onPermissionRequest(b);
        a.resolved = true;
        dialogAnswers(true);
        dialogCloses();
        assertEquals("the other request is still answered", 1, b.grants);
        assertEquals(0, a.grants + a.denies);
    }

    @Test
    public void theSameResultDeliveredTwiceAnswersOnce() {
        FakeRequest a = audio();
        client.onPermissionRequest(a);
        dialogShows();
        ShadowActivity.PermissionsRequest last = shadow.getLastRequestedPermission();
        dialogAnswers(true);
        deliver(last.requestCode, last.requestedPermissions, new int[last.requestedPermissions.length]);
        dialogCloses();
        assertEquals(1, a.grants);
        assertEquals(0, a.denies);
    }

    @Test
    public void lostAnswerIsSettledByHeldPermissionsAndAFreshDialogStarts() {
        FakeRequest a = audio(), b = audio();
        client.onPermissionRequest(a);
        launches();
        dialogShows();
        // Another flow takes Capacitor's shared listener while the media dialog is up.
        client.onGeolocationPermissionsShowPrompt("https://__APP_HOST__", new GeolocationPermissions.Callback() {
            @Override public void invoke(String origin, boolean allow, boolean retain) { }
        });
        // The system takes one permission request at a time, so this second launch is cancelled at
        // once and no new dialog opens; what matters here is that the listener is now the geolocation one.
        assertEquals(0, launches());
        dialogAnswers(false);
        dialogCloses();
        assertEquals("the media answer was lost: nothing answered yet", 0, a.grants + a.denies);
        client.onPermissionRequest(b);
        assertEquals("the old request is settled by the permissions held", 1, a.denies);
        assertEquals("a fresh dialog", 1, launches());
        dialogShows();
        dialogAnswers(true);
        dialogCloses();
        assertEquals(1, b.grants);
        assertEquals(1, a.denies);
    }

    @Test
    public void cancelledRequestGetsNoAnswer() {
        FakeRequest a = audio(), b = audio();
        client.onPermissionRequest(a);
        dialogShows();
        client.onPermissionRequest(b);
        client.onPermissionRequestCanceled(b);
        dialogAnswers(true);
        dialogCloses();
        assertEquals(1, a.grants);
        assertEquals(0, b.grants + b.denies);
    }

    @Test
    public void foreignOriginIsDeniedWithoutADialog() {
        FakeRequest f = new FakeRequest(Uri.parse("https://evil.example"), AUDIO);
        client.onPermissionRequest(f);
        assertEquals(1, f.denies);
        assertEquals(0, launches());
    }

    @Test
    public void heldPermissionsAreGrantedWithoutADialog() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS);
        FakeRequest a = audio();
        client.onPermissionRequest(a);
        assertEquals(1, a.grants);
        assertEquals(0, launches());
    }
}
