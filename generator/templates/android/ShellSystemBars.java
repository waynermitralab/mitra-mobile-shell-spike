package __PACKAGE__;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.webkit.WebView;

import com.getcapacitor.Bridge;
import com.getcapacitor.WebViewListener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

// Keeps the status and navigation bar content readable on a page that draws under them. The page
// has its own light/dark toggle, so a style fixed at generation time can end up white on white;
// shell-bars.js (bundled with the app) reads the color the page paints behind each bar and hands
// it to Capacitor's SystemBars plugin. The page does not know the shell exists, so the script is
// run from here, on the pages of the app host only.
public final class ShellSystemBars {

    private static final String APP_HOST = "__APP_HOST__";
    private static final String SCRIPT_ASSET = "public/shell-bars.js";
    private static final String RECHECK_SCRIPT = "window.__mitraShellBars && window.__mitraShellBars.recheck && window.__mitraShellBars.recheck()";

    // Capacitor's SystemBars lets the page under the bars only from this WebView major version (it
    // is the one that fixed the safe area insets). Below it the bars sit over the native window
    // background, where the device-theme style is the right one and what the page paints says
    // nothing about what is behind them.
    private static final int WEBVIEW_VERSION_WITH_SAFE_AREA_FIX = 140;

    private ShellSystemBars() { }

    public static void install(Bridge bridge) {
        if (webViewMajorVersion() < WEBVIEW_VERSION_WITH_SAFE_AREA_FIX) return;
        final String script = readScript(bridge.getContext());
        if (script == null) return;
        // Commit visible, like SystemBars itself: onPageLoaded waits for the whole load and would
        // leave the first paint with the wrong style.
        bridge.addWebViewListener(new WebViewListener() {
            @Override
            public void onPageCommitVisible(WebView webView, String url) {
                if (isAppPage(url)) webView.evaluateJavascript(script, null);
            }
        });
    }

    // After a configuration change (rotation, device dark mode) the bridge makes SystemBars and the
    // StatusBar plugin re-apply their stored styles, and the configured one of StatusBar can win;
    // the probe only reports changes, so it has to be asked to report again. A page the probe never
    // ran on has no hook and nothing happens.
    public static void recheck(Bridge bridge) {
        if (bridge == null || bridge.getWebView() == null) return;
        bridge.getWebView().evaluateJavascript(RECHECK_SCRIPT, null);
    }

    // Same source as SystemBars.getWebViewMajorVersion(): the major of the current WebView package.
    // Before API 26 the framework cannot name it, and such a WebView is far below the fix anyway.
    private static int webViewMajorVersion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return 0;
        try {
            PackageInfo info = WebView.getCurrentWebViewPackage();
            if (info == null || info.versionName == null) return 0;
            return Integer.parseInt(info.versionName.split("\\.")[0]);
        } catch (Exception e) {
            return 0;
        }
    }

    static boolean isAppPage(String url) {
        if (url == null) return false;
        String host = Uri.parse(url).getHost();
        return host != null && APP_HOST.equalsIgnoreCase(host);
    }

    // A missing script is a no-op, not a failure: the style then stays the configured one.
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
