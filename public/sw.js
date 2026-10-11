/* Lembar service worker: offline shell + Web Share Target receiver. */
const VERSION = 'lembar-v6';
const SHELL = [
  './',
  'manifest.webmanifest',
  'sample.pdf',
  'vendor/pdf.min.js',
  'vendor/pdf.worker.min.js',
  'icons/icon-192.png',
  'icons/icon-512.png',
  'icons/maskable-192.png',
  'icons/maskable-512.png'
];
const FONTS = 'lembar-fonts';
const SHARE = 'lembar-share';
const HOME = new URL('./', self.registration.scope).href;

self.addEventListener('install', e => {
  e.waitUntil(caches.open(VERSION).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil((async () => {
    for (const k of await caches.keys()) if (![VERSION, FONTS, SHARE].includes(k)) await caches.delete(k);
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', e => {
  const req = e.request;
  const url = new URL(req.url);

  // 1. PDF shared from another app (WhatsApp → Bagikan → Lembar)
  if (req.method === 'POST' && url.origin === location.origin && url.pathname.endsWith('/share-target')) {
    e.respondWith(receiveShare(req));
    return;
  }
  if (req.method !== 'GET') return;

  // 2. Google Fonts: cache first, so text looks the same offline
  if (url.hostname === 'fonts.googleapis.com' || url.hostname === 'fonts.gstatic.com') {
    e.respondWith(caches.open(FONTS).then(async c => {
      const hit = await c.match(req);
      if (hit) return hit;
      const res = await fetch(req);
      if (res.ok || res.type === 'opaque') c.put(req, res.clone());
      return res;
    }));
    return;
  }
  if (url.origin !== location.origin) return;

  // 3. App shell: serve from cache instantly, refresh in background
  e.respondWith((async () => {
    const c = await caches.open(VERSION);
    // Navigations always get the root page. Never cache /index.html itself: hosts like
    // Cloudflare Pages redirect it to /, and a redirected response cannot answer a navigation.
    const key = req.mode === 'navigate' ? HOME : req;
    const hit = await c.match(key, { ignoreSearch: req.mode === 'navigate' });
    const net = fetch(req).then(res => {
      if (res.ok && req.mode !== 'navigate') c.put(req, res.clone());
      if (res.ok && !res.redirected && req.mode === 'navigate' && url.pathname.endsWith('/')) c.put(HOME, res.clone());
      return res;
    }).catch(() => null);
    if (hit) { e.waitUntil(net); return hit; }
    return (await net) || new Response('Offline', { status: 503 });
  })());
});

async function receiveShare(req) {
  const home = new URL('./', self.registration.scope);
  try {
    const form = await req.formData();
    // Accept a file under any field name; Android apps don't always use the declared one.
    const files = [];
    const fields = [];
    for (const [key, value] of form.entries()) {
      fields.push(key + (value && typeof value === 'object' ? `(file ${value.size}b ${value.type || '?'})` : '(text)'));
      if (value && typeof value === 'object' && value.size > 0) files.push(value);
    }
    const file = files.find(f => /pdf$/i.test(f.type) || /\.pdf$/i.test(f.name)) || files[0];
    if (!file) {
      home.searchParams.set('share', 'error');
      home.searchParams.set('why', 'nofile');
      home.searchParams.set('detail', fields.join(', ').slice(0, 200) || 'empty form');
      return Response.redirect(home.href, 303);
    }
    const name = /\.pdf$/i.test(file.name) ? file.name : (file.name || 'Dokumen') + '.pdf';
    const c = await caches.open(SHARE);
    await c.put('/__shared__', new Response(file, {
      headers: { 'Content-Type': 'application/pdf', 'X-Name': encodeURIComponent(name) }
    }));
    home.searchParams.set('share', '1');
  } catch (err) {
    home.searchParams.set('share', 'error');
    home.searchParams.set('why', 'exception');
    home.searchParams.set('detail', String(err && err.message || err).slice(0, 200));
  }
  return Response.redirect(home.href, 303);
}
