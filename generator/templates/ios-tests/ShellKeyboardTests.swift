import AuthenticationServices
import UIKit
import WebKit
import XCTest
@testable import App

// The keyboard of the iOS shell on the real ShellBridgeViewController of the test host, with its
// real WKWebView and Capacitor bridge: the test posts the keyboard notifications UIKit would post,
// loads its own documents in the web view, and reads what the page heard and how the view moved.
// Covered: resize (the default), notify and off; a switch of mode with the keyboard up, from the
// shell and from the page; a navigation that commits (the mode goes back to the config's) and one
// the shell takes over, which does not (the document keeps its mode); a document that fits the viewport and one that
// scrolls; the state a page reads in its first inline script. Added to the generated project by
// `generate.mjs --ios-tests` and run by ci.yml, on a project generated with the default (resize).
@MainActor
final class ShellKeyboardTests: XCTestCase {

    private var shell: ShellBridgeViewController!
    private var webView: WKWebView!
    private var baseURL: URL!
    private let keyboardHeight: CGFloat = 300

    // The page logs what it heard. __initial is what its first inline script read, before any
    // message of the shell could have arrived.
    private static func page(_ body: String) -> String {
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <script>
              window.__initial = JSON.stringify(window.__mitraShellKeyboard);
              window.__events = [];
              addEventListener("mitrashell:keyboard", function (e) {
                __events.push({ phase: e.phase, mode: e.mode, height: e.height });
              });
            </script>
            <style>html, body { margin: 0; }</style></head>
            <body>\(body)</body></html>
            """
    }

    private static let fits = page("<input id=f><p>short</p>")
    private static let scrolls = page("<input id=f><div style=\"height: 4000px\">tall</div>")

    override func setUp() async throws {
        let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
        shell = windows.compactMap { $0.rootViewController as? ShellBridgeViewController }.first
        let shell = try XCTUnwrap(shell, "the test host runs the shell")
        webView = try XCTUnwrap(shell.webView)
        baseURL = try XCTUnwrap(shell.bridge?.config.serverURL)
        // The first load of the app (the fixture page, or a failure without it) must be over, or
        // its commit would reset the mode in the middle of a test.
        try await eventually("the first load ends") { !self.webView.isLoading }
        try await load(Self.fits)
        XCTAssertEqual(UIApplication.shared.applicationState, .active, "the test host is in front, or WebKit stops laying out")
    }

    override func tearDown() async throws {
        if shell != nil {
            keyboard(UIResponder.keyboardWillHideNotification, up: false)
            keyboard(UIResponder.keyboardDidHideNotification, up: false)
            try await load(Self.fits)
        }
    }

    // MARK: helpers

    private func load(_ html: String) async throws {
        let marker = UUID().uuidString
        webView.loadHTMLString(html.replacingOccurrences(of: "</body>", with: "<i id='\(marker)'></i></body>"), baseURL: baseURL)
        try await eventually("the document loads") {
            if self.webView.isLoading { return false }
            let found = try? await self.js("!!document.getElementById('\(marker)')")
            return (found as? Bool) == true
        }
        // The shell hears of the new document by a message that crosses after the page's scripts.
        try await settle()
    }

    private func js(_ script: String) async throws -> Any? {
        return try await webView.evaluateJavaScript(script)
    }

    private func settle() async throws {
        try await Task.sleep(nanoseconds: 150_000_000)
        _ = try await js("1")
    }

    private func eventually(_ what: String, timeout: TimeInterval = 10, _ condition: () async -> Bool) async throws {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end {
            if await condition() { return }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTFail("timed out: \(what)")
        throw CancellationError()
    }

    // The frame of a docked keyboard at the bottom of the screen, in screen coordinates, as UIKit
    // sends it.
    private func keyboardFrame(up: Bool) -> CGRect {
        let screen = shell.view.window!.screen.bounds
        return CGRect(x: 0, y: up ? screen.maxY - keyboardHeight : screen.maxY, width: screen.width, height: keyboardHeight)
    }

    private func keyboard(_ name: Notification.Name, up: Bool, duration: Double = 0.25) {
        NotificationCenter.default.post(name: name, object: nil, userInfo: [
            UIResponder.keyboardFrameEndUserInfoKey: NSValue(cgRect: keyboardFrame(up: up)),
            UIResponder.keyboardAnimationDurationUserInfoKey: NSNumber(value: duration),
            UIResponder.keyboardAnimationCurveUserInfoKey: NSNumber(value: 7),
            UIResponder.keyboardIsLocalUserInfoKey: NSNumber(value: true)
        ])
    }

    private func keyboardOpens() async throws {
        keyboard(UIResponder.keyboardWillChangeFrameNotification, up: true)
        keyboard(UIResponder.keyboardWillShowNotification, up: true)
        keyboard(UIResponder.keyboardDidChangeFrameNotification, up: true)
        keyboard(UIResponder.keyboardDidShowNotification, up: true)
        try await settle()
    }

    private func keyboardCloses() async throws {
        keyboard(UIResponder.keyboardWillChangeFrameNotification, up: false)
        keyboard(UIResponder.keyboardWillHideNotification, up: false)
        keyboard(UIResponder.keyboardDidChangeFrameNotification, up: false)
        keyboard(UIResponder.keyboardDidHideNotification, up: false)
        try await settle()
    }

    private struct Heard: Decodable, Equatable {
        let phase: String
        let mode: String
        let height: Double
    }

    private func events() async throws -> [Heard] {
        let jsonValue = try await js("JSON.stringify(__events)")
        let json = try XCTUnwrap(jsonValue as? String)
        return try JSONDecoder().decode([Heard].self, from: Data(json.utf8))
    }

    private func state() async throws -> Heard {
        let jsonValue = try await js("JSON.stringify(window.__mitraShellKeyboard)")
        let json = try XCTUnwrap(jsonValue as? String)
        return try JSONDecoder().decode(Heard.self, from: Data(json.utf8))
    }

    private func initialState() async throws -> Heard? {
        guard let json = try await js("window.__initial || null") as? String else { return nil }
        return try JSONDecoder().decode(Heard.self, from: Data(json.utf8))
    }

    private var fullHeight: CGFloat { shell.view.superview!.bounds.height }
    private var overlap: CGFloat {
        let window = shell.view.window!
        let container = shell.view.superview!
        let full = container.convert(container.bounds, to: window)
        let keyboard = window.convert(keyboardFrame(up: true), from: window.screen.coordinateSpace)
        return min(max(0, full.maxY - keyboard.minY), full.height)
    }

    private func clearEvents() async throws {
        _ = try await js("__events = []")
    }

    // MARK: modes

    func testAPageReadsTheStateInItsFirstInlineScript() async throws {
        let got1 = try await initialState()
        XCTAssertEqual(got1, Heard(phase: "didHide", mode: "resize", height: 0),
                       "the state is on window before the page's own scripts, with the mode of the config")
        XCTAssertEqual(ShellBridgeViewController.documentStartScript(mode: "notify").hasPrefix(
            "window.__mitraShellKeyboard = {"), true)
    }

    func testResizeEndsTheWebViewAtTheKeyboardAndTellsThePage() async throws {
        let beforeValue = try await js("window.innerHeight")
        let before = try XCTUnwrap(beforeValue as? Double)
        try await keyboardOpens()
        XCTAssertEqual(shell.view.frame.height, fullHeight - overlap, accuracy: 0.5, "the web view ends at the keyboard")
        let heard = try await events()
        XCTAssertEqual(heard, [Heard(phase: "willShow", mode: "resize", height: Double(overlap)),
                               Heard(phase: "didShow", mode: "resize", height: Double(overlap))],
                       "one willShow and one didShow, although two notifications report each")
        let got2 = try await state()
        XCTAssertEqual(got2, Heard(phase: "didShow", mode: "resize", height: Double(overlap)))
        let innerHeightValue = try await js("window.innerHeight")
        let innerHeight = try XCTUnwrap(innerHeightValue as? Double)
        // The layout viewport is the frame less the insets the scroll view keeps (the status bar with
        // contentInset automatic); the bottom safe area is gone with a frame that ends at the keyboard.
        let insets = webView.scrollView.adjustedContentInset
        XCTAssertEqual(innerHeight, Double(shell.view.frame.height - insets.top - insets.bottom), accuracy: 1,
                       "the layout viewport of the page follows the frame")
        XCTAssertLessThan(innerHeight, before - 100, "and is shorter than before the keyboard")

        try await keyboardCloses()
        XCTAssertEqual(shell.view.frame.height, fullHeight, accuracy: 0.5)
        let got3 = try await events().suffix(2)
        XCTAssertEqual(got3, [Heard(phase: "willHide", mode: "resize", height: 0),
                                                       Heard(phase: "didHide", mode: "resize", height: 0)])
    }

    func testNotifyKeepsTheWebViewAndTellsThePageWhereTheKeyboardGoes() async throws {
        XCTAssertNotNil(shell.switchKeyboardMode("notify"))
        try await keyboardOpens()
        XCTAssertEqual(shell.view.frame.height, fullHeight, accuracy: 0.5, "the page moves itself")
        let got4 = try await events()
        XCTAssertEqual(got4, [Heard(phase: "willShow", mode: "notify", height: Double(overlap)),
                                            Heard(phase: "didShow", mode: "notify", height: Double(overlap))])
        try await keyboardCloses()
        let got5 = try await events().last
        XCTAssertEqual(got5, Heard(phase: "didHide", mode: "notify", height: 0))
    }

    func testOffSendsNothingAndReportsNoKeyboard() async throws {
        XCTAssertEqual(shell.switchKeyboardMode("off")?["mode"] as? String, "off")
        try await keyboardOpens()
        XCTAssertEqual(shell.view.frame.height, fullHeight, accuracy: 0.5)
        let got6 = try await events()
        XCTAssertEqual(got6, [], "no event in off")
        let got7 = try await state()
        XCTAssertEqual(got7, Heard(phase: "didHide", mode: "off", height: 0))
        XCTAssertTrue(webView.scrollView.panGestureRecognizer.isEnabled, "and WebKit is left as it comes")
        XCTAssertNil(shell.switchKeyboardMode("sideways"), "a name that is not a mode is refused")
    }

    // MARK: switching with the keyboard up

    func testASwitchWithTheKeyboardUpMovesTheViewAtOnceWithoutAnEvent() async throws {
        try await keyboardOpens()
        try await clearEvents()
        XCTAssertEqual(shell.view.frame.height, fullHeight - overlap, accuracy: 0.5)

        let notify = try XCTUnwrap(shell.switchKeyboardMode("notify"))
        XCTAssertEqual(notify["mode"] as? String, "notify")
        XCTAssertEqual(notify["height"] as? Double, Double(overlap), "the state under the new mode has the keyboard")
        XCTAssertEqual(shell.view.frame.height, fullHeight, accuracy: 0.5, "leaving resize gives the full height back")
        try await settle()
        let got8 = try await state()
        XCTAssertEqual(got8, Heard(phase: "didShow", mode: "notify", height: Double(overlap)))

        XCTAssertNotNil(shell.switchKeyboardMode("resize"))
        XCTAssertEqual(shell.view.frame.height, fullHeight - overlap, accuracy: 0.5, "entering resize ends at the keyboard")

        XCTAssertNotNil(shell.switchKeyboardMode("off"))
        XCTAssertEqual(shell.view.frame.height, fullHeight, accuracy: 0.5)
        try await settle()
        let got9 = try await state()
        XCTAssertEqual(got9, Heard(phase: "didHide", mode: "off", height: 0))
        let got10 = try await events()
        XCTAssertEqual(got10, [], "a switch makes up no event")
    }

    func testThePageSwitchesTheModeThroughThePlugin() async throws {
        try await keyboardOpens()
        let result = try await webView.callAsyncJavaScript(
            "return JSON.stringify(await window.Capacitor.Plugins.MitraShell.setKeyboardMode({ mode: 'notify' }))",
            contentWorld: .page)
        let state = try JSONDecoder().decode(Heard.self, from: Data(try XCTUnwrap(result as? String).utf8))
        XCTAssertEqual(state, Heard(phase: "didShow", mode: "notify", height: Double(overlap)))
        XCTAssertEqual(shell.view.frame.height, fullHeight, accuracy: 0.5)
        do {
            _ = try await webView.callAsyncJavaScript(
                "return await window.Capacitor.Plugins.MitraShell.setKeyboardMode({ mode: 'sideways' })", contentWorld: .page)
            XCTFail("a name that is not a mode rejects")
        } catch {}
        let got11 = try await self.state().mode
        XCTAssertEqual(got11, "notify", "and changes nothing")
    }

    // MARK: navigation

    func testACommittedDocumentStartsWithTheModeOfTheConfig() async throws {
        XCTAssertNotNil(shell.switchKeyboardMode("notify"))
        try await load(Self.fits)
        let got12 = try await state()
        XCTAssertEqual(got12, Heard(phase: "didHide", mode: "resize", height: 0))
        try await keyboardOpens()
        XCTAssertEqual(shell.view.frame.height, fullHeight - overlap, accuracy: 0.5, "resize again")
    }

    func testADocumentThatStartsWithTheKeyboardUpIsToldWhereItIs() async throws {
        try await keyboardOpens()
        XCTAssertNotNil(shell.switchKeyboardMode("notify"))
        try await load(Self.fits)
        let got13 = try await initialState()
        XCTAssertEqual(got13, Heard(phase: "didHide", mode: "resize", height: 0),
                       "its first script read the state of a new document")
        let got14 = try await state()
        XCTAssertEqual(got14, Heard(phase: "didShow", mode: "resize", height: Double(overlap)), "then the real one")
        let got15 = try await events()
        XCTAssertEqual(got15, [Heard(phase: "didShow", mode: "resize", height: Double(overlap))],
                       "and the event a listener of its first scripts needs")
        XCTAssertEqual(shell.view.frame.height, fullHeight - overlap, accuracy: 0.5)
    }

    private final class HeldSession: ShellSignInSession {
        private(set) var started = false
        func start() -> Bool { started = true; return true }
    }

    // A navigation the shell takes over does not commit: the page goes to a sign-in host, the
    // shell cancels it and starts its sign-in session (a fake one here, so no sheet shows), and the
    // document that asked stays, with the mode it chose.
    func testANavigationThatDoesNotCommitKeepsTheModeOfTheDocument() async throws {
        guard #available(iOS 17.4, *) else { throw XCTSkip("the sign-in session needs iOS 17.4") }
        let plugin = try XCTUnwrap(shell.bridge?.plugin(withName: "MitraShell") as? MitraShellPlugin)
        let session = HeldSession()
        var end: ((URL?, Error?) -> Void)?
        plugin.sessionFactory = { _, _, _, completion in
            end = completion
            return session
        }
        defer {
            end?(nil, ASWebAuthenticationSessionError(.canceledLogin))
            plugin.sessionFactory = nil
        }
        XCTAssertNotNil(shell.switchKeyboardMode("notify"))
        _ = try await js("__marker = 'same document'; location.href = 'https://accounts.google.com/o/oauth2/v2/auth?client_id=1'")
        try await eventually("the shell takes the navigation over") { session.started }
        try await settle()
        XCTAssertFalse(webView.isLoading, "nothing loads")
        let marker = try await js("window.__marker || null")
        XCTAssertEqual(marker as? String, "same document")
        let mode = try await state().mode
        XCTAssertEqual(mode, "notify", "the document stayed, and so did its mode")
    }

    // MARK: documents that fit and documents that scroll

    func testInNotifyADocumentThatFitsDoesNotPanOrScrollUnderTheKeyboard() async throws {
        XCTAssertNotNil(shell.switchKeyboardMode("notify"))
        try await keyboardOpens()
        let scrollView = webView.scrollView
        XCTAssertFalse(scrollView.panGestureRecognizer.isEnabled, "a document that fits has nowhere to go")
        let top = -scrollView.adjustedContentInset.top
        scrollView.setContentOffset(CGPoint(x: 0, y: top + 120), animated: false)
        XCTAssertEqual(scrollView.contentOffset.y, top, accuracy: 0.5, "a scroll of WebKit is put back")
        try await keyboardCloses()
        XCTAssertTrue(scrollView.panGestureRecognizer.isEnabled, "the pan comes back with the keyboard down")
    }

    // A document reports its new size a few frames after it loads; until then the shell measures
    // the one before it, and that is what it acts on (the next size it sees puts things right).
    private func tallDocumentLaidOut() async throws {
        try await eventually("the tall document is laid out") {
            self.webView.scrollView.contentSize.height > self.webView.scrollView.bounds.height * 2
        }
    }

    func testInNotifyADocumentThatScrollsKeepsItsPanAndItsScroll() async throws {
        try await load(Self.scrolls)
        try await tallDocumentLaidOut()
        XCTAssertNotNil(shell.switchKeyboardMode("notify"))
        try await keyboardOpens()
        let scrollView = webView.scrollView
        XCTAssertTrue(scrollView.panGestureRecognizer.isEnabled, "the page scrolling is the page")
        let top = -scrollView.adjustedContentInset.top
        scrollView.setContentOffset(CGPoint(x: 0, y: top + 120), animated: false)
        XCTAssertEqual(scrollView.contentOffset.y, top + 120, accuracy: 0.5, "and a scroll stays")
    }

    func testInResizeADocumentThatFitsIsHeldAtTheTop() async throws {
        try await keyboardOpens()
        let scrollView = webView.scrollView
        let top = -scrollView.adjustedContentInset.top
        scrollView.setContentOffset(CGPoint(x: 0, y: top + 80), animated: false)
        XCTAssertEqual(scrollView.contentOffset.y, top, accuracy: 0.5)
        XCTAssertTrue(scrollView.panGestureRecognizer.isEnabled, "resize does not take the pan")
    }

    func testInResizeADocumentThatScrollsKeepsItsScroll() async throws {
        try await load(Self.scrolls)
        try await tallDocumentLaidOut()
        try await keyboardOpens()
        // Past the half second the shell holds a document at the top while it settles.
        try await Task.sleep(nanoseconds: 700_000_000)
        let scrollView = webView.scrollView
        let top = -scrollView.adjustedContentInset.top
        scrollView.setContentOffset(CGPoint(x: 0, y: top + 120), animated: false)
        XCTAssertEqual(scrollView.contentOffset.y, top + 120, accuracy: 0.5)
    }
}
