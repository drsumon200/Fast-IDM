function sendToApp(url, referer = "", is_stream = false, quality = "") {
  return fetch("http://127.0.0.1:9876/download", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url, referer, is_stream, quality })
  })
    .then((res) => res.ok)
    .catch(() => false);
}

// Add IDM-style Right-Click Menu
chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({
    id: "udm-download",
    title: "⬇ Download with Universal DM",
    contexts: ["link", "video", "audio", "page"]
  });
});

chrome.contextMenus.onClicked.addListener((info, tab) => {
  if (info.menuItemId !== "udm-download") return;
  const targetUrl = info.linkUrl || info.srcUrl || info.pageUrl || (tab && tab.url);
  if (targetUrl) {
    sendToApp(targetUrl, info.pageUrl || "", false);
  }
});

// Sniff direct video files loaded in tabs
chrome.webRequest.onHeadersReceived.addListener(
  (details) => {
    if (details.tabId < 0) return;
    const headers = details.responseHeaders || [];
    const ctHeader = headers.find((h) => h.name.toLowerCase() === "content-type");
    const contentType = ctHeader ? ctHeader.value.toLowerCase() : "";

    const isVideoMime = contentType.startsWith("video/");
    const isVideoExt = /\.(mp4|webm|mkv|mov|m4v)(\?|$)/i.test(details.url);

    if (isVideoMime || isVideoExt) {
      if (!tabVideos[details.tabId]) tabVideos[details.tabId] = [];
      const list = tabVideos[details.tabId];
      if (!list.includes(details.url)) {
        list.push(details.url);
        if (list.length > 10) list.shift();
      }
    }
  },
  { urls: ["<all_urls>"] },
  ["responseHeaders"]
);

chrome.tabs.onRemoved.addListener((tabId) => delete tabVideos[tabId]);
chrome.tabs.onUpdated.addListener((tabId, info) => {
  if (info.status === "loading") delete tabVideos[tabId];
});

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg.action === "get_detected_videos") {
    const tabId = sender.tab ? sender.tab.id : -1;
    sendResponse({ videos: tabVideos[tabId] || [] });
    return;
  }

  if (msg.action === "send_to_udm") {
    sendToApp(msg.url, msg.referer, msg.is_stream, msg.quality || "").then((ok) => sendResponse({ ok }));
    return true;
  }
});