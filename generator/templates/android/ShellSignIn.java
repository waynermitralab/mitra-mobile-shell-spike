package __PACKAGE__;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.browser.auth.AuthTabIntent;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebViewClient;

import java.util.Arrays;
import java.util.Locale;

// The sign-in of the app, which the page starts by navigating to a sign-in host (the platform API,
// then Google or Microsoft). The bridge sends such a navigation to the system browser, where the
// sign-in ends far from the page that started it and only comes back by a link the browser has to
// hand over. Here it runs in an Auth Tab (Chrome 137 and later) instead: a sheet over the app that
// reports how it ended, so the return loads in the page and a sign-in the user gave up is not left
// looking like one in progress. iOS does the same with ASWebAuthenticationSession (SceneDelegate.swift).
//
// Without an Auth Tab nothing changes: the navigation is left to the bridge, and the return comes by
// MainActivity.onNewIntent as before. The same goes for a shell whose sign-in returns on the app host
// (no URL scheme): the Auth Tab matches an https return by one exact path, and that sign-in ends on
// any path of the host, so the Auth Tab could keep a return it was meant to hand over. The account connections in a popup are another path (ShellPopups).
// The code in the return URL is passed on as is: it is not read, stored or logged here.
public class ShellSignIn {

    // The page that started the sign-in shows it in progress until the return reloads it. An Auth Tab
    // that ends without a return loads nothing, so the page is told and can put its button back.
    static final String AUTH_CANCEL_EVENT = "mitrashell:authcancel";
    static final String AUTH_FAIL_EVENT = "mitrashell:authfail";

    // From capacitor.config.json by the generator (plugins.MitraShell.authHosts).
    private static final String APP_HOST = "__APP_HOST__";
    private static final String[] AUTH_HOSTS = { __AUTH_HOSTS__ };

    // Loads a return in the page (MainActivity.loadReturn). False when the URL is not one of the app.
    public interface ReturnLoader {
        boolean load(Uri url);
    }

    private final Bridge bridge;
    private final String callbackScheme;
    private final ReturnLoader returnLoader;
    private final Activity activity;
    private final ActivityResultLauncher<Intent> launcher;
    private ShellPopups.SystemBrowser browser;
    // One signal for each sign-in. It only holds back the events: an activity recreated while the tab
    // was up starts with it false, and the return of that sign-in still has to load.
    private boolean inAuthTab = false;

    // Called before the activity starts, because it registers an activity result launcher.
    public static ShellSignIn install(Bridge bridge, String callbackScheme, ReturnLoader returnLoader) {
        ShellSignIn signIn = new ShellSignIn(bridge, callbackScheme, returnLoader);
        bridge.setWebViewClient(new Client(bridge, signIn));
        return signIn;
    }

    private ShellSignIn(Bridge bridge, String callbackScheme, ReturnLoader returnLoader) {
        this.bridge = bridge;
        this.callbackScheme = callbackScheme;
        this.returnLoader = returnLoader;
        this.activity = bridge.getActivity();
        // Its own launcher, so a result here is never taken for the one of an account connection.
        this.launcher = AuthTabIntent.registerActivityResultLauncher(bridge.getActivity(), this::onAuthResult);
        setAuthTabSupport(ShellPopups.SystemBrowser::chromeHasAuthTab);
    }

    // Lets the unit test say whether the browser has an Auth Tab: no browser is installed there.
    void setAuthTabSupport(ShellPopups.SystemBrowser.AuthTabSupport support) {
        this.browser = new ShellPopups.SystemBrowser(activity, launcher, support);
    }

    // The bridge's client, with the sign-in taken first. The plugin hook of the bridge
    // (Plugin.shouldOverrideLoad) is not used: it does not say which frame navigates, and it also
    // sees every window.open, which belongs to ShellPopups.
    private static final class Client extends BridgeWebViewClient {
        private final ShellSignIn signIn;

        Client(Bridge bridge, ShellSignIn signIn) {
            super(bridge);
            this.signIn = signIn;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (request.isForMainFrame() && signIn.start(request.getUrl())) return true;
            return super.shouldOverrideUrlLoading(view, request);
        }
    }

    // True when the Auth Tab took the navigation, which is then cancelled in the page.
    private boolean start(Uri url) {
        if (callbackScheme == null || !isSignIn(url)) return false;
        // The page asked again while the tab is up (a second tap, a script that retries): the one
        // sign-in in front of the user is enough.
        if (inAuthTab) return true;
        if (!browser.launchSignIn(url, callbackScheme)) return false;
        inAuthTab = true;
        return true;
    }

    // https only, as the Auth Tab asks; the app host itself is the page, never a sign-in.
    static boolean isSignIn(Uri url) {
        if (url == null || !"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null) return false;
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return !APP_HOST.equalsIgnoreCase(host) && Arrays.asList(AUTH_HOSTS).contains(host);
    }

    private void onAuthResult(AuthTabIntent.AuthResult result) {
        onAuthOutcome(result.resultCode, result.resultUri);
    }

    // A return loads in the page like one that reached the activity as a link. Anything else tells
    // the page: the user closing the tab is a cancel, the rest is a failure (an unknown result, or an
    // OK with a URL that is not a return of the app). A failure does not open the browser again.
    void onAuthOutcome(int resultCode, Uri resultUri) {
        boolean wasOpen = inAuthTab;
        inAuthTab = false;
        // Loaded even when this instance opened nothing: Android can recreate the activity under the
        // tab, and dropping the return would send a person who signed in back to a signed-out page.
        if (resultCode == AuthTabIntent.RESULT_OK && resultUri != null && returnLoader.load(resultUri)) return;
        if (!wasOpen) return;
        // The name is one of the constants above, never page or URL data: the bridge quotes it into script.
        bridge.triggerWindowJSEvent(resultCode == AuthTabIntent.RESULT_CANCELED ? AUTH_CANCEL_EVENT : AUTH_FAIL_EVENT);
    }
}
