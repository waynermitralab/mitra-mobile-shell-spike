#!/usr/bin/env node
// Generate a Capacitor shell for ONE app published by the platform.
//
//   node generator/generate.mjs --app <app uuid> [--api https://beta.mitralab.io] [...flags]
//   node generator/generate.mjs --url https://<published host> [...flags]
//   node generator/generate.mjs --descriptor path/to/app.json [flags override the file]
//   node generator/generate.mjs --selftest
//
//   flags: [--name "..."] [--app-id x.y.z] [--icon <file|url>] [--theme "#0f172a"] [--version 1.0]
//          [--build-number 1] [--associated-domains] [--auth-hosts a.com,b.com] [--out out]
//          [--callback scheme|https] [--build debug|release] [--install] [--no-ios] [--debug]
//          [--verbose] [--identity] [--push] [--google-services <google-services.json>]
//
// Source of truth for the identity is the platform: GET {api}/code-studio/api/v1/apps/{app}/info
// (public, no token) gives name, published origins, accent color and the login logo. The page
// itself is only scraped for what the API does not carry yet (the icon when there is no login
// logo, title and theme-color in --url mode). An app without an icon gets one drawn from its
// initials on the theme color. Then it writes package.json +
// capacitor.config.json + www/ + assets/, npm ci,
// cap add android/ios, apply the native patches (App Link intent-filter, onNewIntent
// in MainActivity, release signing from env, exempt encryption, optional Associated Domains,
// SceneDelegate with the sign-in session and Universal Link handling on iOS),
// compose the icon and splash from the logo (composeBrand), generate icons and splash, cap sync,
// copy the CI workflow. Optionally builds and installs
// the Android APK on the connected device or emulator.
//
// Sign-in return (--callback): the SDK ends a redirect sign-in on the app host, in the system
// browser, with the single-use code in the URL fragment. "scheme" (default) brings it back to the
// shell by a URL scheme named after the app id, which needs nothing on the host: the app page
// bounces the fragment to the scheme (the snippet in app-snippet/, which the app must include).
// "https" is the Universal Link callback, which needs the host to serve the
// apple-app-site-association file and --associated-domains.
//
// Push (--push): off by default, and then the push plugin is left out of the native projects, so a
// page that calls it gets "not implemented". iOS needs only the flag (aps-environment entitlement
// and the AppDelegate hooks). Android also needs the Firebase config of this application id
// (--google-services or GOOGLE_SERVICES_JSON_B64); without it Android is built without the plugin,
// because the plugin crashes the app on register() when Firebase is not configured.

import { execFileSync, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, writeFileSync, cpSync, readdirSync, rmSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, resolve, extname } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const templateRoot = resolve(here, "..");

// ---------- CLI ----------
const args = process.argv.slice(2);
const flags = {};
for (let i = 0; i < args.length; i++) {
  const a = args[i];
  if (!a.startsWith("--")) continue;
  const key = a.slice(2);
  const next = args[i + 1];
  if (next === undefined || next.startsWith("--")) flags[key] = true;
  else { flags[key] = next; i++; }
}

const HEX_COLOR = /^#[0-9a-fA-F]{3}([0-9a-fA-F]{3})?$/;
const DEFAULT_THEME_COLOR = "#0f172a";

// Size of the mark's bounding circle, as a fraction of the picture it is drawn on.
// Adaptive icon: capacitor-assets wraps the foreground in a 16.7% inset, so the PNG is the 72dp
// window the launcher masks, and only a 66dp circle of it is guaranteed to show.
const ADAPTIVE_ICON_CIRCLE = (66 / 72) * 0.96;
// Android 12+ splash icon: the system masks it to the central 2/3 circle of its 288dp frame; the
// mark takes 80% of that circle so it does not look larger than on iOS.
const SPLASH_ICON_CIRCLE = (2 / 3) * 0.8;
// splash.png is only what Android shows below 12 (and if the system splash fails). The picture is
// scaled to the height of a portrait phone and cropped on the sides, so this fraction of its side
// lands at about the same 37.5% of the screen width as the 12+ icon on a 9:18 screen.
const SPLASH_PNG_CIRCLE = 0.18;
// xxhdpi: 288dp of the splash icon at 3 px per dp. Lower densities scale it down. An xxxhdpi screen
// (4 px per dp) decodes the same resource upscaled to 1152 px, about 5.3 MB per frame against 3 MB
// at 864, and all frames are held while the animation runs. Shipping 1152 px frames would make the
// upscaled case sharp for twice the memory on every device, so 864 is the middle ground.
const SPLASH_ICON_PX = 864;
// Enough for a 3x iPhone (about 150pt) and an iPad (about 290pt at 2x), so SceneDelegate only
// ever scales it down.
const IOS_MARK_CIRCLE_PX = 768;
const ENTRANCE_FRAMES = 8;
const ENTRANCE_FRAME_MS = 60;
// Of the side, ignored when looking for the mark: a thin border around the logo is not the mark.
const MARK_FRAME = 0.04;
const BACKGROUND_TOLERANCE = 12;
const MARK_TOLERANCE = 28;
const MARK_ALPHA = 40;
// A background that covers less of the picture than this is a gradient or a picture, not a color.
const MIN_BACKGROUND_SHARE = 0.25;
// Fringes and soft shadows around the mark are too faint to count as mark, but cut off they leave a hard edge.
const MARK_PADDING = 0.02;
// Names SceneDelegate.swift calls on its own types: the view controller members by receiver, plus
// the ones that live in other types of the file. Definitions are var, func or init, never a local let.
const SWIFT_MEMBERS = ["appHost", "callbackScheme", "appURL", "loadInShell", "showSplashEntrance", "play"];

if (flags.selftest) { selftest(); process.exit(0); }

const descriptor = flags.descriptor ? JSON.parse(readFileSync(resolve(flags.descriptor), "utf8")) : {};
const appUuid = flags.app || descriptor.app;
const apiBase = String(flags.api || descriptor.api || process.env.MITRA_API_URL || "https://beta.mitralab.io").replace(/\/$/, "");
const outRoot = resolve(flags.out || descriptor.out || join(templateRoot, "out"));
const wantIos = !flags["no-ios"] && descriptor.ios !== false;

// ---------- 1. Resolve the app identity ----------
// Platform first: the public app info carries name, origins, accent color and login logo.
const info = appUuid ? await fetchAppInfo(apiBase, appUuid) : null;
const url = flags.url || descriptor.url || (info && pickOrigin(info.origins));
if (!url) fail("Missing --app <uuid> (platform app) or --url <published URL>, or a descriptor with one of them.");
if (info && !info.origins?.length) fail(`App ${appUuid} has no published origin yet; publish it first or pass --url.`);
const page = await fetchText(url);
const scraped = scrape(page, url);
const appName = clean(flags.name || descriptor.name || info?.name || scraped.title || new URL(url).hostname);
const slug = slugify(appName);
const appId = flags["app-id"] || descriptor.appId || `cloud.${brandSegment(info?.brand)}.${packageSegment(slug)}`;
let themeColor;
try {
  themeColor = resolveThemeColor(flags.theme || descriptor.themeColor, [info?.accentColor, scraped.themeColor]);
} catch (e) { fail(e.message); }
// Both end up unquoted or inside a Groovy string in build.gradle and in the Xcode project.
const versionName = String(flags.version || descriptor.version || "1.0");
if (!isVersionName(versionName)) fail(`--version must look like 1, 1.2 or 1.2.3, got ${JSON.stringify(versionName)}`);
const buildNumber = Number(flags["build-number"] || descriptor.buildNumber || 1);
if (!Number.isInteger(buildNumber) || buildNumber < 1) fail("--build-number must be a positive integer");
const associatedDomains = Boolean(flags["associated-domains"] || descriptor.associatedDomains);
const callback = String(flags.callback || descriptor.callback || "scheme");
if (!["scheme", "https"].includes(callback)) fail(`--callback must be scheme or https, got ${callback}`);
const wantPush = Boolean(flags.push || descriptor.push);
const iconSource = flags.icon || descriptor.icon || info?.logoUrl || scraped.icon;
const host = new URL(url).host;
// The host is written into AndroidManifest.xml and the iOS entitlements as is.
if (!isPlainHost(host)) fail(`Unsupported host in the app URL: ${JSON.stringify(host)}`);
// A bundle id is a valid URL scheme (letters, digits, dot, hyphen), unique per app on the device.
const callbackScheme = callback === "scheme" ? appId.toLowerCase() : undefined;
// Hosts whose top-level navigation is a sign-in: the shell opens them in a native auth session
// (iOS) so the return lands in the WebView. The platform API host is where the SDK redirect
// starts; the providers are where it continues.
const DEFAULT_AUTH_HOSTS = ["accounts.google.com", "login.microsoftonline.com", "login.live.com", "appleid.apple.com"];
// The auth page the SDK starts on is named by the published page itself (window.__mitraEnv.authPageUrl)
// and is not always on the API host: the Infusion brand serves it from app.infusion.cloud.
const authPageHost = (page.match(/"authPageUrl"\s*:\s*"(https?:\/\/[^"\/]+)/) || [])[1];
const authHosts = [...new Set([
  new URL(apiBase).host,
  ...(authPageHost ? [new URL(authPageHost).host] : []),
  ...(descriptor.authHosts || []),
  ...String(flags["auth-hosts"] || "").split(",").map((s) => s.trim()).filter(Boolean),
  ...DEFAULT_AUTH_HOSTS
].map((h) => h.toLowerCase()))];
const projectDir = join(outRoot, slug);

validateAppId(appId);
const googleServices = wantPush ? readGoogleServices(appId) : null;
const pushIos = wantPush && wantIos;
const pushAndroid = Boolean(googleServices);
if (info) log(`source   ${apiBase}/code-studio/api/v1/apps/${appUuid}/info (origins: ${info.origins.join(", ")})`);
log(`app      ${appName}`);
log(`slug     ${slug}`);
log(`appId    ${appId}`);
log(`url      ${url}`);
log(`theme    ${themeColor}`);
log(`icon     ${iconSource ? describe(iconSource) : `(none, drawing the initials ${initials(appName, slug)})`}`);
log(`push     ${wantPush ? `ios ${pushIos ? "on" : "off"}, android ${pushAndroid ? "on" : "off (no Firebase config for " + appId + ")"}` : "off"}`);
log(`out      ${projectDir}`);

// --identity: resolve and print the identity only (CI uses it to ask App Store Connect for the
// next build number before generating, since the bundle id derives from the app name).
if (flags.identity) {
  const identity = { slug, appName, appId, url, host, authHosts, callback, callbackScheme, push: wantPush };
  console.log(JSON.stringify(identity));
  githubOutputs(identity);
  process.exit(0);
}

// ---------- 2. Scaffold the web side ----------
if (existsSync(projectDir)) {
  log("out dir exists, removing it for a clean generation");
  rmSync(projectDir, { recursive: true, force: true });
}
mkdirSync(join(projectDir, "www"), { recursive: true });
mkdirSync(join(projectDir, "assets"), { recursive: true });

const templatePkg = JSON.parse(readFileSync(join(templateRoot, "package.json"), "utf8"));
const pkg = {
  name: `mitra-shell-${slug}`,
  version: `${versionName}.${buildNumber}`,
  private: true,
  type: "commonjs",
  description: `Capacitor shell for ${appName} (${url}), generated by mitra-mobile-shell/generator`,
  // No "assets" script: running capacitor-assets again would undo finishIcons, so the project is
  // generated again instead.
  scripts: {
    sync: "cap sync"
  },
  dependencies: templatePkg.dependencies
};
writeJson(join(projectDir, "package.json"), pkg);
cpSync(join(templateRoot, "package-lock.json"), join(projectDir, "package-lock.json"));
cpSync(join(here, "templates", "gitignore"), join(projectDir, ".gitignore"));

// The published page is not ours, so a plugin is in a platform only when that platform is ready
// for it: the push plugin crashes Android on register() without Firebase, and on iOS it would
// ship code for an entitlement the app does not have.
const PUSH_PLUGIN = "@capacitor/push-notifications";
const capPlugins = Object.keys(templatePkg.dependencies)
  .filter((d) => d.startsWith("@capacitor/") && !["android", "ios", "core", "cli", "assets"].includes(d.split("/")[1]));
const pluginsFor = (push) => capPlugins.filter((d) => push || d !== PUSH_PLUGIN);

writeFileSync(join(projectDir, "www", "index.html"), offlinePage(appName, themeColor));

// Icon: the source logo is kept in assets/ as it came. The files capacitor-assets reads are
// composed from it in assets/composed/ (see composeBrand), which needs sharp from npm ci.
const logoPath = await materializeIcon(iconSource, join(projectDir, "assets"), initialsLogoSvg(initials(appName, slug), themeColor));
log(`logo     ${logoPath}`);
run("npm", ["ci", "--no-audit", "--no-fund", "--loglevel=error"], projectDir);
// Logos are third-party input and any logo used to generate an app, so a composition that fails
// falls back to handing the plain logo to capacitor-assets, without the entrance.
let brand;
try {
  brand = await composeBrand(projectDir, logoPath, normalizeHex(themeColor));
} catch (e) {
  log(`WARNING  the icon could not be composed (${e.message}); using the logo as it is on the theme color, without the splash entrance`);
  brand = useLogoAsIs(projectDir, logoPath, normalizeHex(themeColor));
}
log(`brand    ${{ "fallback": "plain logo on the theme color", "own-background": "the logo has its own background", "transparent-mark": "transparent mark on the theme color", "full-bleed": "full-bleed logo, used as the whole icon" }[brand.branch]}; splash color ${brand.bg}${brand.branch === "fallback" ? "" : `, mark box ${brand.box.w}x${brand.box.h} of ${brand.side}${brand.markFound || brand.branch === "full-bleed" ? "" : " (none found, whole image)"}`}`);

writeJson(join(projectDir, "capacitor.config.json"), {
  appId,
  appName,
  webDir: "www",
  server: { url, cleartext: false },
  ios: { contentInset: "automatic", includePlugins: pluginsFor(pushIos) },
  android: { allowMixedContent: false, webContentsDebuggingEnabled: Boolean(flags.debug || descriptor.debug), includePlugins: pluginsFor(pushAndroid) },
  plugins: {
    // The splash sits behind the mark, so it takes the color behind the mark; the theme color is
    // the app chrome after load (status bar).
    SplashScreen: { launchShowDuration: 1500, launchAutoHide: true, backgroundColor: brand.bg, showSpinner: false },
    StatusBar: { style: isDark(themeColor) ? "DARK" : "LIGHT", backgroundColor: themeColor },
    MitraShell: { authHosts, splashBackground: brand.bg, splashDark: brand.isDarkBg, splashMarkCircle: IOS_MARK_CIRCLE_PX, ...(callbackScheme ? { callbackScheme } : {}) },
    ...(wantPush ? { PushNotifications: { presentationOptions: ["badge", "sound", "alert"] } } : {})
  }
});

// Descriptor echo, so the generated project is reproducible from itself.
writeJson(join(projectDir, "mitra-shell.json"), {
  app: appUuid, api: appUuid ? apiBase : undefined,
  url, name: appName, appId, themeColor, version: versionName, buildNumber, associatedDomains, authHosts, callback,
  push: wantPush ? { ios: pushIos, android: pushAndroid } : false,
  icon: iconSource && !String(iconSource).startsWith("data:") ? iconSource : undefined,
  generatedAt: new Date().toISOString()
});

// ---------- 3. Native projects ----------
run("npx", ["cap", "add", "android"], projectDir);
if (wantIos) run("npx", ["cap", "add", "ios"], projectDir);

// ---------- 4. Android patches ----------
patchAndroid(projectDir, { appId, host, versionName, buildNumber, callbackScheme, googleServices, brand });

// ---------- 5. iOS patches ----------
if (wantIos) patchIos(projectDir, { appId, host, versionName, buildNumber, associatedDomains, callbackScheme, push: pushIos, brand });
if (wantIos) log(`ios      SceneDelegate with sign-in session for ${authHosts.join(", ")}, callback ${callbackScheme ? callbackScheme + "://" : "https://" + host + "/"}`);

// ---------- 6. Assets, sync, CI ----------
run("npx", capacitorAssetsArgs(brand), projectDir);
if (brand.branch !== "fallback") await finishIcons(projectDir, brand, wantIos);
run("npx", ["cap", "sync"], projectDir);
mkdirSync(join(projectDir, ".github", "workflows"), { recursive: true });
cpSync(join(here, "templates", "github", "mobile.yml"), join(projectDir, ".github", "workflows", "mobile.yml"));
writeFileSync(join(projectDir, "README.md"), readme({ appName, appId, url, slug, associatedDomains, callbackScheme, pushIos, pushAndroid }));
if (wantPush) {
  mkdirSync(join(projectDir, "app-snippet"), { recursive: true });
  writeFileSync(join(projectDir, "app-snippet", "push-example.html"), pushExampleSnippet());
  log("snippet  app-snippet/push-example.html (how the app page asks for the push token)");
}

// What the published app itself has to carry for the return by scheme: the snippet for the head
// of its index.html (the host serves nothing for the shell). The assetlinks.json Android reads
// comes from generator/wellknown.mjs and goes to public/.well-known/ of the app.
if (callbackScheme) {
  mkdirSync(join(projectDir, "app-snippet"), { recursive: true });
  writeFileSync(join(projectDir, "app-snippet", "shell-return.html"), shellReturnSnippet(callbackScheme));
  log(`snippet  app-snippet/shell-return.html (the app includes it in index.html; return scheme ${callbackScheme}://)`);
}

// ---------- 7. Optional: build and install ----------
const build = flags.build || descriptor.build;
let apk;
if (build) {
  const task = build === "release" ? "assembleRelease" : "assembleDebug";
  run("./gradlew", ["--no-daemon", "-q", task], join(projectDir, "android"));
  apk = findApk(join(projectDir, "android", "app", "build", "outputs", "apk", build === "release" ? "release" : "debug"));
  log(`apk      ${apk}`);
  if (flags.install || descriptor.install) {
    run("adb", ["install", "-r", apk], projectDir);
    log(`installed ${appId} on the connected device`);
  }
}

// Machine-readable summary for the caller (CI reads it to name artifacts and find the project).
const summary = { slug, appName, appId, url, host, projectDir, apk, pushIos, pushAndroid };
writeJson(join(outRoot, "last.json"), summary);
githubOutputs(summary);
log("done");

// ======================================================================
function scrape(html, baseUrl) {
  const title = (html.match(/<title[^>]*>([^<]*)<\/title>/i) || [])[1];
  const themeColor = (html.match(/<meta[^>]+name=["']theme-color["'][^>]+content=["']([^"']+)["']/i) || [])[1];
  // Prefer the apple-touch-icon (square, no transparency expected), then any rel=icon.
  const links = [...html.matchAll(/<link\s+[^>]*>/gi)].map((m) => m[0]);
  const pick = (test) => {
    for (const tag of links) {
      const rel = (tag.match(/rel=["']([^"']+)["']/i) || [])[1] || "";
      if (!test(rel.toLowerCase())) continue;
      const href = (tag.match(/href=["']([^"']+)["']/i) || [])[1];
      if (href) return href.startsWith("data:") ? href : new URL(href, baseUrl).toString();
    }
  };
  const icon = pick((r) => r.includes("apple-touch-icon")) || pick((r) => r.split(/\s+/).includes("icon"));
  return { title: title && decodeEntities(title.trim()), themeColor, icon };
}

async function materializeIcon(source, assetsDir, fallbackSvg) {
  if (!source) {
    writeFileSync(join(assetsDir, "logo.svg"), fallbackSvg);
    return join(assetsDir, "logo.svg");
  }
  let bytes;
  let ext;
  if (String(source).startsWith("data:")) {
    const m = String(source).match(/^data:([^;,]+)(;base64)?,(.*)$/s);
    if (!m) fail("Unreadable data: URI in the icon");
    ext = mimeToExt(m[1]);
    bytes = m[2] ? Buffer.from(m[3], "base64") : Buffer.from(decodeURIComponent(m[3]), "utf8");
  } else if (/^https?:/.test(source)) {
    const res = await fetch(source);
    if (!res.ok) fail(`Icon download failed: ${res.status} ${source}`);
    ext = mimeToExt(res.headers.get("content-type") || "") || extname(new URL(source).pathname).slice(1) || "png";
    bytes = Buffer.from(await res.arrayBuffer());
  } else {
    // A path is only read when the caller gave it; the platform and the page are third parties.
    if (source !== (flags.icon || descriptor.icon)) fail(`Icon must be an http(s) or data: URL, got ${JSON.stringify(source)}`);
    const p = resolve(source);
    ext = extname(p).slice(1).toLowerCase();
    bytes = readFileSync(p);
  }
  if (!["svg", "png"].includes(ext)) fail(`Icon must be SVG or PNG, got ${ext}`);
  const target = join(assetsDir, `logo.${ext}`);
  writeFileSync(target, bytes);
  return target;
}

// The logo is third-party input: it is only rasterized here, never interpolated into a command or
// markup. Everything capacitor-assets and the native projects need is composed from that raster:
// the icon layers, the splash picture, the frames of the Android entrance and the iOS mark.
async function composeBrand(dir, logoPath, theme) {
  const sharp = sharpFor(dir);
  const side = 1024;
  const raw = { raw: { width: side, height: side, channels: 4 } };
  const clear = { r: 0, g: 0, b: 0, alpha: 0 };
  const pixels = (buf) => (x, y) => { const o = (y * side + x) * 4; return [buf[o], buf[o + 1], buf[o + 2], buf[o + 3]]; };
  let data;
  try {
    data = await rasterizeLogo(sharp, logoPath, side);
  } catch (e) {
    throw new Error(`the icon could not be read as an image: ${e.message}`);
  }
  let px = pixels(data);
  let kind = analyzeLogo(side, px);
  // A transparent margin (a Figma export leaves one) puts the edge samples outside the logo. Trim
  // to what is drawn and re-square it; the trimmed picture is used only when it turns out to be a
  // background with a mark on it, so a small transparent mark is not blown up into a "background".
  const drawn = findMarkBox(side, (x, y) => px(x, y)[3] > MARK_ALPHA, 0);
  if (drawn && (drawn.w < side * 0.99 || drawn.h < side * 0.99)) {
    const squared = await sharp(data, raw).extract({ left: drawn.x, top: drawn.y, width: drawn.w, height: drawn.h })
      .resize(side, side, { fit: "contain", background: clear }).ensureAlpha().raw().toBuffer();
    const trimmedPx = pixels(squared);
    const trimmedKind = analyzeLogo(side, trimmedPx);
    if (trimmedKind.kind === "own-background") {
      const rgb = hexToRgb(trimmedKind.color);
      if (findMarkBox(side, (x, y) => isMarkOnBackground(trimmedPx(x, y), rgb), MARK_FRAME)) { data = squared; px = trimmedPx; kind = trimmedKind; }
    }
  }
  const fullBleed = kind.kind === "full-bleed";
  const bg = kind.color || theme;

  let plan = { hit: null, box: { x: 0, y: 0, w: side, h: side }, circle: Math.hypot(side, side) };
  let cropped, foreground, iconOnly, iconBackground;
  if (fullBleed) {
    // The logo is the whole icon (a gradient, a picture, an outline to the edge): cropping a
    // "mark" out of it would cut it, so it is the icon background and the foreground stays empty.
    // On the splash it is a rounded tile; the corners of the rounded-square icons are ignored.
    const flat = await sharp(data, raw).flatten({ background: bg }).png().toBuffer();
    iconOnly = flat;
    iconBackground = flat;
    foreground = await sharp({ create: { width: side, height: side, channels: 4, background: clear } }).png().toBuffer();
    const radius = Math.round(side * 0.224);
    const mask = Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${side}" height="${side}"><rect width="${side}" height="${side}" rx="${radius}" fill="#fff"/></svg>`);
    cropped = await sharp(flat).ensureAlpha().composite([{ input: mask, blend: "dest-in" }]).png().toBuffer();
  } else {
    const rgb = hexToRgb(bg);
    const isMark = kind.kind === "own-background"
      ? (x, y) => isMarkOnBackground(px(x, y), rgb)
      : (x, y) => px(x, y)[3] > MARK_ALPHA;
    // The circle is sized from the pixels of the mark, not from the corners of its box: a round
    // mark has far less to fit in the safe zone than its square box.
    plan = planMark(side, isMark, MARK_FRAME);
    let base = sharp(data, raw);
    if (kind.kind === "own-background") base = base.flatten({ background: bg });
    const region = { left: plan.box.x, top: plan.box.y, width: plan.box.w, height: plan.box.h };
    if (kind.kind === "own-background") {
      // Only the mark is laid over the flat background: the near-background pixels around it (a
      // gradient within tolerance, noise) would show as a faint rectangle.
      const cut = await base.extract(region).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
      clearBackground(cut.data, rgb, BACKGROUND_TOLERANCE);
      cropped = await sharp(cut.data, { raw: { width: cut.info.width, height: cut.info.height, channels: 4 } }).png().toBuffer();
    } else {
      cropped = await base.extract(region).png().toBuffer();
    }
  }
  const { box, circle } = plan;
  const markAt = async (diameter) => {
    const scale = diameter / circle;
    return sharp(cropped).resize({ width: Math.max(1, Math.round(box.w * scale)), height: Math.max(1, Math.round(box.h * scale)) }).png().toBuffer();
  };
  // Never larger than the canvas, whatever the proportions of the mark, or sharp refuses to composite.
  const onto = async (mark, canvas, background) => sharp({ create: { width: canvas, height: canvas, channels: 4, background } })
    .composite([{ input: await sharp(mark).resize({ width: canvas, height: canvas, fit: "inside", withoutEnlargement: true }).png().toBuffer(), gravity: "center" }])
    .png().toBuffer();

  if (!fullBleed) {
    foreground = await onto(await markAt(side * ADAPTIVE_ICON_CIRCLE), side, clear);
    // The App Store rejects an icon with an alpha channel, and legacy Android icons are not masked.
    iconOnly = await sharp(foreground).flatten({ background: bg }).png().toBuffer();
    iconBackground = await sharp({ create: { width: side, height: side, channels: 3, background: bg } }).png().toBuffer();
  }
  const splash = await onto(await markAt(2732 * SPLASH_PNG_CIRCLE), 2732, bg);

  // linear() runs before ensureAlpha() in a pipeline, so the alpha band has to exist already.
  const iconMark = await sharp(await markAt(SPLASH_ICON_PX * SPLASH_ICON_CIRCLE)).ensureAlpha().png().toBuffer();
  const iconMarkSize = await sharp(iconMark).metadata();
  const frames = [];
  for (let i = 0; i < ENTRANCE_FRAMES; i++) {
    const { alpha, scale } = entranceFrame(i, ENTRANCE_FRAMES);
    const frame = await sharp(iconMark)
      .resize({ width: Math.max(1, Math.round(iconMarkSize.width * scale)), height: Math.max(1, Math.round(iconMarkSize.height * scale)) })
      .linear([1, 1, 1, alpha], [0, 0, 0, 0]).png().toBuffer();
    frames.push(await onto(frame, SPLASH_ICON_PX, clear));
  }
  const iosMark = await markAt(IOS_MARK_CIRCLE_PX);

  const out = join(dir, "assets", "composed");
  mkdirSync(out, { recursive: true });
  for (const [name, bytes] of [["icon-only", iconOnly], ["icon-foreground", foreground], ["icon-background", iconBackground],
    ["splash", splash], ["splash-dark", splash]]) {
    writeFileSync(join(out, `${name}.png`), bytes);
  }
  return { bg, branch: kind.kind, isDarkBg: isDark(bg), side, box, markFound: Boolean(plan.hit), foreground, iconBackground, frames, iosMark };
}

// The generator before the composition existed: the logo goes to capacitor-assets as it is, which
// draws icon and splash on the theme color itself. No frames, so no entrance on either platform.
function useLogoAsIs(dir, logoPath, theme) {
  const out = join(dir, "assets", "composed");
  rmSync(out, { recursive: true, force: true });
  mkdirSync(out, { recursive: true });
  writeFileSync(join(out, `logo${extname(logoPath)}`), readFileSync(logoPath));
  return { bg: theme, branch: "fallback", isDarkBg: isDark(theme), side: 0, box: { w: 0, h: 0 }, markFound: false, frames: [], iosMark: null };
}
function capacitorAssetsArgs(brand) {
  const args = ["capacitor-assets", "generate", "--assetPath", "assets/composed"];
  if (brand.branch === "fallback") {
    for (const flag of ["iconBackgroundColor", "iconBackgroundColorDark", "splashBackgroundColor", "splashBackgroundColorDark"]) args.push(`--${flag}`, brand.bg);
  }
  return args;
}

// sharp is a dependency of @capacitor/assets, so it is in the generated project after npm ci.
function sharpFor(dir) {
  return createRequire(join(dir, "package.json"))("sharp");
}

// Raw RGBA, square. A vector logo is rendered at the target size, not scaled up from 72 dpi.
async function rasterizeLogo(sharp, file, side) {
  const bytes = readFileSync(file);
  let density;
  if (extname(file) === ".svg") {
    const meta = await sharp(bytes).metadata();
    density = Math.min(4800, Math.max(72, Math.ceil((72 * side) / Math.max(meta.width, meta.height))));
  }
  return sharp(bytes, density ? { density } : {})
    .resize(side, side, { fit: "contain", background: { r: 0, g: 0, b: 0, alpha: 0 } })
    .ensureAlpha().raw().toBuffer();
}

// capacitor-assets draws both adaptive layers from the legacy icon sizes (48dp), which the launcher
// then shows at 72dp. The layers are rewritten at 108dp so they are never upscaled (the background
// matters for a full-bleed logo, which is all in that layer), and
// the iOS launch image becomes the plain background: the mark comes in from SceneDelegate, and a
// launch image that already showed it would have it vanish and fade in again.
async function finishIcons(dir, brand, wantIos) {
  const sharp = sharpFor(dir);
  const res = join(dir, "android", "app", "src", "main", "res");
  for (const [density, px] of Object.entries({ ldpi: 81, mdpi: 108, hdpi: 162, xhdpi: 216, xxhdpi: 324, xxxhdpi: 432 })) {
    for (const [layer, source] of [["foreground", brand.foreground], ["background", brand.iconBackground]]) {
      const file = join(res, `mipmap-${density}`, `ic_launcher_${layer}.png`);
      if (!existsSync(file)) fail(`capacitor-assets did not write ${file}`);
      await sharp(source).resize(px, px).png().toFile(file);
    }
  }
  if (!wantIos) return;
  const set = join(dir, "ios", "App", "App", "Assets.xcassets", "Splash.imageset");
  const files = existsSync(set) ? readdirSync(set).filter((f) => f.startsWith("Default@") && f.endsWith(".png")) : [];
  if (!files.length) fail(`capacitor-assets did not write the launch images in ${set}`);
  const plain = await sharp({ create: { width: 8, height: 8, channels: 3, background: brand.bg } }).png().toBuffer();
  for (const f of files) writeFileSync(join(set, f), plain);
}

// Android 12+ draws the launch theme's icon on windowSplashScreenBackground by itself, and it
// accepts a frame animation as the icon. Before Android 12 the same attribute is a plain
// image, so the animation lives in drawable-v31 and the older versions get its last frame.
function patchAndroidSplash(res, brand) {
  const stylesPath = join(res, "values", "styles.xml");
  if (!brand.frames.length) {
    writeFileSync(stylesPath, patchLaunchTheme(readFileSync(stylesPath, "utf8"), brand));
    return;
  }
  const frames = brand.frames.map((bytes, i) => [`splash_icon_f${String(i).padStart(2, "0")}`, bytes]);
  mkdirSync(join(res, "drawable-xxhdpi"), { recursive: true });
  mkdirSync(join(res, "drawable-v31"), { recursive: true });
  for (const [name, bytes] of frames) writeFileSync(join(res, "drawable-xxhdpi", `${name}.png`), bytes);
  const xml = (body) => `<?xml version="1.0" encoding="utf-8"?>\n${body}\n`;
  writeFileSync(join(res, "drawable", "splash_icon.xml"),
    xml(`<bitmap xmlns:android="http://schemas.android.com/apk/res/android" android:src="@drawable/${frames[frames.length - 1][0]}" />`));
  writeFileSync(join(res, "drawable-v31", "splash_icon.xml"), xml(`<animation-list xmlns:android="http://schemas.android.com/apk/res/android" android:oneshot="true">
${frames.map(([name]) => `    <item android:drawable="@drawable/${name}" android:duration="${ENTRANCE_FRAME_MS}" />`).join("\n")}
</animation-list>`));

  writeFileSync(stylesPath, patchLaunchTheme(readFileSync(stylesPath, "utf8"), brand));
}

// The attributes are replaced when already there, so patching twice changes nothing.
function patchLaunchTheme(styles, brand) {
  const launch = /(<style name="AppTheme\.NoActionBarLaunch" parent="Theme\.SplashScreen">)([\s\S]*?)(<\/style>)/;
  if (!launch.test(styles)) fail("styles.xml layout changed, the launch theme was not patched");
  const ours = ["windowSplashScreenBackground", "windowSplashScreenAnimatedIcon", "windowSplashScreenAnimationDuration", "android:windowLightStatusBar"];
  return styles.replace(launch, (_, open, body, close) => {
    const kept = body.split("\n").filter((line) => !ours.some((name) => line.includes(`<item name="${name}">`))).join("\n");
    // Without frames (the plain-logo fallback) the system keeps its own icon and only the color is ours.
    const icon = brand.frames.length ? `        <item name="windowSplashScreenAnimatedIcon">@drawable/splash_icon</item>
        <item name="windowSplashScreenAnimationDuration">${ENTRANCE_FRAMES * ENTRANCE_FRAME_MS}</item>
` : "";
    return `${open}${kept.replace(/\s*$/, "\n")}        <item name="windowSplashScreenBackground">${brand.bg}</item>
${icon}        <item name="android:windowLightStatusBar">${!brand.isDarkBg}</item>
    ${close}`;
  });
}

// Capacitor starts with this style until the StatusBar plugin applies the theme color's, so the
// launch follows the splash background while the app keeps its own style after load.
function withLaunchStatusBar(plist, dark) {
  if (plist.includes("<key>UIStatusBarStyle</key>")) return plist;
  return plist.replace("\t<key>LSRequiresIPhoneOS</key>", `\t<key>UIStatusBarStyle</key>
\t<string>${dark ? "UIStatusBarStyleLightContent" : "UIStatusBarStyleDarkContent"}</string>
\t<key>LSRequiresIPhoneOS</key>`);
}

// The mark SceneDelegate animates in over the launch image (see templates/ios/SceneDelegate.swift).
function patchIosSplash(appDir, brand) {
  if (!brand.iosMark) return;
  const set = join(appDir, "Assets.xcassets", "SplashMark.imageset");
  mkdirSync(set, { recursive: true });
  writeFileSync(join(set, "SplashMark.png"), brand.iosMark);
  writeJson(join(set, "Contents.json"), {
    images: [{ idiom: "universal", filename: "SplashMark.png", scale: "1x" }],
    info: { version: 1, author: "xcode" }
  });
}

function patchAndroid(dir, { appId, host, versionName, buildNumber, callbackScheme, googleServices, brand }) {
  const app = join(dir, "android", "app");
  patchAndroidSplash(join(app, "src", "main", "res"), brand);

  // Manifest: App Link for the published host, so the sign-in that ends on this host comes
  // back to the shell instead of staying in Chrome.
  const manifestPath = join(app, "src", "main", "AndroidManifest.xml");
  let manifest = readFileSync(manifestPath, "utf8");
  const filter = `
            <!-- App Link for the published app host (needs /.well-known/assetlinks.json there) -->
            <intent-filter android:autoVerify="true">
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="https" android:host="${host}" />
            </intent-filter>${callbackScheme ? `
            <!-- Sign-in return by URL scheme: the app page bounces the SDK fragment here (see app-snippet/) -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="${callbackScheme}" />
            </intent-filter>` : ""}
        </activity>`;
  manifest = manifest.replace(/\s*<\/activity>/, filter);
  if (googleServices) {
    // Android 13+ shows a notification only with this permission, which the page asks for with
    // PushNotifications.requestPermissions().
    manifest = manifest.replace(/(\s*)<uses-permission android:name="android.permission.INTERNET" \/>/,
      `$&$1<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`);
    if (!manifest.includes("POST_NOTIFICATIONS")) fail("AndroidManifest layout changed, the notification permission was not added");
    // The Capacitor template applies the google-services Gradle plugin when this file exists.
    writeFileSync(join(app, "google-services.json"), googleServices);
  }
  writeFileSync(manifestPath, manifest);

  // MainActivity: load the incoming link in the WebView.
  const javaDir = join(app, "src", "main", "java", ...appId.split("."));
  const mainActivity = join(javaDir, "MainActivity.java");
  if (!existsSync(mainActivity)) fail(`cap add did not create ${mainActivity}`);
  writeFileSync(mainActivity, mainActivityJava(appId, callbackScheme));

  // build.gradle: version and release signing from the environment.
  const gradlePath = join(app, "build.gradle");
  let gradle = readFileSync(gradlePath, "utf8");
  gradle = gradle.replace(/versionCode \d+/, `versionCode ${buildNumber}`).replace(/versionName "[^"]*"/, `versionName "${versionName}"`);
  if (!gradle.includes("ANDROID_KEYSTORE_PATH")) {
    gradle = gradle.replace(/\n    buildTypes \{\n        release \{\n/, `
    // Release signing comes from the environment (CI secrets or a local .env). Without it the
    // release build is unsigned, which is fine for a compile check.
    signingConfigs {
        release {
            def ksPath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (ksPath != null && file(ksPath).exists()) {
                storeFile file(ksPath)
                storePassword System.getenv("KEYSTORE_PASSWORD")
                keyAlias System.getenv("KEY_ALIAS")
                keyPassword System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            if (System.getenv("ANDROID_KEYSTORE_PATH") != null) {
                signingConfig signingConfigs.release
            }
`);
    if (!gradle.includes("signingConfigs {")) fail("build.gradle layout changed, signing patch did not apply");
  }
  writeFileSync(gradlePath, gradle);

  // local.properties: point Gradle at the SDK on this machine (CI sets ANDROID_HOME itself).
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || firstExisting([
    "/opt/homebrew/share/android-commandlinetools",
    join(process.env.HOME || "", "Library", "Android", "sdk")
  ]);
  if (sdk) writeFileSync(join(dir, "android", "local.properties"), `sdk.dir=${sdk}\n`);
}

function patchIos(dir, { appId, host, versionName, buildNumber, associatedDomains, callbackScheme, push, brand }) {
  const appDir = join(dir, "ios", "App", "App");
  patchIosSplash(appDir, brand);
  const plistPath = join(appDir, "Info.plist");
  let plist = readFileSync(plistPath, "utf8");
  if (callbackScheme && !plist.includes("CFBundleURLTypes")) {
    // The sign-in return by scheme. ASWebAuthenticationSession catches it by itself; registering it
    // also brings a tap on the fallback link of the app page back to the shell.
    plist = plist.replace("\t<key>LSRequiresIPhoneOS</key>", `\t<key>CFBundleURLTypes</key>
\t<array>
\t\t<dict>
\t\t\t<key>CFBundleURLName</key>
\t\t\t<string>${appId}</string>
\t\t\t<key>CFBundleURLSchemes</key>
\t\t\t<array>
\t\t\t\t<string>${callbackScheme}</string>
\t\t\t</array>
\t\t</dict>
\t</array>
\t<key>LSRequiresIPhoneOS</key>`);
  }
  plist = withLaunchStatusBar(plist, brand.isDarkBg);
  if (!plist.includes("ITSAppUsesNonExemptEncryption")) {
    plist = plist.replace("\t<key>LSRequiresIPhoneOS</key>", "\t<key>ITSAppUsesNonExemptEncryption</key>\n\t<false/>\n\t<key>LSRequiresIPhoneOS</key>");
  }
  writeFileSync(plistPath, plist);

  const pbxPath = join(dir, "ios", "App", "App.xcodeproj", "project.pbxproj");
  let pbx = readFileSync(pbxPath, "utf8");
  pbx = pbx.replace(/MARKETING_VERSION = [^;]+;/g, `MARKETING_VERSION = ${versionName};`)
           .replace(/CURRENT_PROJECT_VERSION = [^;]+;/g, `CURRENT_PROJECT_VERSION = ${buildNumber};`);
  if (!pbx.includes(`PRODUCT_BUNDLE_IDENTIFIER = ${appId};`)) fail("cap add ios did not set the bundle id");

  // The SceneDelegate the template ships: Universal Links loaded in the WebView and the sign-in
  // session for the identity hosts (see generator/templates/ios/SceneDelegate.swift).
  cpSync(join(here, "templates", "ios", "SceneDelegate.swift"), join(appDir, "SceneDelegate.swift"));

  // Universal Link and the https callback of the sign-in session, both on the published host:
  // only useful once the host serves /.well-known/apple-app-site-association, harmless before.
  // Push: Xcode switches aps-environment to production when it signs for the App Store.
  const entitlements = [
    ...(push ? ["\t<key>aps-environment</key>\n\t<string>development</string>"] : []),
    ...(associatedDomains ? [`\t<key>com.apple.developer.associated-domains</key>
\t<array>
\t\t<string>applinks:${host}</string>
\t\t<string>webcredentials:${host}</string>
\t</array>`] : [])
  ];
  if (entitlements.length) {
    writeFileSync(join(appDir, "App.entitlements"), `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
${entitlements.join("\n")}
</dict>
</plist>
`);
    pbx = pbx.replace(new RegExp(`(\\t+)PRODUCT_BUNDLE_IDENTIFIER = ${appId.replace(/\./g, "\\.")};`, "g"),
      `$1CODE_SIGN_ENTITLEMENTS = App/App.entitlements;\n$1PRODUCT_BUNDLE_IDENTIFIER = ${appId};`);
  }
  if (push) {
    // The push plugin learns the device token (or the failure) from these two AppDelegate calls.
    const delegatePath = join(appDir, "AppDelegate.swift");
    let delegate = readFileSync(delegatePath, "utf8");
    if (!delegate.includes("capacitorDidRegisterForRemoteNotifications")) {
      const hooks = `
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        NotificationCenter.default.post(name: .capacitorDidRegisterForRemoteNotifications, object: deviceToken)
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        NotificationCenter.default.post(name: .capacitorDidFailToRegisterForRemoteNotifications, object: error)
    }
}
`;
      const end = delegate.lastIndexOf("}");
      if (end === -1) fail("AppDelegate.swift layout changed, the push hooks were not added");
      delegate = delegate.slice(0, end).replace(/\s*$/, "\n") + hooks;
      writeFileSync(delegatePath, delegate);
    }
  }
  writeFileSync(pbxPath, pbx);
}

function mainActivityJava(appId, callbackScheme) {
  return `package ${appId};

import android.content.Intent;
import android.net.Uri;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private static final String SHELL_LINK_PARAM = "shellLink";
    // Sign-in return by URL scheme (null when the shell relies on the App Link alone).
    private static final String CALLBACK_SCHEME = ${callbackScheme ? JSON.stringify(callbackScheme) : "null"};

    // Capacitor only tells the web app about an incoming link (appUrlOpen); the app published
    // by the platform does not know the shell exists. So when the link is on the app's own host,
    // load it in the WebView: the SDK finishes the sign-in from the URL fragment at boot.
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null || getBridge() == null) return;
        Uri data = intent.getData();
        if (data == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;

        String serverUrl = getBridge().getServerUrl();
        Uri app = Uri.parse(serverUrl != null ? serverUrl : getBridge().getAppUrl());
        if (CALLBACK_SCHEME != null && CALLBACK_SCHEME.equalsIgnoreCase(data.getScheme())) {
            // The app page bounced the SDK fragment to the shell by its scheme: carry the fragment
            // to the app host, where the SDK finishes the sign-in at boot.
            data = app.buildUpon().path("/").encodedQuery(data.getEncodedQuery())
                .encodedFragment(data.getEncodedFragment()).build();
        }
        if (data.getHost() == null || !data.getHost().equalsIgnoreCase(app.getHost())) return;

        // A fragment-only change would not reload the document, and the sign-in completes
        // only when the app boots. A fresh query value forces a full load.
        Uri target = data.buildUpon()
            .appendQueryParameter(SHELL_LINK_PARAM, String.valueOf(System.currentTimeMillis()))
            .build();
        WebView webView = getBridge().getWebView();
        webView.post(() -> webView.loadUrl(target.toString()));
    }
}
`;
}

// The return snippet of the published app. A sign-in the shell started ends in the system browser
// on the app host with the single-use code in the fragment; that browser never saw the sign-in
// context (it lives in the WebView, in sessionStorage), so the page hands the fragment to the shell
// by its scheme. A tab that holds a pending context started the sign-in itself and is left to the
// SDK, so the web app keeps working in a plain browser. The fragment is cleared before the SDK
// boots, or it would report the missing context as a CSRF error.
function shellReturnSnippet(scheme) {
  return `<script>
(function () {
  var scheme = ${JSON.stringify(scheme)};
  var hash = location.hash || "";
  if (hash.indexOf("codeMitra=") === -1 && hash.indexOf("errorMitra=") === -1) return;
  try {
    var stores = [sessionStorage, localStorage];
    for (var s = 0; s < stores.length; s++) {
      for (var i = 0; i < stores[s].length; i++) {
        if (/^mitra_[a-z]+_redirect_/.test(stores[s].key(i) || "")) return;
      }
    }
  } catch (e) {}
  var target = scheme + "://auth" + hash;
  history.replaceState(null, "", location.pathname + location.search);
  location.replace(target);
  document.addEventListener("DOMContentLoaded", function () {
    var a = document.createElement("a");
    a.href = target;
    a.textContent = "Voltar ao app";
    a.style.cssText = "position:fixed;left:0;right:0;bottom:32px;text-align:center;font:600 17px -apple-system,system-ui,sans-serif;z-index:2147483647";
    document.body.appendChild(a);
  });
})();
</script>
`;
}

// What the published app does to receive push. It is Capacitor's own plugin API, reached through
// the bridge the shell injects in the page, so the app needs no dependency.
function pushExampleSnippet() {
  return `<script>
// Call from a user action (a button), never at page load: iOS shows the permission dialog once.
async function enablePush(onToken, onNotification, onOpened) {
  var cap = window.Capacitor;
  var push = cap && cap.isNativePlatform && cap.isNativePlatform() && cap.Plugins && cap.Plugins.PushNotifications;
  if (!push) return "unavailable";                        // plain browser, or a shell built without push
  push.addListener("registration", function (t) { onToken(t.value, cap.getPlatform()); });   // send to your backend with the signed-in user
  push.addListener("registrationError", function (e) { console.warn("push registration failed", e); });
  push.addListener("pushNotificationReceived", function (n) { if (onNotification) onNotification(n); });           // app in foreground
  push.addListener("pushNotificationActionPerformed", function (a) { if (onOpened) onOpened(a.notification); });   // user tapped it
  var perm = await push.requestPermissions();
  if (perm.receive !== "granted") return "denied";
  try { await push.register(); } catch (e) { return "unavailable"; }
  return "granted";
}
</script>
`;
}

function readGoogleServices(appId) {
  const raw = flags["google-services"] ? readFileSync(resolve(String(flags["google-services"])), "utf8")
    : descriptor.googleServices ? readFileSync(resolve(String(descriptor.googleServices)), "utf8")
    : process.env.GOOGLE_SERVICES_JSON_B64 ? Buffer.from(process.env.GOOGLE_SERVICES_JSON_B64, "base64").toString("utf8")
    : null;
  const fromEnv = !flags["google-services"] && !descriptor.googleServices;
  if (!raw) return null;
  let json;
  try { json = JSON.parse(raw); } catch { fail("The Firebase config (google-services.json) is not valid JSON"); }
  const packages = (json.client || []).map((c) => c.client_info?.android_client_info?.package_name);
  if (packages.includes(appId)) return raw;
  const hint = `The Firebase config has no Android app for ${appId} (it has: ${packages.join(", ") || "none"}). Register the app in the Firebase project and download the config again.`;
  // A file given for this app is a mistake (Gradle would fail later with "No matching client
  // found"). The shared config of the pipeline covers only the apps registered so far, so an app
  // missing from it is built without push on Android, as documented.
  if (!fromEnv) fail(hint);
  log(`push     android off: ${hint}`);
  return null;
}

function offlinePage(name, color) {
  const fg = isDark(color) ? "#e2e8f0" : "#0f172a";
  return `<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>${escapeHtml(name)}</title>
<style>
  body { margin: 0; font-family: -apple-system, system-ui, sans-serif; background: ${color}; color: ${fg};
         display: flex; min-height: 100vh; align-items: center; justify-content: center; text-align: center; padding: 24px; }
  h1 { font-size: 20px; margin: 0 0 8px; }
  p { margin: 0; opacity: .8; }
</style>
</head>
<body>
  <div>
    <h1>Sem conexão</h1>
    <p>${escapeHtml(name)} precisa de internet. Conecte-se e abra de novo.</p>
  </div>
</body>
</html>
`;
}

function readme({ appName, appId, url, slug, associatedDomains, callbackScheme, pushIos, pushAndroid }) {
  return `# ${appName} (mobile shell)

Generated by \`mitra-mobile-shell/generator\` from ${url}. Do not edit by hand: change
\`mitra-shell.json\` (or the flags) and generate again.

- App id / bundle id: \`${appId}\`
- Loads: ${url}
- Android: App Link intent-filter for the host, \`onNewIntent\` hands sign-in returns to the WebView,
  release signing from \`ANDROID_KEYSTORE_PATH\`, \`KEYSTORE_PASSWORD\`, \`KEY_ALIAS\`, \`KEY_PASSWORD\`.
- iOS: exempt encryption declared; SceneDelegate opens sign-in hosts in an ASWebAuthenticationSession
  and loads Universal Links in the WebView; Associated Domains ${associatedDomains ? "ON (applinks and webcredentials for " + new URL(url).host + ")" : "off (pass --associated-domains once the host serves the AASA file)"}.
- Sign-in return: ${callbackScheme ? `by URL scheme \`${callbackScheme}://\` (nothing needed on the host). The app itself must carry
  \`app-snippet/shell-return.html\` in the head of its index.html, and for Android App Links its
  \`public/.well-known/assetlinks.json\` (from \`generator/wellknown.mjs\`).` : `by https callback on the app host (Universal Link),
  which needs the host to serve the apple-app-site-association file.`}
- Push: iOS ${pushIos ? "on (aps-environment entitlement, AppDelegate hooks)" : "off"}, Android ${pushAndroid ? "on (Firebase config in android/app/google-services.json, not to be committed)" : "off"}.${pushIos || pushAndroid ? " The page uses Capacitor's PushNotifications plugin through the bridge, see \`app-snippet/push-example.html\`." : ""}
- CI: \`.github/workflows/mobile.yml\` (secrets: Android keystore, App Store Connect API key).

\`\`\`bash
cd android && ./gradlew assembleDebug      # local APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
\`\`\`

Slug: \`${slug}\`.
`;
}

// ---------- helpers ----------
async function fetchAppInfo(base, id) {
  if (!/^[0-9a-f-]{36}$/i.test(id)) fail(`--app must be the app UUID, got ${id}`);
  const u = `${base}/code-studio/api/v1/apps/${id}/info`;
  const res = await fetch(u, { headers: { accept: "application/json" } });
  if (!res.ok) fail(`GET ${u} returned ${res.status}`);
  return res.json();
}
function pickOrigin(origins) {
  // code-studio lists the platform host(s) first and the active custom domain last; the custom
  // domain is what the owner shows to users, so the last entry is the one the shell should load.
  return origins[origins.length - 1];
}
function brandSegment(brand) {
  return brand && /^[a-z][a-z0-9]*$/.test(brand) ? brand : "infusion";
}
function run(cmd, cmdArgs, cwd) {
  log(`$ ${cmd} ${cmdArgs.join(" ")}   (in ${cwd.replace(templateRoot, ".")})`);
  const r = spawnSync(cmd, cmdArgs, { cwd, stdio: flags.verbose ? "inherit" : ["ignore", "pipe", "pipe"], env: { ...process.env, CI: "1" } });
  if (r.status !== 0) {
    if (!flags.verbose) process.stderr.write((r.stdout || "") + (r.stderr || ""));
    fail(`${cmd} ${cmdArgs.join(" ")} exited with ${r.status}`);
  }
}
async function fetchText(u) {
  const res = await fetch(u, { redirect: "follow" });
  if (!res.ok) fail(`GET ${u} returned ${res.status}`);
  return res.text();
}
function clean(title) {
  // "Name - tagline", "Name | tagline" and the dash variants: keep the name only. Inner line
  // breaks of a page title collapse to a space: the name ends up in single-line files and outputs.
  return title.split(/\s+[-|\u2013\u2014:]\s+/)[0].replace(/\s+/g, " ").trim();
}
function slugify(s) {
  return s.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().replace(/[^a-z0-9]+/g, "").slice(0, 40) || "app";
}
function packageSegment(seg) {
  const javaReserved = new Set(["abstract","assert","boolean","break","byte","case","catch","char","class","const","continue","default","do","double","else","enum","extends","final","finally","float","for","goto","if","implements","import","instanceof","int","interface","long","native","new","package","private","protected","public","return","short","static","strictfp","super","switch","synchronized","this","throw","throws","transient","try","void","volatile","while"]);
  if (/^\d/.test(seg)) seg = `app${seg}`;
  if (javaReserved.has(seg)) seg = `${seg}app`;
  return seg;
}
function isVersionName(v) { return /^\d+(\.\d+){0,2}$/.test(v); }
function isPlainHost(h) { return /^[a-z0-9.-]+(:\d+)?$/.test(h); }
function validateAppId(id) {
  const parts = id.split(".");
  if (parts.length < 2 || parts.some((p) => !/^[a-zA-Z][a-zA-Z0-9_]*$/.test(p))) fail(`Invalid app id for Android/iOS: ${id}`);
}
// A color from the caller is a mistake to report; one from the platform or the page belongs to a
// third party and must not break the build, so it is skipped.
function resolveThemeColor(own, external) {
  if (own) {
    if (!HEX_COLOR.test(own)) throw new Error(`Theme color must be a hex color like #0f172a or #abc, got ${JSON.stringify(own)}`);
    return own;
  }
  for (const color of external.filter(Boolean)) {
    if (HEX_COLOR.test(color)) return color;
    log(`theme    ignoring ${JSON.stringify(color)} (not a hex color)`);
  }
  return DEFAULT_THEME_COLOR;
}
// Only A-Z and 0-9 come out of here, so the result goes into the SVG without escaping.
function initials(name, slug = "") {
  const words = name.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toUpperCase().split(/[^A-Z0-9]+/).filter(Boolean);
  const letters = words.length >= 2 ? words[0][0] + words[1][0] : (words[0] || "").slice(0, 2);
  return letters || slug.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 2) || "A";
}
function initialsLogoSvg(letters, color) {
  // The baseline is computed from the cap height of Helvetica and Arial (0.716 em) because
  // librsvg, which rasterizes the file, ignores dominant-baseline. The size keeps the widest pair
  // ("WM") inside the circle Android guarantees to show of an adaptive icon (66% of the side).
  const size = 340;
  const baseline = Math.round(512 + 0.358 * size);
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">
  <rect width="1024" height="1024" fill="${color}"/>
  <text x="512" y="${baseline}" font-family="Helvetica, Arial, sans-serif" font-size="${size}" font-weight="700" fill="${isDark(color) ? "#ffffff" : "#0f172a"}" text-anchor="middle">${letters}</text>
</svg>
`;
}
// What kind of logo the raster is, from pixels only (px(x, y) is [r, g, b, a]):
//  - "own-background": opaque at the edges in one color that also holds at the corners (or they are
//    transparent) and covers a good part of the picture: that color is the background and the rest
//    is the mark;
//  - "full-bleed": opaque at the edges but not one color or not one all over (gradient, outline,
//    picture): the logo is the icon;
//  - "transparent-mark": anything else, a mark on the theme color.
// Samples go several points along the middle of each edge and at two depths, so a thin outline
// at the very edge is not taken for the background.
function analyzeLogo(side, px) {
  const samples = edgeSamples(side, px);
  if (samples.some((s) => s[3] < 250)) return { kind: "transparent-mark" };
  const uniform = samples.every((s) => colorDistance(s, samples[0]) <= BACKGROUND_TOLERANCE);
  // A radial gradient is the same color at the four edge middles and different at the corners.
  const corner = Math.round(side * 0.06);
  const corners = [px(corner, corner), px(side - 1 - corner, corner), px(corner, side - 1 - corner), px(side - 1 - corner, side - 1 - corner)];
  const cornersAgree = corners.every((c) => c[3] < 250 || colorDistance(c, samples[0]) <= BACKGROUND_TOLERANCE);
  if (uniform && cornersAgree && backgroundShare(side, px, samples[0]) >= MIN_BACKGROUND_SHARE) return { kind: "own-background", color: toHex(samples[0]) };
  // The inner samples only: the outer ones may be an outline.
  const inner = samples.slice(samples.length / 2);
  return { kind: "full-bleed", color: dominantColor(inner) };
}
function edgeSamples(side, px) {
  const depths = [Math.round(side * 0.004), Math.round(side * 0.03)];
  const along = [0.4, 0.5, 0.6].map((f) => Math.round(side * f));
  const far = (d) => side - 1 - d;
  return depths.flatMap((d) => along.flatMap((a) => [px(a, d), px(a, far(d)), px(d, a), px(far(d), a)]));
}
// Share of the picture that looks like the background `rgb`, with the tolerance the edges had to
// meet: a vignette, a drifting background or noise is not flat enough to be cut out of the mark.
// Transparent pixels count, as the corners of a rounded-square logo do.
function backgroundShare(side, px, rgb) {
  let hits = 0, total = 0;
  for (let y = 0; y < side; y += 4) {
    for (let x = 0; x < side; x += 4) {
      total++;
      if (!isMarkOnBackground(px(x, y), rgb, BACKGROUND_TOLERANCE)) hits++;
    }
  }
  return hits / total;
}
// A pixel is part of the mark when, as it would look on the background, it differs from it.
function isMarkOnBackground([r, g, b, a], rgb, tolerance = MARK_TOLERANCE) {
  const k = a / 255;
  return colorDistance([r * k + rgb[0] * (1 - k), g * k + rgb[1] * (1 - k), b * k + rgb[2] * (1 - k)], rgb) > tolerance;
}
// Makes the pixels of an RGBA buffer that are within `tolerance` of `rgb` transparent, in place.
// The others keep their color and alpha, so antialiased edges of the mark stay as they were.
function clearBackground(rgba, rgb, tolerance) {
  for (let o = 0; o < rgba.length; o += 4) {
    if (colorDistance([rgba[o], rgba[o + 1], rgba[o + 2]], rgb) <= tolerance) rgba[o + 3] = 0;
  }
}
// The box grown by a share of its own larger side on each side, kept inside the picture. The share
// is of the mark, not of the picture: a fixed number of source pixels is enormous once a small mark
// is scaled up to the icon.
function padBox(box, side, share) {
  const pad = Math.round(Math.max(box.w, box.h) * share);
  const x = Math.max(0, box.x - pad), y = Math.max(0, box.y - pad);
  return { x, y, w: Math.min(side, box.x + box.w + pad) - x, h: Math.min(side, box.y + box.h + pad) - y };
}
// Where the mark is, what to crop and the diameter of the circle around its pixels, all in raster pixels.
function planMark(side, isMark, frame) {
  const hit = findMarkBox(side, isMark, frame);
  if (!hit) return { hit: null, box: { x: 0, y: 0, w: side, h: side }, circle: Math.hypot(side, side) };
  const box = padBox(hit, side, MARK_PADDING);
  return { hit, box, circle: markCircle(side, isMark, frame, hit, box) + Math.max(hit.w, hit.h) * MARK_PADDING };
}
// Names SceneDelegate.swift calls on its own types, besides those called on the view controller by
// receiver. Definitions are var, func or init, never a local let.
function missingSwiftMembers(swift) {
  const inherited = new Set(["webView", "bridge", "view", "statusBarStyle", "setStatusBarStyle"]);
  const called = [...swift.matchAll(/\b(?:shell\??|ShellBridgeViewController\)\?)\.(\w+)/g)].map((m) => m[1]);
  const names = [...new Set([...called, ...SWIFT_MEMBERS])].filter((n) => !inherited.has(n));
  const missing = names.filter((n) => !new RegExp(`\\b(?:var|func) ${n}\\b`).test(swift));
  if (!swift.includes("init?(hex:")) missing.push("init?(hex");
  return missing;
}
// Splash color of a full-bleed logo from its edge samples: their mean when they are close, else
// the most common color (samples in the same 16-level bucket), so a large badge over a white
// margin gives white or the badge color and not a muddy mix of the two.
function dominantColor(samples) {
  const close = samples.every((a) => samples.every((b) => colorDistance(a, b) <= 2 * BACKGROUND_TOLERANCE));
  let group = samples;
  if (!close) {
    const buckets = new Map();
    for (const c of samples) {
      const key = [c[0] >> 4, c[1] >> 4, c[2] >> 4].join();
      buckets.set(key, [...(buckets.get(key) || []), c]);
    }
    group = [...buckets.values()].reduce((best, g) => (g.length > best.length ? g : best));
  }
  return toHex([0, 1, 2].map((c) => group.reduce((sum, s) => sum + s[c], 0) / group.length));
}
// The largest difference of any channel, which is enough to tell a border from a mark.
function colorDistance(a, b) {
  return Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]), Math.abs(a[2] - b[2]));
}
// Bounding box of the pixels isMark(x, y) accepts in a square of side `side`, without looking at
// the outer frame. A mark that reaches the edge of what was looked at goes on in the frame, so that
// side is extended to the edge of the picture instead of cutting the mark. null: nothing found.
function findMarkBox(side, isMark, frame) {
  const f = Math.round(side * frame);
  let x0 = side, y0 = side, x1 = -1, y1 = -1;
  for (let y = f; y < side - f; y++) {
    for (let x = f; x < side - f; x++) {
      if (!isMark(x, y)) continue;
      if (x < x0) x0 = x;
      if (x > x1) x1 = x;
      if (y < y0) y0 = y;
      if (y > y1) y1 = y;
    }
  }
  if (x1 < 0) return null;
  if (x0 === f) x0 = 0;
  if (y0 === f) y0 = 0;
  if (x1 === side - f - 1) x1 = side - 1;
  if (y1 === side - f - 1) y1 = side - 1;
  return { x: x0, y: y0, w: x1 - x0 + 1, h: y1 - y0 + 1, extended: x0 === 0 || y0 === 0 || x1 === side - 1 || y1 === side - 1 };
}
// Diameter of the circle, centered on the middle of `box`, that holds every pixel of the mark. A
// mark that runs into the ignored frame was not fully scanned, so its box corners stand in.
function markCircle(side, isMark, frame, hit, box) {
  const cx = box.x + box.w / 2, cy = box.y + box.h / 2;
  if (hit.extended) return Math.hypot(box.w, box.h);
  const f = Math.round(side * frame);
  let far = 0;
  for (let y = hit.y; y < hit.y + hit.h; y++) {
    for (let x = hit.x; x < hit.x + hit.w; x++) {
      if (x < f || y < f || x >= side - f || y >= side - f || !isMark(x, y)) continue;
      // The far corner of the pixel, so a square mark is not measured short by half a pixel.
      far = Math.max(far, Math.hypot(Math.abs(x + 0.5 - cx) + 0.5, Math.abs(y + 0.5 - cy) + 0.5));
    }
  }
  return 2 * far;
}
// Ease-out entrance: opacity and size of frame i of n, the last one being the mark as it stays.
function entranceFrame(i, n) {
  const t = n > 1 ? i / (n - 1) : 1;
  const eased = 1 - Math.pow(1 - t, 3);
  return { alpha: eased, scale: 0.8 + 0.2 * eased };
}
function hexToRgb(hex) {
  const full = normalizeHex(hex).slice(1);
  return [0, 2, 4].map((i) => parseInt(full.slice(i, i + 2), 16));
}
function toHex(rgb) {
  return "#" + rgb.slice(0, 3).map((v) => Math.round(v).toString(16).padStart(2, "0")).join("");
}
function normalizeHex(hex) {
  const m = hex.replace("#", "").toLowerCase();
  return "#" + (m.length === 3 ? m.split("").map((c) => c + c).join("") : m);
}
function isDark(hex) {
  const m = hex.replace("#", "");
  const full = m.length === 3 ? m.split("").map((c) => c + c).join("") : m;
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(full.slice(i, i + 2), 16));
  return (0.299 * r + 0.587 * g + 0.114 * b) < 140;
}
function mimeToExt(mime) {
  if (/svg/.test(mime)) return "svg";
  if (/png/.test(mime)) return "png";
  return "";
}
function describe(src) { return String(src).startsWith("data:") ? "inline data: URI from the page" : String(src); }
function decodeEntities(s) { return s.replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;/g, "'"); }
function escapeHtml(s) { return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;"); }
function writeJson(p, obj) { writeFileSync(p, JSON.stringify(obj, null, 2) + "\n"); }
function firstExisting(paths) { return paths.find((p) => p && existsSync(p)); }
function findApk(dir) {
  const apk = existsSync(dir) ? readdirSync(dir).find((f) => f.endsWith(".apk")) : undefined;
  if (!apk) fail(`No APK under ${dir}`);
  return join(dir, apk);
}
// GITHUB_OUTPUT is one "key=value" per line: a line break inside a value would let it define
// other outputs of the step.
function githubOutputLines(obj) {
  return Object.entries(obj).filter(([, v]) => v !== undefined && !Array.isArray(v)).map(([k, v]) => {
    if (/[\r\n]/.test(String(v))) throw new Error(`Output ${k} has a line break: ${JSON.stringify(v)}`);
    return `${k.replace(/[A-Z]/g, (c) => "_" + c.toLowerCase())}=${v}\n`;
  }).join("");
}
function githubOutputs(obj) {
  if (!process.env.GITHUB_OUTPUT) return;
  let lines;
  try { lines = githubOutputLines(obj); } catch (e) { fail(e.message); }
  writeFileSync(process.env.GITHUB_OUTPUT, lines, { flag: "a" });
}
function selftest() {
  const assert = (cond, msg) => { if (!cond) fail(`selftest: ${msg}`); };
  const throws = (fn) => { try { fn(); return false; } catch { return true; } };
  for (const [name, expected] of [["Minha Casa", "MC"], ["ROUTER", "RO"], ["x", "X"], ["Ação Já", "AJ"],
    ["one two three", "OT"], ["  Café-da manhã ", "CD"], ["3M Company", "3C"], ["<b>&\"", "B"]]) {
    assert(initials(name) === expected, `initials(${JSON.stringify(name)}) is ${initials(name)}, expected ${expected}`);
  }
  assert(initials("!!!", "app") === "AP", "initials falls back to the slug");
  assert(initials("!!!") === "A", "initials falls back to A");
  const svg = initialsLogoSvg("MC", "#ffffff");
  assert(svg.includes(">MC</text>") && svg.includes('fill="#ffffff"') && svg.includes('fill="#0f172a"'), "light logo has dark letters");
  assert(initialsLogoSvg("MC", "#000").includes('fill="#ffffff"'), "dark logo has light letters");
  assert(resolveThemeColor("#ABC", ["#111111"]) === "#ABC", "own color wins");
  assert(throws(() => resolveThemeColor("red", [])), "own color must be hex");
  assert(throws(() => resolveThemeColor("#12345", [])), "5 hex digits are refused");
  assert(throws(() => resolveThemeColor("#fff' ; touch x '", [])), "shell text in the color is refused");
  assert(resolveThemeColor(undefined, [undefined, "rgb(1,2,3)", "#0a0b0c"]) === "#0a0b0c", "invalid external color is skipped");
  assert(resolveThemeColor(undefined, ["red", undefined]) === DEFAULT_THEME_COLOR, "no valid color -> default");
  assert(githubOutputLines({ appName: "A B", skip: undefined, list: ["x"], n: 3 }) === "app_name=A B\nn=3\n", "githubOutputLines");
  assert(throws(() => githubOutputLines({ appName: "A\nevil=1" })), "line feed in an output is refused");
  assert(throws(() => githubOutputLines({ appName: "A\rB" })), "carriage return in an output is refused");
  assert(clean("My\n  App | tagline") === "My App", "clean collapses line breaks");
  assert(isVersionName("1") && isVersionName("1.2.3") && !isVersionName("1.0${x}") && !isVersionName('1.0"') && !isVersionName("1.2.3.4"), "isVersionName");
  assert(isPlainHost("a-1.example.com") && isPlainHost("127.0.0.1:8099") && !isPlainHost('a"b.com') && !isPlainHost("a b"), "isPlainHost");
  // Synthetic 100x100 logos, as px(x, y) -> [r, g, b, a].
  const white = [255, 255, 255, 255];
  const teal = [0, 224, 198, 255];
  const inBlob = (x, y) => Math.hypot(x - 50, y - 50) < 20;
  const roundedSquare = (x, y) => {
    const dx = Math.max(0, 22 - Math.min(x, 99 - x)), dy = Math.max(0, 22 - Math.min(y, 99 - y));
    return Math.hypot(dx, dy) <= 22 ? (inBlob(x, y) ? teal : white) : [0, 0, 0, 0];
  };
  assert(analyzeLogo(100, roundedSquare).kind === "own-background" && analyzeLogo(100, roundedSquare).color === "#ffffff",
    "transparent corners do not stop four agreeing edges from being the background");
  assert(!isMarkOnBackground([0, 0, 0, 0], [255, 255, 255]) && !isMarkOnBackground([255, 255, 255, 120], [255, 255, 255]), "transparent and faint pixels are background");
  assert(isMarkOnBackground(teal, [255, 255, 255]), "a different opaque color is mark");
  assert(analyzeLogo(100, (x, y) => (inBlob(x, y) ? [255, 0, 0, 255] : [0, 0, 0, 0])).kind === "transparent-mark", "transparent edges: a mark on the theme color");
  const vertical = (x, y) => [40 + y * 2, 60, 200 - y, 255];
  assert(analyzeLogo(100, vertical).kind === "full-bleed", "a vertical gradient is a full-bleed logo");
  const radial = (x, y) => { const v = 255 - Math.round(Math.hypot(x - 50, y - 50) * 2.4); return [v, v, 255, 255]; };
  assert(analyzeLogo(100, radial).kind === "full-bleed", "a radial gradient has equal edges but is not a background");
  const outlined = (x, y) => (Math.min(x, y, 99 - x, 99 - y) < 2 ? [20, 20, 20, 255] : inBlob(x, y) ? teal : white);
  assert(analyzeLogo(100, outlined).kind === "full-bleed", "a thin outline at the edge is not the background");
  const plain = analyzeLogo(100, (x, y) => (inBlob(x, y) ? teal : white));
  assert(plain.kind === "own-background" && plain.color === "#ffffff", "a flat logo keeps its own background");
  assert(dominantColor([[0, 150, 130, 255], [4, 154, 134, 255]]) === "#029884", "close edge colors are averaged");
  const badge = [[0, 150, 130, 255], [0, 150, 130, 255], [255, 255, 255, 255], [255, 255, 255, 255], [255, 255, 255, 255], [252, 254, 255, 255]];
  assert(hexToRgb(dominantColor(badge)).every((v) => v >= 250), `a badge over white gives white, not a mix (${dominantColor(badge)})`);
  const strip = analyzeLogo(100, (x, y) => (x < 3 || x > 96 ? [255, 255, 255, 255] : [0, 150, 130, 255]));
  assert(strip.kind === "full-bleed" && strip.color === "#009682", "a picture with a white margin on two sides takes the color most of the edge has");
  // A 100x100 picture: mark at x 30..49, y 40..59, and a border inside the 4% frame.
  const mark = (x, y) => (x >= 30 && x < 50 && y >= 40 && y < 60) || x === 1 || y === 98;
  const box = findMarkBox(100, mark, 0.04);
  assert(box && box.x === 30 && box.y === 40 && box.w === 20 && box.h === 20, `findMarkBox ignores the frame, got ${JSON.stringify(box)}`);
  assert(findMarkBox(100, () => false, 0.04) === null, "findMarkBox finds nothing in a flat picture");
  const wide = findMarkBox(100, (x, y) => y >= 40 && y < 60, 0.04);
  assert(wide.x === 0 && wide.w === 100 && wide.y === 40 && wide.h === 20, "a mark that runs into the frame reaches the edge");
  const padded = padBox({ x: 1, y: 400, w: 200, h: 100 }, 1024, 0.02);
  assert(padded.x === 0 && padded.y === 396 && padded.w === 205 && padded.h === 108, `padBox grows the box by its own size and stays inside, got ${JSON.stringify(padded)}`);
  assert(padBox({ x: 500, y: 500, w: 4, h: 4 }, 1024, 0.02).w === 4, "a tiny mark gets no padding of the size of the picture");
  // Whatever the size of the mark, the padded box scaled to the icon fits the canvas it is drawn on
  // (a fixed padding once made a small mark overflow it and sharp refused to composite).
  for (const d of [4, 30, 100, 200, 1000]) {
    const plan = planMark(1024, (x, y) => Math.hypot(x - 512, y - 512) < d / 2, MARK_FRAME);
    assert(plan.hit, `a ${d}px mark is found`);
    const scale = (1024 * ADAPTIVE_ICON_CIRCLE) / plan.circle;
    assert(plan.box.w * scale <= 1024 && plan.box.h * scale <= 1024, `a ${d}px mark overflows the canvas (${Math.round(plan.box.w * scale)} px)`);
    // A mark that runs into the ignored frame is measured by its box corners, so it ends smaller.
    assert(plan.hit.extended || Math.abs(d * scale - 1024 * ADAPTIVE_ICON_CIRCLE) < 1024 * ADAPTIVE_ICON_CIRCLE * 0.1, `a ${d}px mark ends at about the intended size (${Math.round(d * scale)} px)`);
  }
  const cutout = Buffer.from([255, 255, 255, 255, 245, 250, 255, 255, 0, 200, 170, 255, 120, 220, 200, 128]);
  clearBackground(cutout, [255, 255, 255], BACKGROUND_TOLERANCE);
  assert(cutout[3] === 0 && cutout[7] === 0 && cutout[11] === 255 && cutout[15] === 128 && cutout[8] === 0 && cutout[12] === 120,
    "near-background pixels become transparent and the mark keeps its color and alpha");
  assert(findMarkBox(100, (x, y) => (x >= 10 && x < 20 && y >= 5 && y < 90), 0).w === 10, "the drawn area of a logo with a transparent margin is found with no frame");
  const disc = (x, y) => Math.hypot(x - 50, y - 50) < 20;
  const discHit = findMarkBox(100, disc, 0.04);
  const discCircle = markCircle(100, disc, 0.04, discHit, discHit);
  assert(discCircle > 39 && discCircle < 42, `the circle of a round mark follows its pixels, not its box corners (${discCircle})`);
  const soft = (x, y) => [220 - Math.round(Math.hypot(x - 50, y - 50) * 1.5), 120, 120, 255];
  assert(analyzeLogo(100, soft).kind === "full-bleed", "a vignette is not a flat background");
  const drift = (x, y) => (inBlob(x, y) ? [255, 0, 0, 255] : [100 + Math.round(x * 0.35), 100, 100, 255]);
  assert(analyzeLogo(100, drift).kind === "full-bleed", "a background that drifts across the picture is not flat");
  const noise = (x, y) => { const n = ((x * 7919 + y * 104729) % 29) - 14; return inBlob(x, y) ? [255, 0, 0, 255] : [120 + n, 120 + n, 120 - n, 255]; };
  assert(analyzeLogo(100, noise).kind === "full-bleed", "noisy edges are not a flat background");

  const first = entranceFrame(0, ENTRANCE_FRAMES);
  const last = entranceFrame(ENTRANCE_FRAMES - 1, ENTRANCE_FRAMES);
  assert(first.alpha === 0 && first.scale === 0.8, "the entrance starts invisible at 80%");
  assert(last.alpha === 1 && last.scale === 1, "the entrance ends on the mark as it stays");
  assert(entranceFrame(2, ENTRANCE_FRAMES).alpha > 2 / (ENTRANCE_FRAMES - 1), "the entrance eases out");
  assert(ENTRANCE_FRAMES * ENTRANCE_FRAME_MS <= 1000, "the system cuts a splash animation at one second");
  assert(normalizeHex("#ABC") === "#aabbcc" && normalizeHex("#0F172A") === "#0f172a", "normalizeHex");
  assert(hexToRgb("#009A86").join() === "0,154,134" && toHex([0, 154.2, 134]) === "#009a86", "hex and rgb round trip");
  const capacitorStyles = `<resources>
    <style name="AppTheme.NoActionBarLaunch" parent="Theme.SplashScreen">
        <item name="android:background">@drawable/splash</item>
    </style>
</resources>`;
  const fixtureBrand = { bg: "#ffffff", isDarkBg: false, frames: [Buffer.alloc(1)] };
  const themed = patchLaunchTheme(capacitorStyles, fixtureBrand);
  const attr = (xml, name) => xml.split(`<item name="${name}">`).length - 1;
  assert(["windowSplashScreenBackground", "windowSplashScreenAnimatedIcon", "windowSplashScreenAnimationDuration", "android:windowLightStatusBar", "android:background"].every((n) => attr(themed, n) === 1), "each launch theme attribute lands once");
  assert(themed.includes("windowSplashScreenBackground\">#ffffff<") && themed.includes("windowLightStatusBar\">true<"), "the launch theme carries the splash color and a light status bar");
  assert(patchLaunchTheme(themed, fixtureBrand) === themed, "patching the launch theme twice changes nothing");
  assert(patchLaunchTheme(themed, { ...fixtureBrand, bg: "#000000", isDarkBg: true }).includes("windowLightStatusBar\">false<"), "a dark splash gets light status bar icons");
  const plist = "<dict>\n\t<key>CFBundleName</key>\n\t<key>LSRequiresIPhoneOS</key>\n\t<true/>\n</dict>";
  const withBar = withLaunchStatusBar(plist, false);
  assert(withBar.includes("UIStatusBarStyleDarkContent") && withBar.split("<key>UIStatusBarStyle</key>").length === 2, "the launch status bar style is added once");
  assert(withLaunchStatusBar(withBar, true) === withBar, "an Info.plist that has the style is left alone");
  assert(withLaunchStatusBar(plist, true).includes("UIStatusBarStyleLightContent"), "a dark splash gets light status bar content");
  // Nothing compiles the Swift here, so check that every member the file calls is still defined in
  // it (a past edit dropped four of them). The check itself is tried on a mutated copy per name.
  const swift = readFileSync(join(here, "templates", "ios", "SceneDelegate.swift"), "utf8");
  assert(!missingSwiftMembers(swift).length, `SceneDelegate.swift calls ${missingSwiftMembers(swift)} but does not define it`);
  for (const name of SWIFT_MEMBERS) {
    const broken = swift.replace(new RegExp(`(var|func) ${name}\\b`), "let $1_x").replace("init?(hex:", "init?(hexx:");
    assert(missingSwiftMembers(broken).includes(name), `the Swift check does not notice ${name} going missing`);
  }
  assert(missingSwiftMembers(swift.replace("init?(hex:", "init?(hexx:")).includes("init?(hex"), "the Swift check does not notice init?(hex going missing");
  assert(missingSwiftMembers(swift.replace("func appURL(fromReturn", "func other(fromReturn") + "\nlet appHost = 1").includes("appURL"), "a local let does not stand in for a member");
  // The plain-logo fallback keeps the theme color and drops the entrance.
  const asIs = { bg: "#0f172a", isDarkBg: true, frames: [], branch: "fallback" };
  const plainTheme = patchLaunchTheme(capacitorStyles, asIs);
  assert(plainTheme.includes("windowSplashScreenBackground") && !plainTheme.includes("windowSplashScreenAnimatedIcon"), "the fallback theme has the color and no animated icon");
  assert(capacitorAssetsArgs(asIs).includes("--splashBackgroundColor") && !capacitorAssetsArgs({ branch: "own-background" }).includes("--splashBackgroundColor"), "only the fallback gives capacitor-assets the colors");
  console.log("[generate] selftest ok");
}
function log(msg) { console.log(`[generate] ${msg}`); }
function fail(msg) { console.error(`[generate] ERROR: ${msg}`); process.exit(1); }
