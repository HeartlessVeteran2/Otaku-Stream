package com.otakustream.core.sources.mangayomi.runtime

// The JavaScript bootstrap injected into every extension's QuickJS context before the
// extension source runs. It defines the Mangayomi/AnymeX runtime surface that extensions
// (`class DefaultExtension extends MProvider`) are written against — the `MProvider` base
// class, the async-looking `Client` HTTP wrapper, and the jsoup-backed `Document`/`Element`
// DOM API — on top of a small set of native bridge functions the Kotlin host installs as
// globals (`__http`, `__html_*`).
//
// Design notes:
// - The native bridges are synchronous/blocking (they run on the engine's dedicated thread).
//   Extensions `await` `client.get(...)`; awaiting a plain (non-thenable) value simply yields
//   it, so a blocking bridge composes correctly with `async/await` without a real event loop.
// - The DOM bridge is handle-based: jsoup nodes live on the Kotlin side keyed by an int handle,
//   and the JS `Document`/`Element` classes are thin wrappers over those handles. This avoids
//   marshalling whole node trees across the JNI boundary.
// - Crypto/deobfuscation helpers and video extractors are intentionally NOT here yet; they land
//   in a later PR. Extensions that need them will fail at call time rather than load time.
internal const val MANGAYOMI_HOST_BOOTSTRAP = """
(function (global) {
  function __headersToObject(h) {
    var out = {};
    if (h) { for (var k in h) { if (Object.prototype.hasOwnProperty.call(h, k)) out[k] = h[k]; } }
    return out;
  }

  function Response(raw) {
    this.statusCode = raw.status;
    this.body = raw.body;
    this.headers = raw.headers || {};
    this.request = { url: raw.url };
  }

  function Client(opts) { this.opts = opts || {}; }
  Client.prototype._req = function (method, url, headers, body) {
    var raw = JSON.parse(__http(method, url, JSON.stringify(headers || {}), body == null ? null : String(body)));
    if (raw.error) { throw new Error(raw.error); }
    return new Response(raw);
  };
  Client.prototype.get = function (url, headers) { return this._req('GET', url, headers, null); };
  Client.prototype.post = function (url, headers, body) { return this._req('POST', url, headers, body); };
  Client.prototype.request = function (r) { return this._req(r.method || 'GET', r.url, r.headers, r.body); };

  function Element(handle) { this.__h = handle; }
  Element.prototype.select = function (sel) {
    return JSON.parse(__html_select(this.__h, sel)).map(function (h) { return new Element(h); });
  };
  Element.prototype.selectFirst = function (sel) {
    var h = __html_selectFirst(this.__h, sel);
    return h < 0 ? null : new Element(h);
  };
  Element.prototype.attr = function (name) { return __html_attr(this.__h, name, false); };
  Element.prototype.getHref = function (name) { return __html_attr(this.__h, name || 'href', true); };
  Element.prototype.attrAbs = function (name) { return __html_attr(this.__h, name, true); };
  Object.defineProperty(Element.prototype, 'text', { get: function () { return __html_text(this.__h); } });
  Object.defineProperty(Element.prototype, 'html', { get: function () { return __html_html(this.__h, false); } });
  Object.defineProperty(Element.prototype, 'outerHtml', { get: function () { return __html_html(this.__h, true); } });

  function Document(html) { this.__h = __html_load(html); }
  Document.prototype = Object.create(Element.prototype);
  Document.prototype.constructor = Document;

  // Base64 (QuickJS has no DOM atob/btoa). ISO-8859-1 round-trips bytes 1:1 through the bridge.
  global.atob = function (s) { return __b64_decode(s); };
  global.btoa = function (s) { return __b64_encode(s); };

  // Crypto / deobfuscation, matching the Mangayomi host API names.
  global.encryptAESCryptoJS = function (text, password) { return __crypto_encrypt_cryptojs(text, password); };
  global.decryptAESCryptoJS = function (cipher, password) { return __crypto_decrypt_cryptojs(cipher, password); };
  global.cryptoHandler = function (text, iv, key, encrypt) {
    return __crypto_handler(text, iv, key, encrypt === undefined ? true : encrypt);
  };
  global.unpackJs = function (source) { return __unpack_js(source); };

  function MProvider() {}
  MProvider.prototype.getPreference = function (key) {
    var raw = __pref_get(key);
    if (raw == null) return null;
    try { return JSON.parse(raw).value; } catch (e) { return null; }
  };
  // Also expose the crypto/unpack helpers as methods, since some extensions call them via `this`.
  MProvider.prototype.encryptAESCryptoJS = global.encryptAESCryptoJS;
  MProvider.prototype.decryptAESCryptoJS = global.decryptAESCryptoJS;
  MProvider.prototype.cryptoHandler = global.cryptoHandler;
  MProvider.prototype.unpackJs = global.unpackJs;
  // Generic extractor for the large family of hosts (Filemoon, StreamWish, VidHide, …) that hide
  // their playlist inside a p.a.c.k.e.r'd `eval(...)`: unpack, then pull the first m3u8/mp4.
  MProvider.prototype.extractPackedStreams = function (html) {
    var unpacked = unpackJs(html);
    var out = [];
    var re = /(https?:\/\/[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*)/g;
    var m;
    while ((m = re.exec(unpacked)) !== null) {
      out.push({ url: m[1], quality: 'default', headers: {} });
    }
    return out;
  };
  MProvider.prototype.substringBefore = function (s, d) { var i = s.indexOf(d); return i < 0 ? s : s.substring(0, i); };
  MProvider.prototype.substringBeforeLast = function (s, d) { var i = s.lastIndexOf(d); return i < 0 ? s : s.substring(0, i); };
  MProvider.prototype.substringAfter = function (s, d) { var i = s.indexOf(d); return i < 0 ? '' : s.substring(i + d.length); };
  MProvider.prototype.substringAfterLast = function (s, d) { var i = s.lastIndexOf(d); return i < 0 ? '' : s.substring(i + d.length); };
  MProvider.prototype.substringBetween = function (s, a, b) {
    var i = s.indexOf(a); if (i < 0) return '';
    i += a.length; var j = s.indexOf(b, i); return j < 0 ? '' : s.substring(i, j);
  };

  global.Client = Client;
  global.Response = Response;
  global.Document = Document;
  global.Element = Element;
  global.MProvider = MProvider;
})(globalThis);
"""
