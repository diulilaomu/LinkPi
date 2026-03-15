/**
 * LinkPi MiniApp SDK — Storage
 * Key-value persistent storage, isolated per app.
 */
(function() {
  'use strict';
  var B = window.NativeBridge;

  /** Save a key-value pair. */
  window.saveData = function(key, value) {
    if (B && B.saveData) B.saveData(String(key), String(value));
  };

  /** Load a saved value by key. Returns string or ''. */
  window.loadData = function(key) {
    if (!B || !B.loadData) return '';
    return B.loadData(String(key));
  };

  /** Remove a stored key. */
  window.removeData = function(key) {
    if (B && B.removeData) B.removeData(String(key));
  };

  /** Clear all stored data for this app. */
  window.clearData = function() {
    if (B && B.clearData) B.clearData();
  };

  /** List all stored keys. Returns array of strings. */
  window.listKeys = function() {
    if (!B || !B.listKeys) return [];
    var raw = B.listKeys();
    return raw ? raw.split(',').filter(function(k) { return k.length > 0; }) : [];
  };

  /** Get this app's unique ID. */
  window.getAppId = function() {
    if (!B || !B.getAppId) return '';
    return B.getAppId();
  };
})();
