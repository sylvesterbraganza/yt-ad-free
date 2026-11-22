/*
 * Hide AV1 video support from YouTube, so it sends VP9 or H.264 instead.
 *
 * Why: YouTube sends AV1 to Firefox-based browsers whenever the engine says it can play it.
 * Gecko can always play AV1 in *software*, but most phones have no AV1 *hardware* decoder,
 * so playback (especially Shorts, which autoplays and preloads) becomes slow and drains the
 * battery. VP9/H.264 are hardware-decoded on almost every phone.
 *
 * How: YouTube asks the browser which formats it supports via MediaSource.isTypeSupported()
 * and HTMLMediaElement.canPlayType(). We answer "no" for AV1. Same idea as the well-known
 * h264ify extension. Runs before any page script (document_start).
 *
 * exportFunction() is Firefox's API for putting a content-script function into the page.
 */
(() => {
  const isAv1 = (type) => typeof type === "string" && /av01|av1/i.test(type);
  const page = window.wrappedJSObject;
  let reported = false;
  const reportOnce = (type) => {
    if (!reported) {
      reported = true;
      console.info("[ProjectY] hid AV1 from YouTube (" + type + ")");
    }
  };

  const ms = page.MediaSource;
  if (ms && typeof ms.isTypeSupported === "function") {
    const original = ms.isTypeSupported;
    exportFunction(function (type) {
      if (isAv1(type)) { reportOnce(type); return false; }
      return original.call(ms, type);
    }, ms, { defineAs: "isTypeSupported" });
  }

  const mediaProto = page.HTMLMediaElement && page.HTMLMediaElement.prototype;
  if (mediaProto && typeof mediaProto.canPlayType === "function") {
    const originalCanPlay = mediaProto.canPlayType;
    exportFunction(function (type) {
      if (isAv1(type)) { reportOnce(type); return ""; }
      return originalCanPlay.call(this, type);
    }, mediaProto, { defineAs: "canPlayType" });
  }
})();
