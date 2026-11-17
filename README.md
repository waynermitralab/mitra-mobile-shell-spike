# mitra-mobile-shell

Capacitor shell generator and store pipeline for Mitra platform apps (App Store and Google Play).

One repository serves every app. Nothing about a specific app is stored here: the generator
takes an app published by the platform and writes a complete Capacitor project for it (a native
shell whose WebView loads the published URL), and the workflows build that project and hand it
to the stores.

## Layout

- `generator/generate.mjs`: writes the Capacitor project for one app under `out/<slug>/`.
- `generator/asc.mjs`: App Store Connect API client (bundle id, build number, TestFlight group).
- `generator/play.mjs`: Google Play Developer API client (AAB upload to a track).
- `generator/wellknown.mjs`: writes the app association files (`apple-app-site-association`,
  `assetlinks.json`) for a generated project.
- `generator/test/`: tests that need a JVM (`MediaPermissionGateTest.java`, see Microphone and camera, and
  `PopupPolicyTest.java`, see Account connections in a popup).
- `generator/templates/`: files copied into every generated project (iOS `SceneDelegate.swift`,
  its CI workflow, its `.gitignore`).
- `package.json` and `package-lock.json`: the pinned Capacitor dependencies. The generator copies
  both into the generated project, so a dependency bump here reaches every app.

## Running the generator

Needs Node 22. A debug or release build also needs Java 21 and the Android SDK; the iOS project
is generated on any machine but only builds on macOS with Xcode.

```bash
npm ci
node generator/generate.mjs --app <app uuid> [--api https://beta.mitralab.io] [flags]
node generator/generate.mjs --url https://<published host> [flags]
node generator/generate.mjs --descriptor path/to/app.json [flags override the file]
```

With `--app` the identity comes from the platform:
`GET {api}/code-studio/api/v1/apps/{app}/info` (public, no token) gives the name, the published
origins, the accent color and the login logo. With `--url` the name, theme color and icon are
read from the published page. The page icon is the SVG if there is one, else the largest PNG,
among its `<link rel=icon>`, `apple-touch-icon` and the icons of the web manifest it links
(maskable and monochrome entries are skipped; an unreachable manifest is ignored). When the page's
icon is used and that manifest declares a hex `background_color`, it is the color behind a logo
with no background of its own, unless `--theme` (or the descriptor's `themeColor`) is given. An SVG
is preferred even over a larger PNG, so to use a specific artwork pass `--icon`. An icon that
fails to download, or is not an SVG or a PNG, is skipped for the next one (at most 6 are tried).
The manifest and icon downloads time out at 15 s and are capped at 1 MB (manifest) and 10 MB (icons).
The app id
defaults to `cloud.<brand>.<slug>`. The API base URL is `--api`, then the `MITRA_API_URL`
environment variable, then `https://beta.mitralab.io`.

An app without an icon, or whose page icons are all unusable (an `.ico` only), gets one drawn by
the generator: the initials of its name (the first letter of the first two words, or the first two
letters of a single word) on the theme color.

### Safe area

The generator reads the viewport meta of the published page (ignoring comments, `<script>` and
`<noscript>` blocks). When it declares `viewport-fit=cover`, the page reserves the notch and status
bar space itself (`env(safe-area-inset-top)`), so the shell writes `ios.contentInset: "never"`;
otherwise it keeps `"automatic"` and the web view pushes the content below the status bar. With
`automatic` on a page that already reserves the space, a blank band the height of the status bar
shows above the header. The run prints the value chosen and why. `mitra-shell.json` records it as
`iosContentInset` only when it was forced, so that file can be reused as a descriptor without
freezing the detection. `--ios-content-inset never|automatic` (or `iosContentInset` in the
descriptor) forces the value; anything else fails the run. The choice is made at generation, so a
page that later adds or removes `viewport-fit=cover` needs a new generation. Only the HTML the
server sends is read, and only its first 256 KB: a viewport meta added by script after load, or
past a very large inline head, is not seen, and the page then gets `automatic`.

On Android, Capacitor's `SystemBars` plugin reads the live page's `viewport-fit=cover` at runtime
and applies the insets accordingly, so a later change in the page is followed without a new
generation. When the page declares cover at generation time, the shell also passes
`plugins.SystemBars.initialViewportFitValueHint: "cover"`, which only spares the first layout a
shift.

#### Status bar style

A page that draws under the bars has to be readable there, and a style fixed at generation time
cannot follow a theme the page switches at runtime (an in-app light/dark toggle, independent of the
device theme, can leave a white clock on a white header). So the bundle carries `shell-bars.js`
(`public/shell-bars.js`), which samples the color the page paints at the top center of the viewport
(the element stack there, composited, then `body`, `html` and the theme color), maps it to light or
dark with the same luminance rule as the generator, and calls `SystemBars.setStyle`. It re-checks
when the DOM, the device color scheme, the size or the visibility change, and after CSS transitions.
`plugins.StatusBar.style` from the theme color stays as the first style, until the page reports.

- iOS: the script is a `WKUserScript` added by `SceneDelegate.swift` only when the config has
  `ios.contentInset: "never"` (the page draws under the bar). `SystemBars.setStyle` on iOS styles
  the status bar and ignores `bar`, so only the top edge is sampled. The splash overlay keeps the
  bar on the splash style while it is up, and the style the page asked for in that time is applied
  when it leaves.
- Android: `ShellSystemBars` (added to `MainActivity`) runs the script after each page load on the
  app host, and only when the live viewport meta has `viewport-fit=cover`, the same rule
  `SystemBars` uses to go edge to edge. The bottom edge is sampled too and styles the navigation
  bar.

On iOS the probe is injected in any main-frame document of the web view; on Android it runs only
for pages on the app host, and only on a WebView of major version 140 or newer, the version from
which Capacitor lets the page under the bars (older ones keep the device-theme style, since the
bars sit over the native window background). On Android the probe is also asked to report again
after a configuration change, because the bridge re-applies the stored styles then.

Known limit: background images and gradients are not read, so a top edge painted that way falls
back to the color of `body`, `html` or the theme color. Elements with `pointer-events: none` are
not seen either, and an element's own `opacity` counts but opacity inherited from an ancestor does
not.

### Microphone and camera

Every generated app declares microphone use, so a page can record audio with `getUserMedia`:

- Android: `RECORD_AUDIO` and `MODIFY_AUDIO_SETTINGS` in the manifest, plus the microphone feature
  as not required (`RECORD_AUDIO` alone would make Play hide the app from devices without a
  microphone). Capacitor's `BridgeWebChromeClient` asks for the runtime permission when the page
  requests audio. `CAMERA` is not declared on purpose: a file input that opens the camera then goes
  through the system camera app, and declaring it would make that path require the runtime grant.
  As a consequence `getUserMedia` asking for video, alone or together with audio, is denied on Android;
  file-input capture still works.
- iOS: `NSMicrophoneUsageDescription` and `NSCameraUsageDescription` in `Info.plist` (the second
  because the file input offers "Take Photo", and iOS terminates an app that opens the camera
  without the key). The strings are in Portuguese and carry the app name, which is escaped (control
  characters are dropped).

On Android only the app's own host can capture audio: a request from an iframe of another host is
denied, and so is any request that needs `CAMERA`, which the manifest does not declare. iOS has no
such filter: Capacitor 8.6's `WebViewDelegationHandler` grants media capture to whatever is loaded
in the web view, other hosts included, once the OS permission is granted.

The Android shell installs its own web chrome client (`ShellWebChromeClient`, written next to
`MainActivity`) for media permissions. Capacitor's `BridgeWebChromeClient` shares one permission
listener between media, geolocation and the camera of the file chooser, so a second request while a
dialog is up, or a request the WebView had already cancelled, was answered twice or not at all, and
the double answer crashed the app. The decision logic is `MediaPermissionGate`, pure Java: one dialog
in progress for all media requests, and when it closes each queued request is decided by the
permissions the app holds, whatever the dialog reported. A lost answer is detected by the lifecycle:
the dialog pauses the activity and its result arrives before the activity resumes, so a request that
arrives with a dialog in progress after the activity was paused for it and is resumed again means
the answer is gone, and the queue is settled by the permissions held before a fresh dialog starts.
Everything else stays Capacitor's. Both classes are templates in `generator/templates/android/`
(package and app host are substituted); the gate has a behavioral test on a plain JVM,
`generator/test/MediaPermissionGateTest.java`, which the `test` job of `ci.yml` compiles and runs
with `javac` and `java`. It is not part of `npm test`, because Java is not guaranteed where that
runs.

The glue is tested on the real Capacitor classes. Every generated project carries
`android/app/src/test/java/<package>/ShellWebChromeClientTest.java` (from
`generator/templates/android/ShellWebChromeClientTest.java`) and the generator adds Robolectric to
its `build.gradle`. The test starts `MainActivity` with the real Bridge, takes the client that
`onCreate` installed, and sends it fake web requests. The dialog is played by Robolectric and its
result goes through `Activity.dispatchRequestPermissionsResult`, `BridgeActivity` and Capacitor's
own launcher callback and permission listener, so what runs is the path of production. It covers
queuing, grant and deny, a request the WebView already resolved, a lost answer after another flow
took Capacitor's listener, cancel, a foreign origin and held permissions. Only the service worker
controller is replaced (Robolectric has no WebView provider), and the WebView itself is
Robolectric's stub, so no page is loaded. Run it in a generated project with
`./gradlew --no-daemon testDebugUnitTest` in its `android/` folder; the `build` job of `ci.yml`
does that on its fixture project. `shell.yml` does not run it: it is the same code for every app,
and a download of Robolectric should not be able to block a store release.

The permission prompt appears only when the page asks for the microphone or camera. Once the OS
permission is granted, Capacitor grants the page loaded in the web view access without asking
again (`WebViewDelegationHandler` on iOS, `BridgeWebChromeClient` on Android), so the page is
trusted with it. The store privacy answers (App Privacy in App Store Connect, Data safety in Play
Console) are about data collected or shared, not about access: what changes them is whether
recorded audio or photos leave the device, which depends on what the published app does with them.

### Background audio (`--background-audio`)

A page holding a live voice conversation (getUserMedia audio plus WebRTC playback) keeps playing
with the phone locked, but both platforms silence the microphone of an app that is not in the
foreground. With `--background-audio` (descriptor key `backgroundAudio`, workflow input
`background_audio`) the generator handles both, and the option is the same on both platforms, so it
also applies with `--no-ios`. It is off by default, and with it off none of what follows is in the
app. Turn it on only for an app that really keeps audio going in the background.

**iOS.** WebKit mutes microphone capture in a WKWebView app when the phone is locked or the app goes
to the background (WebKit bugs 226620 and 233419). The generator adds `audio` to `UIBackgroundModes`
in `Info.plist`, merging into an array that is already there. Apple rejects apps that declare the
mode without using it (guideline 2.5.4).

**Android.** Android silences capture for an app that is not in the foreground unless it runs a
foreground service of type `microphone`. The shell runs one exactly while the page is capturing:

- The manifest gets `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `WAKE_LOCK` and
  `POST_NOTIFICATIONS` (one line even with `--push`), and declares `.ShellVoiceService`
  (`foregroundServiceType="microphone"`, not exported). On Android 13+ the first "capturing" of a
  process asks for `POST_NOTIFICATIONS` when it is not granted, because without it the notification
  is listed only in the Task Manager and not in the shade. The service does not wait for the answer,
  and a denial only hides the notification (it is not asked again in the same process).
- Detection needs no change in the page. `shell-voice.js` (bundled as `public/shell-voice.js`, injected by
  `ShellVoiceCapture` with `addDocumentStartJavaScript` for the app origin when the WebView supports
  `DOCUMENT_START_SCRIPT`, otherwise with `evaluateJavascript` on the app host's pages at
  `onPageCommitVisible`, once per document) wraps
  `navigator.mediaDevices.getUserMedia` and watches the live audio tracks it hands out. It reports
  "capturing" when a call resolves with at least one live audio track and "idle" when the last one is
  gone: the track fires `ended`, or the page calls `stop()` (which does not fire `ended`, so the track's
  `stop` is wrapped on the instance), or a poll every 2 seconds finds a track that ended without
  either (`stop()` called through the prototype). The wrapper returns the same promise, resolves
  with the same stream and rejects with the same error; a rejection, a video-only stream or a track
  that is already ended never reports. Not covered: `getDisplayMedia`, the old callback
  `getUserMedia`, and a page that stops the original track while a `clone()` of it keeps capturing.
- The report reaches native code through a `WebMessageListener` named `mitraShellVoice`, added to the
  main WebView with the origin rules of the Capacitor bridge. The native side then accepts a message
  only from the main frame of the app host, and only the two literal strings "capturing" and "idle".
  It is not a `JavascriptInterface`, which every frame of every page could call, and the child windows
  of the popup contract never get it. If adding the listener fails, one line is logged (tag
  `ShellVoice`) and the feature stays off. A WebView without `WEB_MESSAGE_LISTENER` support leaves the feature
  off, and the conversation works as before.
- On "capturing" the start waits about 1 second, so a page that probes the microphone and lets go
  of it at once never starts a service ("idle" inside the window cancels it). The service starts only
  if `RECORD_AUDIO` is granted (Android 14 throws otherwise) and the activity is started (a service
  started from the background is refused); a start refused for that reason is retried when the activity
  next starts, as long as the page is still capturing. On "idle" it stops after a grace period of 8
  seconds (a new "capturing" cancels it), because with the screen locked a stopped service cannot come
  back and pages reopen the microphone on a reconnect or a device change. A service that did not
  yet reach `startForeground` is never stopped (Android kills the app for that): `onStartCommand`
  always goes foreground first and then stops itself if the request was withdrawn. It also stops when the
  main frame starts loading a page, when the WebView renderer dies and when the activity is destroyed;
  any exception from the system is caught, leaving the conversation as it was without the service.
- The page is third-party code and can report "capturing" without recording, so every 30 seconds the
  service asks `AudioManager.getActiveRecordingConfigurations()` whether the app has an active
  recording and stops after two checks in a row without one (assumption: the in-process capture of the
  WebView is listed there for the app). The list cannot be narrowed to this app (other apps' recordings
  appear with their client details hidden), so the check bounds a page that fakes capture only while no
  other app is recording. The same check renews the wake lock, which is acquired with a
  timeout of 75 seconds instead of unbounded.
- `ShellVoiceService` shows an ongoing, low importance notification on its own channel (the app name and
  "Conversa por voz em andamento"; a tap brings the app back) with a monochrome microphone icon
  (`ic_stat_voice`, shipped by the generator), holds a `PARTIAL_WAKE_LOCK` for as long as it runs,
  releases it on stop and destroy, and is `START_NOT_STICKY`.
- Capacitor does not pause the WebView when the activity stops (`KeepRunning` defaults to true, see
  `Bridge.shouldKeepRunning()`; the generator does not set it), so the page's JavaScript and WebRTC
  keep running under lock. The service is all that is missing.

Google Play requires a foreground service declaration in Play Console (App content, Foreground
service permissions) for an app that declares `FOREGROUND_SERVICE_MICROPHONE`: the task is a live
voice conversation the user started, and the app shows the notification while it runs. The console
also asks for a short demo video of the feature in use. Only a real
device with the screen locked proves the behavior (the microphone staying live on Android, the
notification, a start from the background, OEM battery limits on a long call); the selftests, the
Robolectric tests of the generated project and CI check the manifest, the script and the service
logic, not Chromium or WebKit.

### Account connections in a popup

A page that connects an account (Google, GitHub, Notion, Slack) uses the popup contract of the
browser: `window.open(consentUrl, name, features)` on a click, then it keeps the window it gets,
listens for `message`, and may poll `window.closed`. The page on the way back, on the app host,
posts to `window.opener` and calls `window.close()`. A shell that sends the consent to the system
browser without honoring that leaves the return page with no opener, and the page never finishes.
The shell honors it with a real child window, so the site needs no change and no script is injected:

1. A `window.open` gets a child window (a hidden web view, never on screen) only when it comes from
   the main frame of a page of the app host, with a user gesture, to an `https` URL whose host is in
   the consent list, and no other child is pending (one at a time). The page receives a real window,
   and the page that runs in the child has a real `window.opener`. Any other `window.open` behaves as
   it did before, whatever the scheme (`mailto:`, `tel:`, `intent:` included): other hosts open in the
   system browser (Android) or the system (iOS), the app host loads in the page. On Android `data:`
   and `blob:` in a new window no longer load in the main page: nothing happens.
2. The consent never runs in a web view. The child's first URL goes to the system browser: on Android
   the Auth Tab when the default browser has it (androidx.browser 1.9, Chrome 137 and later) and the
   exact return path is known, else a Custom Tab that comes back by App Link; on iOS 17.4 and later an
   `ASWebAuthenticationSession` with an https callback (`.https(host:path:)`), again only when the
   exact path is known, else the window opens outside as before. The Auth Tab matches the redirect
   path by equality, not by prefix (Chromium `AuthTabVerifier`), and Apple does not say how the
   session matches it, so the shell never hands either one the prefix and hopes. The exact path is
   the `redirect_uri` parameter of the consent URL (standard OAuth 2.0), used only when it is, once
   decoded, an `https` URL of the app host (default port, no user info, no fragment) with a plain
   path (letters, digits, `. _ ~ -` and `/`, no percent escape and no dot segment) inside the return
   prefix, and only when the parameter appears once. A missing, repeated or failing `redirect_uri`
   means no exact path.
3. What comes back loads in the child, and only if it is an `https` URL of the app host inside the
   return prefix, from a session the shell itself opened, and when the session has an exact path,
   only that path. The main page is not reloaded. The page
   of the return does its own `postMessage` and `close`. The shell's own code does not read, store or
   log the `code`. Capacitor does hand an App Link URL, `code` and `state` included, to the main page
   as an `appUrlOpen` event (`@capacitor/app`), which stays inside the app's own origin.
4. A cancelled or abandoned browser makes the child run `window.close()` and destroys it, so
   `closed` turns `true` on the opener. A child that never asks for a URL, or that does not close
   after the return, is destroyed after 15 seconds so it does not hold the slot.
5. After the return the child only follows `https` URLs of the app host. On Android it has no
   Capacitor bridge and no JavaScript interface; on iOS it gets a fresh user content controller, so
   no Capacitor script or message handler. A load that starts without the navigation callback (a
   form POST with `target=_blank`) is judged by the same rules when it starts. The request has
   already gone out by then, so what is not allowed is interrupted and the child is closed like on
   a cancel. A POST to a consent host reopens in the browser as a GET, without the body.

Options, with a flag or a descriptor field, and echoed in `capacitor.config.json`
(`plugins.MitraShell`) and `mitra-shell.json`:

| Flag | Descriptor | Default |
|---|---|---|
| `--consent-hosts a.com,b.com` | `consentHosts` | `accounts.google.com`, `login.microsoftonline.com`, `login.live.com`, `appleid.apple.com`, `github.com`, `api.notion.com`, `slack.com`; the flag and the descriptor add to them, and the app host is never in the list |
| `--return-path /oauth/` | `returnPath` | `/oauth/`; a path prefix that starts and ends with `/`, and is not `/` itself |

The consent list is not `authHosts` (the sign-in hosts on iOS, which include the platform API host
and the auth page host): a `window.open` to those keeps opening in the system browser.

The return prefix must not cover the path where the sign-in of the app comes back. The sign-in uses
its own return (the URL scheme, or the whole host with `--callback https`); a sign-in that ended
under the prefix would be taken as the return of a connection, or, with no connection open, loaded
in the page without its context. Pick a prefix only the connections use.

The sign-in return by URL scheme (`--callback scheme`) is a separate path and did not change.

What the hosts have to serve, which this repository does not do (`generator/wellknown.mjs` writes
the files, the site or the platform edge serves them):

- Android: `/.well-known/assetlinks.json`. The manifest's App Link is now limited to the return
  path (`android:pathPrefix`), so a link to any other page of the site keeps opening in the browser.
  The exception is `--callback https`, whose sign-in ends on any path of the host and keeps the
  whole-host filter.
- iOS: `webcredentials:<host>` (Associated Domains, from `--associated-domains`) and the
  `apple-app-site-association` file, which the https callback of the session needs.

Rollout order, because the host-wide App Link and the file interact: publish the new version in
the store first, and serve `assetlinks.json` after most users have it. A version that still has the
whole-host filter loses the return of its sign-in once the file is live (the browser hands the
return to the app as a link, and the page that started it is gone), which is the problem of the
first published version. Test the sign-in on the old version before publishing the file.

Until the file is served, a connection fails without a message on Android: with Chrome 137 or newer
the Auth Tab reports a failed or timed out verification, and the shell closes the window like a
cancel; with another browser the Custom Tab shows the return page, which asks to be opened by the
app, and closing the tab closes the window. On iOS the session fails and the consent opens in the
system browser, the window closes too.

Limits:

- If Android kills the app while the browser is open, the return arrives in a new process: the page
  that opened the window does not exist anymore, so the return page has no opener and nothing
  finishes. There is no way to recover the window.
- iOS below 17.4 keeps Capacitor's behavior (the system opens the URL, the page gets no window).
- Android cannot tell which frame called `window.open`: the check is on the URL the web view shows,
  so an iframe of another origin inside an app page with a user gesture can also get a child.
- A consent whose URL has no valid `redirect_uri` has no exact path: Android falls back to the Custom
  Tab and the App Link, and iOS opens it outside as before (the page gets no window). The Auth Tab
  and the exact path were not exercised in a real browser here; the intent the shell builds is
  checked in the unit test, what Chrome does with it is not.
- The child is never attached to a view. A page that depends on being visible in it (for example
  timers throttled in a hidden web view) can be slower to close; the 15 second limit covers a
  page that never does.

On Android the logic is `PopupPolicy` (pure Java: phases, host and path checks; JVM test
`generator/test/PopupPolicyTest.java`, run by the `test` job of `ci.yml`) and `ShellPopups` (the child
windows and the system browser; `ShellWebChromeClient.onCreateWindow` hands the call to it, and
`MainActivity.onNewIntent` offers it the App Link first). Both are templates in
`generator/templates/android/`, and the generated project carries `ShellPopupsTest.java`, a Robolectric
test on the real Bridge: the child is created only under the conditions above, the rest opens as
before, the return loads in the child and not in the page, a return outside the host or the prefix
is discarded, a cancelled browser closes the child, the scheme sign-in return still reloads the
page, a consent with a valid `redirect_uri` hands the browser its exact path (and any other consent,
none), and the real Auth Tab and Custom Tab intents are built and inspected (host and path of the Auth
Tab, the plain Custom Tab otherwise). The system browser is replaced by a recorder in the other cases,
so what a browser does with the intent is not exercised there. On iOS it is `ShellPopups` in
`SceneDelegate.swift`, a forwarding `WKUIDelegate` in front of Capacitor's.

### Icon and splash

The logo is kept as it came in `assets/logo.*`; what `@capacitor/assets` reads is composed from it
(with `sharp`, which that package brings) in `assets/composed/`. A logo is often a complete icon
already, an opaque rounded square with the mark in the middle, and shrinking that into the
adaptive icon leaves a disc with cut edges. So the generator looks at the logo first (after trimming
a transparent margin, which exports often leave), and the run prints which case it found:

- Own background: the edges are opaque and of one color (sampled at several points and two
  depths, so a thin outline is not taken for it), the corners agree or are transparent, and that
  color covers a good part of the picture. It becomes the background of icon and splash, and the
  mark is what differs from it, ignoring a 4% outer frame (a thin border is not the mark), cropped
  with 2% of padding. The mark is centered with the circle around its own pixels at 68% of the
  launcher's 72dp viewport, the visual weight of system app icons and well inside the 66dp circle
  Android guarantees to show. iOS and legacy Android get the same mark over the background, full
  bleed and without transparency.
- Full-bleed: the edges are opaque but not one color all over (gradient, outline, picture). The
  logo itself is the icon background and the iOS icon, the Android foreground is empty, the splash
  color is the mean of the edge samples when they agree and otherwise the most common one, and the splash shows the logo as a rounded tile.
- Transparent mark: anything else. The mark is everything that is not transparent (no outer frame is
  ignored, since there is no border to skip), on the theme color. A wide
  or tall logo with its own background lands here too (the picture is padded to a square with
  transparency), so it shows as a card on the theme color.

The splash takes that color, not the theme color, on both platforms and in
`SplashScreen.backgroundColor`; the theme color stays the status bar color after load. Over the
splash the status bar icons fit the splash color: the Android launch theme, and on iOS
`UIStatusBarStyle` in `Info.plist` plus `SceneDelegate.swift` while the overlay is up. When the
overlay leaves, the iOS status bar goes to the latest style the app asked for (see Status bar
style above).

If the composition fails for any logo, the run warns and hands the plain logo to `capacitor-assets`
on the theme color, without the splash entrance, instead of failing the generation.

The logo comes from the app owner, so it is only rasterized: its bytes never go into a command or
markup. Running `capacitor-assets` again by hand undoes part of this (the icon layers go back to
48dp sizes, the iOS launch image to the logo); generate the project again instead.

The mark comes in with a short entrance (opacity and size, ease-out):

- Android 12+: the launch theme sets `windowSplashScreenBackground` and a frame animation
  (`drawable-v31/splash_icon.xml`, 8 frames of 60 ms, 864 px in `drawable-xxhdpi`) as
  `windowSplashScreenAnimatedIcon`. Older versions get its last frame on the same color, and
  `splash.png` is sized so its mark is about as large.
- iOS: the launch storyboard cannot animate, so the launch image is the plain background and
  `SceneDelegate.swift` puts the mark (`SplashMark` in the asset catalog) over it for as long as
  the Capacitor splash's `launchShowDuration`, then fades like it does (0.2 s); it can outlast the
  plugin's splash by a moment, behind the same color. With Reduce Motion
  on, the mark is shown without the animation. The overlay does not take touches and does not
  hold up the web view.

| Flag | Effect |
|---|---|
| `--name "..."` | App name (default: platform name or page title) |
| `--app-id x.y.z` | Android application id and iOS bundle id |
| `--icon <file\|url>` | SVG or PNG icon (default: login logo, page icon as above, then the initials of the name). Any raster icon used (page, manifest, login logo or this flag) under 512 px logs a warning, as it will look soft; give an SVG or a 1024 px PNG |
| `--theme "#0f172a"` | Status bar color, and the splash and icon background when the logo has none of its own, as `#rgb` or `#rrggbb` |
| `iconBackground` (descriptor only) | Icon and splash background for a logo with none of its own, as a hex color. `mitra-shell.json` records it when the manifest `background_color` was used, so regenerating from that file keeps the color; `--theme` still wins |
| `--version 1.0`, `--build-number 1` | Marketing version and build number |
| `--out out` | Output root; the project goes to `<out>/<slug>/` |
| `--no-ios` | Android project only |
| `--ios-content-inset never\|automatic` | Force the iOS scroll view inset, see Safe area (default: `never` when the page declares `viewport-fit=cover`) |
| `--no-ios-keyboard-resize` | Keep the iOS web view at full height under the keyboard, see Keyboard resize (default: it ends at the keyboard) |
| `--callback scheme\|https` | How the sign-in returns to the shell, see below |
| `--associated-domains` | iOS `applinks` and `webcredentials` entitlements for the host |
| `--background-audio` | Keep the microphone of a live voice conversation with the screen locked: the `audio` background mode on iOS, a microphone foreground service on Android, see Background audio |
| `--auth-hosts a.com,b.com` | Extra hosts opened in the native sign-in session on iOS |
| `--consent-hosts a.com,b.com` | Extra hosts whose `window.open` is an account connection, see Account connections in a popup |
| `--return-path /oauth/` | Path prefix on the app host where a connection comes back (default `/oauth/`) |
| `--push`, `--google-services <file>` | Push notifications, see below |
| `--build debug\|release`, `--install` | Run Gradle, then `adb install` the APK |
| `--identity` | Resolve and print the identity only, generate nothing |
| `--debug`, `--verbose` | WebView debugging on Android; show the output of each command |

The run ends with `<out>/last.json` and, under GitHub Actions, the same fields as step outputs.
The generated project carries `mitra-shell.json`, the echo of what it was generated from.

### Sign-in return (`--callback`)

A redirect sign-in ends on the app host, in the system browser, with a single-use code in the
URL fragment. The shell has two ways to get it back into the WebView:

- `scheme` (default): the shell registers a URL scheme named after the app id. Nothing is served
  by the host; the published app includes `app-snippet/shell-return.html` (written into the
  generated project) in the head of its `index.html`, and that script hands the fragment to the
  scheme. Trade-off: another app installed on the device can register the same scheme and
  receive the single-use code.
- `https`: the return is a Universal Link (iOS) or App Link (Android) on the app host (on Android
  this keeps the App Link of the whole host, where the other mode claims only the return path of the
  account connections). It needs `--associated-domains` and the host serving `/.well-known/apple-app-site-association` and
  `/.well-known/assetlinks.json`, which `generator/wellknown.mjs` writes. No other app can claim
  the host, so this is the path for apps where the scheme risk is not acceptable.

On iOS 17.4 and later the sign-in runs in an `ASWebAuthenticationSession`, and a session that ends
without a return loads nothing, so a page that shows the sign-in in progress would stay that way.
The shell tells the page instead, with an event on `window` (no detail), and the page can put its
button back:

- `mitrashell:authcancel`: the user closed the sign-in sheet.
- `mitrashell:authfail`: the session failed or could not start, or came back with a URL that is not
  a return of the app. When it failed, the sign-in then opens in the system browser, as before.

```js
window.addEventListener("mitrashell:authcancel", resetSignInButton);
window.addEventListener("mitrashell:authfail", resetSignInButton);
```

Neither event exists on Android or below iOS 17.4, where the sign-in goes to the system browser.
The account connections in a popup do not use them: there the page sees the child window close.

### Keyboard form bar (iOS)

WKWebView puts a bar above the keyboard of a form field (previous, next, Done), which reads as a web
page. Every iOS shell hides it, with no page change and no Keyboard plugin: `SceneDelegate.swift`
gives the web view's content view a runtime subclass whose `inputAccessoryView` is nil. The
Capacitor Keyboard plugin can hide the bar too; the shell does it itself so that no app carries a
plugin, with its JS surface, for this one effect. Without the bar there is no Done button, so a
field whose keyboard has no return or go key closes only when the page blurs it. The wheel pickers
lose their Done too (a `<select>`, a date or time input in wheel mode): they close by a tap outside
them. If a WebKit release renames the content view, the bar just stays.

### Keyboard resize (iOS)

WKWebView keeps its height when the keyboard opens and reveals the focused field by scrolling the
whole page, so a screen that fills the viewport (a chat: header, messages, composer) slides up,
header included. Every iOS shell ends the web view at the top of the keyboard instead, with no
page change and no Keyboard plugin. What the page sees while the keyboard is up:

- The viewport is shorter: `window.innerHeight`, `100dvh`, `100vh` and `visualViewport.height` are
  the height above the keyboard, and the page lays out again (a `resize` event), with the
  keyboard's own animation. They come back when it closes.
- `visualViewport.offsetTop` stays 0 and the document is not scrolled for the keyboard, so a page
  that compensated with `visualViewport` (offsetting its top, or sizing itself to
  `visualViewport.height`) keeps working: the compensation is now zero.
- `env(safe-area-inset-bottom)` is 0, since the web view no longer reaches the home indicator.
- A page that scrolls keeps scrolling, and WebKit still brings the field into view. Only a
  document that fits the shorter viewport is kept at the top. If the shorter viewport leaves the
  focused field out of view, the shell scrolls to it once, about half a second after the keyboard
  starts (`scrollIntoView`, which also reaches a field inside a box with its own overflow). An
  iframe, an element with no size and a field taller than the viewport are left where they are.

A screen locked to the viewport height should let its content scroll inside it (`overflow: auto`)
or fit above the keyboard: content it clips with `overflow: hidden` is reached only by that one
scroll. The keyboard only counts when it is docked across the bottom: a floating, undocked or
split keyboard on iPad, a hardware keyboard and the keyboard of the sign-in sheet leave the page
as it is. The window behind the web view takes the page background, which is what shows around the
rounded corners of the keyboard.

`--no-ios-keyboard-resize` (or `iosKeyboardResize: false` in the descriptor) keeps WebKit's
behavior; the choice is written to `plugins.MitraShell.keyboardResize` and to `mitra-shell.json`
only when it is off.

### Push (`--push`)

Off by default, and then the push plugin is left out of the native projects.

- iOS needs only the flag: `aps-environment` entitlement, the `AppDelegate` hooks, and the Push
  Notifications capability on the bundle id (`asc.mjs ensure-bundle-id --push`).
- Android uses Firebase and also needs the Firebase config of that application id:
  `--google-services <google-services.json>` locally, the `GOOGLE_SERVICES_JSON_B64` secret in
  CI. Without a config that lists the app, Android is built without the plugin, because the
  plugin crashes the app on `register()` when Firebase is not configured. The shared CI config
  only covers the apps registered in it, so an app missing there is built without push; a file
  given with `--google-services` that does not list the app fails the generation. The file must
  not be committed.
- The published page calls Capacitor's `PushNotifications` plugin through the bridge; the
  generated project has an example in `app-snippet/push-example.html`.

## Workflows

The jobs that read the `stores` secrets on Linux (`version` and `android` in `shell.yml`, and
`asc-info.yml`) run on the org's `mitra-delivery` runner group, which has no Android SDK: the
Android job installs it (`android-actions/setup-android`). `ci.yml` runs on `mitra-ci`. iOS runs on
`MACOS_RUNNER` (default `macos-26`).

### App shell (`shell.yml`)

Manual run, one app per run: generates the shell, builds Android (signed AAB and APK, optional
Play upload) and iOS (unsigned device build, or cloud-signed archive uploaded to TestFlight).
The build number is resolved once, in its own job before both builds: one above the highest build
in App Store Connect and the highest versionCode in any Play track, and never below the run number,
so both stores get the same, always increasing number. A run that uploads to a store whose history
cannot be read stops, unless `build_number` is given.

| Input | Default | Meaning |
|---|---|---|
| `app` | empty | Platform app UUID |
| `api` | `https://beta.mitralab.io` | Platform API base URL |
| `url` | empty | Published URL, when there is no app UUID |
| `app_id` | empty | Override the application id and bundle id |
| `version` | `1.0` | Marketing version |
| `build_number` | empty | Override the build number |
| `associated_domains` | `true` | Declare `applinks` and `webcredentials` on iOS |
| `background_audio` | `false` | Generate with `--background-audio` (iOS and Android) |
| `push` | `false` | Generate with `--push` |
| `upload_testflight` | `false` | Archive, upload to TestFlight and add the build to the group |
| `testflight_group` | `Mitra shell testers` | TestFlight group that receives the build |
| `upload_play` | `true` | Upload the AAB when the service account secret exists |
| `play_track` | `internal` | Play track for the upload |

Secrets: `ANDROID_KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`,
`PLAY_SERVICE_ACCOUNT_JSON_B64`, `GOOGLE_SERVICES_JSON_B64`, `ASC_KEY_ID`, `ASC_ISSUER_ID`,
`ASC_KEY_P8_B64`, `APPLE_TEAM_ID`. Without the keystore the Android build is unsigned; without
the Play service account the upload is skipped with a warning.

The store secrets live in the `stores` environment of the repository, not at repository level:
the jobs that read them declare `environment: stores`. Restrict that environment to the `main`
branch (deployment branches), so a workflow changed on another branch cannot read them.

### ASC tools (`asc-info.yml`)

Manual run: prints what App Store Connect has for a bundle id and does small TestFlight chores.

| Input | Meaning |
|---|---|
| `bundle_id` (required) | Bundle id to inspect |
| `build` with `group` | Add that build to that TestFlight group |
| `copy_testers_from` | Bundle id whose internal testers are copied to `bundle_id` |
| `expire` | Build number to expire in TestFlight |

Secrets, from the `stores` environment: `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8_B64`.

### Push smoke test (`push-smoke-ios.yml`)

Manual run: generates a shell with `--push`, builds it for the iOS simulator and delivers a
notification with `simctl push`; the run fails when the page does not receive it. Inputs: `app`
(required, platform app UUID used for the identity) and `api`. Reads no secret.

### CI (`ci.yml`)

Runs on pull requests and on `main`. `test` runs `npm test` (syntax check of the generator
scripts and their self-tests) and the JVM tests of the permission gate and the popup policy. `build` generates a shell
from a local fixture page, without the platform API or any secret, checks the generated project,
including the icon drawn from the initials, and runs the unit tests of the generated Android
project (`testDebugUnitTest`); it builds no APK.

### Workflow of the generated project

`generator/templates/github/mobile.yml` is copied to `.github/workflows/mobile.yml` of every
generated project, for when that project lives in its own repository. It reads
`ANDROID_KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `ASC_KEY_ID`,
`ASC_ISSUER_ID`, `ASC_KEY_P8_B64` and `APPLE_TEAM_ID`.

## Known limits

- Three steps stay manual in each store account: creating the app record in App Store Connect
  (no public API), creating the app and uploading its first AAB in Play Console, and creating
  the Play service account key.
- Cloud signing needs an App Store Connect API key with the Admin role; an App Manager key
  archives but fails on export.
- App Store Connect only accepts builds made with the iOS 26 SDK, hence the `macos-26` runner.
- The shell loads the published URL; offline it shows a fallback page only. An App Store
  submission still has to meet the store rules for apps that are mostly a WebView.
- Sign-in works only when the published app starts it in redirect mode inside the shell and, for
  `--callback scheme`, carries the return snippet.
- `--callback https` on iOS needs iOS 17.4 or later; below that the sign-in opens in the system
  browser. The platform does not write the association files on publish yet, so they are an
  operator copy that the next publish of the app can remove.
- For apps installed from Google Play, `assetlinks.json` must list the Play App Signing
  certificate, not only the upload key.
- Push: sending is not in this repo (it takes an APNs key and a Firebase service account), and
  delivery with real credentials has not been exercised on either platform.
