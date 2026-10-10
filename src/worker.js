// Serves the static PWA from ./public via Workers static assets.
// The worker only runs for requests that don't match a static file.

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    // Web Share Target fallback. Normally sw.js intercepts POST /share-target in the browser.
    // If the service worker isn't running yet (first launch after install, cleared data),
    // send the user to the app with an error notice instead of a 405.
    if (request.method === 'POST' && url.pathname === '/share-target') {
      return Response.redirect(new URL('/?share=error', url).href, 303);
    }

    return env.ASSETS.fetch(request);
  },
};
