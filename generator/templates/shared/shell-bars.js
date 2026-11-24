// Written by mitra-mobile-shell/generator, shipped in the app bundle as public/shell-bars.js and
// run in the page by the native side (a WKUserScript on iOS, evaluateJavascript on Android).
//
// The published page draws under the status bar, and the bar content has to contrast with what
// the page paints there. The page has its own light/dark toggle, independent of the device theme,
// so neither the theme color read at generation time nor the system theme can decide it: this
// samples the color at the top (and, on Android, bottom) edge of the live page and tells
// Capacitor's SystemBars plugin whether the bar content should be light or dark.
(function (win) {
  "use strict";
  if (win.top !== win || win.__mitraShellBars) return;
  var shellBars = win.__mitraShellBars = {};

  var doc = win.document;
  var DEBOUNCE_MS = 150;
  var TRAILING_MS = 400;
  // The generator decides the same way for the initial style (isDark in generate.mjs).
  var DARK_BELOW = 140;

  function platform() {
    try { return win.Capacitor.getPlatform(); } catch (e) { return ""; }
  }

  // Android goes edge to edge only for a page that declares viewport-fit=cover, the rule of
  // Capacitor's SystemBars (it reads the last viewport meta). Without it the bars sit over a
  // native background and there is nothing of the page to sample. iOS decides on the native side.
  function drawsUnderBars() {
    var metas = doc.querySelectorAll("meta[name=viewport]");
    if (!metas.length) return false;
    return String(metas[metas.length - 1].content).indexOf("viewport-fit=cover") !== -1;
  }

  var canvasContext;
  function canvas1x1() {
    if (canvasContext === undefined) {
      try {
        var canvas = doc.createElement("canvas");
        canvas.width = canvas.height = 1;
        canvasContext = canvas.getContext("2d", { willReadFrequently: true }) || null;
      } catch (e) { canvasContext = null; }
    }
    return canvasContext;
  }

  function throughCanvas(ctx, css, sentinel) {
    ctx.clearRect(0, 0, 1, 1);
    ctx.fillStyle = sentinel;
    ctx.fillStyle = css;
    ctx.fillRect(0, 0, 1, 1);
    return ctx.getImageData(0, 0, 1, 1).data;
  }

  // Any CSS color to [r, g, b, a] (a in 0..1), or null. getComputedStyle answers rgb() and rgba()
  // for most colors, which the fast path reads; oklch(), color() and the like go through a canvas,
  // which converts them to sRGB. A string the canvas rejects leaves the previous fillStyle in
  // place, so two different sentinels that give different pixels mean it was rejected.
  // Busy pages re-check often and modern CSS colors go through the canvas, so answers are kept,
  // and dropped wholesale before the table can grow without bound.
  var colorCache = {}, colorCacheSize = 0;
  function parseColor(css) {
    if (!css) return null;
    if (Object.prototype.hasOwnProperty.call(colorCache, css)) return colorCache[css];
    if (colorCacheSize >= 300) { colorCache = {}; colorCacheSize = 0; }
    colorCacheSize++;
    return (colorCache[css] = parseColorUncached(css));
  }

  function parseColorUncached(css) {
    var m = /^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)(?:\s*[,\/]\s*([\d.]+%?))?\s*\)$/i.exec(css.trim());
    if (m) {
      var a = m[4] === undefined ? 1 : (m[4].slice(-1) === "%" ? parseFloat(m[4]) / 100 : parseFloat(m[4]));
      return [parseFloat(m[1]), parseFloat(m[2]), parseFloat(m[3]), a];
    }
    if (/^transparent$/i.test(css.trim())) return [0, 0, 0, 0];
    var ctx = canvas1x1();
    if (!ctx) return null;
    try {
      var one = throughCanvas(ctx, css, "#010203");
      var first = [one[0], one[1], one[2], one[3] / 255];
      var two = throughCanvas(ctx, css, "#fdfcfb");
      if (one[0] !== two[0] || one[1] !== two[1] || one[2] !== two[2] || one[3] !== two[3]) return null;
      return first;
    } catch (e) { return null; }
  }

  // Front-to-back "over": layers come topmost first, each [r, g, b, a]. Returns the color seen
  // through the stack and how opaque the stack is, so the caller knows whether to look further.
  function compose(layers) {
    var r = 0, g = 0, b = 0, alpha = 0;
    for (var i = 0; i < layers.length && alpha < 0.999; i++) {
      var layer = layers[i];
      if (!layer || !(layer[3] > 0)) continue;
      var weight = (1 - alpha) * layer[3];
      r += weight * layer[0];
      g += weight * layer[1];
      b += weight * layer[2];
      alpha += weight;
    }
    return { r: r, g: g, b: b, alpha: alpha };
  }

  function styleOf(rgb) {
    return (0.299 * rgb[0] + 0.587 * rgb[1] + 0.114 * rgb[2]) < DARK_BELOW ? "DARK" : "LIGHT";
  }

  // The element's own opacity scales its paint: a modal backdrop that is fading in or out at
  // opacity 0 is not an overlay yet. Opacity inherited from ancestors is not followed.
  function backgroundOf(el) {
    try {
      var style = win.getComputedStyle(el);
      var color = parseColor(style.backgroundColor);
      var opacity = parseFloat(style.opacity);
      if (color && opacity >= 0 && opacity < 1) color = [color[0], color[1], color[2], color[3] * opacity];
      return color;
    } catch (e) { return null; }
  }

  function themeColor() {
    var metas = doc.querySelectorAll("meta[name=theme-color]");
    // Of several (one per prefers-color-scheme), the one whose media applies; else the first.
    var chosen = null;
    for (var i = 0; i < metas.length; i++) {
      var media = metas[i].getAttribute("media");
      if (!media) { if (!chosen) chosen = metas[i]; continue; }
      try { if (win.matchMedia(media).matches) { chosen = metas[i]; break; } } catch (e) { /* unusable media: skipped */ }
    }
    return chosen ? parseColor(chosen.getAttribute("content")) : null;
  }

  // The color the page paints at (x, y): the element stack under that point, then body, html and
  // the theme color. Background images are not read.
  function colorAt(x, y) {
    var stack = [];
    try { stack = Array.prototype.slice.call(doc.elementsFromPoint(x, y)); } catch (e) { /* no stack */ }
    var layers = stack.map(backgroundOf);
    // The stack usually ends with body and html already; counting a translucent one twice would
    // darken it.
    [doc.body, doc.documentElement].forEach(function (el) {
      if (el && stack.indexOf(el) === -1) layers.push(backgroundOf(el));
    });
    var seen = compose(layers);
    if (seen.alpha < 0.999) {
      var base = themeColor();
      if (base) {
        seen = compose(layers.concat([base]));
      } else if (seen.alpha > 0) {
        // Translucent paint over nothing known: the canvas behind it is white by default.
        seen = compose(layers.concat([[255, 255, 255, 1]]));
      } else {
        return null;
      }
    }
    if (!(seen.alpha > 0)) return null;
    return [seen.r / seen.alpha, seen.g / seen.alpha, seen.b / seen.alpha];
  }

  var sent = {};
  function report(bar, rgb) {
    if (!rgb) return;
    var style = styleOf(rgb);
    if (sent[bar] === style) return;
    var capacitor = win.Capacitor;
    if (!capacitor || typeof capacitor.nativePromise !== "function") return;
    sent[bar] = style;
    try {
      capacitor.nativePromise("SystemBars", "setStyle", { style: style, bar: bar }).then(null, function () { sent[bar] = null; });
    } catch (e) { sent[bar] = null; }
  }

  function check() {
    var android = platform() === "android";
    if (android && !drawsUnderBars()) return;
    report("StatusBar", colorAt(win.innerWidth / 2, 1));
    // iOS SystemBars.setStyle ignores `bar` and styles the status bar, so a bottom sample would
    // override the top one there.
    if (android) report("NavigationBar", colorAt(win.innerWidth / 2, win.innerHeight - 2));
  }

  // A page that mutates all the time would starve a restarting debounce, so a pending check is
  // kept and the burst is read once, at the end of the window.
  var timer = null, trailing = null;
  function schedule() {
    if (timer) return;
    timer = win.setTimeout(function () {
      timer = null;
      check();
      // A CSS color transition is still running when the first check reads it.
      if (trailing) win.clearTimeout(trailing);
      trailing = win.setTimeout(function () { trailing = null; check(); }, TRAILING_MS);
    }, DEBOUNCE_MS);
  }

  // The native side asks for a fresh report when it may have reset the style on its own (the iOS
  // status bar plugin re-applies its configured style whenever the view appears again).
  shellBars.recheck = function () { sent = {}; schedule(); };

  ["DOMContentLoaded", "load", "pageshow", "resize", "visibilitychange"].forEach(function (name) {
    (name === "visibilitychange" ? doc : win).addEventListener(name, schedule);
  });
  doc.addEventListener("transitionend", schedule, true);
  try { win.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", schedule); } catch (e) { /* older engines */ }
  try {
    new win.MutationObserver(schedule).observe(doc.documentElement, { attributes: true, childList: true, subtree: true });
  } catch (e) { /* no observer: the events above still apply */ }
  schedule();
})(window);
