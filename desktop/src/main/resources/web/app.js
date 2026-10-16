// Countdowns and live refresh of the pairing page (no inline scripts: the CSP forbids them).
(function () {
  var start = Date.now();
  function tick() {
    var expired = false;
    document.querySelectorAll('[data-left]').forEach(function (el) {
      var left = Math.max(0, parseInt(el.getAttribute('data-left'), 10) - (Date.now() - start));
      var s = Math.ceil(left / 1000);
      el.textContent = Math.floor(s / 60) + ':' + ('0' + (s % 60)).slice(-2);
      if (left === 0) expired = true;
    });
    if (expired) location.reload();
  }
  if (document.querySelector('[data-left]')) { tick(); setInterval(tick, 1000); }

  // Reload when requests come or go, unless a PIN is being typed.
  var body = document.body;
  var sig = body.getAttribute('data-pairing');
  if (sig !== null) {
    setInterval(function () {
      fetch('pairing/status', { credentials: 'same-origin', cache: 'no-store' })
        .then(function (r) { return r.ok ? r.text() : null; })
        .then(function (now) {
          if (now === null || now === sig) return;
          var typing = Array.prototype.some.call(document.querySelectorAll('input[name=pin]'), function (i) { return i.value !== ''; });
          if (!typing) location.reload();
        })
        .catch(function () {});
    }, 2000);
  }

  document.querySelectorAll('[data-autofocus]').forEach(function (el) { el.focus(); });
})();
