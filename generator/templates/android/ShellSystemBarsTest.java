package __PACKAGE__;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowWebView;

import java.util.ArrayList;
import java.util.List;

// Runs the generated MainActivity, which installs ShellSystemBars on the real Bridge, and checks
// what reaches the web view: the script on the app host only, nothing below the WebView version
// Capacitor needs for edge to edge, and the recheck after a configuration change. The page load is
// played through the bridge's own WebViewClient, so the real listener dispatch is what runs.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = { ShellWebChromeClientTest.NoServiceWorkerProvider.class, ShellSystemBarsTest.RecordingWebView.class })
public class ShellSystemBarsTest {

    // Capacitor evaluates scripts of its own (SystemBars does on every commit visible), so the last
    // evaluated script says nothing; this keeps all of them.
    @Implements(WebView.class)
    public static class RecordingWebView extends ShadowWebView {
        static final List<String> evaluated = new ArrayList<>();

        @Implementation
        @Override
        protected void evaluateJavascript(String script, ValueCallback<String> callback) {
            evaluated.add(script);
            super.evaluateJavascript(script, callback);
        }
    }

    private static final String APP_URL = "https://__APP_HOST__/home";

    private MainActivity activity;

    @Before
    public void setUp() {
        RecordingWebView.evaluated.clear();
    }

    private void boot(String webViewVersion) {
        Application application = ApplicationProvider.getApplicationContext();
        PackageInfo webView = new PackageInfo();
        webView.packageName = "com.google.android.webview";
        webView.versionName = webViewVersion;
        ShadowWebView.setCurrentWebViewPackage(webView);
        shadowOf(application).declareActionUnbindable("android.support.customtabs.action.CustomTabsService");
        activity = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible().get();
    }

    private void pageBecomesVisible(String url) {
        WebView webView = activity.getBridge().getWebView();
        webView.getWebViewClient().onPageCommitVisible(webView, url);
    }

    private static boolean isProbe(String script) {
        return script.contains("__mitraShellBars") && script.contains("SystemBars");
    }

    private static boolean isRecheck(String script) {
        return script.startsWith("window.__mitraShellBars && window.__mitraShellBars.recheck");
    }

    private long probes() {
        return RecordingWebView.evaluated.stream().filter(ShellSystemBarsTest::isProbe).count();
    }

    private long rechecks() {
        return RecordingWebView.evaluated.stream().filter(ShellSystemBarsTest::isRecheck).count();
    }

    @Test
    public void theBundledProbeRunsOnTheAppHostAtTheMinimumVersion() {
        boot("140.0.0.0");
        pageBecomesVisible(APP_URL);
        assertEquals("the script from the app assets reaches the web view", 1, probes());
    }

    @Test
    public void aNewerWebViewRunsItToo() {
        boot("147.0.7727.55");
        pageBecomesVisible(APP_URL);
        assertEquals(1, probes());
    }

    @Test
    public void everyAppPageGetsTheProbeAgain() {
        boot("140.0.0.0");
        pageBecomesVisible(APP_URL);
        pageBecomesVisible("https://__APP_HOST__/other");
        assertEquals("the script guards itself per document, so the shell sends it each time", 2, probes());
    }

    @Test
    public void anotherHostGetsNothing() {
        boot("140.0.0.0");
        pageBecomesVisible("https://evil.example/home");
        pageBecomesVisible("https://__APP_HOST__.evil.example/home");
        pageBecomesVisible("https://evil.example/https://__APP_HOST__/");
        pageBecomesVisible(null);
        assertEquals(0, probes());
    }

    @Test
    public void belowTheMinimumVersionNothingIsInstalled() {
        boot("139.0.7258.0");
        pageBecomesVisible(APP_URL);
        assertEquals("the bars sit over the native background there", 0, probes());
    }

    @Test
    public void aConfigurationChangeAsksTheProbeToReportAgain() {
        boot("140.0.0.0");
        assertEquals(0, rechecks());
        activity.onConfigurationChanged(new Configuration(activity.getResources().getConfiguration()));
        assertEquals(1, rechecks());
        assertTrue("the recheck calls the hook the probe publishes", RecordingWebView.evaluated.stream()
            .filter(ShellSystemBarsTest::isRecheck).allMatch((s) -> s.endsWith("window.__mitraShellBars.recheck()")));
    }
}
