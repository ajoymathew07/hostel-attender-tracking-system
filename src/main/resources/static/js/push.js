// "Enable alerts" on the attender's queue (section 9.6, subscription flow).
//
// The button stays hidden unless the browser can do push and the server has a VAPID key. On a tap
// the browser asks for permission, subscribes, and the subscription is stored; a test alert then
// shows the attender that it works.
(function () {
  var button = document.getElementById('enable-alerts');
  var status = document.getElementById('alerts-status');
  if (!button || !('serviceWorker' in navigator) || !('PushManager' in window)) {
    return;
  }
  var csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
  var csrfToken = document.querySelector('meta[name="_csrf"]').content;

  function post(url, body) {
    var headers = { 'Content-Type': 'application/json' };
    headers[csrfHeader] = csrfToken;
    return fetch(url, { method: 'POST', headers: headers, body: JSON.stringify(body || {}) });
  }

  // pushManager.subscribe wants the key as bytes; the server sends it base64url-encoded.
  function bytes(base64url) {
    var base64 = (base64url + '==='.slice((base64url.length + 3) % 4)).replace(/-/g, '+').replace(/_/g, '/');
    return Uint8Array.from(atob(base64), function (c) { return c.charCodeAt(0); });
  }

  fetch('/api/attender/push/key')
    .then(function (response) { return response.ok ? response.json() : null; })
    .then(function (key) {
      if (!key) {
        return;
      }
      button.hidden = false;
      button.addEventListener('click', function () {
        navigator.serviceWorker
          .register('/sw.js')
          .then(function () { return navigator.serviceWorker.ready; })
          .then(function (registration) {
            return registration.pushManager.subscribe({
              userVisibleOnly: true,
              applicationServerKey: bytes(key.publicKey)
            });
          })
          .then(function (subscription) { return post('/api/attender/push/subscribe', subscription.toJSON()); })
          .then(function (response) {
            if (!response.ok) {
              throw new Error('subscribe failed');
            }
            status.textContent = 'Alerts are on for this phone.';
            return post('/api/attender/push/test');
          })
          .catch(function () {
            status.textContent = 'Alerts could not be turned on. Check the browser allows notifications.';
          });
      });
    });
})();
