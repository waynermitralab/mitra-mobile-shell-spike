package __PACKAGE__;

import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

// Decides when the shell may give a page a real child window for window.open, and which URL may
// load in it. No Android types, so a plain JVM test runs every case (generator/test/PopupPolicyTest.java).
//
// One child at a time, and it moves through fixed phases:
//   IDLE -> AWAITING_URL (child created, its first URL not seen yet)
//        -> IN_BROWSER   (the first URL is a consent host, and the system browser holds it)
//        -> RETURNED     (the browser handed back the return URL, which now loads in the child)
//        -> IDLE         (the child closed or was discarded; end() from any phase).
// The system browser that can watch for the return itself (the Auth Tab) matches the return path by
// equality, not by prefix, so the exact path of a session is read from the redirect_uri parameter
// of the consent URL, and only when that URL passes the same rules as the return. Without one the
// session has no exact path and the Auth Tab is not used.
// The code in the return URL is never read here: URLs are only checked and passed on.
// Main thread only.
public final class PopupPolicy {

    public enum Phase { IDLE, AWAITING_URL, IN_BROWSER, RETURNED }

    private final String appHost;
    private final Set<String> consentHosts = new HashSet<>();
    private final String returnPath;
    private Phase phase = Phase.IDLE;
    private String sessionPath = null;

    // returnPath is a path prefix that starts and ends with "/", so "/oauth/" never matches "/oauthx".
    public PopupPolicy(String appHost, Iterable<String> consentHosts, String returnPath) {
        if (!returnPath.startsWith("/") || !returnPath.endsWith("/")) {
            throw new IllegalArgumentException("returnPath must start and end with /: " + returnPath);
        }
        this.appHost = appHost.toLowerCase(Locale.ROOT);
        for (String host : consentHosts) this.consentHosts.add(host.toLowerCase(Locale.ROOT));
        this.returnPath = returnPath;
    }

    public Phase phase() { return phase; }

    // The exact return path of the session in the browser, or null when the consent URL named none.
    public String sessionPath() { return sessionPath; }

    // window.open from a page of the app, with a user gesture, when no other child is pending.
    // The WebView does not say which frame called it, so the opener is judged by the URL of the
    // page the WebView shows.
    public boolean begin(String openerUrl, boolean userGesture) {
        if (phase != Phase.IDLE || !userGesture) return false;
        URI opener = parse(openerUrl);
        if (opener == null || !onAppHost(opener)) return false;
        phase = Phase.AWAITING_URL;
        return true;
    }

    // The first URL the child asks for. True when the system browser should take it.
    public boolean browserStarted(String firstUrl) {
        if (phase != Phase.AWAITING_URL) return false;
        URI uri = parse(firstUrl);
        if (uri == null || !isHttps(uri) || uri.getUserInfo() != null) return false;
        if (uri.getHost() == null || !consentHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) return false;
        phase = Phase.IN_BROWSER;
        sessionPath = exactReturnPath(firstUrl);
        return true;
    }

    // What the system browser handed back. Only a URL of the app host inside the return prefix,
    // and only while a session the shell opened is waiting for it.
    public boolean acceptReturn(String url) {
        if (phase != Phase.IN_BROWSER || !isReturnUrl(url)) return false;
        // A session that knows its exact path takes nothing else: a return for another path is not
        // the one this consent asked for.
        if (sessionPath != null && !sessionPath.equals(parse(url).getRawPath())) return false;
        phase = Phase.RETURNED;
        return true;
    }

    // After the return the child may follow redirects and links inside the app host, nothing else.
    public boolean childMayNavigate(String url) {
        if (phase != Phase.RETURNED) return false;
        URI uri = parse(url);
        return uri != null && onAppHost(uri);
    }

    public void end() {
        phase = Phase.IDLE;
        sessionPath = null;
    }

    // The path of the redirect_uri parameter of a consent URL, when it can be trusted as the exact
    // return path: one parameter only (a repeated one is ambiguous), once decoded an https URL of the
    // app host with the default port and no user info or fragment, whose path is inside the return
    // prefix and is made of plain characters (so no encoded dot, slash or backslash survives a second
    // decoding). Null otherwise.
    public String exactReturnPath(String consentUrl) {
        URI consent = parse(consentUrl);
        String query = consent == null ? null : consent.getRawQuery();
        if (query == null) return null;
        String value = null;
        for (String pair : query.split("&", -1)) {
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            if (!"redirect_uri".equals(name)) continue;
            if (value != null || eq < 0) return null;
            value = decode(pair.substring(eq + 1));
            if (value == null) return null;
        }
        if (value == null || !isReturnUrl(value)) return null;
        URI redirect = parse(value);
        if (redirect.getRawFragment() != null) return null;
        String path = redirect.getRawPath();
        return path.matches("[A-Za-z0-9._~/-]+") ? path : null;
    }

    boolean isReturnUrl(String url) {
        URI uri = parse(url);
        if (uri == null || !onAppHost(uri)) return false;
        String path = uri.getRawPath();
        if (path == null || !path.startsWith(returnPath)) return false;
        // The WebView resolves dot segments, so "/oauth/../admin" would leave the prefix.
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c") || lower.contains("\\")) return false;
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    private boolean onAppHost(URI uri) {
        return isHttps(uri) && uri.getUserInfo() == null && uri.getHost() != null
            && appHost.equals(uri.getHost().toLowerCase(Locale.ROOT));
    }

    private static boolean isHttps(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private static String decode(String text) {
        try {
            return java.net.URLDecoder.decode(text, "UTF-8");
        } catch (java.io.UnsupportedEncodingException | IllegalArgumentException e) {
            return null;
        }
    }

    private static URI parse(String url) {
        if (url == null) return null;
        try {
            return new URI(url);
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }
}
