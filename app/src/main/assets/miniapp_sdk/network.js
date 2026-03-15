/**
 * LinkPi MiniApp SDK — Network
 * HTTP requests via native bridge (bypasses CORS, HTTPS only).
 * Requires core.js (callback system).
 */
(function() {
  'use strict';
  var B = window.NativeBridge;

  /**
   * Fetch a URL via native HTTP client.
   * @param {string} url - HTTPS URL to fetch.
   * @param {object} [options] - {method, headers, body}
   * @returns {Promise<{status, statusText, headers, body, ok, json(), text()}>}
   */
  window.nativeFetch = function(url, o) {
    o = o || {};
    return new Promise(function(resolve, reject) {
      if (!B || !B.httpRequest) {
        reject(new Error('NativeBridge unavailable')); return;
      }
      var id = window.__genId('_');
      window.__nfCbs[id] = function(r) {
        if (r.error) reject(new Error(r.error));
        else resolve({
          status: r.status, statusText: r.statusText, headers: r.headers,
          body: r.body, ok: r.status >= 200 && r.status < 300,
          json: function() { return Promise.resolve(JSON.parse(r.body)); },
          text: function() { return Promise.resolve(r.body); }
        });
      };
      B.httpRequest(id, url, o.method || 'GET',
        JSON.stringify(o.headers || {}), o.body || '');
    });
  };
})();
