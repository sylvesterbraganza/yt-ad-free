/*
 * Optional "H.264 only" mode, switched on in Settings (the app enables/disables this extension).
 *
 * Builds on tweaks/no-av1.js: that one hides AV1; this one also hides VP9 and VP8, so YouTube
 * falls back to H.264 (avc1). H.264 is the cheapest format for a phone to decode and every phone
 * has a hardware decoder for it. Trade-off: YouTube offers H.264 only up to 1080p, and it uses
 * a bit more data than VP9 at the same quality.
 */
(() => {
  const isHeavy = (type) => typeof type === "string" && /av01|av1|vp0?9|vp0?8/i.test(type);
  const page = window.wrappedJSObject;
  let reported = false;
  const reportOnce = (type) => {
    if (!reported) {
      reported = true;
      console.info("[ProjectY] H.264-only mode: hid " + type);
    }
  };

  const ms = page.MediaSource;
  if (ms && typeof ms.isTypeSupported === "function") {
    const original = ms.isTypeSupported;
    exportFunction(function (type) {
      if (isHeavy(type)) { reportOnce(type); return false; }
      return original.call(ms, type);
    }, ms, { defineAs: "isTypeSupported" });
  }

  const mediaProto = page.HTMLMediaElement && page.HTMLMediaElement.prototype;
  if (mediaProto && typeof mediaProto.canPlayType === "function") {
    const originalCanPlay = mediaProto.canPlayType;
    exportFunction(function (type) {
      if (isHeavy(type)) { reportOnce(type); return ""; }
      return originalCanPlay.call(this, type);
    }, mediaProto, { defineAs: "canPlayType" });
  }
})();
