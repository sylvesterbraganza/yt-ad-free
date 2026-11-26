/*
 * Two-way link between the app (Kotlin) and YouTube's page, over GeckoView native messaging.
 *
 * The app tells each page its "role":
 *   browse - the main view (home, search, Shorts...). Taps on video links are sent to the app,
 *            which opens them in the separate player view instead.
 *   player - the player view. The app can switch it into mini-player mode (only the video is
 *            shown, filling the small window), play/pause it, and hears when it plays/pauses.
 *
 * Messages page -> app:  {type:"openVideo", url}, {type:"playing", playing}
 * Messages app -> page:  {type:"role", role}, {type:"mini", on}, {type:"togglePlay"}, {type:"pause"}
 */
(() => {
  let port = null;
  let role = null;
  let tries = 0;

  const send = (msg) => { if (port) port.postMessage(msg); };

  function connect() {
    try {
      port = browser.runtime.connectNative("projecty");
    } catch (e) {
      port = null;
      return;
    }
    port.onMessage.addListener(onAppMessage);
    port.onDisconnect.addListener(() => {
      port = null;
      // The app may not be listening yet right after install; retry a few times.
      if (++tries < 10) setTimeout(connect, 1000);
    });
  }

  function mainVideo() {
    return document.querySelector(".html5-video-player video") || document.querySelector("video");
  }

  // ---- Mini player: show only the player, filling the (small) window -------------------------
  // The player itself must stay visible: YouTube pauses a player it thinks is hidden. So instead
  // of hiding everything, pin the player to the window and hide only what's *around* it.
  const MINI_CSS = `
    html.projecty-mini, html.projecty-mini body {
      overflow: hidden !important; background: #000 !important;
    }
    html.projecty-mini .projecty-mini-player {
      position: fixed !important; top: 0 !important; left: 0 !important;
      right: auto !important; bottom: auto !important; margin: 0 !important;
      width: 100vw !important; height: 100vh !important; max-height: none !important;
      z-index: 2147483647 !important; transform: none !important; background: #000 !important;
    }
    html.projecty-mini .projecty-mini-player video {
      width: 100vw !important; height: 100vh !important; left: 0 !important; top: 0 !important;
      object-fit: contain !important;
    }
    /* Ancestors with these properties would trap position:fixed inside them. */
    html.projecty-mini .projecty-mini-ancestor {
      transform: none !important; filter: none !important; perspective: none !important;
      contain: none !important; will-change: auto !important;
    }
    html.projecty-mini .projecty-mini-sibling { visibility: hidden !important; }`;

  const MINI_CLASSES = ["projecty-mini-player", "projecty-mini-ancestor", "projecty-mini-sibling"];

  function setMini(on) {
    if (!document.getElementById("projecty-mini-style")) {
      const style = document.createElement("style");
      style.id = "projecty-mini-style";
      style.textContent = MINI_CSS;
      (document.head || document.documentElement).appendChild(style);
    }
    document.querySelectorAll(MINI_CLASSES.map((c) => "." + c).join(",")).forEach((el) => {
      el.classList.remove(...MINI_CLASSES);
    });
    const wasMini = document.documentElement.classList.contains("projecty-mini");
    const wasPlaying = isPlaying();
    document.documentElement.classList.toggle("projecty-mini", on);
    if (!on) {
      // Expanding: YouTube still "remembers" the pause it tried while mini and applies it now.
      // Keep the video going if it was playing.
      if (wasMini && wasPlaying) {
        for (const delay of [300, 1000, 2000]) {
          setTimeout(() => { if (!isPlaying()) play(); }, delay);
        }
      }
      window.dispatchEvent(new Event("resize"));
      return;
    }
    const video = mainVideo();
    const player = document.getElementById("movie_player") || (video && video.parentElement);
    if (!player) return;
    player.classList.add("projecty-mini-player");
    for (let el = player; el.parentElement && el !== document.body; el = el.parentElement) {
      if (el !== player) el.classList.add("projecty-mini-ancestor");
      for (const sib of el.parentElement.children) {
        if (sib !== el) sib.classList.add("projecty-mini-sibling");
      }
    }
    // Let YouTube's player re-measure itself for the small window.
    window.dispatchEvent(new Event("resize"));
  }

  // ---- Playback via YouTube's own player API ------------------------------------------------
  // YouTube's mobile player only loads the video stream once *its* play is triggered, so calling
  // play() on the bare <video> isn't enough. #movie_player exposes playVideo()/pauseVideo().
  function ytPlayer() {
    const el = document.getElementById("movie_player");
    const page = el && el.wrappedJSObject;
    return page && typeof page.playVideo === "function" ? page : null;
  }

  function isPlaying() {
    const v = mainVideo();
    return !!v && !v.paused && !v.ended;
  }

  function play() {
    const yt = ytPlayer();
    if (yt) yt.playVideo();
    else { const v = mainVideo(); if (v) v.play(); }
  }

  let ourPause = false;

  function pause() {
    ourPause = true;
    try {
      const yt = ytPlayer();
      if (yt) yt.pauseVideo();
      const v = mainVideo();
      if (v && !v.paused) v.pause();
    } finally {
      ourPause = false;
    }
  }

  /** A video opened in the player view starts by itself, like tapping a video in the app. */
  function autoplayWhenReady() {
    if (location.pathname !== "/watch") return;
    let waited = 0;
    const timer = setInterval(() => {
      waited += 300;
      if (isPlaying() || waited > 15000) { clearInterval(timer); return; }
      if (ytPlayer()) { play(); clearInterval(timer); }
    }, 300);
  }

  function onAppMessage(msg) {
    switch (msg && msg.type) {
      case "role":
        role = msg.role;
        if (role === "player") autoplayWhenReady();
        break;
      case "mini":
        setMini(!!msg.on);
        send({ type: "playing", playing: isPlaying() });
        break;
      case "togglePlay":
        if (isPlaying()) pause(); else play();
        break;
      case "pause":
        if (isPlaying()) pause();
        break;
    }
  }

  // ---- Browse view: send taps on video links to the app's player ----------------------------
  window.addEventListener("click", (event) => {
    if (role !== "browse" || !port) return;
    const link = event.target && event.target.closest && event.target.closest("a[href]");
    if (!link) return;
    let url;
    try { url = new URL(link.getAttribute("href"), location.href); } catch (e) { return; }
    if (url.pathname !== "/watch" || !url.searchParams.get("v")) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    send({ type: "openVideo", url: url.href });
  }, true);

  // ---- Player view: report play/pause so the mini player's button shows the right icon --------
  document.addEventListener("play", (e) => {
    if (role === "player" && e.target === mainVideo()) send({ type: "playing", playing: true });
  }, true);
  document.addEventListener("pause", (e) => {
    if (role === "player" && e.target === mainVideo()) send({ type: "playing", playing: false });  }, true);

  // ---- Keep playing in the mini player --------------------------------------------------------
  // When the player window shrinks, YouTube's own code calls pause() (it treats a tiny player as
  // not watchable). While mini, ignore pauses that YouTube starts by itself; pauses from the
  // mini player's button (ourPause) still go through.
  try {
    const proto = window.wrappedJSObject.HTMLMediaElement.prototype;
    const originalPause = proto.pause;
    exportFunction(function () {
      const mini = document.documentElement.classList.contains("projecty-mini");
      if (role === "player" && mini && !ourPause) return undefined;
      return originalPause.call(this);
    }, proto, { defineAs: "pause" });
  } catch (e) {
    // If this fails the mini player still works; it just may pause when shrinking.
  }

  connect();
})();
