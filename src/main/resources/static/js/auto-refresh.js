// Keeps the screens that show live state current (sections 9.5, 10.3).
//
// The manager dashboard listens to the SSE feed named in `data-stream` and refetches itself when
// anything changes, or when the connection comes back after a drop: a refetch shows the same screen a
// replay of missed events would (ADR-012). The attender's queue has no feed and polls every
// `data-refresh-seconds` instead; push reaches the attender when the page is closed.
//
// An external file rather than inline script, because the Content-Security-Policy of section 11.6
// is `default-src 'self'` with no `unsafe-inline` for scripts — and that is worth keeping.
(function () {
  var seconds = Number(document.body.getAttribute('data-refresh-seconds') || 0);
  var stream = document.body.getAttribute('data-stream');
  var stale = false;

  function busy() {
    // Reloading under someone's hands would lose what they were typing and move the control they
    // were about to press. A background tab is not worth reloading at all.
    if (document.hidden) {
      return true;
    }
    var focused = document.activeElement;
    return (
      focused !== null &&
      ['INPUT', 'SELECT', 'TEXTAREA', 'BUTTON'].indexOf(focused.tagName) !== -1
    );
  }

  if (stream && window.EventSource) {
    var source = new EventSource(stream);
    var dropped = false;
    source.onmessage = function () { stale = true; };
    source.onerror = function () { dropped = true; };
    source.onopen = function () {
      if (dropped) {
        stale = true;
      }
    };
  }
  if (seconds) {
    window.setInterval(function () { stale = true; }, seconds * 1000);
  }
  if (stream || seconds) {
    window.setInterval(function () {
      if (stale && !busy()) {
        window.location.reload();
      }
    }, 1000);
  }
})();
