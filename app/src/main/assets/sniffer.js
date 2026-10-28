/* Grabbit media sniffer — injected into every page and iframe.
 * Finds <video>, <audio>, <source>, <img>, srcset/poster, CSS backgrounds and
 * anything the page later plays or inserts, then reports it to the app through
 * the window.GrabbitBridge JavaScript interface. Blob/MSE sources cannot be
 * downloaded directly, so they are reported as "stream" hints only. */
(function () {
  if (window.__grabbitInstalled) { window.__grabbitRescan && window.__grabbitRescan(); return; }
  window.__grabbitInstalled = true;

  var seen = Object.create(null);
  var MIN_IMG = 200; // ignore icons / avatars smaller than this (CSS px)

  function post(type, url, extra) {
    try {
      if (!url || typeof url !== 'string') return;
      if (url.indexOf('data:') === 0 && url.length < 2048) return; // tiny inline data, skip
      if (url.indexOf('blob:') === 0) return;
      if (url.indexOf('javascript:') === 0) return;
      var abs;
      try { abs = new URL(url, document.baseURI).href; } catch (e) { return; }
      var key = type + '|' + abs.split('#')[0];
      if (seen[key]) return;
      seen[key] = 1;
      var payload = {
        type: type, url: abs,
        page: location.href, title: document.title || location.hostname,
        width: (extra && extra.width) || 0, height: (extra && extra.height) || 0,
        duration: (extra && extra.duration) || 0, poster: (extra && extra.poster) || ''
      };
      if (window.GrabbitBridge && window.GrabbitBridge.onMedia) {
        window.GrabbitBridge.onMedia(JSON.stringify(payload));
      }
    } catch (e) {}
  }

  function mediaEl(el) {
    var type = el.tagName === 'AUDIO' ? 'audio' : 'video';
    var extra = { width: el.videoWidth || el.clientWidth, height: el.videoHeight || el.clientHeight,
                  duration: isFinite(el.duration) ? el.duration : 0, poster: el.poster || '' };
    if (el.currentSrc) post(type, el.currentSrc, extra);
    if (el.src) post(type, el.src, extra);
    var srcs = el.querySelectorAll('source');
    for (var i = 0; i < srcs.length; i++) {
      var s = srcs[i];
      var t = (s.type || '').indexOf('audio') === 0 ? 'audio' : type;
      if (s.src) post(t, s.src, extra);
    }
    if (!el.__grabbitHooked) {
      el.__grabbitHooked = true;
      ['play', 'playing', 'loadedmetadata', 'loadeddata', 'durationchange'].forEach(function (ev) {
        el.addEventListener(ev, function () { mediaEl(el); }, true);
      });
    }
  }

  function imgEl(img) {
    var w = img.naturalWidth || img.clientWidth, h = img.naturalHeight || img.clientHeight;
    if (w && h && (w < MIN_IMG || h < MIN_IMG)) return;
    var extra = { width: w, height: h };
    if (img.currentSrc) post('image', img.currentSrc, extra);
    else if (img.src) post('image', img.src, extra);
    if (!img.complete && !img.__grabbitHooked) {
      img.__grabbitHooked = true;
      img.addEventListener('load', function () { imgEl(img); }, true);
    }
  }

  function scan(root) {
    root = root || document;
    var all = root.querySelectorAll ? root.querySelectorAll('video,audio,img') : [];
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el.tagName === 'IMG') imgEl(el); else mediaEl(el);
    }
    if (root.tagName === 'VIDEO' || root.tagName === 'AUDIO') mediaEl(root);
    if (root.tagName === 'IMG') imgEl(root);
    // Plain links to media files (download pages, file listings, forums).
    var links = root.querySelectorAll ? root.querySelectorAll('a[href]') : [];
    for (var k = 0; k < links.length; k++) {
      var href = links[k].getAttribute('href') || '';
      var path = href.split('?')[0].split('#')[0].toLowerCase();
      var m = path.match(/\.(mp4|m4v|webm|mkv|mov|3gp|avi|mp3|m4a|aac|ogg|opus|wav|flac|m3u8)$/);
      if (!m) continue;
      var t = /^(mp3|m4a|aac|ogg|opus|wav|flac)$/.test(m[1]) ? 'audio' : 'video';
      post(t, href, { linkText: (links[k].textContent || '').trim().slice(0, 80) });
    }
    // Open Graph hints are often the direct media of the page (Instagram, Twitter…)
    var og = document.querySelectorAll('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"],meta[property="og:image"],meta[name="twitter:player:stream"]');
    for (var j = 0; j < og.length; j++) {
      var p = (og[j].getAttribute('property') || og[j].getAttribute('name') || '');
      var c = og[j].getAttribute('content');
      if (c && c.indexOf('http') === 0) post(p.indexOf('image') >= 0 ? 'image' : 'video', c, {});
    }
  }

  // Media URLs carried inside JSON: GraphQL / XHR responses and inline data blobs (Instagram,
  // Facebook, X, TikTok…). Instagram's progressive "video_versions" (with sound) live here.
  function scanText(text) {
    try {
      if (!text || typeof text !== 'string' || text.length > 8000000) return;
      if (text.indexOf('.mp4') < 0 && text.indexOf('.m3u8') < 0 && text.indexOf('.m4a') < 0 && text.indexOf('.mp3') < 0 && text.indexOf('.webm') < 0) return;
      // JSON escapes: \/ and any \uXXXX (Instagram escapes '%' as \u0025, '=' as \u003d, '&' as \u0026).
      var s = text.replace(/\\\//g, '/').replace(/\\u([0-9a-fA-F]{4})/g, function (_, h) { return String.fromCharCode(parseInt(h, 16)); }).replace(/&amp;/g, '&');
      var re = /https?:\/\/[^"'\s<>\\]+?\.(mp4|m4a|m3u8|mp3|webm)(\?[^"'\s<>\\]*)?/g;
      var m, n = 0;
      while ((m = re.exec(s)) && n < 80) {
        n++;
        var u = m[0];
        if (/\.(jpg|png|webp)/i.test(u)) continue;
        post(/\.(mp3|m4a)(\?|$)/.test(u) ? 'audio' : 'video', u, {});
      }
    } catch (e) {}
  }
  try {
    var ofetch = window.fetch;
    if (ofetch && !ofetch.__grabbit) {
      window.fetch = function () {
        var p = ofetch.apply(this, arguments);
        try {
          p.then(function (r) {
            try {
              var ct = (r.headers && r.headers.get('content-type')) || '';
              if (ct.indexOf('json') >= 0 || ct.indexOf('text') >= 0 || ct.indexOf('javascript') >= 0) r.clone().text().then(scanText).catch(function () {});
            } catch (e) {}
          }).catch(function () {});
        } catch (e) {}
        return p;
      };
      window.fetch.__grabbit = 1;
    }
  } catch (e) {}
  try {
    var osend = XMLHttpRequest.prototype.send;
    if (!osend.__grabbit) {
      XMLHttpRequest.prototype.send = function () {
        try {
          this.addEventListener('load', function () {
            try { if (this.responseType === '' || this.responseType === 'text') scanText(this.responseText); } catch (e) {}
          });
        } catch (e) {}
        return osend.apply(this, arguments);
      };
      XMLHttpRequest.prototype.send.__grabbit = 1;
    }
  } catch (e) {}
  function scanInlineJson() {
    var sc = document.querySelectorAll('script[type="application/json"],script[type="application/ld+json"],script[data-sjs],script[data-content-len]');
    for (var i = 0; i < sc.length; i++) scanText(sc[i].textContent);
  }

  // Hook setters so dynamically assigned sources are caught (SPA players).
  function hookSrc(proto, type) {
    try {
      var d = Object.getOwnPropertyDescriptor(proto, 'src');
      if (!d || !d.set) return;
      Object.defineProperty(proto, 'src', {
        configurable: true, enumerable: d.enumerable,
        get: d.get,
        set: function (v) { d.set.call(this, v); try { setTimeout(function (el) { return function () { type === 'image' ? imgEl(el) : mediaEl(el); }; }(this), 0); } catch (e) {} }
      });
    } catch (e) {}
  }
  hookSrc(HTMLMediaElement.prototype, 'media');
  hookSrc(HTMLImageElement.prototype, 'image');

  try {
    var play = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () { try { mediaEl(this); } catch (e) {} return play.apply(this, arguments); };
  } catch (e) {}

  // Watch the DOM for new players/images.
  try {
    var pending = false;
    var mo = new MutationObserver(function () {
      if (pending) return; pending = true;
      setTimeout(function () { pending = false; scan(document); }, 400);
    });
    mo.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'srcset', 'poster'] });
  } catch (e) {}

  window.__grabbitRescan = function () { scan(document); scanInlineJson(); };
  scan(document);
  scanInlineJson();
  setTimeout(function () { scan(document); }, 1500);
  setTimeout(function () { scan(document); }, 4000);
})();
