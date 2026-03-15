/**
 * LinkPi MiniApp SDK — Modules
 * Python service module calls.
 * Requires core.js (callback system).
 */
(function() {
  'use strict';
  var B = window.NativeBridge;

  /**
   * Call a running module's HTTP endpoint.
   * @param {string} moduleName - Module name or ID.
   * @param {string} path - HTTP path (e.g., '/hello', '/process').
   * @param {object} [options] - { method: 'GET'|'POST'|..., body: object|string }
   * @returns {Promise<object>}
   */
  window.callModule = function(moduleName, path, options) {
    return new Promise(function(resolve, reject) {
      if (!B || !B.callModule) {
        reject(new Error('NativeBridge unavailable')); return;
      }
      var opts = options || {};
      var method = opts.method || 'GET';
      var body = opts.body ? (typeof opts.body === 'string' ? opts.body : JSON.stringify(opts.body)) : '';
      var id = window.__genId('_m');
      window.__nfCbs[id] = function(r) {
        if (r.error) reject(new Error(r.error));
        else resolve(r);
      };
      B.callModule(id, moduleName, path || '/', method, body);
    });
  };

  /**
   * List all modules and their running status.
   * @returns {Array<{id, name, serviceType, running, port, description, mainScript, scripts}>}
   */
  window.listModules = function() {
    if (!B || !B.listModules) return [];
    try { return JSON.parse(B.listModules()); } catch (e) { return []; }
  };
})();
