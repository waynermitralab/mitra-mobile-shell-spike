// Written by mitra-mobile-shell/generator, shipped in the app bundle as public/shell-voice.js and
// injected by ShellVoiceCapture as a document start script for the app origin (evaluateJavascript on
// the app host's pages at page commit when the WebView cannot do that).
//
// Android silences the microphone of an app that is not in the foreground unless it runs a
// foreground service of type microphone, and the published page does not know the shell exists.
// So this watches what the page does with getUserMedia and tells the native side when a live audio
// track exists ("capturing") and when the last one is gone ("idle"). It only observes: the page
// gets the same promise, the same stream and the same rejections.
//
// The channel is mitraShellVoice, a WebMessageListener the native side registers with the bridge's
// origin rules and answers only for the main frame of the app host (see ShellVoiceCapture). Without it there is nothing to report to and the script does nothing.
(function (win) {
  "use strict";
  if (win.top !== win || win.__mitraShellVoice) return;
  var channel = win.mitraShellVoice;
  var devices = win.navigator && win.navigator.mediaDevices;
  if (!channel || typeof channel.postMessage !== "function") return;
  if (!devices || typeof devices.getUserMedia !== "function") return;
  win.__mitraShellVoice = true;

  // stop() does not fire "ended", and a page can also reach the native stop through the prototype,
  // which no wrapper on the instance sees. A track that ended without telling is found by looking.
  var POLL_MS = 2000;

  var live = [];
  var state = "idle";
  var poll = null;

  function send(next) {
    if (next === state) return;
    state = next;
    try { channel.postMessage(next); } catch (e) { /* the channel is gone with the document */ }
  }

  function update() {
    live = live.filter(function (track) { return track.readyState === "live"; });
    send(live.length ? "capturing" : "idle");
    if (live.length && !poll) poll = win.setInterval(update, POLL_MS);
    if (!live.length && poll) { win.clearInterval(poll); poll = null; }
  }

  function watch(track) {
    live.push(track);
    track.addEventListener("ended", update);
    var stop = track.stop;
    // On the instance, so MediaStreamTrack.prototype stays the browser's own.
    track.stop = function () {
      try { return stop.apply(this, arguments); } finally { update(); }
    };
  }

  function observe(stream) {
    var tracks = stream && typeof stream.getAudioTracks === "function" ? stream.getAudioTracks() : [];
    var added = false;
    for (var i = 0; i < tracks.length; i++) {
      if (tracks[i].readyState === "live" && live.indexOf(tracks[i]) === -1) { watch(tracks[i]); added = true; }
    }
    if (added) update();
  }

  var original = devices.getUserMedia;
  devices.getUserMedia = function () {
    var result = original.apply(this, arguments);
    // The no-op rejection handler keeps this branch from reporting an unhandled rejection of its
    // own; the rejection the page gets is the original promise's, untouched.
    if (result && typeof result.then === "function") {
      result.then(function (stream) {
        try { observe(stream); } catch (e) { /* observing must never surface in the page */ }
      }, function () {});
    }
    return result;
  };
})(window);
