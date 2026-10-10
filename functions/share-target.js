// Fallback for the Web Share Target.
// Normally sw.js intercepts POST /share-target and the request never reaches Cloudflare.
// If the service worker is not running yet (first launch after install, cleared data),
// send the user to the app with an error notice instead of a 405 page.
export const onRequestPost = ({ request }) =>
  Response.redirect(new URL('/?share=error', request.url).href, 303);
