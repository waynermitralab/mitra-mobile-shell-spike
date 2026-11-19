import AuthenticationServices
import XCTest
@testable import App

// The sign-in of the iOS shell (MitraShellPlugin.startSession) on the real plugin, with the system
// sheet replaced by a fake session the test ends by hand: how each end reaches the page, the
// browser and the shell, and that a session ends once whatever the order of start() and its
// completion. Added to the generated project by `generate.mjs --ios-tests` and run by ci.yml.
@available(iOS 17.4, *)
final class ShellSignInTests: XCTestCase {

    private final class FakeSession: ShellSignInSession {
        var startResult = true
        var onStart: (() -> Void)?
        private(set) var starts = 0
        func start() -> Bool {
            starts += 1
            onStart?()
            return startResult
        }
    }

    private final class FakeShell: ShellSignInHost {
        var callbackScheme: String? = "io.example.app"
        private(set) var loaded: [URL] = []
        func appURL(fromReturn url: URL) -> URL? {
            guard url.scheme == callbackScheme else { return nil }
            var c = URLComponents(string: "https://app.example.com/noodle")!
            c.percentEncodedFragment = URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedFragment
            return c.url
        }
        func loadInShell(_ url: URL) { loaded.append(url) }
    }

    private let signIn = URL(string: "https://app.infusion.cloud/sdk-auth.html?brand=infusion")!
    private let goodReturn = URL(string: "io.example.app://auth#codeMitra=c1&stateMitra=s1")!
    private var plugin: MitraShellPlugin!
    private var shell: FakeShell!
    private var session: FakeSession!
    private var completion: ((URL?, Error?) -> Void)?
    private var factoryCalls: [(url: URL, scheme: String?, host: String)] = []
    private var events: [String] = []
    private var opened: [URL] = []

    override func setUp() {
        super.setUp()
        plugin = MitraShellPlugin()
        shell = FakeShell()
        session = FakeSession()
        plugin.sessionFactory = { [unowned self] url, scheme, host, done in
            self.factoryCalls.append((url, scheme, host))
            self.completion = done
            return self.session
        }
        plugin.pageNotifier = { [unowned self] in self.events.append($0) }
        plugin.browserOpener = { [unowned self] in self.opened.append($0) }
    }

    private func start() {
        plugin.startSession(signIn, appHost: "app.example.com", shell: shell)
    }

    private var cancel: Error { ASWebAuthenticationSessionError(.canceledLogin) }
    private var failure: Error { ASWebAuthenticationSessionError(.presentationContextInvalid) }

    func testStartsOneSessionWithTheSchemeOfTheShell() {
        start()
        XCTAssertEqual(session.starts, 1)
        XCTAssertEqual(factoryCalls.count, 1)
        XCTAssertEqual(factoryCalls.first?.url, signIn)
        XCTAssertEqual(factoryCalls.first?.scheme, "io.example.app")
        XCTAssertEqual(factoryCalls.first?.host, "app.example.com")
        XCTAssertNotNil(plugin.session, "the session is held while it runs")
    }

    func testHttpsCallbackWhenTheShellHasNoScheme() {
        shell.callbackScheme = nil
        start()
        XCTAssertNil(factoryCalls.first?.scheme)
        XCTAssertEqual(factoryCalls.first?.host, "app.example.com")
    }

    func testAValidReturnLoadsInTheShellAndTellsThePageNothing() {
        start()
        completion?(goodReturn, nil)
        XCTAssertEqual(shell.loaded.map(\.absoluteString), ["https://app.example.com/noodle#codeMitra=c1&stateMitra=s1"])
        XCTAssertEqual(events, [])
        XCTAssertEqual(opened, [])
        XCTAssertNil(plugin.session, "the session is released when it ends")
    }

    func testACancelTellsThePageOnceAndOpensNothing() {
        start()
        completion?(nil, cancel)
        XCTAssertEqual(events, [MitraShellPlugin.authCancelEvent])
        XCTAssertEqual(opened, [])
        XCTAssertEqual(shell.loaded, [])
        XCTAssertNil(plugin.session)
    }

    func testAFailureTellsThePageAndFallsBackToTheBrowserOnce() {
        start()
        completion?(nil, failure)
        XCTAssertEqual(events, [MitraShellPlugin.authFailEvent])
        XCTAssertEqual(opened, [signIn])
        XCTAssertEqual(shell.loaded, [])
    }

    func testAReturnThatIsNotOfTheAppFailsWithoutTheBrowser() {
        start()
        completion?(URL(string: "other.app://auth#codeMitra=c1")!, nil)
        XCTAssertEqual(events, [MitraShellPlugin.authFailEvent])
        XCTAssertEqual(opened, [], "a return is not retried in the browser")
        XCTAssertEqual(shell.loaded, [])
    }

    func testStartFalseFailsOnceAndALateCompletionChangesNothing() {
        session.startResult = false
        start()
        XCTAssertEqual(events, [MitraShellPlugin.authFailEvent])
        XCTAssertEqual(opened, [signIn])
        XCTAssertNil(plugin.session)
        completion?(nil, failure)
        completion?(goodReturn, nil)
        XCTAssertEqual(events, [MitraShellPlugin.authFailEvent], "one event per sign-in")
        XCTAssertEqual(opened, [signIn], "the browser at most once")
        XCTAssertEqual(shell.loaded, [], "a late return is dropped")
    }

    func testStartFalseAfterACompletionInsideStartEndsOnce() {
        session.startResult = false
        session.onStart = { [unowned self] in self.completion?(nil, self.failure) }
        start()
        XCTAssertEqual(events, [MitraShellPlugin.authFailEvent])
        XCTAssertEqual(opened, [signIn])
        XCTAssertNil(plugin.session)
    }

    func testACancelInsideAStartThatFailsIsTheOnlyEnd() {
        session.startResult = false
        session.onStart = { [unowned self] in self.completion?(nil, self.cancel) }
        start()
        XCTAssertEqual(events, [MitraShellPlugin.authCancelEvent])
        XCTAssertEqual(opened, [])
    }

    func testDuplicateCompletionsEndTheSessionOnce() {
        start()
        completion?(goodReturn, nil)
        completion?(goodReturn, nil)
        completion?(nil, failure)
        completion?(nil, cancel)
        XCTAssertEqual(shell.loaded.count, 1)
        XCTAssertEqual(events, [])
        XCTAssertEqual(opened, [])
    }

    func testEachSignInHasItsOwnEnd() {
        start()
        let first = completion
        completion?(nil, cancel)
        start()
        completion?(nil, cancel)
        first?(nil, failure)
        XCTAssertEqual(events, [MitraShellPlugin.authCancelEvent, MitraShellPlugin.authCancelEvent])
        XCTAssertEqual(opened, [])
        XCTAssertEqual(session.starts, 2)
    }

    func testTheRealFactoryMakesASystemSessionThatKeepsTheBrowserCookies() {
        let real = plugin.makeSession(signIn, callbackScheme: "io.example.app", appHost: "app.example.com") { _, _ in }
        let system = real as? ASWebAuthenticationSession
        XCTAssertNotNil(system)
        XCTAssertEqual(system?.prefersEphemeralWebBrowserSession, false)
        XCTAssertTrue(system?.presentationContextProvider === plugin)
    }
}
