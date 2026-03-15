/**
 * LinkPi MiniApp SDK — Realtime
 * WebSocket server for LAN real-time communication.
 * Requires core.js (callback system).
 */
(function() {
  'use strict';
  var B = window.NativeBridge;

  /** Server event callback dispatch (called from native side). */
  window.__wsServerEvent = function(b) {
    try {
      var e = JSON.parse(atob(b));
      if (window.onServerEvent) window.onServerEvent(e);
    } catch (ex) {}
  };

  /**
   * Start a WebSocket server.
   * @param {number} [port=8080] - Port to listen on.
   * @returns {Promise<{port, ip}>}
   */
  window.startServer = function(port) {
    return new Promise(function(resolve, reject) {
      if (!B || !B.startWebSocketServer) {
        reject(new Error('NativeBridge unavailable')); return;
      }
      var id = window.__genId('_ws');
      window.__nfCbs[id] = function(r) {
        if (r.ok) resolve({ port: r.port, ip: r.ip });
        else reject(new Error(r.error || 'Failed'));
      };
      B.startWebSocketServer(id, port || 8080);
    });
  };

  /** Stop the WebSocket server. */
  window.stopServer = function() {
    if (B && B.stopWebSocketServer) B.stopWebSocketServer();
  };

  /** Send message to a specific client. */
  window.serverSend = function(clientId, msg) {
    if (B && B.serverSend) B.serverSend(clientId, msg);
  };

  /** Broadcast message to all connected clients. */
  window.serverBroadcast = function(msg) {
    if (B && B.serverBroadcast) B.serverBroadcast(msg);
  };

  /** Get device LAN IP address. */
  window.getLocalIp = function() {
    if (!B || !B.getLocalIpAddress) return '';
    return B.getLocalIpAddress();
  };
})();
