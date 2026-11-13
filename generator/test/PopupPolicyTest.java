package __PACKAGE__;

import java.util.Arrays;

// Behavioral test of PopupPolicy on a plain JVM, no framework: the template is compiled with its
// placeholder package and this file in the same one. Exit code 1 on the first failure.
//
// javac -d build generator/templates/android/PopupPolicy.java generator/test/PopupPolicyTest.java
// java -cp build __PACKAGE__.PopupPolicyTest
//
public final class PopupPolicyTest {

    static int checks = 0;

    static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            System.err.println("FAIL: " + message);
            System.exit(1);
        }
    }

    static PopupPolicy policy() {
        return new PopupPolicy("app.example.com", Arrays.asList("Accounts.Google.com", "github.com"), "/oauth/");
    }

    static final String APP_PAGE = "https://app.example.com/home";
    static final String CONSENT = "https://accounts.google.com/o/oauth2/v2/auth?client_id=1";
    static final String RETURN = "https://app.example.com/oauth/google?code=c&state=s";

    public static void main(String[] args) {
        // begin: gesture, app host page, one at a time
        PopupPolicy p = policy();
        check(p.begin(APP_PAGE, true), "a gesture on an app page begins");
        check(p.phase() == PopupPolicy.Phase.AWAITING_URL, "phase after begin");
        check(!p.begin(APP_PAGE, true), "a second child while one is pending is refused");
        p.end();
        check(!p.begin(APP_PAGE, false), "no gesture, no child");
        check(!p.begin("https://evil.example/home", true), "another host does not begin");
        check(!p.begin("https://app.example.com.evil.example/home", true), "a host that only starts with the app host does not begin");
        check(!p.begin("http://app.example.com/home", true), "plain http does not begin");
        check(!p.begin("https://user@app.example.com/home", true), "user info does not begin");
        check(!p.begin("https://app.example.com:8443/home", true), "another port does not begin");
        check(!p.begin(null, true), "no opener url does not begin");
        check(!p.begin("not a url", true), "garbage opener does not begin");
        check(p.begin("https://APP.example.com:443/home", true), "case and the default port are fine");
        p.end();

        // first URL: only consent hosts, only https
        p = policy();
        check(!p.browserStarted(CONSENT), "browserStarted needs begin first");
        p.begin(APP_PAGE, true);
        check(!p.browserStarted("https://other.example/auth"), "a host outside the list is not a consent url");
        check(p.phase() == PopupPolicy.Phase.AWAITING_URL, "a refused first url leaves the phase for the caller to end");
        check(!p.browserStarted("http://accounts.google.com/x"), "plain http is not a consent url");
        check(!p.browserStarted("https://accounts.google.com.evil.example/x"), "lookalike host is not a consent url");
        check(!p.browserStarted("https://a@accounts.google.com/x"), "user info is not a consent url");
        check(!p.browserStarted("javascript:alert(1)"), "javascript is not a consent url");
        check(p.browserStarted(CONSENT), "the list is case-insensitive");
        check(p.phase() == PopupPolicy.Phase.IN_BROWSER, "phase in browser");
        check(!p.browserStarted(CONSENT), "the first url is taken once");

        // return: only while in the browser, only app host inside the prefix
        check(!p.acceptReturn("https://evil.example/oauth/google?code=c"), "return on another host");
        check(!p.acceptReturn("https://app.example.com/home?code=c"), "return outside the prefix");
        check(!p.acceptReturn("https://app.example.com/oauthx/google"), "prefix is a path segment boundary");
        check(!p.acceptReturn("https://app.example.com/oauth/../admin"), "dot segments leave the prefix");
        check(!p.acceptReturn("https://app.example.com/oauth/%2e%2e/admin"), "encoded dot segments");
        check(!p.acceptReturn("https://app.example.com/oauth/%2E%2E%2Fadmin"), "encoded slash");
        check(!p.acceptReturn("https://app.example.com/oauth/a%5Cb"), "encoded backslash");
        check(!p.acceptReturn("http://app.example.com/oauth/google"), "return over http");
        check(!p.acceptReturn("cloud.mitra.app://oauth/google"), "a scheme url is not a return");
        check(p.phase() == PopupPolicy.Phase.IN_BROWSER, "refused returns leave the session open");
        check(p.acceptReturn(RETURN), "a good return is accepted");
        check(p.phase() == PopupPolicy.Phase.RETURNED, "phase returned");
        check(p.acceptReturn(RETURN) == false, "a return is taken once");

        // child navigation after the return
        check(p.childMayNavigate("https://app.example.com/home"), "the child follows the app host");
        check(!p.childMayNavigate("https://accounts.google.com/x"), "the child never goes to another host");
        check(!p.childMayNavigate("http://app.example.com/home"), "the child never downgrades");
        p.end();
        check(p.phase() == PopupPolicy.Phase.IDLE, "end resets");
        check(!p.childMayNavigate("https://app.example.com/home"), "no navigation without a child");
        check(!p.acceptReturn(RETURN), "no return without a session");

        // a return that arrives with no session the shell opened
        p = policy();
        check(!p.acceptReturn(RETURN), "a return with nothing pending is refused");
        p.begin(APP_PAGE, true);
        check(!p.acceptReturn(RETURN), "a return before the browser started is refused");

        // the exact return path comes from redirect_uri of the consent url, and only when it is trusted
        p = policy();
        String base = "https://accounts.google.com/o/oauth2/v2/auth?client_id=1&";
        check("/oauth/google".equals(p.exactReturnPath(base + "redirect_uri=https%3A%2F%2Fapp.example.com%2Foauth%2Fgoogle&state=s")), "an encoded redirect_uri gives its path");
        check("/oauth/google".equals(p.exactReturnPath(base + "redirect_uri=https://app.example.com/oauth/google?x=1")), "a query on the redirect_uri does not change the path");
        check(p.exactReturnPath("https://accounts.google.com/o/oauth2/v2/auth?client_id=1") == null, "no query parameter, no exact path");
        check(p.exactReturnPath("https://accounts.google.com/o/oauth2/v2/auth") == null, "no query at all");
        check(p.exactReturnPath(null) == null && p.exactReturnPath("not a url") == null, "garbage");
        check(p.exactReturnPath(base + "redirect_uri=") == null && p.exactReturnPath(base + "redirect_uri") == null, "an empty redirect_uri");
        check(p.exactReturnPath(base + "redirect_uri=https%3A%2F%2Fevil.example%2Foauth%2Fgoogle") == null, "another host");
        check(p.exactReturnPath(base + "redirect_uri=https%3A%2F%2Fapp.example.com.evil.example%2Foauth%2Fgoogle") == null, "a lookalike host");
        check(p.exactReturnPath(base + "redirect_uri=http%3A%2F%2Fapp.example.com%2Foauth%2Fgoogle") == null, "http");
        check(p.exactReturnPath(base + "redirect_uri=https%3A%2F%2Fapp.example.com%2Fhome") == null, "a path outside the prefix");
        check(p.exactReturnPath(base + "redirect_uri=https%3A%2F%2Fapp.example.com%2Foauthx%2Fgoogle") == null, "a sibling of the prefix");
        check(p.exactReturnPath(base + "redirect_uri=https%3A%2F%2Fapp.example.com%2Foauth%2F..%2Fadmin") == null, "dot segments");
        check(p.exactReturnPath(base + "redirect_uri=https%3A%2F%2Fapp.example.com%2Foauth%2F%252e%252e%2Fadmin") == null, "double encoded dot segments");
        check(p.exactReturnPath(base + "redirect_uri=https%3A%2F%2Fapp.example.com%2Foauth%2Fa%252Fb") == null, "double encoded slash");
        check(p.exactReturnPath(base + "redirect_uri=https%3A%2F%2Fapp.example.com%2Foauth%2Fa%255Cb") == null, "double encoded backslash");
        check(p.exactReturnPath(base + "redirect_uri=https%3A%2F%2Fapp.example.com%2Foauth%2Fa%2520b") == null, "any percent left in the path");
        check(p.exactReturnPath(base + "redirect_uri=https%3A%2F%2Fuser%40app.example.com%2Foauth%2Fgoogle") == null, "user info");
        check(p.exactReturnPath(base + "redirect_uri=https%3A%2F%2Fapp.example.com%3A8443%2Foauth%2Fgoogle") == null, "another port");
        check(p.exactReturnPath(base + "redirect_uri=https%3A%2F%2Fapp.example.com%2Foauth%2Fgoogle%23frag") == null, "a fragment");
        check(p.exactReturnPath(base + "redirect_uri=https%3A%2F%2Fapp.example.com%2Foauth%2Fgoogle&redirect_uri=https%3A%2F%2Fapp.example.com%2Foauth%2Fgithub") == null, "a repeated parameter is ambiguous");
        check(p.exactReturnPath(base + "redirect_uri=https%3A%2F%2Fapp.example.com%2Foauth%2Fgoogle&redirect_uri=https%3A%2F%2Fevil.example%2F") == null, "a repeated parameter with a hostile second value");
        check(p.exactReturnPath(base + "redirect_uri=%E0%A4%A") == null, "a bad escape");
        check(p.exactReturnPath(base + "xredirect_uri=https%3A%2F%2Fapp.example.com%2Foauth%2Fgoogle") == null, "another parameter name");

        // a session with an exact path takes that path only; one without takes the prefix
        String exactConsent = base + "redirect_uri=https%3A%2F%2Fapp.example.com%2Foauth%2Fgoogle";
        p = policy();
        p.begin(APP_PAGE, true);
        check(p.browserStarted(exactConsent), "a consent with redirect_uri starts");
        check("/oauth/google".equals(p.sessionPath()), "the session keeps the exact path");
        check(!p.acceptReturn("https://app.example.com/oauth/github?code=c"), "another path inside the prefix is not this session's return");
        check(!p.acceptReturn("https://app.example.com/oauth/google/x?code=c"), "a longer path is not this session's return");
        check(p.phase() == PopupPolicy.Phase.IN_BROWSER, "refused, still waiting");
        check(p.acceptReturn("https://app.example.com/oauth/google?code=c&state=s"), "the exact path is accepted");
        p.end();
        check(p.sessionPath() == null, "end forgets the path");
        p.begin(APP_PAGE, true);
        p.browserStarted(CONSENT);
        check(p.sessionPath() == null, "no redirect_uri, no exact path");
        check(p.acceptReturn("https://app.example.com/oauth/github?code=c"), "then the prefix rules");
        p = policy();
        p.begin(APP_PAGE, true);
        p.browserStarted(base + "redirect_uri=https%3A%2F%2Fevil.example%2Fx");
        check(p.sessionPath() == null && p.acceptReturn(RETURN), "a hostile redirect_uri gives no exact path and the prefix still guards the return");

        // a prefix without slashes is a configuration error
        boolean threw = false;
        try { new PopupPolicy("a.example", Arrays.asList("b.example"), "/oauth"); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "return path must end with /");

        System.out.println("PopupPolicyTest ok (" + checks + " checks)");
    }
}
