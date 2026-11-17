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
// A third is the popup contract of the browser for the pages that connect an account (Google, GitHub,
// Notion, Slack): window.open(url) gets a real child window, so the page keeps a window it can watch
// (.closed) and hear from (postMessage from window.opener). The consent never runs in a web view: it
// goes to an ASWebAuthenticationSession with an https callback on the app host (iOS 17.4 and later,
// see ShellPopups), and what comes back loads in the child, whose own page posts to its opener and
// closes. Below 17.4 Capacitor's behavior stays: window.open goes to the system browser.
//
// A fourth thing is cosmetic: the launch image is a plain color (a storyboard cannot animate), and
// the mark of the app comes in over it, see SplashEntranceView.
//
// A fifth keeps the status bar readable: a page that draws under the bar (contentInset never) gets
// shell-bars.js, which reports the color the page paints behind the bar so its content can contrast.
//
// A sixth makes forms feel native: the bar WebKit puts above the keyboard (previous, next, Done)
// is hidden, see hideFormAccessoryBar.
//
// A seventh makes the keyboard behave as in a native app: the web view ends at the top of the
// keyboard, so the page gets a shorter viewport instead of being scrolled, or the page is told
// where the keyboard is going and moves itself, see installKeyboard.

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
        installPopups()
        hideFormAccessoryBar()
        installKeyboard()
    }

    // Users and App Review read the form bar above the keyboard as a web page, not an app. The
    // Capacitor Keyboard plugin can hide it too, but it would put a whole plugin, with its own
    // keyboard resize handling and a JS surface, in every app for this one effect. WebKit asks its
    // content view (a private WKContentView) for inputAccessoryView, so that one view gets a
    // runtime subclass returning nil.
    // A WebKit that renames the view only keeps the bar: nothing else depends on finding it. The
    // runtime class is what gets subclassed, and one that is not WebKit's own (a KVO subclass, say)
    // is left alone rather than subclassed again under it.
    private func hideFormAccessoryBar() {
        guard let content = webView?.scrollView.subviews.first(where: { object_getClass($0).map { NSStringFromClass($0).hasPrefix("WKContent") } ?? false }),
              let base = object_getClass(content) else { return }
        let name = NSStringFromClass(base) + "_MitraShellNoAccessory"
        if let existing = NSClassFromString(name) {
            object_setClass(content, existing)
            return
        }
        let selector = #selector(getter: UIResponder.inputAccessoryView)
        guard let original = class_getInstanceMethod(UIResponder.self, selector),
              let subclass = objc_allocateClassPair(base, name, 0) else { return }
        let noAccessory: @convention(block) (AnyObject) -> UIView? = { _ in nil }
        class_addMethod(subclass, selector, imp_implementationWithBlock(noAccessory), method_getTypeEncoding(original))
        objc_registerClassPair(subclass)
        object_setClass(content, subclass)
    }

    // How much of the bottom of the full frame of this view the docked keyboard covers.
    private var keyboardOverlap: CGFloat = 0
    private var keyboardMode = KeyboardMode.off
    // The last keyboard event sent to the page: the same one is not sent twice, and a document
    // that loads later is told where the keyboard is, see publishKeyboardState.
    private var keyboardPhase = "didHide"
    private var announcedHeight: Double = 0
    // Where the last notification taken into account sent the keyboard, and the window it was
    // measured in, see keyboardMoved.
    private var announcedFrame: CGRect?
    private var announcedBounds: CGRect?
    private var keyboardObservations: [NSKeyValueObservation] = []
    // Set from a resize until the page has laid out in the shorter viewport: the height the document
    // had before it, and whether it fit the viewport then, see dropStrayScroll.
    private var settlingHeight: CGFloat?
    private var holdsTop = false
    private var settleTimer: DispatchWorkItem?

    // What the shell does when the keyboard opens, from plugins.MitraShell.keyboard:
    // - resize: the web view ends at the keyboard, see below. The page needs no change, but WebKit
    //   does not paint a web view again on every frame while its size animates, and the page lays
    //   out in one step, so the move is not as smooth as in a native screen.
    // - notify: the web view keeps its size and the page moves itself, with a transform in step
    //   with the keyboard. It can only do that if it hears where the keyboard is going before the
    //   animation starts, which no web API gives: see notifyKeyboard. The shell only keeps WebKit
    //   from scrolling a document that fits the viewport, see dropStrayScroll.
    // - off: WebKit as it comes, and no event.
    private enum KeyboardMode: String { case resize, notify, off }

    // WKWebView keeps its height under the keyboard and reveals the focused field by scrolling its
    // scroll view and shifting the visual viewport. A page that fills the screen (a chat: header,
    // messages, composer) slides up whole, header included, and can only chase visualViewport to
    // undo it, which shows as a jump. Ending the web view at the keyboard makes the layout viewport
    // itself shorter (innerHeight, 100dvh), so such a page lays out again with the header in place
    // and the composer on the keyboard, and nothing has to scroll.
    //
    // The frame of the view is what changes, as in the "native" resize of the Capacitor Keyboard
    // plugin. In Capacitor the web view is the view of this controller, and here that is the root
    // view of the window: it has no superview of ours to hold a bottom constraint or a
    // keyboardLayoutGuide, and additionalSafeAreaInsets would only change env(safe-area-inset-bottom),
    // not the viewport height. UIKit puts the frame of a root view back on rotation and on a window
    // resize, so viewDidLayoutSubviews applies the overlap again. Unlike the plugin, which resizes
    // after the keyboard has landed, the frame moves inside the keyboard's own animation, so the
    // bottom edge travels with the keyboard and the composer is never under it.
    //
    // A shorter frame no longer reaches the home indicator, so UIKit takes the bottom safe area
    // inset to 0 by itself and it is not counted on top of the keyboard, with either contentInset.
    private func installKeyboard() {
        // A config written before the modes existed has no value and gets the default.
        let mode = KeyboardMode(rawValue: bridge?.config.getPluginConfig("MitraShell").getString("keyboard") ?? "") ?? .resize
        guard mode != .off, let webView = webView else { return }
        let scrollView = webView.scrollView
        keyboardMode = mode
        let center = NotificationCenter.default
        // willChangeFrame alone covers show, hide and a change of height (emoji, dictation); show
        // and hide are for a system that posts only them. On rotation the will notifications are
        // measured against the bounds the window had before it, so didChangeFrame measures again.
        // didShow and didHide only tell the page that the keyboard has landed.
        for name in [UIResponder.keyboardWillChangeFrameNotification, UIResponder.keyboardWillShowNotification,
                     UIResponder.keyboardWillHideNotification, UIResponder.keyboardDidChangeFrameNotification,
                     UIResponder.keyboardDidShowNotification, UIResponder.keyboardDidHideNotification] {
            center.addObserver(self, selector: #selector(keyboardMoved(_:)), name: name, object: nil)
        }
        // Both fire per scroll frame and per layout of the page; dropStrayScroll is a few compares.
        keyboardObservations = [
            scrollView.observe(\.contentOffset) { [weak self] _, _ in self?.dropStrayScroll() },
            scrollView.observe(\.contentSize) { [weak self] scrollView, _ in
                guard let self = self else { return }
                if self.holdsTop, let height = self.settlingHeight, abs(scrollView.contentSize.height - height) > 0.5 {
                    self.endSettling()
                } else {
                    self.dropStrayScroll()
                }
            },
            // The navigation delegate belongs to Capacitor, so the end of a load is read here.
            webView.observe(\.isLoading) { [weak self] webView, _ in
                if !webView.isLoading { self?.publishKeyboardState() }
            }
        ]
    }

    @objc private func keyboardMoved(_ notification: Notification) {
        let name = notification.name
        let info = notification.userInfo
        let curve = (info?[UIResponder.keyboardAnimationCurveUserInfoKey] as? NSNumber)?.uintValue ?? 0
        // These two only confirm where the keyboard is. One that arrives late, from a transition
        // the user reversed, does not match the state and must not move anything.
        if name == UIResponder.keyboardDidShowNotification || name == UIResponder.keyboardDidHideNotification {
            let shown = name == UIResponder.keyboardDidShowNotification
            if shown == (keyboardOverlap > 0) { notifyKeyboard(shown ? "didShow" : "didHide", duration: 0, curve: curve) }
            return
        }
        let landed = name == UIResponder.keyboardDidChangeFrameNotification
        let end = (info?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue
        let bounds = view.window?.bounds
        // didChangeFrame measures again only for what the will notifications could not know: on
        // rotation they are measured against the bounds of before. In the same bounds, one whose
        // frame is not where the last will sent the keyboard is late, from a transition the user
        // reversed, and taking it would tell the page of a hide with the keyboard up.
        if landed, let announced = announcedFrame, end != announced, bounds == announcedBounds { return }
        announcedFrame = end
        announcedBounds = bounds
        let overlap = name == UIResponder.keyboardWillHideNotification ? 0 : dockedKeyboardOverlap(notification)
        // After the fact there is no keyboard animation left to travel with.
        let duration = landed ? 0 : (info?[UIResponder.keyboardAnimationDurationUserInfoKey] as? NSNumber)?.doubleValue ?? 0
        if overlap != keyboardOverlap {
            let taller = overlap > keyboardOverlap
            keyboardOverlap = overlap
            // First, so the page starts its own transition with the keyboard and not after it.
            notifyKeyboard(overlap > 0 ? "willShow" : "willHide", duration: duration, curve: curve)
            if keyboardMode == .resize {
                resizeForKeyboard(taller: taller, duration: duration, curve: curve)
            } else {
                dropStrayScroll()
            }
        }
        if landed { notifyKeyboard(keyboardOverlap > 0 ? "didShow" : "didHide", duration: 0, curve: curve) }
    }

    private func resizeForKeyboard(taller: Bool, duration: Double, curve: UInt) {
        if taller {
            beginSettling()
        } else if keyboardOverlap == 0 {
            settleTimer?.cancel()
            endSettling()
        }
        // What shows through a translucent keyboard, and around its rounded corners, is the window:
        // without a color it is black under a light page. The color is set once per keyboard, from
        // the page as it is then, and is not taken back: nothing else lets the window show.
        if keyboardOverlap > 0, let window = view.window {
            window.backgroundColor = webView?.underPageBackgroundColor
        }
        guard duration > 0 else { return fitToKeyboard() }
        // The curve of the keyboard is not one of the public ones; shifted by 16 it is the option.
        UIView.animate(withDuration: duration, delay: 0, options: [UIView.AnimationOptions(rawValue: curve << 16), .beginFromCurrentState]) {
            self.fitToKeyboard()
        }
    }

    static let keyboardEvent = "mitrashell:keyboard"

    // The page hears of the keyboard from the shell because the web only reports it afterwards
    // (visualViewport resizes when the keyboard has landed), too late to animate along with it.
    // The fields are set on the event object itself, as Capacitor does with the data of its own
    // window events, not on a detail:
    //   mode      resize or notify: whether the web view shrinks by itself or the page has to
    //             lift its content, so one page can run under either.
    //   phase     willShow, didShow, willHide or didHide. willShow comes again when the height
    //             changes with the keyboard up (emoji, dictation).
    //   height    how much of the bottom of the web view at full height the keyboard covers when
    //             the transition ends, home indicator area included, 0 on a hide. In points, which
    //             are CSS px for a page at width=device-width that is not zoomed.
    //   duration  of the keyboard animation, in ms; 0 on did* and when there is none.
    //   curve     the raw UIView.AnimationCurve of the notification.
    //   easing    the CSS timing function closest to that curve.
    // The same phase with the same height is sent once: willChangeFrame and willShow, or
    // didChangeFrame and didShow, report one transition twice.
    //
    // The script goes straight to the web view and not through triggerWindowJSEvent of the bridge:
    // that one waits a turn of the main queue, by when the keyboard animation is committed, and
    // needs window.Capacitor in the page. What is pasted into it is JSON of the constants above
    // and finite numbers, never page data.
    private func notifyKeyboard(_ phase: String, duration: Double, curve: UInt) {
        let height = Double(keyboardOverlap)
        guard phase != keyboardPhase || height != announcedHeight, height.isFinite, duration.isFinite else { return }
        keyboardPhase = phase
        announcedHeight = height
        let fields: [String: Any] = ["mode": keyboardMode.rawValue, "phase": phase, "height": height,
                                     "duration": (duration * 1000).rounded(), "curve": Int(curve),
                                     "easing": Self.keyboardEasing(curve)]
        guard let json = Self.json(fields), let state = keyboardState() else { return }
        webView?.evaluateJavaScript("""
            (function (fields) {
              var event = new Event("\(Self.keyboardEvent)");
              for (var name in fields) event[name] = fields[name];
              window.__mitraShellKeyboard = \(state);
              window.dispatchEvent(event);
            })(\(json))
            """)
    }

    // A page that loads, or starts listening, after an event has no way to ask where the keyboard
    // is, so the last state is also left on window. A new document starts without it and gets it
    // when its load ends.
    private func publishKeyboardState() {
        guard keyboardMode != .off, let state = keyboardState() else { return }
        webView?.evaluateJavaScript("window.__mitraShellKeyboard = \(state)")
    }

    private func keyboardState() -> String? {
        return Self.json(["phase": keyboardPhase, "mode": keyboardMode.rawValue, "height": announcedHeight])
    }

    private static func json(_ fields: [String: Any]) -> String? {
        guard let data = try? JSONSerialization.data(withJSONObject: fields) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    // The keyboard reports curve 7, which is private: UIKit runs it as a spring and publishes no
    // control points. The bezier is the fit that circulates for it in React Native keyboard code
    // (Easing.bezier(0.38, 0.7, 0.125, 1)), not something measured here; a page that needs an exact
    // match has to tune it against a recording.
    private static func keyboardEasing(_ curve: UInt) -> String {
        switch curve {
        case 0: return "ease-in-out"
        case 1: return "ease-in"
        case 2: return "ease-out"
        case 3: return "linear"
        default: return "cubic-bezier(0.38, 0.7, 0.125, 1)"
        }
    }

    // Only a keyboard that sits on the bottom edge across the whole width takes space from the
    // page. A floating, undocked or split keyboard on iPad reports an empty or detached frame and
    // a hardware keyboard one below the screen: the page keeps its height, as in Safari. The bar a
    // hardware keyboard may leave docked at the bottom counts, since it covers the page all the
    // same. A keyboard of another process (the sign-in sheet over this view, the other app in
    // Split View) is not typing into the page, so the page does not move for it.
    private func dockedKeyboardOverlap(_ notification: Notification) -> CGFloat {
        let info = notification.userInfo
        guard (info?[UIResponder.keyboardIsLocalUserInfoKey] as? NSNumber)?.boolValue ?? true,
              let end = (info?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue,
              let window = view.window, let container = view.superview else { return 0 }
        // The frame comes in screen coordinates, and on iPad the window may not be the screen
        // (Split View, Slide Over, Stage Manager).
        let keyboard = window.convert(end, from: window.screen.coordinateSpace)
        let bounds = window.bounds
        guard keyboard.intersects(bounds), keyboard.maxY >= bounds.maxY - 1,
              keyboard.minX <= bounds.minX + 1, keyboard.maxX >= bounds.maxX - 1 else { return 0 }
        let full = container.convert(container.bounds, to: window)
        return min(max(0, full.maxY - keyboard.minY), full.height)
    }

    // The frame is the full one of the container less the keyboard. It is written on every layout
    // and when the view comes back, not only while the keyboard is up: a keyboard that closes with
    // the view out of the hierarchy (the camera or a full-screen picker over it) finds no container
    // to measure, and the height would otherwise stay short for good.
    private func fitToKeyboard() {
        guard keyboardMode == .resize, let container = view.superview else { return }
        var frame = container.bounds
        frame.size.height -= keyboardOverlap
        if view.frame != frame { view.frame = frame }
        dropStrayScroll()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        fitToKeyboard()
    }

    // WebKit starts scrolling the focused field into view from the layout it had before the resize,
    // and for a page that fills the viewport that leaves the document pushed up with nothing to
    // scroll back with. Scrolling stays on, because a long form needs WebKit to bring its field
    // into view: the offset is put back only while the keyboard is up and only when the document
    // fits the resized viewport, where no offset but the top is a real scroll position (the scroll
    // view does not bounce in Capacitor). A page that scrolls is taller than the viewport and is
    // never touched.
    //
    // In notify mode the web view keeps its height, and this is all the shell does to the page:
    // WebKit still scrolls the field into view and shifts the visual viewport with it, which for a
    // document that fits the viewport is the whole screen sliding up under a page that is already
    // moving its composer by itself. No field is scrolled to there: the page owns the layout.
    // The bottom inset UIKit adds to the scroll view for the keyboard stays, so a document that
    // scrolls can scroll that much further; whether that is wanted is to be judged on a device.
    //
    // The new size of the document arrives a few frames after the resize, and WebKit scrolls in
    // between, when the old size still says there is room: the header would flash out and back.
    // So a document that fit the viewport before the resize is held at the top until its new size
    // is known.
    private func dropStrayScroll() {
        guard keyboardOverlap > 0, let scrollView = webView?.scrollView else { return }
        let top = -scrollView.adjustedContentInset.top
        guard abs(scrollView.contentOffset.y - top) > 0.5, holdsTop || documentFits(scrollView) else { return }
        scrollView.setContentOffset(CGPoint(x: scrollView.contentOffset.x, y: top), animated: false)
    }

    // The bottom inset comes from the safe area and not from adjustedContentInset, which also
    // carries what UIKit adds for the keyboard when WebKit hears of it before the shell does.
    private func documentFits(_ scrollView: UIScrollView) -> Bool {
        let bottom = scrollView.contentInsetAdjustmentBehavior == .never ? 0 : scrollView.safeAreaInsets.bottom
        return scrollView.contentSize.height <= scrollView.bounds.height - scrollView.adjustedContentInset.top - bottom + 1
    }

    // Runs before the frame changes, so the document is measured against the full viewport.
    private func beginSettling() {
        guard let scrollView = webView?.scrollView else { return }
        settlingHeight = scrollView.contentSize.height
        holdsTop = documentFits(scrollView) && abs(scrollView.contentOffset.y + scrollView.adjustedContentInset.top) <= 0.5
        // A held document as tall after the resize as before reports no new size, so the hold also
        // ends on time. Half a second is past the keyboard animation and any layout seen.
        settleTimer?.cancel()
        let timer = DispatchWorkItem { [weak self] in
            self?.endSettling()
            self?.revealFocusedField()
        }
        settleTimer = timer
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: timer)
    }

    private func endSettling() {
        guard settlingHeight != nil else { return }
        settlingHeight = nil
        holdsTop = false
        dropStrayScroll()
    }

    // WebKit brought the field into view against the old layout, and does not try again after the
    // page lays out: a field the shorter viewport pushed out of sight (a page that no longer fits
    // and now scrolls, or one that keeps its content in a scrolling or clipping box) would stay
    // under the keyboard. The hold at the top is one cause too: it drops the scroll WebKit asked
    // for, which a page that turned out to scroll needed. Chrome on Android scrolls to the field
    // after its own resize; here the shell asks once, when the keyboard has landed and the page
    // has had time for its own transition, and only for a field that is not wholly in view, so a
    // visible one never moves. Left alone: an iframe (the field is inside it, out of reach), a box
    // with no size (the hidden input behind a drawn code field) and one taller than the viewport
    // (a long text area, where centering would take the caret away).
    private func revealFocusedField() {
        guard keyboardOverlap > 0 else { return }
        webView?.evaluateJavaScript("""
            (function () {
              var field = document.activeElement;
              if (!field || field === document.body || field.tagName === "IFRAME" || !field.getBoundingClientRect) return;
              var box = field.getBoundingClientRect();
              if (!box.width || !box.height || box.height > window.innerHeight) return;
              if (box.top < 0 || box.bottom > window.innerHeight) field.scrollIntoView({ block: "center" });
            })()
            """)
    }

    // The popup contract needs the https callback of ASWebAuthenticationSession, from iOS 17.4.
    // Held here because the web view only keeps its UI delegate weakly.
    private var popups: AnyObject?

    private func installPopups() {
        guard #available(iOS 17.4, *), let webView = webView else { return }
        let popups = ShellPopups(shell: self, base: webView.uiDelegate as? NSObject)
        self.popups = popups
        webView.uiDelegate = popups
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

    // Hosts whose window.open is a consent for an account connection (plugins.MitraShell.consentHosts).
    var consentHosts: [String] {
        return (bridge?.config.getPluginConfig("MitraShell").getArray("consentHosts") ?? [])
            .compactMap { ($0 as? String)?.lowercased() }
    }

    // The path prefix on the app host where a connection comes back (plugins.MitraShell.returnPath).
    var returnPath: String {
        return bridge?.config.getPluginConfig("MitraShell").getString("returnPath") ?? "/oauth/"
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
        fitToKeyboard()
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

// The Android twin is ShellPopups.java and PopupPolicy.java. Only the rules that fit iOS are here:
// WebKit already refuses a window.open without a user gesture (javaScriptCanOpenWindowsAutomatically
// stays off), so what is checked is the frame, the host of the page and the host of the URL.
// The code in the return URL is passed on as is: it is not read, stored or logged here.
@available(iOS 17.4, *)
final class ShellPopups: NSObject, WKUIDelegate, WKNavigationDelegate, ASWebAuthenticationPresentationContextProviding {
    private weak var shell: ShellBridgeViewController?
    // Capacitor's own UI delegate: everything but window.open stays its.
    private let base: NSObject?
    // One child at a time.
    private var child: WKWebView?
    private var session: ASWebAuthenticationSession?
    private var returned = false
    // The one path this consent comes back on, from the redirect_uri of its URL. The https callback of
    // the session is not known to match a path prefix (Apple does not say), so it only gets this.
    private var sessionPath: String?

    // A child that does not close itself after the return would hold the only slot for the rest of the run.
    private static let reapAfter: TimeInterval = 15
    private static let closeWait: TimeInterval = 2

    init(shell: ShellBridgeViewController, base: NSObject?) {
        self.shell = shell
        self.base = base
        super.init()
    }

    // MARK: Forwarding to Capacitor's delegate

    override func responds(to aSelector: Selector!) -> Bool {
        return super.responds(to: aSelector) || (base?.responds(to: aSelector) ?? false)
    }

    override func forwardingTarget(for aSelector: Selector!) -> Any? {
        if let base = base, base.responds(to: aSelector) { return base }
        return super.forwardingTarget(for: aSelector)
    }

    // MARK: window.open

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        // Without a valid redirect_uri there is no exact callback path to hand the session, so the
        // window opens outside as before instead of registering the prefix and hoping it matches.
        guard child == nil, let url = navigationAction.request.url, isPopupConsent(navigationAction, url: url),
              let exactPath = exactReturnPath(in: url) else {
            return openAsBefore(webView, configuration, navigationAction, windowFeatures)
        }
        // The child gets no native bridge: it only has to run the page of the return. A fresh content
        // controller drops the Capacitor scripts and message handlers the opener's configuration carries.
        configuration.userContentController = WKUserContentController()
        let popup = WKWebView(frame: .zero, configuration: configuration)
        popup.navigationDelegate = self
        popup.uiDelegate = self
        child = popup
        returned = false
        sessionPath = exactPath
        // After this callback returns: presenting from inside it is not safe.
        DispatchQueue.main.async { [weak self] in self?.start(url, popup: popup) }
        return popup
    }

    // What Capacitor did with a window.open before: the system opens it and the page gets no window.
    private func openAsBefore(_ webView: WKWebView, _ configuration: WKWebViewConfiguration, _ navigationAction: WKNavigationAction, _ windowFeatures: WKWindowFeatures) -> WKWebView? {
        guard let base = base as? WKUIDelegate else { return nil }
        return base.webView?(webView, createWebViewWith: configuration, for: navigationAction, windowFeatures: windowFeatures) ?? nil
    }

    // From the main frame of a page of the app, to a consent host over https.
    private func isPopupConsent(_ action: WKNavigationAction, url: URL) -> Bool {
        guard let shell = shell, let appHost = shell.appHost,
              action.sourceFrame.isMainFrame,
              let opener = action.sourceFrame.request.url, isAppPage(opener, appHost: appHost),
              url.scheme?.lowercased() == "https", url.user == nil,
              let host = url.host?.lowercased() else { return false }
        return shell.consentHosts.contains(host)
    }

    private func isAppPage(_ url: URL, appHost: String) -> Bool {
        return url.scheme?.lowercased() == "https" && url.user == nil && url.host?.lowercased() == appHost
            && (url.port == nil || url.port == 443)
    }

    // The return: the app host, inside the return prefix, and the exact path of the session. The
    // WebView resolves dot segments, so a path that has one could leave the prefix.
    private func isReturn(_ url: URL) -> Bool {
        guard let path = returnPath(of: url), path == sessionPath else { return false }
        return true
    }

    // The path of a URL of the app host inside the return prefix, made of plain characters (no percent
    // escape, so no encoded dot, slash or backslash), or nil.
    private func returnPath(of url: URL) -> String? {
        guard let shell = shell, let appHost = shell.appHost, isAppPage(url, appHost: appHost),
              let path = URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedPath,
              path.hasPrefix(shell.returnPath),
              path.range(of: "^[A-Za-z0-9._~/-]+$", options: .regularExpression) != nil,
              !path.split(separator: "/", omittingEmptySubsequences: false).contains(where: { $0 == "." || $0 == ".." }) else { return nil }
        return path
    }

    // The exact return path of a consent URL: its single redirect_uri parameter, when that is a URL of
    // the app host inside the return prefix. A repeated parameter is ambiguous, so it gives nil.
    private func exactReturnPath(in consent: URL) -> String? {
        let items = (URLComponents(url: consent, resolvingAgainstBaseURL: false)?.queryItems ?? []).filter { $0.name == "redirect_uri" }
        guard items.count == 1, let value = items[0].value, let redirect = URL(string: value), redirect.fragment == nil else { return nil }
        return returnPath(of: redirect)
    }

    // MARK: The consent in the system browser

    private func start(_ url: URL, popup: WKWebView) {
        guard child === popup, let appHost = shell?.appHost, let exactPath = sessionPath else { return }
        let callback = ASWebAuthenticationSession.Callback.https(host: appHost, path: exactPath)
        let session = ASWebAuthenticationSession(url: url, callback: callback) { [weak self] callbackURL, error in
            guard let self = self, self.child === popup else { return }
            self.session = nil
            if let callbackURL = callbackURL, self.isReturn(callbackURL) {
                self.deliver(callbackURL, to: popup)
                return
            }
            // A URL that is not the return (a browser that matched the path another way) only closes
            // the window: sending the user back to the consent would be wrong.
            let cancelled = (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin
            if callbackURL == nil && !cancelled {
                // The session could not run, for example because the host is not an associated
                // domain yet: the system browser, as Capacitor would have done.
                UIApplication.shared.open(url)
            }
            self.close(popup)
        }
        session.prefersEphemeralWebBrowserSession = false
        session.presentationContextProvider = self
        self.session = session
        if !session.start() {
            self.session = nil
            UIApplication.shared.open(url)
            close(popup)
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        return shell?.view.window ?? ASPresentationAnchor()
    }

    private func deliver(_ url: URL, to popup: WKWebView) {
        returned = true
        popup.load(URLRequest(url: url))
        DispatchQueue.main.asyncAfter(deadline: .now() + ShellPopups.reapAfter) { [weak self] in
            if self?.child === popup { self?.discard(popup) }
        }
    }

    // The session ended without a return: the child closes itself, so the page sees .closed.
    private func close(_ popup: WKWebView) {
        popup.evaluateJavaScript("window.close()") { [weak self] _, _ in self?.discard(popup) }
        DispatchQueue.main.asyncAfter(deadline: .now() + ShellPopups.closeWait) { [weak self] in self?.discard(popup) }
    }

    private func discard(_ popup: WKWebView) {
        if child === popup {
            child = nil
            returned = false
            sessionPath = nil
            session?.cancel()
            session = nil
        }
        popup.stopLoading()
        popup.navigationDelegate = nil
        popup.uiDelegate = nil
    }

    // MARK: The child window

    func webViewDidClose(_ webView: WKWebView) {
        if webView === child {
            discard(webView)
        } else if let base = base as? WKUIDelegate {
            base.webViewDidClose?(webView)
        }
    }

    // Before the return nothing loads in the child, its own first load included: the consent is not
    // for a web view. After it, only the app host over https.
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard webView === child, returned, let url = navigationAction.request.url,
              let appHost = shell?.appHost, isAppPage(url, appHost: appHost) else {
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
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
        // Apple does not say whether the completion also runs when start() returns false, and only
        // the first end of a session may tell the page and open the browser.
        var finished = false
        let session = ASWebAuthenticationSession(url: url, callback: callback) { [weak self, weak shell] callbackURL, error in
            guard !finished else { return }
            finished = true
            self?.session = nil
            guard let callbackURL = callbackURL else {
                let cancelled = (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin
                if cancelled {
                    self?.notifyPage(MitraShellPlugin.authCancelEvent)
                } else {
                    // The session could not run, for example because the host is not an associated
                    // domain yet: fall back to the browser, as Capacitor would have done.
                    self?.notifyPage(MitraShellPlugin.authFailEvent)
                    UIApplication.shared.open(url)
                }
                return
            }
            guard let target = shell?.appURL(fromReturn: callbackURL) else {
                self?.notifyPage(MitraShellPlugin.authFailEvent)
                return
            }
            shell?.loadInShell(target)
        }
        session.prefersEphemeralWebBrowserSession = false
        session.presentationContextProvider = self
        self.session = session
        if !session.start(), !finished {
            finished = true
            self.session = nil
            notifyPage(MitraShellPlugin.authFailEvent)
            UIApplication.shared.open(url)
        }
    }

    // The page that started the sign-in shows it in progress until the return reloads it. A session
    // that ends without a return loads nothing, so the page is told, and can put its button back.
    static let authCancelEvent = "mitrashell:authcancel"
    static let authFailEvent = "mitrashell:authfail"

    // The name is one of the constants above, never page or URL data: Capacitor quotes it into script.
    private func notifyPage(_ event: String) {
        bridge?.triggerWindowJSEvent(eventName: event)
    }
}

extension MitraShellPlugin: ASWebAuthenticationPresentationContextProviding {
    public func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        return bridge?.viewController?.view.window ?? ASPresentationAnchor()
    }
}
