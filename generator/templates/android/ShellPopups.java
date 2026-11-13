package __PACKAGE__;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.result.ActivityResultLauncher;
import androidx.browser.auth.AuthTabIntent;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;

import com.getcapacitor.Bridge;

import java.util.Arrays;

// The popup contract of the browser, for the app pages that connect an account (Google, GitHub,
// Notion, Slack): window.open(url) returns a window the page keeps, watches (.closed) and hears from
// (postMessage from window.opener). Without it the consent opens in the system browser and the
// page on the way back has no opener, so it asks to be opened by the app and the connection never
// finishes.
//
// What the shell does, with PopupPolicy deciding every step:
//  1. onCreateWindow gives the page a real child WebView, never attached to the screen. The child
//     has no Capacitor bridge and no JavaScript interface of its own.
//  2. The first URL the child asks for is read. A consent host goes to the system
//     browser, never to a WebView: the Auth Tab when the default browser has it (Chrome 137+) and
//     the consent names its exact return path (redirect_uri), otherwise a Custom Tab, which comes
//     back by App Link. Anything else, or a window.open
//     the policy refuses, is opened as the bridge always did and the child is dropped.
//  3. The return URL (app host, inside the return prefix) loads in the child, whose own page posts
//     to window.opener and closes itself. The main page is not reloaded.
//  4. A cancelled or abandoned browser closes the child, so the page sees .closed become true.
// The code in the return URL is passed on as is: it is not read, stored or logged here.
public class ShellPopups {

    // From capacitor.config.json by the generator: hosts that ask for consent, and the path prefix
    // of the return on the app host.
    private static final String APP_HOST = "__APP_HOST__";
    private static final String[] CONSENT_HOSTS = { __CONSENT_HOSTS__ };
    private static final String RETURN_PATH = "__RETURN_PATH__";

    // A child that does not close itself after the return, or never asks for a first URL, would hold
    // the only slot for the rest of the run.
    private static final long REAP_AFTER_MS = 15_000;
    private static final long CLOSE_WAIT_MS = 2_000;

    // How the consent reaches the system browser. A seam for the unit test, which cannot run one.
    public interface SecureBrowser {
        // exactReturnPath is the one path the consent will come back on (from its redirect_uri), or
        // null when it is not known. False when no browser could take it.
        boolean launch(Uri url, String exactReturnPath);
    }

    private final Bridge bridge;
    private final Activity activity;
    private final PopupPolicy policy = new PopupPolicy(APP_HOST, Arrays.asList(CONSENT_HOSTS), RETURN_PATH);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<Intent> authTab;
    private SecureBrowser browser;
    private Child current;
    private boolean leftApp = false;

    private final class Child {
        final WebView view;
        // False for the window.open the policy refused: it only exists to learn the URL.
        final boolean owned;
        boolean decided = false;
        boolean destroyed = false;
        final Runnable reaper = this::reap;

        Child(WebView view, boolean owned) {
            this.view = view;
            this.owned = owned;
        }

        void reap() {
            if (owned && current == this) policy.end();
            discard(this);
        }
    }

    public ShellPopups(Bridge bridge) {
        this.bridge = bridge;
        this.activity = bridge.getActivity();
        // Registered before the activity starts, like every launcher.
        this.authTab = AuthTabIntent.registerActivityResultLauncher(bridge.getActivity(), this::onAuthResult);
        this.browser = new SystemBrowser(activity, authTab, SystemBrowser::chromeHasAuthTab);
        bridge.getWebView().getSettings().setSupportMultipleWindows(true);
        bridge.getActivity().getLifecycle().addObserver((LifecycleEventObserver) (source, event) -> {
            if (event == Lifecycle.Event.ON_PAUSE && policy.phase() == PopupPolicy.Phase.IN_BROWSER) leftApp = true;
            // The Custom Tab closed without a return: an Auth Tab result and an App Link return
            // both arrive before the activity resumes, so a session still waiting here was abandoned.
            if (event == Lifecycle.Event.ON_RESUME && leftApp && policy.phase() == PopupPolicy.Phase.IN_BROWSER) cancel();
            if (event == Lifecycle.Event.ON_RESUME) leftApp = false;
        });
    }

    void setSecureBrowser(SecureBrowser browser) {
        this.browser = browser;
    }

    // Called by ShellWebChromeClient. Always answers with a child, because the URL of the window.open
    // is only known once the child asks for it.
    public boolean onCreateWindow(WebView opener, boolean userGesture, Message resultMsg) {
        if (!(resultMsg.obj instanceof WebView.WebViewTransport)) return false;
        boolean owned = policy.begin(opener.getUrl(), userGesture);
        Child child = newChild(owned);
        if (owned) current = child;
        ((WebView.WebViewTransport) resultMsg.obj).setWebView(child.view);
        resultMsg.sendToTarget();
        handler.postDelayed(child.reaper, REAP_AFTER_MS);
        return true;
    }

    // An App Link on the app host reached the activity. True when it was the return of a session
    // this class opened, in which case the main page must not load it.
    public boolean onReturnLink(Uri link) {
        return link != null && deliverReturn(link.toString());
    }

    private Child newChild(boolean owned) {
        WebView view = new WebView(activity);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setGeolocationEnabled(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setUserAgentString(bridge.getWebView().getSettings().getUserAgentString());
        final Child child = new Child(view, owned);
        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                return onChildNavigation(child, request.getUrl().toString());
            }

            // shouldOverrideUrlLoading is not called for a POST (a form with target=_blank), so the same
            // rules are applied when the load starts. A first load that gets here is decided like any
            // other; one that is not allowed is stopped. The request has already gone out by now, so a
            // child that was already decided is closed as for a cancel, rather than left holding a
            // document of another origin with a window.opener. A consent that arrives here by POST is
            // reopened in the browser as a GET: the body is lost, which is rare and acceptable.
            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                if (url == null || url.equals("about:blank")) return;
                boolean decided = child.decided;
                if (!onChildNavigation(child, url)) return;
                v.stopLoading();
                if (decided) {
                    if (child.owned && current == child) policy.end();
                    discard(child);
                }
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onCloseWindow(WebView window) {
                if (child.owned && current == child) policy.end();
                discard(child);
            }
        });
        return child;
    }

    // True cancels the navigation.
    private boolean onChildNavigation(Child child, String url) {
        if (child.decided) return !(child.owned && policy.childMayNavigate(url));
        child.decided = true;
        if (child.owned && policy.browserStarted(url)) {
            handler.removeCallbacks(child.reaper);
            if (!browser.launch(Uri.parse(url), policy.sessionPath())) {
                policy.end();
                openAsBefore(url);
                discard(child);
            }
            return true;
        }
        if (child.owned) policy.end();
        openAsBefore(url);
        discard(child);
        return true;
    }

    // What the bridge did with a window.open before there were child windows: another host goes to
    // the system, the app host loads in the page.
    // Any scheme goes through the bridge, as it did when the window opened in the page itself (mailto:,
    // tel:, intent:). Only what the bridge leaves to the page, an http(s) URL of the app, is loaded in it.
    // data: and blob: are the exception: the bridge leaves them to the page, and a new window no longer
    // loads them in the main page, which is better than leaving the app.
    private void openAsBefore(String url) {
        Uri uri = Uri.parse(url);
        if (bridge.launchIntent(uri)) return;
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) bridge.getWebView().loadUrl(url);
    }

    private boolean deliverReturn(String url) {
        Child child = current;
        if (child == null || !policy.acceptReturn(url)) return false;
        child.view.loadUrl(url);
        handler.postDelayed(child.reaper, REAP_AFTER_MS);
        return true;
    }

    private void onAuthResult(AuthTabIntent.AuthResult result) {
        onAuthOutcome(result.resultCode, result.resultUri);
    }

    // Anything but a good return closes the child, a failed verification of the App Link included
    // (the host does not serve assetlinks.json yet): the page sees the window closed, as for a cancel.
    void onAuthOutcome(int resultCode, Uri resultUri) {
        if (policy.phase() != PopupPolicy.Phase.IN_BROWSER) return;
        boolean delivered = resultCode == AuthTabIntent.RESULT_OK && resultUri != null && deliverReturn(resultUri.toString());
        if (!delivered) cancel();
    }

    // The session ended without a return: the child closes itself so the page sees .closed.
    private void cancel() {
        Child child = current;
        policy.end();
        if (child == null) return;
        handler.removeCallbacks(child.reaper);
        child.view.evaluateJavascript("window.close()", (ignored) -> discard(child));
        handler.postDelayed(() -> discard(child), CLOSE_WAIT_MS);
    }

    // Frees the slot now and destroys the child on the next turn of the loop: most calls come from the
    // child's own client, which a WebView does not survive being destroyed inside.
    private void discard(Child child) {
        if (child.destroyed) return;
        child.destroyed = true;
        handler.removeCallbacks(child.reaper);
        if (current == child) current = null;
        handler.post(() -> {
            child.view.stopLoading();
            child.view.destroy();
        });
    }

    // Auth Tab when the default browser supports it and the exact return path is known, a Custom Tab
    // otherwise. The Auth Tab matches the redirect path by equality, so it only gets a path that came
    // from the redirect_uri of the consent (PopupPolicy.exactReturnPath); the Custom Tab comes back by
    // the App Link of the return prefix. Only https consent hosts get here (PopupPolicy).
    static final class SystemBrowser implements SecureBrowser {
        interface AuthTabSupport {
            boolean isSupported(Activity activity);
        }

        private final Activity activity;
        private final ActivityResultLauncher<Intent> authTab;
        private final AuthTabSupport support;

        SystemBrowser(Activity activity, ActivityResultLauncher<Intent> authTab, AuthTabSupport support) {
            this.activity = activity;
            this.authTab = authTab;
            this.support = support;
        }

        static boolean chromeHasAuthTab(Activity activity) {
            String packageName = CustomTabsClient.getPackageName(activity, null);
            return packageName != null && CustomTabsClient.isAuthTabSupported(activity, packageName);
        }

        @Override
        public boolean launch(Uri url, String exactReturnPath) {
            try {
                if (exactReturnPath != null && support.isSupported(activity)) {
                    new AuthTabIntent.Builder().build().launch(authTab, url, APP_HOST, exactReturnPath);
                } else {
                    new CustomTabsIntent.Builder().build().launchUrl(activity, url);
                }
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }
    }
}
