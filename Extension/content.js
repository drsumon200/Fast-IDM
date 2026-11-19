(() => {
  if (window.__udmInjected) return;
  window.__udmInjected = true;

  const STREAM_HOSTS = [
    "youtube.com", "youtu.be", "facebook.com", "fb.watch",
    "instagram.com", "tiktok.com", "x.com", "twitter.com",
    "vimeo.com", "dailymotion.com", "reddit.com", "twitch.tv"
  ];

  const QUALITIES = [
    { label: "🎬 Best MP4 (Max)", value: "Best MP4" },
    { label: "📺 1080p Full HD", value: "1080p" },
    { label: "📺 720p HD", value: "720p" },
    { label: "📱 480p SD", value: "480p" },
    { label: "📱 360p Data Saver", value: "360p" },
    { label: "🎵 Audio Only (MP3)", value: "Audio (MP3)" }
  ];

  let activeVideo = null;
  let hideTimer = null;

  // Shadow DOM host so website CSS cannot break our popup
  const host = document.createElement("div");
  Object.assign(host.style, {
    position: "fixed",
    zIndex: "2147483647",
    display: "none",
    top: "0px",
    left: "0px"
  });
  const shadow = host.attachShadow({ mode: "open" });

  shadow.innerHTML = `
    <style>
      .udm-wrap {
        font-family: "Segoe UI", Arial, sans-serif;
        position: relative;
        display: inline-block;
        user-select: none;
      }
      .udm-btn {
        display: flex;
        align-items: center;
        gap: 6px;
        padding: 7px 13px;
        background: #2563eb;
        color: #ffffff;
        border: 1px solid #93c5fd;
        border-radius: 6px;
        font-size: 12px;
        font-weight: 600;
        cursor: pointer;
        box-shadow: 0 4px 14px rgba(0, 0, 0, 0.45);
        white-space: nowrap;
      }
      .udm-btn:hover {
        background: #1d4ed8;
      }
      .udm-menu {
        display: none;
        position: absolute;
        top: 100%;
        right: 0;
        margin-top: 4px;
        min-width: 175px;
        background: #1e1e2e;
        border: 1px solid #45475a;
        border-radius: 8px;
        padding: 5px;
        box-shadow: 0 8px 24px rgba(0, 0, 0, 0.55);
      }
      .udm-wrap.open .udm-menu {
        display: block;
      }
      .udm-title {
        font-size: 10px;
        color: #9399b2;
        padding: 4px 8px 6px;
        border-bottom: 1px solid #313244;
        margin-bottom: 4px;
        text-transform: uppercase;
        letter-spacing: 0.5px;
      }
      .udm-item {
        display: block;
        width: 100%;
        text-align: left;
        background: transparent;
        color: #cdd6f4;
        border: none;
        border-radius: 5px;
        padding: 7px 10px;
        font-size: 12px;
        font-weight: 500;
        cursor: pointer;
        font-family: inherit;
      }
      .udm-item:hover {
        background: #3b82f6;
        color: #ffffff;
      }
    </style>
    <div class="udm-wrap" id="wrap">
      <button class="udm-btn" id="mainBtn">⬇ Download this video ▾</button>
      <div class="udm-menu" id="menu">
        <div class="udm-title">Select Quality</div>
      </div>
    </div>
  `;

  document.documentElement.appendChild(host);

  const wrap = shadow.getElementById("wrap");
  const mainBtn = shadow.getElementById("mainBtn");
  const menu = shadow.getElementById("menu");

  // Populate quality menu buttons
  QUALITIES.forEach((q) => {
    const item = document.createElement("button");
    item.className = "udm-item";
    item.textContent = q.label;
    item.addEventListener("click", (e) => {
      e.preventDefault();
      e.stopPropagation();
      wrap.classList.remove("open");
      triggerDownload(q.value);
    });
    menu.appendChild(item);
  });

  function isStreamSite() {
    const h = window.location.hostname.toLowerCase();
    return STREAM_HOSTS.some((d) => h === d || h.endsWith("." + d));
  }

  function findFacebookVideoUrl(videoEl) {
    const pageUrl = window.location.href;
    if (/\/(watch|reel|videos|share\/v|share\/r)/i.test(pageUrl)) {
      return pageUrl;
    }
    let node = videoEl;
    for (let depth = 0; depth < 18 && node; depth++) {
      const links = node.querySelectorAll
        ? node.querySelectorAll('a[href*="/videos/"], a[href*="/watch"], a[href*="/reel/"], a[href*="/share/v"], a[href*="/posts/"]')
        : [];
      for (const a of links) {
        if (a.href && a.href.startsWith("http")) return a.href;
      }
      node = node.parentElement;
    }
    return pageUrl;
  }

  function showPopupForVideo(video) {
    clearTimeout(hideTimer);
    activeVideo = video;
    const rect = video.getBoundingClientRect();
    const top = Math.max(8, rect.top + 10);
    const left = Math.max(8, Math.min(window.innerWidth - 200, rect.right - 190));
    host.style.top = top + "px";
    host.style.left = left + "px";
    host.style.display = "block";
  }

  function scheduleHide() {
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => {
      wrap.classList.remove("open");
      host.style.display = "none";
      activeVideo = null;
    }, 350);
  }

  // Open quality menu when mouse hovers over the popup button/wrap
  wrap.addEventListener("mouseenter", () => {
    clearTimeout(hideTimer);
    wrap.classList.add("open");
  });

  wrap.addEventListener("mouseleave", () => {
    wrap.classList.remove("open");
    scheduleHide();
  });

  // Clicking the main button directly downloads Best MP4
  mainBtn.addEventListener("click", (e) => {
    e.preventDefault();
    e.stopPropagation();
    wrap.classList.remove("open");
    triggerDownload("Best MP4");
  });

  // Detect mouse hover over any visible <video>
  let lastCheck = 0;
  document.addEventListener("mousemove", (e) => {
    const now = Date.now();
    if (now - lastCheck < 80) return;
    lastCheck = now;

    if (e.target === host || host.contains(e.target)) return;

    const videos = document.querySelectorAll("video");
    for (const v of videos) {
      const r = v.getBoundingClientRect();
      if (
        r.width >= 150 &&
        r.height >= 100 &&
        e.clientX >= r.left &&
        e.clientX <= r.right &&
        e.clientY >= r.top &&
        e.clientY <= r.bottom
      ) {
        showPopupForVideo(v);
        return;
      }
    }
    if (host.style.display === "block" && !wrap.classList.contains("open")) {
      scheduleHide();
    }
  });

  function sendUrl(url, isStream, quality) {
    const original = "⬇ Download this video ▾";
    mainBtn.textContent = `Sending (${quality})...`;
    chrome.runtime.sendMessage(
      { action: "send_to_udm", url, referer: window.location.href, is_stream: isStream, quality },
      (resp) => {
        mainBtn.textContent = resp && resp.ok ? `✓ Sent (${quality})` : "⚠ Open UniversalDM first";
        setTimeout(() => (mainBtn.textContent = original), 2500);
      }
    );
  }

  function triggerDownload(quality) {
    const hostName = window.location.hostname.toLowerCase();

    // 1. Facebook (Feed, Watch, Reels)
    if (hostName.includes("facebook.com") || hostName.includes("fb.watch")) {
      sendUrl(findFacebookVideoUrl(activeVideo), true, quality);
      return;
    }

    // 2. YouTube, TikTok, Instagram, X, Vimeo, etc.
    if (isStreamSite()) {
      sendUrl(window.location.href, true, quality);
      return;
    }

    // 3. Direct HTML5 video
    let videoUrl = "";
    if (activeVideo) {
      videoUrl = activeVideo.currentSrc || activeVideo.src || "";
      if (!videoUrl) {
        const srcEl = activeVideo.querySelector("source[src]");
        if (srcEl) videoUrl = srcEl.src;
      }
    }

    if (videoUrl.startsWith("http://") || videoUrl.startsWith("https://")) {
      sendUrl(videoUrl, false, quality);
      return;
    }

    // 4. Fallback
    chrome.runtime.sendMessage({ action: "get_detected_videos" }, (resp) => {
      const list = (resp && resp.videos) || [];
      if (list.length > 0) {
        sendUrl(list[list.length - 1], false, quality);
      } else {
        sendUrl(window.location.href, true, quality);
      }
    });
  }
})();