// Serves the static PWA from ./public via Workers static assets.
// The worker only runs for requests that don't match a static file.

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    // Web Share Target fallback. Normally sw.js intercepts POST /share-target in the browser.
    // If the service worker isn't running yet (first launch after install, cleared data),
    // send the user to the app with an error notice instead of a 405.
    if (request.method === 'POST' && url.pathname === '/share-target') {
      return Response.redirect(new URL('/?share=error&why=nosw', url).href, 303);
    }

    // Digital Asset Links for the Android app (TWA). Chrome checks this file to trust the APK
    // and hide the URL bar. Configure TWA_PACKAGE_ID and TWA_SHA256 in wrangler.toml.
    if (url.pathname === '/.well-known/assetlinks.json') {
      return assetLinks(env);
    }

    return env.ASSETS.fetch(request);
  },
};

function assetLinks(env) {
  const fingerprints = String(env.TWA_SHA256 || '')
    .split(',')
    .map((f) => f.trim().toUpperCase())
    .filter((f) => /^([0-9A-F]{2}:){31}[0-9A-F]{2}$/.test(f));

  if (!env.TWA_PACKAGE_ID || fingerprints.length === 0) {
    return new Response('Not found', { status: 404 });
  }

  const body = [
    {
      relation: ['delegate_permission/common.handle_all_urls'],
      target: {
        namespace: 'android_app',
        package_name: env.TWA_PACKAGE_ID,
        sha256_cert_fingerprints: fingerprints,
      },
    },
  ];

  return new Response(JSON.stringify(body, null, 2), {
    headers: {
      'Content-Type': 'application/json',
      'Cache-Control': 'public, max-age=3600',
      'Access-Control-Allow-Origin': '*',
    },
  });
}
