// Döngü: önce ağ, çevrimdışıysa önbellek. Yalnızca uygulamanın kendi dosyalarını yönetir.
const CACHE = "dongu-v2";
const ASSETS = ["./dongu.html", "./manifest.json", "./icon.svg"];

self.addEventListener("install", e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(ASSETS)).then(() => self.skipWaiting()));
});
self.addEventListener("activate", e => {
  e.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k.startsWith("dongu-") && k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});
self.addEventListener("fetch", e => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== location.origin) return;
  if (!ASSETS.some(a => url.pathname.endsWith(a.slice(1)))) return;
  e.respondWith(
    fetch(e.request)
      .then(res => { if (res.ok){ const copy = res.clone(); caches.open(CACHE).then(c => c.put(e.request, copy)); } return res; })
      .catch(() => caches.match(e.request, { ignoreSearch: true }))
  );
});
