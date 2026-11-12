import UIKit
import WebKit
import AuthenticationServices
import Capacitor

// Written by mitra-mobile-shell/generator. The platform publishes a web app that does not
// know this shell exists, so the shell does the two native things sign-in needs:
//
//  1. A top-level navigation to an identity host (the platform API, Google, Microsoft) opens in
//     an ASWebAuthenticationSession. Its callback is either the shell's URL scheme
//     (plugins.MitraShell.callbackScheme, the default: the app page bounces the SDK fragment,
//     #codeMitra and #stateMitra, to <scheme>://auth, see app-snippet/) or the app's own host
//     (Universal Link, which needs the apple-app-site-association file served there). Either way
//     the session hands the URL back, fragment included.
//  2. A URL that reaches the app by Universal Link, by its scheme, or back from the session, is
//     loaded in the WebView as the app host with the same fragment and a fresh query value, so the
//     SDK finishes the sign-in at boot. The CSRF context the SDK wrote to sessionStorage is still
//     there because the WebView never navigated away.
//
// The identity hosts come from capacitor.config.json (plugins.MitraShell.authHosts). Anything
// else off-host keeps Capacitor's default and opens in the system browser.
//
// A third thing is cosmetic: the launch image is a plain color (a storyboard cannot animate), and
// the mark of the app comes in over it, see SplashEntranceView.
//
// A fourth keeps the status bar readable: a page that draws under the bar (contentInset never) gets
// shell-bars.js, which reports the color the page paints behind the bar so its content can contrast.

class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }

        window = UIWindow(windowScene: windowScene)
        window?.rootViewController = ShellBridgeViewController()
        window?.makeKeyAndVisible()

        SceneDelegateProxy.shared.scene(scene, willConnectTo: session, options: connectionOptions)
        for activity in connectionOptions.userActivities {
            handleUniversalLink(activity)
        }
    }

    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        SceneDelegateProxy.shared.scene(scene, openURLContexts: URLContexts)
        // The fallback link of the app page, or any other tap on the scheme, outside the session.
        for context in URLContexts {
            handleShellReturn(context.url)
        }
    }

    func scene(_ scene: UIScene, continue userActivity: NSUserActivity) {
        SceneDelegateProxy.shared.scene(scene, continue: userActivity)
        handleUniversalLink(userActivity)
    }

    private func handleUniversalLink(_ activity: NSUserActivity) {
        guard activity.activityType == NSUserActivityTypeBrowsingWeb, let url = activity.webpageURL else { return }
        (window?.rootViewController as? ShellBridgeViewController)?.loadInShell(url)
    }

    private func handleShellReturn(_ url: URL) {
        guard let shell = window?.rootViewController as? ShellBridgeViewController,
              let target = shell.appURL(fromReturn: url) else { return }
        shell.loadInShell(target)
    }
}

class ShellBridgeViewController: CAPBridgeViewController {
    private var entranceShown = false
    private var barsProbeInstalled = false

    // Set only while the splash overlay is up: the style it shows, and the style to apply when it
    // leaves, which starts as the one the app had and is replaced by each style asked for meanwhile.
    private var overlayStyle: UIStatusBarStyle?
    private var requestedStyle: UIStatusBarStyle?

    // Runs from loadView, before viewDidLoad loads the page, so the user script is in place for
    // the first document.
    override func capacitorDidLoad() {
        bridge?.registerPluginInstance(MitraShellPlugin())
        installBarsProbe()
    }

    // Under the bar means the page owns what is behind it, and a fixed style chosen at generation
    // time cannot follow a theme the page switches at runtime. With any other inset the web view
    // starts below the bar, over the native background, and the configured style stays right.
    // A missing file leaves the configured style in place instead of failing the launch.
    private func installBarsProbe() {
        guard let webView = webView,
              webView.scrollView.contentInsetAdjustmentBehavior == .never,
              let root = bridge?.config.appLocation,
              let source = try? String(contentsOf: root.appendingPathComponent("shell-bars.js"), encoding: .utf8) else { return }
        webView.configuration.userContentController.addUserScript(
            WKUserScript(source: source, injectionTime: .atDocumentEnd, forMainFrameOnly: true))
        barsProbeInstalled = true
    }

    // The overlay owns the status bar while it is up: a style the app asks for in that time (the
    // StatusBar plugin from the config, shell-bars.js from the page) is kept and applied when the
    // overlay leaves, so it neither shows over the splash nor is lost.
    override func setStatusBarStyle(_ statusBarStyle: UIStatusBarStyle) {
        if let held = overlayStyle {
            requestedStyle = statusBarStyle
            super.setStatusBarStyle(held)
        } else {
            super.setStatusBarStyle(statusBarStyle)
        }
    }

    private func applyStatusBarStyle(_ style: UIStatusBarStyle) {
        super.setStatusBarStyle(style)
    }

    // With server.url this is the published host; it is the only host the shell claims.
    var appHost: String? {
        return bridge?.config.serverURL.host?.lowercased()
    }

    // The URL scheme the sign-in comes back by, when the shell is not relying on the host.
    var callbackScheme: String? {
        guard let scheme = bridge?.config.getPluginConfig("MitraShell").getString("callbackScheme"),
              !scheme.isEmpty else { return nil }
        return scheme.lowercased()
    }

    // A return on the app host is used as is. One on the scheme, <scheme>://auth#codeMitra=...,
    // becomes the app host with the same fragment: the SDK reads the fragment, never the scheme.
    func appURL(fromReturn url: URL) -> URL? {
        guard let appHost = appHost else { return nil }
        if url.host?.lowercased() == appHost { return url }
        guard let scheme = url.scheme?.lowercased(), scheme == callbackScheme,
              var components = URLComponents(string: "https://\(appHost)/") else { return nil }
        let incoming = URLComponents(url: url, resolvingAgainstBaseURL: false)
        components.percentEncodedQuery = incoming?.percentEncodedQuery
        components.percentEncodedFragment = incoming?.percentEncodedFragment
        return components.url
    }

    func loadInShell(_ url: URL) {
        guard let appHost = appHost, url.host?.lowercased() == appHost,
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return }

        // A fragment-only change would not reload the document, and the sign-in completes only
        // when the app boots: a fresh query value forces a full load.
        var items = components.queryItems ?? []
        items.append(URLQueryItem(name: "shellLink", value: String(Int(Date().timeIntervalSince1970 * 1000))))
        components.queryItems = items
        guard let target = components.url else { return }

        DispatchQueue.main.async { [weak self] in
            self?.webView?.load(URLRequest(url: target))
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        showSplashEntrance()
        // The StatusBar plugin put its configured style back when the view appeared again (for
        // example after the sign-in browser closed); the page reports its own once more.
        if barsProbeInstalled {
            webView?.evaluateJavaScript("window.__mitraShellBars && window.__mitraShellBars.recheck && window.__mitraShellBars.recheck()")
        }
    }

    // The overlay goes on the window, above the Capacitor splash view that lives inside the web
    // view, and it only decorates: touches pass through and nothing waits for it. It stays as long
    // as the Capacitor splash does, so the mark does not leave before the color behind it.
    private func showSplashEntrance() {
        // One attempt only: viewDidAppear comes back after the sign-in browser closes, and a splash
        // over a running app would be wrong.
        guard !entranceShown else { return }
        entranceShown = true
        guard let window = view.window,
              let hex = bridge?.config.getPluginConfig("MitraShell").getString("splashBackground"),
              let color = UIColor(hex: hex),
              let image = UIImage(named: "SplashMark") else { return }

        // The splash plugin starts its timer in load(), shortly before this runs, and fades out for a
        // fixed 200 ms after launchShowDuration (its launchFadeOutDuration is not read). The overlay
        // holds for the same duration and then fades the same way, so it can outlast the plugin's
        // splash by the time between load() and here, which is hidden behind the same color.
        let splash = bridge?.config.getPluginConfig("SplashScreen")
        let hold = max(0.45, Double(splash?.getInt("launchShowDuration", 500) ?? 500) / 1000)
        let fade = 0.2

        let circle = CGFloat(bridge?.config.getPluginConfig("MitraShell").getDouble("splashMarkCircle", 0) ?? 0)
        let overlay = SplashEntranceView(background: color, image: image, circle: circle)
        overlay.frame = window.bounds
        window.addSubview(overlay)
        overlay.play(animated: !UIAccessibility.isReduceMotionEnabled, hold: hold, fade: fade) { [weak overlay] in
            overlay?.removeFromSuperview()
        }

        // The status bar over the splash follows the splash color, and the style the app asked for
        // comes back when the overlay leaves. The StatusBar plugin applies its style in a main-queue
        // block queued by super.viewDidAppear; setStatusBarStyle keeps whatever arrives while the
        // overlay is up, so the order of that block against this one does not matter, but this
        // has to queue behind it to capture the style it set and not the Info.plist one.
        let dark = bridge?.config.getPluginConfig("MitraShell").getBoolean("splashDark", false) ?? false
        let splashStyle: UIStatusBarStyle = dark ? .lightContent : .darkContent
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.requestedStyle = self.statusBarStyle
            self.overlayStyle = splashStyle
            self.applyStatusBarStyle(splashStyle)
            DispatchQueue.main.asyncAfter(deadline: .now() + hold + fade) { [weak self] in
                guard let self = self else { return }
                let latest = self.requestedStyle
                self.overlayStyle = nil
                self.requestedStyle = nil
                if let latest = latest { self.applyStatusBarStyle(latest) }
            }
        }
    }
}

// The mark over the launch color: opacity 0 to 1 and size 80% to 100%, 0.45 s, ease-out. Its
// size follows the same rule as the Android splash icon: the circle around the mark is 37.5% of
// the short side of the screen.
final class SplashEntranceView: UIView {
    private let markView = UIImageView()

    // The picture is drawn so that the circle around the mark itself (not the corners of the
    // picture) is `circle` pixels wide; 0 means the corners are all there is to go by.
    private let circle: CGFloat

    init(background: UIColor, image: UIImage, circle: CGFloat) {
        self.circle = circle
        super.init(frame: .zero)
        backgroundColor = background
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
        isUserInteractionEnabled = false
        markView.image = image
        markView.contentMode = .scaleAspectFit
        addSubview(markView)
    }

    required init?(coder: NSCoder) {
        return nil
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard let image = markView.image, image.size.width > 0, image.size.height > 0 else { return }
        let diameter = circle > 0 ? circle : hypot(image.size.width, image.size.height)
        let factor = min(bounds.width, bounds.height) * 0.375 / diameter
        markView.bounds = CGRect(x: 0, y: 0, width: image.size.width * factor, height: image.size.height * factor)
        markView.center = CGPoint(x: bounds.midX, y: bounds.midY)
    }

    func play(animated: Bool, hold: TimeInterval, fade: TimeInterval, completion: @escaping () -> Void) {
        if animated {
            markView.alpha = 0
            markView.transform = CGAffineTransform(scaleX: 0.8, y: 0.8)
            UIView.animate(withDuration: 0.45, delay: 0, options: [.curveEaseOut]) {
                self.markView.alpha = 1
                self.markView.transform = .identity
            }
        }
        UIView.animate(withDuration: fade, delay: hold, options: [.curveLinear], animations: {
            self.alpha = 0
        }, completion: { _ in
            completion()
        })
    }
}

private extension UIColor {
    convenience init?(hex: String) {
        var digits = hex.trimmingCharacters(in: .whitespaces)
        if digits.hasPrefix("#") { digits.removeFirst() }
        guard digits.count == 6, let value = UInt32(digits, radix: 16) else { return nil }
        self.init(red: CGFloat((value >> 16) & 0xff) / 255,
                  green: CGFloat((value >> 8) & 0xff) / 255,
                  blue: CGFloat(value & 0xff) / 255,
                  alpha: 1)
    }
}

@objc(MitraShellPlugin)
public class MitraShellPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "MitraShellPlugin"
    public let jsName = "MitraShell"
    public let pluginMethods: [CAPPluginMethod] = []

    private var session: ASWebAuthenticationSession?

    private var authHosts: [String] {
        return (getConfig().getArray("authHosts") ?? []).compactMap { ($0 as? String)?.lowercased() }
    }

    public override func shouldOverrideLoad(_ navigationAction: WKNavigationAction) -> NSNumber? {
        guard navigationAction.targetFrame?.isMainFrame != false,
              let url = navigationAction.request.url,
              let host = url.host?.lowercased(),
              let shell = bridge?.viewController as? ShellBridgeViewController,
              let appHost = shell.appHost,
              host != appHost,
              authHosts.contains(host) else { return nil }

        if #available(iOS 17.4, *) {
            startSession(url, appHost: appHost, shell: shell)
            return NSNumber(value: true)
        }
        // Older iOS: Capacitor's default, the system browser. The return depends on a Universal
        // Link from a user tap there, which the SDK redirect does not produce.
        return nil
    }

    @available(iOS 17.4, *)
    private func startSession(_ url: URL, appHost: String, shell: ShellBridgeViewController) {
        let callback: ASWebAuthenticationSession.Callback
        if let scheme = shell.callbackScheme {
            // Nothing on the host: the app page bounces the SDK fragment to this scheme.
            callback = .customScheme(scheme)
        } else {
            // The https callback needs the webcredentials associated domain for the same host, which
            // the generator declares in App.entitlements and the platform serves in the AASA file.
            callback = .https(host: appHost, path: "/")
        }
        let session = ASWebAuthenticationSession(url: url, callback: callback) { [weak self, weak shell] callbackURL, error in
            self?.session = nil
            guard let callbackURL = callbackURL else {
                let cancelled = (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin
                if !cancelled {
                    // The session could not run, for example because the host is not an associated
                    // domain yet: fall back to the browser, as Capacitor would have done.
                    UIApplication.shared.open(url)
                }
                return
            }
            guard let target = shell?.appURL(fromReturn: callbackURL) else { return }
            shell?.loadInShell(target)
        }
        session.prefersEphemeralWebBrowserSession = false
        session.presentationContextProvider = self
        self.session = session
        if !session.start() {
            self.session = nil
            UIApplication.shared.open(url)
        }
    }
}

extension MitraShellPlugin: ASWebAuthenticationPresentationContextProviding {
    public func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        return bridge?.viewController?.view.window ?? ASPresentationAnchor()
    }
}
