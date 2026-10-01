// Polling auto-refresh for the screens that show live state (section 10.3).
//
// The design drives the dashboard and the queue with SSE and puts SSE in Phase 4 (section 13.6).
// This closes the gap in the meantime: the page reloads itself on an interval, which needs no
// endpoint, no connection to keep alive and no reconnect logic, and is demonstrably live.
//
// An external file rather than inline script, because the Content-Security-Policy of section 11.6
// is `default-src 'self'` with no `unsafe-inline` for scripts — and that is worth keeping.
(function () {
  var seconds = Number(document.body.getAttribute('data-refresh-seconds') || 0);
  if (!seconds) {
    return;
  }

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

  window.setInterval(function () {
    if (!busy()) {
      window.location.reload();
    }
  }, seconds * 1000);
})();
