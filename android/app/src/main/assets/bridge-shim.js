/* CineFlow Android TV bridge shim.
 * Injected BEFORE any page script (see WwwServer). Implements window.cineflow
 * with the same 34-method surface as Electron's preload, but every call is a
 * Promise routed to the native side via CineflowNative.postMessage.
 *
 * Playback interception: the WebView must never actually play media itself.
 * getMediaProxyUrl() launches the native ExoPlayer activity (debounced) and
 * returns '' so the in-WebView player stays idle; HTMLVideoElement.play() is
 * neutered for #resourcePlayer as a second line of defence.
 */
(function () {
  'use strict';
  if (window.cineflow) return;

  var nativeBridge = null;
  try { nativeBridge = window.CineflowNative || null; } catch (e) { nativeBridge = null; }

  var seq = 0;
  var pending = {};

  function callNative(method, args) {
    return new Promise(function (resolve, reject) {
      if (!nativeBridge) {
        reject(new Error('no native bridge'));
        return;
      }
      var id = ++seq;
      pending[id] = { resolve: resolve, reject: reject };
      try {
        nativeBridge.postMessage(JSON.stringify({ id: id, method: method, args: args || [] }));
      } catch (e) {
        delete pending[id];
        reject(e);
      }
      setTimeout(function () {
        if (pending[id]) {
          delete pending[id];
          var err = new Error('bridge timeout: ' + method);
          err.code = 'BRIDGE_TIMEOUT';
          reject(err);
        }
      }, 90000);
    });
  }

  window.__cineflowResolve = function (id, data) {
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    p.resolve(data);
  };
  window.__cineflowReject = function (id, code, message) {
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    var err = new Error(message || code || 'bridge error');
    err.code = code;
    p.reject(err);
  };

  function inferKind(url) {
    var t = String(url || '').toLowerCase();
    if (/\.m3u8(?:$|[?#])/.test(t) || /m3u8/.test(t)) return 'hls';
    if (/\.mpd(?:$|[?#])/.test(t)) return 'dash';
    if (/\.flv(?:$|[?#])/.test(t)) return 'flv';
    if (/\.(?:ts|m2ts|mts)(?:$|[?#])/.test(t)) return 'mpegts';
    if (/\.(?:mp4|m4v)(?:$|[?#])/.test(t)) return 'mp4';
    if (/\.webm(?:$|[?#])/.test(t)) return 'webm';
    return 'native';
  }

  var lastLaunchAt = 0;
  function launchNativePlayer(url) {
    if (!nativeBridge || !url) return;
    var now = Date.now();
    if (now - lastLaunchAt < 2000) return;
    lastLaunchAt = now;
    try {
      nativeBridge.playVideo(JSON.stringify({
        url: url,
        kind: inferKind(url),
        title: document.title || 'CineFlow'
      }));
    } catch (e) { /* ignore */ }
    // Dismiss the in-WebView player overlay so it doesn't linger behind ExoPlayer.
    setTimeout(function () {
      try {
        var btn = document.querySelector('#playerCloseBtn');
        if (btn) btn.click();
      } catch (e) { /* ignore */ }
    }, 600);
  }

  // The WebView player must stay silent: ExoPlayer owns audio/video output.
  try {
    var origPlay = HTMLVideoElement.prototype.play;
    HTMLVideoElement.prototype.play = function () {
      if (this && this.id === 'resourcePlayer') {
        return Promise.resolve();
      }
      return origPlay.apply(this, arguments);
    };
  } catch (e) { /* ignore */ }

  var api = {};
  function def(name, fn) { api[name] = fn; }
  function rpc(method) {
    return function () { return callNative(method, Array.prototype.slice.call(arguments)); };
  }

  // --- settings / storage ---
  def('getCredentialState', rpc('app:getCredentialState'));
  def('saveCredential', rpc('app:saveCredential'));
  def('clearCredential', rpc('app:clearCredential'));
  def('saveProxy', rpc('app:saveProxy'));
  def('getResourceSettings', rpc('app:getResourceSettings'));
  def('saveResourceMode', rpc('app:saveResourceMode'));
  def('saveResourcePlaybackMode', rpc('app:saveResourcePlaybackMode'));
  def('importResourceSources', rpc('app:importResourceSources'));
  def('clearResourceSources', rpc('app:clearResourceSources'));
  def('testConnection', rpc('app:testConnection'));

  // --- TMDB ---
  def('getInitialData', rpc('tmdb:initial'));
  def('searchMovies', rpc('tmdb:search'));
  def('discoverMovies', rpc('tmdb:discover'));
  def('recommendByMovie', rpc('tmdb:recommendByMovie'));
  def('getMovieDetails', rpc('tmdb:details'));

  // --- resources ---
  def('findMovieResources', rpc('resources:findMovie'));

  // --- player ---
  def('resolveMediaUrl', rpc('player:resolveMediaUrl'));
  def('getMediaProxyUrl', function (url) {
    // Intercept: hand playback to the native ExoPlayer activity.
    if (url) launchNativePlayer(url);
    return Promise.resolve('');
  });
  def('getPlaybackProxyMode', rpc('player:getPlaybackProxyMode'));
  def('savePlaybackProxyMode', rpc('player:setPlaybackProxyMode'));
  def('onPlaybackProxyState', function () { return Promise.resolve(null); });

  // --- meta ---
  def('getMetadataStatus', rpc('meta:getStatus'));

  // --- window / island (desktop-only; TV is always fullscreen) ---
  ['minimize', 'enterIsland', 'expandIsland', 'restoreNormal', 'dockIsland',
   'islandDragBegin', 'islandDragMove', 'islandDragEnd', 'maximize', 'close'
  ].forEach(function (m) { def(m, function () { return Promise.resolve(null); }); });
  def('onWindowMode', function () { return Promise.resolve(null); });

  // --- shell ---
  def('openExternal', rpc('shell:openExternal'));

  window.cineflow = api;
})();
