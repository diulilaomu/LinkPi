/**
 * LinkPi MiniApp SDK — Device
 * Toast, vibrate, clipboard, device info, battery, location.
 */
(function() {
  'use strict';
  var B = window.NativeBridge;

  /** Show a native toast notification. */
  window.showToast = function(message) {
    if (B && B.showToast) B.showToast(String(message));
  };

  /** Vibrate the device (max 5000ms). */
  window.vibrate = function(ms) {
    if (B && B.vibrate) B.vibrate(ms || 200);
  };

  /** Copy text to clipboard. */
  window.writeClipboard = function(text) {
    if (B && B.writeClipboard) B.writeClipboard(String(text));
  };

  /** Get device info. Returns {model, brand, manufacturer, sdkVersion, release}. */
  window.getDeviceInfo = function() {
    if (!B || !B.getDeviceInfo) return {};
    try { return JSON.parse(B.getDeviceInfo()); } catch (e) { return {}; }
  };

  /** Get battery level (0-100). */
  window.getBatteryLevel = function() {
    if (!B || !B.getBatteryLevel) return -1;
    return B.getBatteryLevel();
  };

  /** Get GPS location. Returns {latitude, longitude, accuracy} or {error}. */
  window.getLocation = function() {
    if (!B || !B.getLocation) return { error: 'unavailable' };
    try { return JSON.parse(B.getLocation()); } catch (e) { return { error: 'parse_error' }; }
  };
})();
