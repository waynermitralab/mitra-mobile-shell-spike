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
import android.webkit.ValueCallback;
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
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowWebView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Runs the generated MainActivity with the real Bridge and sends the web view client it installed
// the navigations of a page that signs in, then the result of the Auth Tab. The intent is built by
// the real AuthTabIntent and started through the launcher the shell registered, and the result comes
// back through the activity result registry (no browser runs in a unit test, so the test answers for
// it); the return goes through MainActivity.loadReturn, the same code a link takes.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = { ShellWebChromeClientTest.NoServiceWorkerProvider.class, ShellSignInTest.ScriptLog.class })
public class ShellSignInTest {

    // The shadow only keeps the last script and the last URL, and "at most once" needs all of them.
    @Implements(WebView.class)
    public static class ScriptLog extends ShadowWebView {
        static final List<String> scripts = new ArrayList<>();
        static final List<String> loads = new ArrayList<>();

        @Implementation
        protected void loadUrl(String url) {
            loads.add(url);
            super.loadUrl(url);
        }

        @Implementation
        protected void evaluateJavascript(String script, ValueCallback<String> callback) {
            scripts.add(script);
            super.evaluateJavascript(script, callback);
        }
    }

    private static final String APP_PAGE = "https://__APP_HOST__/home";
    // accounts.google.com is a sign-in host of every generated app.
    private static final String SIGN_IN_URL = "https://accounts.google.com/o/oauth2/v2/auth?client_id=1";
    private static final String SCHEME = "__PACKAGE__".toLowerCase();

    private static final class Request implements WebResourceRequest {
        private final Uri url;
        private final boolean mainFrame;
        Request(String url, boolean mainFrame) { this.url = Uri.parse(url); this.mainFrame = mainFrame; }
        @Override public Uri getUrl() { return url; }
        @Override public boolean isForMainFrame() { return mainFrame; }
        @Override public boolean isRedirect() { return false; }
        @Override public boolean hasGesture() { return false; }
        @Override public String getMethod() { return "GET"; }
        @Override public Map<String, String> getRequestHeaders() { return new HashMap<>(); }
    }

    // With the https return (no URL scheme) the sign-in stays on the bridge's path.
    private static final boolean BY_SCHEME = MainActivity.CALLBACK_SCHEME != null;

    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private WebView page;

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
        use(controller.get());
        page.loadUrl(APP_PAGE);
        idle();
        ScriptLog.scripts.clear();
    }

    private void use(MainActivity current) {
        activity = current;
        page = activity.getBridge().getWebView();
        activity.signIn().setAuthTabSupport((a) -> true);
    }

    // A navigation the page starts, as the web view hands it to its client. True means cancelled.
    private boolean navigates(String url, boolean mainFrame) {
        return page.getWebViewClient().shouldOverrideUrlLoading(page, new Request(url, mainFrame));
    }

    private void idle() {
        ShadowLooper.idleMainLooper();
    }

    // What the activity started last: the Auth Tab, through the launcher of the shell, or the plain
    // intent of the bridge. Null when nothing was started since the last call.
    private ShadowActivity.IntentForResult started() {
        return shadowOf(activity).getNextStartedActivityForResult();
    }

    private static boolean isAuthTab(ShadowActivity.IntentForResult started) {
        return started != null && started.intent.getBooleanExtra(AuthTabIntent.EXTRA_LAUNCH_AUTH_TAB, false);
    }

    // The page signs in and the Auth Tab opens. Returned to answer for the browser later.
    private ShadowActivity.IntentForResult signInOpens() {
        assertTrue(navigates(SIGN_IN_URL, true));
        ShadowActivity.IntentForResult tab = started();
        assertTrue("the Auth Tab was started for a result", isAuthTab(tab) && tab.requestCode >= 0);
        return tab;
    }

    // The browser ends the Auth Tab: the result goes through the activity result registry.
    private void authTabEnds(ShadowActivity.IntentForResult tab, int resultCode, String returnUrl) {
        assertTrue(activity.getActivityResultRegistry().dispatchResult(tab.requestCode, resultCode, returnUrl == null ? null : new Intent().setData(Uri.parse(returnUrl))));
        idle();
    }

    private List<String> events() {
        idle();
        List<String> events = new ArrayList<>();
        for (String script : ScriptLog.scripts) if (script.contains("mitrashell:")) events.add(script);
        return events;
    }

    private static List<String> event(String name) {
        return java.util.Collections.singletonList("window.Capacitor.triggerEvent(\"" + name + "\", \"window\")");
    }

    @Test
    public void aMainFrameNavigationToASignInHostOpensInTheAuthTab() {
        org.junit.Assume.assumeTrue(BY_SCHEME);
        assertTrue("the page does not navigate: the sign-in never runs in the web view", navigates(SIGN_IN_URL, true));
        ShadowActivity.IntentForResult tab = started();
        assertTrue(isAuthTab(tab));
        assertEquals(SIGN_IN_URL, tab.intent.getDataString());
        assertEquals("the return is the scheme of the shell", SCHEME, tab.intent.getStringExtra(AuthTabIntent.EXTRA_REDIRECT_SCHEME));
        assertNull("a scheme return names no host", tab.intent.getStringExtra(AuthTabIntent.EXTRA_HTTPS_REDIRECT_HOST));
        assertNull(tab.intent.getStringExtra(AuthTabIntent.EXTRA_HTTPS_REDIRECT_PATH));
        assertNull("and the system browser is not opened as well", started());
    }

    @Test
    public void aSecondSignInNavigationWhileTheAuthTabIsUpOpensNothing() {
        org.junit.Assume.assumeTrue(BY_SCHEME);
        ShadowActivity.IntentForResult tab = signInOpens();
        assertTrue("cancelled in the page", navigates(SIGN_IN_URL, true));
        assertTrue(navigates("https://accounts.google.com/other", true));
        assertNull("no second tab, and no system browser", started());
        authTabEnds(tab, AuthTabIntent.RESULT_CANCELED, null);
        assertEquals(event("mitrashell:authcancel"), events());
        assertTrue("once it ended the page can sign in again", isAuthTab(signInOpens()));
    }

    @Test
    public void onlyAnHttpsSignInHostOnTheMainFrameIsTaken() {
        assertTrue(navigates(SIGN_IN_URL, false));
        ShadowActivity.IntentForResult frame = started();
        assertFalse("a frame goes where the bridge always sent it", isAuthTab(frame));
        assertEquals(SIGN_IN_URL, frame.intent.getDataString());
        assertTrue(navigates("https://elsewhere.example/page", true));
        ShadowActivity.IntentForResult other = started();
        assertFalse(isAuthTab(other));
        assertEquals("https://elsewhere.example/page", other.intent.getDataString());
        String plain = SIGN_IN_URL.replace("https://", "http://");
        assertTrue(navigates(plain, true));
        ShadowActivity.IntentForResult http = started();
        assertFalse(isAuthTab(http));
        assertEquals(plain, http.intent.getDataString());
        assertFalse("a page of the app loads in the web view", navigates(activity.getBridge().getAppUrl() + "/other", true));
        assertNull(started());
        assertFalse(ShellSignIn.isSignIn(Uri.parse(APP_PAGE)));
        assertFalse(ShellSignIn.isSignIn(Uri.parse("https://accounts.google.com.evil.example/auth")));
        assertTrue("the host is compared without its case", ShellSignIn.isSignIn(Uri.parse("https://Accounts.Google.com/auth")));
        // No launch to answer for, so the outcome is given to the class itself.
        activity.signIn().onAuthOutcome(AuthTabIntent.RESULT_CANCELED, null);
        assertEquals("with no Auth Tab open there is nothing to tell the page", 0, events().size());
    }

    @Test
    public void withTheHttpsReturnTheSignInOpensAsItAlwaysDid() {
        org.junit.Assume.assumeFalse(BY_SCHEME);
        assertTrue(navigates(SIGN_IN_URL, true));
        ShadowActivity.IntentForResult external = started();
        assertNotNull("the bridge sends it to the system browser", external);
        assertFalse("an Auth Tab is there, and is not used", isAuthTab(external));
        assertEquals(SIGN_IN_URL, external.intent.getDataString());
        assertFalse(external.intent.hasExtra("android.support.customtabs.extra.SESSION"));
        assertEquals(0, events().size());
        // The return comes as an App Link.
        activity.onNewIntent(new Intent(Intent.ACTION_VIEW, Uri.parse("https://__APP_HOST__/any/path?x=1#codeMitra=abc")));
        idle();
        String loaded = shadowOf(page).getLastLoadedUrl();
        assertTrue(loaded, loaded.startsWith("https://__APP_HOST__/any/path?x=1&shellLink=") && loaded.endsWith("#codeMitra=abc"));
    }

    // The page a return by the scheme loads, read from server.url as the config wrote it: its path,
    // or / when it has none. CI generates this project at the root and under /noodle, and names the
    // path it asked for in SHELL_TEST_APP_PATH, so a config that lost the path does not pass either.
    private String serverPage() {
        Uri server = Uri.parse(activity.getBridge().getServerUrl());
        String expected = System.getenv("SHELL_TEST_APP_PATH");
        if (expected != null) assertEquals("the project was generated for this path", expected, server.getPath());
        String path = server.getPath();
        return server.getScheme() + "://" + server.getEncodedAuthority() + (path == null || path.isEmpty() ? "/" : path);
    }

    private static void assertLoads(String loaded, String page, String query, String code) {
        assertNotNull(loaded);
        String start = page + "?" + (query == null ? "" : query + "&") + "shellLink=";
        assertTrue(loaded + " starts with " + start, loaded.startsWith(start));
        assertTrue(loaded, loaded.endsWith("#codeMitra=" + code));
    }

    @Test
    public void aReturnByTheSchemeLoadsThePageOfServerUrlPathIncluded() {
        org.junit.Assume.assumeTrue(BY_SCHEME);
        String app = serverPage();
        ScriptLog.loads.clear();
        // By the Auth Tab.
        authTabEnds(signInOpens(), AuthTabIntent.RESULT_OK, SCHEME + "://auth?x=4#codeMitra=tab");
        assertEquals(1, ScriptLog.loads.size());
        assertLoads(ScriptLog.loads.get(0), app, "x=4", "tab");
        // As a link, which is the path without an Auth Tab.
        activity.onNewIntent(new Intent(Intent.ACTION_VIEW, Uri.parse(SCHEME + "://auth#codeMitra=link")));
        idle();
        assertEquals(2, ScriptLog.loads.size());
        assertLoads(ScriptLog.loads.get(1), app, null, "link");
        // The same link again is the same return, and loads nothing.
        activity.onNewIntent(new Intent(Intent.ACTION_VIEW, Uri.parse(SCHEME + "://auth#codeMitra=link")));
        idle();
        assertEquals("the single-use code is not spent twice", 2, ScriptLog.loads.size());
        // A return on the app host as an App Link keeps its own path, as it always did.
        activity.onNewIntent(new Intent(Intent.ACTION_VIEW, Uri.parse("https://__APP_HOST__/other/page?y=1#codeMitra=own")));
        idle();
        assertEquals(3, ScriptLog.loads.size());
        assertLoads(ScriptLog.loads.get(2), "https://__APP_HOST__/other/page", "y=1", "own");
        assertEquals("a return is not an event", 0, events().size());
    }

    @Test
    public void aReturnReloadsThePageWithItsFragmentLikeALinkDoes() {
        org.junit.Assume.assumeTrue(BY_SCHEME);
        authTabEnds(signInOpens(), AuthTabIntent.RESULT_OK, SCHEME + "://auth?x=2#codeMitra=def");
        assertLoads(shadowOf(page).getLastLoadedUrl(), serverPage(), "x=2", "def");
        // The same return as a link, which is the path without an Auth Tab.
        activity.onNewIntent(new Intent(Intent.ACTION_VIEW, Uri.parse(SCHEME + "://auth?x=3#codeMitra=ghi")));
        idle();
        assertTrue(shadowOf(page).getLastLoadedUrl().endsWith("#codeMitra=ghi"));
        assertEquals("a return is not an event", 0, events().size());
    }

    @Test
    public void theSameReturnHandedOverTwiceLoadsOnce() {
        org.junit.Assume.assumeTrue(BY_SCHEME);
        String back = SCHEME + "://auth#codeMitra=once";
        ScriptLog.loads.clear();
        authTabEnds(signInOpens(), AuthTabIntent.RESULT_OK, back);
        assertEquals(1, ScriptLog.loads.size());
        assertTrue(ScriptLog.loads.get(0), ScriptLog.loads.get(0).endsWith("#codeMitra=once"));
        activity.onNewIntent(new Intent(Intent.ACTION_VIEW, Uri.parse(back)));
        idle();
        assertEquals("the single-use code is not spent twice", 1, ScriptLog.loads.size());
        assertEquals(0, events().size());
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(4));
        activity.onNewIntent(new Intent(Intent.ACTION_VIEW, Uri.parse(back)));
        idle();
        assertEquals("after the window it is a new return", 2, ScriptLog.loads.size());
    }

    @Test
    public void closingTheAuthTabTellsThePageItWasCancelled() {
        org.junit.Assume.assumeTrue(BY_SCHEME);
        String before = shadowOf(page).getLastLoadedUrl();
        authTabEnds(signInOpens(), AuthTabIntent.RESULT_CANCELED, null);
        assertEquals(event("mitrashell:authcancel"), events());
        assertEquals("the page keeps its document", before, shadowOf(page).getLastLoadedUrl());
        assertNull("and nothing else is opened", started());
    }

    @Test
    public void anAuthTabThatFailsTellsThePageItFailedAndOpensNothing() {
        org.junit.Assume.assumeTrue(BY_SCHEME);
        String before = shadowOf(page).getLastLoadedUrl();
        // 77 is a code the Auth Tab does not define, which the contract turns into RESULT_UNKNOWN_CODE.
        int[] codes = { AuthTabIntent.RESULT_VERIFICATION_FAILED, AuthTabIntent.RESULT_VERIFICATION_TIMED_OUT, 77, AuthTabIntent.RESULT_OK, AuthTabIntent.RESULT_OK };
        String[] urls = { null, null, null, null, "https://evil.example/?x=1#codeMitra=abc" };
        for (int i = 0; i < codes.length; i++) {
            ScriptLog.scripts.clear();
            authTabEnds(signInOpens(), codes[i], urls[i]);
            assertEquals("result " + codes[i] + " with " + urls[i], event("mitrashell:authfail"), events());
            assertEquals("nothing loads", before, shadowOf(page).getLastLoadedUrl());
            assertNull("the browser is not opened again", started());
        }
    }

    @Test
    public void aSignInTellsThePageAtMostOnce() {
        org.junit.Assume.assumeTrue(BY_SCHEME);
        ShadowActivity.IntentForResult tab = signInOpens();
        authTabEnds(tab, AuthTabIntent.RESULT_CANCELED, null);
        authTabEnds(tab, AuthTabIntent.RESULT_CANCELED, null);
        authTabEnds(tab, AuthTabIntent.RESULT_VERIFICATION_FAILED, null);
        assertEquals(1, events().size());

        ScriptLog.scripts.clear();
        tab = signInOpens();
        authTabEnds(tab, AuthTabIntent.RESULT_OK, SCHEME + "://auth#codeMitra=abc");
        authTabEnds(tab, AuthTabIntent.RESULT_CANCELED, null);
        assertEquals("a sign-in that returned has nothing more to say", 0, events().size());

        authTabEnds(signInOpens(), AuthTabIntent.RESULT_VERIFICATION_FAILED, null);
        assertEquals("the next sign-in reports its own end", event("mitrashell:authfail"), events());
    }

    @Test
    public void anActivityRecreatedUnderTheAuthTabStillLoadsTheReturn() {
        org.junit.Assume.assumeTrue(BY_SCHEME);
        ShadowActivity.IntentForResult tab = signInOpens();
        // Android destroys the activity while the browser is in front and builds it again from the
        // saved state, where the registry keeps the request of the launcher.
        controller.recreate();
        assertFalse("a new instance, which opened nothing itself", activity == controller.get());
        use(controller.get());
        ScriptLog.scripts.clear();
        authTabEnds(tab, AuthTabIntent.RESULT_OK, SCHEME + "://auth?x=1#codeMitra=abc");
        String loaded = shadowOf(page).getLastLoadedUrl();
        assertNotNull("the person who signed in is not left on a signed-out page", loaded);
        assertTrue(loaded, loaded.contains("x=1&shellLink=") && loaded.endsWith("#codeMitra=abc"));
        assertEquals(0, events().size());
    }

    @Test
    public void anActivityRecreatedUnderTheAuthTabDoesNotReportACancel() {
        org.junit.Assume.assumeTrue(BY_SCHEME);
        ShadowActivity.IntentForResult tab = signInOpens();
        controller.recreate();
        use(controller.get());
        ScriptLog.scripts.clear();
        authTabEnds(tab, AuthTabIntent.RESULT_CANCELED, null);
        assertEquals("the page that waited for the sign-in is gone", 0, events().size());
    }

    @Test
    public void withoutAnAuthTabTheSignInOpensAsItAlwaysDid() {
        activity.signIn().setAuthTabSupport((a) -> false);
        assertTrue(navigates(SIGN_IN_URL, true));
        ShadowActivity.IntentForResult external = started();
        assertNotNull("the bridge sends it to the system browser", external);
        assertFalse(isAuthTab(external));
        assertEquals(Intent.ACTION_VIEW, external.intent.getAction());
        assertEquals(SIGN_IN_URL, external.intent.getDataString());
        assertFalse("its plain intent, not a Custom Tab", external.intent.hasExtra("android.support.customtabs.extra.SESSION"));
        assertEquals("no Auth Tab, no events", 0, events().size());

        // The return then comes as a link, by the scheme or on the app host.
        activity.onNewIntent(new Intent(Intent.ACTION_VIEW, Uri.parse("https://__APP_HOST__/?x=1#codeMitra=abc")));
        idle();
        String loaded = shadowOf(page).getLastLoadedUrl();
        assertTrue(loaded, loaded.contains("shellLink=") && loaded.endsWith("#codeMitra=abc"));
    }

    @Test
    public void theBuiltInBrowserCheckFindsNoAuthTabAndTheBridgeOpensTheSignIn() {
        // A second activity keeps the check the shell built. Robolectric has no browser installed.
        activity = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible().get();
        page = activity.getBridge().getWebView();
        assertTrue(navigates(SIGN_IN_URL, true));
        ShadowActivity.IntentForResult external = started();
        assertNotNull(external);
        assertFalse(isAuthTab(external));
        assertEquals(SIGN_IN_URL, external.intent.getDataString());
    }
}
