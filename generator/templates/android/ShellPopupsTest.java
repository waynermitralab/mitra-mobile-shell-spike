package __PACKAGE__;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;

import androidx.browser.auth.AuthTabIntent;
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
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowWebView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// Runs the generated MainActivity with the real Bridge and sends it what a page does: window.open
// through the chrome client the activity installed, then the navigations of the child window it
// got, then the return by App Link. The system browser is replaced by a recorder (no browser runs
// in a unit test); everything else is the production path, including MainActivity.onNewIntent.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = { ShellWebChromeClientTest.NoServiceWorkerProvider.class, ShellPopupsTest.CountingWebView.class })
public class ShellPopupsTest {

    // The shadow keeps no record of stopLoading, which is how a load that started is cut short.
    @Implements(WebView.class)
    public static class CountingWebView extends ShadowWebView {
        static int stops = 0;

        @Implementation
        protected void stopLoading() {
            stops++;
        }
    }

    private static final String APP_PAGE = "https://__APP_HOST__/home";
    private static final String RETURN_PATH = "__RETURN_PATH__";
    private static final String RETURN_URL = "https://__APP_HOST__" + RETURN_PATH + "google?code=c1&state=s1";
    // accounts.google.com is a sign-in host of every generated app, so it is always a consent host.
    private static final String CONSENT_URL = "https://accounts.google.com/o/oauth2/v2/auth?client_id=1";
    private static final String EXACT_PATH = RETURN_PATH + "google";
    private static final String CONSENT_WITH_REDIRECT = CONSENT_URL + "&redirect_uri=" + Uri.encode("https://__APP_HOST__" + EXACT_PATH);

    private static final class RecordingBrowser implements ShellPopups.SecureBrowser {
        final List<String> launched = new ArrayList<>();
        final List<String> paths = new ArrayList<>();
        boolean available = true;

        @Override public boolean launch(Uri url, String exactReturnPath) {
            launched.add(url.toString());
            paths.add(exactReturnPath);
            return available;
        }
    }

    private static final class Request implements WebResourceRequest {
        private final Uri url;
        Request(String url) { this.url = Uri.parse(url); }
        @Override public Uri getUrl() { return url; }
        @Override public boolean isForMainFrame() { return true; }
        @Override public boolean isRedirect() { return false; }
        @Override public boolean hasGesture() { return false; }
        @Override public String getMethod() { return "GET"; }
        @Override public Map<String, String> getRequestHeaders() { return new java.util.HashMap<>(); }
    }

    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private WebView page;
    private RecordingBrowser browser;

    @Before
    public void setUp() {
        Application application = ApplicationProvider.getApplicationContext();
        // The bridge refuses to start without a WebView provider that is recent enough.
        PackageInfo webView = new PackageInfo();
        webView.packageName = "com.google.android.webview";
        webView.versionName = "140.0.0.0";
        ShadowWebView.setCurrentWebViewPackage(webView);
        shadowOf(application).declareActionUnbindable("android.support.customtabs.action.CustomTabsService");
        controller = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();
        activity = controller.get();
        page = activity.getBridge().getWebView();
        // The page the user is on: the child policy reads the opener from it.
        page.loadUrl(APP_PAGE);
        CountingWebView.stops = 0;
        browser = new RecordingBrowser();
        activity.popups().setSecureBrowser(browser);
    }

    // window.open as the WebView hands it to the chrome client. The child is what the page keeps.
    private WebView windowOpen(boolean userGesture) {
        WebView.WebViewTransport transport = page.new WebViewTransport();
        Message message = Message.obtain(new Handler(Looper.getMainLooper()), 0, transport);
        assertTrue(page.getWebChromeClient().onCreateWindow(page, false, userGesture, message));
        assertNotNull("the page always gets a window back, so window.open never returns null", transport.getWebView());
        return transport.getWebView();
    }

    // The child asks for a URL the way a window.open(url) does. True means the shell cancelled it.
    private boolean childNavigates(WebView child, String url) {
        return child.getWebViewClient().shouldOverrideUrlLoading(child, new Request(url));
    }

    private void idle() {
        ShadowLooper.idleMainLooper();
    }

    private void idle(long ms) {
        ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS);
    }

    private boolean destroyed(WebView child) {
        idle();
        return shadowOf(child).wasDestroyCalled();
    }

    private Intent startedActivity() {
        return shadowOf(activity).getNextStartedActivity();
    }

    private void appLinkArrives(String url) {
        activity.onNewIntent(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        idle();
    }

    @Test
    public void aGestureOnAnAppPageToAConsentHostGoesToTheSystemBrowserAndKeepsTheChild() {
        WebView child = windowOpen(true);
        assertTrue("the child's own load is cancelled: consent never runs in a WebView", childNavigates(child, CONSENT_URL));
        assertEquals(1, browser.launched.size());
        assertEquals(CONSENT_URL, browser.launched.get(0));
        assertFalse("the child waits for the return", destroyed(child));
        assertNull("nothing else was opened", startedActivity());
        assertNull("the child has no bridge to the app", shadowOf(child).getJavascriptInterface("androidBridge"));
    }

    @Test
    public void withoutAGestureTheWindowOpensAsItAlwaysDidAndTheChildIsDropped() {
        WebView child = windowOpen(false);
        assertTrue(childNavigates(child, CONSENT_URL));
        assertEquals(0, browser.launched.size());
        Intent external = startedActivity();
        assertNotNull("the bridge sends other hosts to the system", external);
        assertEquals(CONSENT_URL, external.getDataString());
        assertTrue(destroyed(child));
    }

    @Test
    public void aHostOutsideTheListOpensAsItAlwaysDid() {
        WebView child = windowOpen(true);
        assertTrue(childNavigates(child, "https://elsewhere.example/share"));
        assertEquals(0, browser.launched.size());
        assertEquals("https://elsewhere.example/share", startedActivity().getDataString());
        assertTrue(destroyed(child));
    }

    @Test
    public void aSchemeThatIsNotHttpStillGoesToTheBridgeLikeBefore() {
        for (boolean gesture : new boolean[] { true, false }) {
            WebView child = windowOpen(gesture);
            assertTrue(childNavigates(child, "mailto:support@example.com"));
            Intent external = startedActivity();
            assertNotNull("mailto: is handed to the system, as when the window opened in the page", external);
            assertEquals("mailto:support@example.com", external.getDataString());
            assertTrue(destroyed(child));
        }
        assertEquals(0, browser.launched.size());
    }

    @Test
    public void aPostThatSkipsShouldOverrideUrlLoadingIsStillJudged() {
        // A form with target=_blank and method=post starts the load without shouldOverrideUrlLoading.
        WebView child = windowOpen(true);
        child.getWebViewClient().onPageStarted(child, CONSENT_URL, null);
        assertEquals("the consent leaves the web view for the browser", 1, browser.launched.size());
        assertEquals(1, CountingWebView.stops);

        WebView other = windowOpen(false);
        int before = CountingWebView.stops;
        other.getWebViewClient().onPageStarted(other, "https://elsewhere.example/post", null);
        assertEquals("a host outside the list goes to the system, not to the hidden web view", "https://elsewhere.example/post", startedActivity().getDataString());
        assertEquals(before + 1, CountingWebView.stops);
    }

    @Test
    public void afterTheReturnAPostOutsideTheAppHostIsStopped() {
        WebView child = windowOpen(true);
        childNavigates(child, CONSENT_URL);
        appLinkArrives(RETURN_URL);
        int before = CountingWebView.stops;
        child.getWebViewClient().onPageStarted(child, RETURN_URL, null);
        assertEquals("the return page itself loads", before, CountingWebView.stops);
        child.getWebViewClient().onPageStarted(child, "https://evil.example/collect", null);
        assertEquals(before + 1, CountingWebView.stops);
        assertTrue("the request is already out, so the child is closed, not left with that document", destroyed(child));
        WebView next = windowOpen(true);
        assertTrue("and the slot is free", childNavigates(next, CONSENT_URL));
        before = CountingWebView.stops;
        next.getWebViewClient().onPageStarted(next, "about:blank", null);
        assertEquals("the empty initial document is not a navigation", before, CountingWebView.stops);
    }

    @Test
    public void theAuthTabResultDrivesTheChild() {
        WebView ok = windowOpen(true);
        childNavigates(ok, CONSENT_URL);
        activity.popups().onAuthOutcome(AuthTabIntent.RESULT_OK, Uri.parse(RETURN_URL));
        assertEquals(RETURN_URL, shadowOf(ok).getLastLoadedUrl());
        ok.getWebChromeClient().onCloseWindow(ok);
        assertTrue(destroyed(ok));

        int[] failures = { AuthTabIntent.RESULT_CANCELED, AuthTabIntent.RESULT_VERIFICATION_FAILED, AuthTabIntent.RESULT_VERIFICATION_TIMED_OUT, AuthTabIntent.RESULT_UNKNOWN_CODE };
        for (int code : failures) {
            WebView child = windowOpen(true);
            childNavigates(child, CONSENT_URL);
            activity.popups().onAuthOutcome(code, null);
            assertEquals("the page sees the window close, result " + code, "window.close()", shadowOf(child).getLastEvaluatedJavascript());
            assertNull(shadowOf(child).getLastLoadedUrl());
            idle(2_500);
            assertTrue(destroyed(child));
        }

        WebView outside = windowOpen(true);
        childNavigates(outside, CONSENT_URL);
        activity.popups().onAuthOutcome(AuthTabIntent.RESULT_OK, Uri.parse("https://__APP_HOST__/home?code=c"));
        assertNull("an OK with a URL outside the prefix is not taken", shadowOf(outside).getLastLoadedUrl());
        assertEquals("window.close()", shadowOf(outside).getLastEvaluatedJavascript());
    }

    @Test
    public void anAuthTabResultWithNoSessionIsIgnored() {
        activity.popups().onAuthOutcome(AuthTabIntent.RESULT_OK, Uri.parse(RETURN_URL));
        WebView child = windowOpen(true);
        assertFalse(destroyed(child));
        assertNull(shadowOf(child).getLastLoadedUrl());
    }

    @Test
    public void aWindowOpenFromAnotherHostIsNotAnAppPopup() {
        page.loadUrl("https://evil.example/home");
        WebView child = windowOpen(true);
        assertTrue(childNavigates(child, CONSENT_URL));
        assertEquals(0, browser.launched.size());
        assertEquals(CONSENT_URL, startedActivity().getDataString());
        assertTrue(destroyed(child));
    }

    @Test
    public void aWindowOpenToTheAppHostLoadsInThePageAsItAlwaysDid() {
        // The bridge's idea of the app URL, whatever scheme the fixture serves.
        String appPage = activity.getBridge().getAppUrl() + "/other";
        WebView child = windowOpen(false);
        assertTrue(childNavigates(child, appPage));
        assertEquals(appPage, shadowOf(page).getLastLoadedUrl());
        assertTrue(destroyed(child));
    }

    @Test
    public void aSecondPopupWhileOneIsPendingIsNotGivenAChild() {
        WebView first = windowOpen(true);
        childNavigates(first, CONSENT_URL);
        WebView second = windowOpen(true);
        assertTrue(childNavigates(second, "https://github.com/login/oauth/authorize"));
        assertEquals("only the first reached the browser", 1, browser.launched.size());
        assertEquals("https://github.com/login/oauth/authorize", startedActivity().getDataString());
        assertTrue(destroyed(second));
        assertFalse("the first one is untouched", destroyed(first));
    }

    @Test
    public void theReturnLoadsInTheChildAndThePageIsNotReloaded() {
        WebView child = windowOpen(true);
        childNavigates(child, CONSENT_URL);
        String pageBefore = shadowOf(page).getLastLoadedUrl();
        appLinkArrives(RETURN_URL);
        assertEquals("the page of the return does its own postMessage and close", RETURN_URL, shadowOf(child).getLastLoadedUrl());
        assertEquals("the main page keeps its document, and with it the state the site waits on", pageBefore, shadowOf(page).getLastLoadedUrl());
    }

    @Test
    public void theChildOnlyFollowsTheAppHostAfterTheReturn() {
        WebView child = windowOpen(true);
        childNavigates(child, CONSENT_URL);
        assertTrue("before the return nothing loads in the child", childNavigates(child, "https://__APP_HOST__/home"));
        appLinkArrives(RETURN_URL);
        assertFalse(childNavigates(child, "https://__APP_HOST__/home"));
        assertTrue(childNavigates(child, "https://evil.example/x"));
        assertTrue(childNavigates(child, "http://__APP_HOST__/home"));
    }

    @Test
    public void aReturnOutsideThePrefixIsNotTakenByTheChild() {
        WebView child = windowOpen(true);
        childNavigates(child, CONSENT_URL);
        appLinkArrives("https://__APP_HOST__/home?code=c1");
        assertNull("the child was not given the URL", shadowOf(child).getLastLoadedUrl());
        assertTrue("it went where an App Link always went", shadowOf(page).getLastLoadedUrl().startsWith("https://__APP_HOST__/home?code=c1&shellLink="));
        appLinkArrives("https://__APP_HOST__" + RETURN_PATH + "../admin");
        assertNull(shadowOf(child).getLastLoadedUrl());
    }

    @Test
    public void aReturnWithNoSessionOfTheShellLoadsInThePageAsBefore() {
        appLinkArrives(RETURN_URL);
        assertTrue(shadowOf(page).getLastLoadedUrl().startsWith(RETURN_URL + "&shellLink="));
    }

    @Test
    public void theSchemeReturnOfTheSignInStillReloadsThePage() {
        WebView child = windowOpen(true);
        childNavigates(child, CONSENT_URL);
        appLinkArrives("__PACKAGE__".toLowerCase() + "://auth?x=1#codeMitra=abc");
        String loaded = shadowOf(page).getLastLoadedUrl();
        assertTrue(loaded, loaded.contains("shellLink=") && loaded.endsWith("#codeMitra=abc") && loaded.contains("x=1"));
        assertNull("and the child of a connection in progress is left alone", shadowOf(child).getLastLoadedUrl());
        assertFalse(destroyed(child));
    }

    @Test
    public void closingTheBrowserWithoutAReturnClosesTheChild() {
        WebView child = windowOpen(true);
        childNavigates(child, CONSENT_URL);
        controller.pause();
        idle();
        assertFalse("still in the browser", destroyed(child));
        controller.resume();
        assertEquals("the page sees closed become true", "window.close()", shadowOf(child).getLastEvaluatedJavascript());
        shadowOf(child).getLastEvaluatedJavascriptCallback().onReceiveValue("null");
        assertTrue(destroyed(child));
    }

    @Test
    public void aChildThatDoesNotAnswerWindowCloseIsDestroyedAnyway() {
        WebView child = windowOpen(true);
        childNavigates(child, CONSENT_URL);
        controller.pause();
        controller.resume();
        assertFalse(destroyed(child));
        idle(2_500);
        assertTrue(destroyed(child));
    }

    @Test
    public void aReturnBeforeTheActivityResumesIsNotACancel() {
        WebView child = windowOpen(true);
        childNavigates(child, CONSENT_URL);
        controller.pause();
        appLinkArrives(RETURN_URL);
        controller.resume();
        assertEquals(RETURN_URL, shadowOf(child).getLastLoadedUrl());
        assertFalse(destroyed(child));
    }

    @Test
    public void theChildClosingItselfFreesTheSlot() {
        WebView child = windowOpen(true);
        childNavigates(child, CONSENT_URL);
        appLinkArrives(RETURN_URL);
        child.getWebChromeClient().onCloseWindow(child);
        assertTrue(destroyed(child));
        WebView next = windowOpen(true);
        assertTrue(childNavigates(next, CONSENT_URL));
        assertEquals(2, browser.launched.size());
    }

    @Test
    public void aChildThatNeverFinishesIsReaped() {
        WebView child = windowOpen(true);
        childNavigates(child, CONSENT_URL);
        appLinkArrives(RETURN_URL);
        idle(16_000);
        assertTrue("the return page never closed itself", destroyed(child));
        WebView next = windowOpen(true);
        assertTrue(childNavigates(next, CONSENT_URL));
    }

    @Test
    public void aChildThatNeverAsksForAnUrlIsReaped() {
        WebView child = windowOpen(true);
        idle(16_000);
        assertTrue(destroyed(child));
        WebView next = windowOpen(true);
        assertTrue(childNavigates(next, CONSENT_URL));
        assertEquals(1, browser.launched.size());
    }

    @Test
    public void whenNoBrowserCanTakeTheConsentItOpensAsItAlwaysDid() {
        browser.available = false;
        WebView child = windowOpen(true);
        assertTrue(childNavigates(child, CONSENT_URL));
        assertEquals(CONSENT_URL, startedActivity().getDataString());
        assertTrue(destroyed(child));
        WebView next = windowOpen(true);
        assertTrue("the slot is free again", childNavigates(next, CONSENT_URL));
    }

    @Test
    public void theBrowserGetsTheExactReturnPathOfTheConsent() {
        WebView child = windowOpen(true);
        childNavigates(child, CONSENT_WITH_REDIRECT);
        assertEquals(EXACT_PATH, browser.paths.get(0));
        child.getWebChromeClient().onCloseWindow(child);
        idle();
        WebView next = windowOpen(true);
        childNavigates(next, CONSENT_URL);
        assertNull("no redirect_uri, no exact path", browser.paths.get(1));
        next.getWebChromeClient().onCloseWindow(next);
        idle();
        for (String hostile : new String[] {
            CONSENT_URL + "&redirect_uri=" + Uri.encode("https://evil.example" + EXACT_PATH),
            CONSENT_URL + "&redirect_uri=" + Uri.encode("http://__APP_HOST__" + EXACT_PATH),
            CONSENT_URL + "&redirect_uri=" + Uri.encode("https://__APP_HOST__/home"),
            CONSENT_URL + "&redirect_uri=" + Uri.encode("https://__APP_HOST__" + RETURN_PATH + "../admin"),
            CONSENT_WITH_REDIRECT + "&redirect_uri=" + Uri.encode("https://__APP_HOST__" + RETURN_PATH + "github") }) {
            WebView c = windowOpen(true);
            childNavigates(c, hostile);
            assertNull("no exact path for " + hostile, browser.paths.get(browser.paths.size() - 1));
            c.getWebChromeClient().onCloseWindow(c);
            idle();
        }
    }

    @Test
    public void aSessionWithAnExactPathOnlyTakesThatReturn() {
        WebView child = windowOpen(true);
        childNavigates(child, CONSENT_WITH_REDIRECT);
        appLinkArrives("https://__APP_HOST__" + RETURN_PATH + "github?code=c&state=s");
        assertNull("another path inside the prefix is not this consent's return", shadowOf(child).getLastLoadedUrl());
        activity.popups().onAuthOutcome(AuthTabIntent.RESULT_OK, Uri.parse("https://__APP_HOST__" + RETURN_PATH + "github?code=c"));
        assertEquals("window.close()", shadowOf(child).getLastEvaluatedJavascript());
    }

    // The real intent the system browser is given, through the real AuthTabIntent and CustomTabsIntent.
    private static final class CapturingLauncher extends androidx.activity.result.ActivityResultLauncher<Intent> {
        final List<Intent> launched = new ArrayList<>();
        @Override public void launch(Intent input, androidx.core.app.ActivityOptionsCompat options) { launched.add(input); }
        @Override public void unregister() { }
        @Override public androidx.activity.result.contract.ActivityResultContract<Intent, ?> getContract() { return null; }
    }

    @Test
    public void theAuthTabIsBuiltWithTheExactHostAndPathOnly() {
        CapturingLauncher launcher = new CapturingLauncher();
        ShellPopups.SystemBrowser real = new ShellPopups.SystemBrowser(activity, launcher, (a) -> true);
        assertTrue(real.launch(Uri.parse(CONSENT_WITH_REDIRECT), EXACT_PATH));
        assertEquals(1, launcher.launched.size());
        Intent intent = launcher.launched.get(0);
        assertEquals(CONSENT_WITH_REDIRECT, intent.getDataString());
        assertEquals("__APP_HOST__", intent.getStringExtra(AuthTabIntent.EXTRA_HTTPS_REDIRECT_HOST));
        assertEquals("the path is the exact one, not the prefix", EXACT_PATH, intent.getStringExtra(AuthTabIntent.EXTRA_HTTPS_REDIRECT_PATH));
        assertNull("nothing started as a plain activity", startedActivity());
    }

    @Test
    public void withoutAnExactPathOrAnAuthTabItIsACustomTab() {
        CapturingLauncher launcher = new CapturingLauncher();
        assertTrue(new ShellPopups.SystemBrowser(activity, launcher, (a) -> true).launch(Uri.parse(CONSENT_URL), null));
        assertEquals("no exact path, no Auth Tab", 0, launcher.launched.size());
        Intent tab = startedActivity();
        assertEquals(CONSENT_URL, tab.getDataString());
        assertTrue(tab.hasExtra("android.support.customtabs.extra.SESSION"));

        assertTrue(new ShellPopups.SystemBrowser(activity, launcher, (a) -> false).launch(Uri.parse(CONSENT_WITH_REDIRECT), EXACT_PATH));
        assertEquals("a browser without the Auth Tab", 0, launcher.launched.size());
        assertEquals(CONSENT_WITH_REDIRECT, startedActivity().getDataString());
    }

    @Test
    public void theBuiltInBrowserFallsBackToACustomTabWhenThereIsNoAuthTab() {
        // A second activity keeps the real system browser. Robolectric has no browser installed, so
        // the Auth Tab check finds none and the Custom Tab starts as an ACTION_VIEW.
        controller = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();
        activity = controller.get();
        page = activity.getBridge().getWebView();
        page.loadUrl(APP_PAGE);
        WebView child = windowOpen(true);
        assertTrue(childNavigates(child, CONSENT_URL));
        Intent tab = startedActivity();
        assertNotNull(tab);
        assertEquals(Intent.ACTION_VIEW, tab.getAction());
        assertEquals(CONSENT_URL, tab.getDataString());
        assertTrue("it is a Custom Tab, not the bridge's plain intent", tab.hasExtra("android.support.customtabs.extra.SESSION"));
        assertFalse("and the child waits for the App Link return", destroyed(child));
    }
}
