/**
 * LinkPi MiniApp SDK — Core
 * Callback system + error overlay. Always injected.
 */
(function() {
  'use strict';
  // ── Async callback registry (used by network, modules, realtime) ──
  window.__nfCbs = {};
  window.__nfCb = function(id, b) {
    var c = window.__nfCbs[id];
    if (c) {
      delete window.__nfCbs[id];
      try { c(JSON.parse(atob(b))); } catch (e) { c({ error: e.message }); }
    }
  };
  window.__genId = function(prefix) {
    return (prefix || '_') + Math.random().toString(36).substr(2, 9);
  };

  // ── Error overlay ──
  var errBox = null;
  function showErr(msg) {
    if (window.NativeBridge && window.NativeBridge.reportError) {
      try { NativeBridge.reportError(msg); } catch (e) {}
    }
    if (!errBox) {
      errBox = document.createElement('div');
      errBox.id = '_err_overlay';
      errBox.style.cssText = 'position:fixed;bottom:0;left:0;right:0;max-height:40vh;overflow:auto;background:rgba(0,0,0,0.85);color:#ff6b6b;font:12px monospace;padding:8px;z-index:999999;white-space:pre-wrap;';
      var closeBtn = document.createElement('span');
      closeBtn.textContent = ' [X] ';
      closeBtn.style.cssText = 'color:#fff;cursor:pointer;float:right;font-weight:bold;';
      closeBtn.onclick = function() { errBox.style.display = 'none'; };
      errBox.appendChild(closeBtn);
      (document.body || document.documentElement).appendChild(errBox);
    }
    errBox.style.display = 'block';
    var line = document.createElement('div');
    line.textContent = msg;
    errBox.appendChild(line);
  }
  window.onerror = function(msg, src, line, col, err) {
    showErr('ERROR: ' + msg + ' (line ' + line + ')');
  };
  window.addEventListener('unhandledrejection', function(e) {
    showErr('PROMISE: ' + (e.reason || e));
  });
  document.addEventListener('error', function(e) {
    var t = e.target;
    if (t && (t.tagName === 'SCRIPT' || t.tagName === 'LINK')) {
      showErr('LOAD FAILED: ' + (t.src || t.href));
    }
  }, true);
})();
