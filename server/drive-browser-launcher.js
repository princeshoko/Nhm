/* NERDHALLA PRIVATE DRIVE LAUNCHER V2 */
(() => {
  'use strict';

  let mounted = false;

  function openHashDrive() {
    if (location.hash.toLowerCase() !== '#drive') return;
    const button = document.querySelector('[data-sharla-target="drive"]');
    if (button) button.click();
  }

  async function getHandoff() {
    const response = await fetch(
      '/portal-api.php?action=drive_browser_token&t=' + Date.now(),
      {method:'GET',credentials:'same-origin',cache:'no-store'}
    );
    let body = {};
    try { body = await response.json(); } catch (_) {}
    if (!response.ok) throw new Error(body.error || ('HTTP ' + response.status));
    return body;
  }

  function mountLauncher() {
    if (mounted || document.getElementById('nh-drive-launcher')) return true;

    const anchor = document.getElementById('nh-drive-web');
    if (!anchor) return false;

    let host = anchor.closest('[data-sharla-panel], .mysharla-panel, .portal-panel, section');
    if (!host) host = anchor.parentElement && anchor.parentElement.parentElement;
    if (!host) return false;

    const box = document.createElement('div');
    box.id = 'nh-drive-launcher';
    box.className = 'nh-drive-launcher';
    box.innerHTML = `
      <div class="nh-drive-launcher-copy">
        <strong>🔒 Private browser files</strong>
        <span>Open your Drive in its own private directory window.</span>
        <div id="nh-drive-launcher-status" class="nh-drive-launcher-status"></div>
      </div>
      <button type="button" id="nh-drive-launcher-open">Open Private Drive</button>`;

    host.appendChild(box);
    mounted = true;

    document.getElementById('nh-drive-launcher-open').addEventListener('click', async () => {
      const status = document.getElementById('nh-drive-launcher-status');
      const popup = window.open('about:blank', '_blank');
      if (!popup) {
        status.textContent = 'Allow pop-ups for Nerdhalla, then try again.';
        status.classList.add('bad');
        return;
      }

      try {
        popup.document.write('<title>Opening Nerdhalla Drive…</title><body style="background:#0b0811;color:#f5efff;font-family:system-ui;padding:24px">Opening your private Drive…</body>');
        status.textContent = 'Opening private Drive…';
        status.classList.remove('bad');

        const handoff = await getHandoff();
        popup.location.replace(
          'https://drive.nerdhalla.net/private/?token=' + encodeURIComponent(handoff.token)
        );
        status.textContent = '';
      } catch (err) {
        try { popup.close(); } catch (_) {}
        status.textContent = err.message || 'Could not open your private Drive.';
        status.classList.add('bad');
      }
    });

    return true;
  }

  function start() {
    openHashDrive();
    if (mountLauncher()) return;

    const observer = new MutationObserver(() => {
      openHashDrive();
      if (mountLauncher()) observer.disconnect();
    });
    observer.observe(document.documentElement, {childList:true,subtree:true});
    setTimeout(() => observer.disconnect(), 30000);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start, {once:true});
  } else {
    start();
  }
})();

/* END NERDHALLA PRIVATE DRIVE LAUNCHER V2 */
