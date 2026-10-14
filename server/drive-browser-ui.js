(() => {
  'use strict';

  const DRIVE_API = 'https://drive.nerdhalla.net/api';
  let currentPath = '';
  let mounted = false;

  function qs(sel, root = document) {
    return root.querySelector(sel);
  }

  function humanBytes(value) {
    let n = Number(value || 0);
    if (!Number.isFinite(n) || n < 0) n = 0;
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let u = 0;
    while (n >= 1024 && u < units.length - 1) {
      n /= 1024;
      u++;
    }
    return (u === 0 ? Math.round(n) : n.toFixed(n >= 10 ? 1 : 2)) + ' ' + units[u];
  }

  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  function setStatus(message, bad = false) {
    const el = qs('#nh-drive-browser-status');
    if (!el) return;
    el.textContent = message || '';
    el.classList.toggle('bad', !!bad);
  }

  function setProgress(show, label = '', percent = 0) {
    const wrap = qs('#nh-drive-browser-progress');
    const bar = qs('#nh-drive-browser-progress-bar');
    const text = qs('#nh-drive-browser-progress-text');
    if (!wrap || !bar || !text) return;
    wrap.hidden = !show;
    text.textContent = label;
    bar.style.width = Math.max(0, Math.min(100, percent)) + '%';
  }

  async function jsonResponse(response) {
    let body = {};
    try {
      body = await response.json();
    } catch (_) {}
    if (!response.ok) {
      const err = new Error(body.error || ('HTTP ' + response.status));
      err.status = response.status;
      err.body = body;
      throw err;
    }
    return body;
  }

  async function establishDriveSession() {
    const handoff = await jsonResponse(await fetch(
      '/portal-api.php?action=drive_browser_token&t=' + Date.now(),
      {
        method: 'GET',
        credentials: 'same-origin',
        cache: 'no-store'
      }
    ));

    await jsonResponse(await fetch(DRIVE_API + '/session', {
      method: 'POST',
      mode: 'cors',
      credentials: 'include',
      cache: 'no-store',
      headers: {
        'Authorization': 'Bearer ' + handoff.token
      }
    }));

    return handoff;
  }

  async function driveFetch(path, options = {}, retry = true) {
    const opts = Object.assign({}, options, {
      mode: 'cors',
      credentials: 'include',
      cache: 'no-store'
    });

    let response = await fetch(DRIVE_API + path, opts);
    if (response.status === 401 && retry) {
      await establishDriveSession();
      response = await fetch(DRIVE_API + path, opts);
    }
    return response;
  }

  function parentPath(path) {
    const parts = String(path || '').split('/').filter(Boolean);
    parts.pop();
    return parts.join('/');
  }

  function renderEntries(data) {
    const list = qs('#nh-drive-browser-list');
    const pathEl = qs('#nh-drive-browser-path');
    const usedEl = qs('#nh-drive-browser-used');
    const freeEl = qs('#nh-drive-browser-free');
    const totalEl = qs('#nh-drive-browser-total');
    if (!list) return;

    pathEl.textContent = '/' + (currentPath || '');

    const usage = data.usage || {};
    totalEl.textContent = humanBytes(usage.total_bytes);
    usedEl.textContent = humanBytes(usage.used_bytes);
    freeEl.textContent = humanBytes(usage.free_bytes);

    const entries = Array.isArray(data.entries) ? data.entries : [];
    if (!entries.length) {
      list.innerHTML = '<div class="nh-drive-empty">This folder is empty.</div>';
      return;
    }

    list.innerHTML = entries.map((item, index) => {
      const icon = item.is_dir ? '📁' : '📄';
      const meta = item.is_dir ? 'Folder' : humanBytes(item.size);
      return `
        <div class="nh-drive-file-row" data-index="${index}">
          <button type="button" class="nh-drive-file-main" data-action="open" data-index="${index}">
            <span class="nh-drive-file-icon">${icon}</span>
            <span class="nh-drive-file-copy">
              <strong>${esc(item.name)}</strong>
              <small>${esc(meta)}</small>
            </span>
          </button>
          <div class="nh-drive-file-actions">
            ${item.is_dir ? '' : `<a class="nh-drive-mini" href="${DRIVE_API}/download?path=${encodeURIComponent(item.path)}" target="_blank" rel="noopener">Download</a>`}
            <button type="button" class="nh-drive-mini" data-action="rename" data-index="${index}">Rename</button>
            <button type="button" class="nh-drive-mini danger" data-action="delete" data-index="${index}">Delete</button>
          </div>
        </div>`;
    }).join('');

    list.onclick = async (event) => {
      const target = event.target.closest('[data-action]');
      if (!target) return;
      const index = Number(target.dataset.index);
      const item = entries[index];
      if (!item) return;
      const action = target.dataset.action;

      if (action === 'open') {
        if (item.is_dir) {
          currentPath = item.path;
          await refreshList();
        } else {
          window.open(
            DRIVE_API + '/download?path=' + encodeURIComponent(item.path),
            '_blank',
            'noopener'
          );
        }
        return;
      }

      if (action === 'rename') {
        const next = window.prompt('Rename to:', item.name);
        if (!next || next === item.name) return;
        try {
          setStatus('Renaming…');
          const response = await driveFetch('/rename', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({path: item.path, name: next})
          });
          await jsonResponse(response);
          await refreshList();
          setStatus('Renamed.');
        } catch (err) {
          setStatus(err.message || 'Rename failed.', true);
        }
        return;
      }

      if (action === 'delete') {
        const kind = item.is_dir ? 'folder and everything inside it' : 'file';
        if (!window.confirm(`Delete this ${kind}?\n\n${item.name}`)) return;
        try {
          setStatus('Deleting…');
          const response = await driveFetch('/delete', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({path: item.path})
          });
          await jsonResponse(response);
          await refreshList();
          setStatus('Deleted.');
        } catch (err) {
          setStatus(err.message || 'Delete failed.', true);
        }
      }
    };
  }

  async function refreshList() {
    try {
      setStatus('Loading private files…');
      const response = await driveFetch('/list?path=' + encodeURIComponent(currentPath));
      const data = await jsonResponse(response);
      renderEntries(data);
      setStatus('Private browser access ready.');
    } catch (err) {
      if (err.status === 404) {
        setStatus('Create your Nerdhalla Drive first, then press Refresh.', true);
      } else {
        setStatus(err.message || 'Could not load Drive files.', true);
      }
      throw err;
    }
  }

  async function uploadOne(file, overwrite = false) {
    await establishDriveSession();

    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      const url = DRIVE_API + '/upload?path=' + encodeURIComponent(currentPath) +
        '&name=' + encodeURIComponent(file.name) +
        '&overwrite=' + (overwrite ? '1' : '0');

      xhr.open('POST', url, true);
      xhr.withCredentials = true;
      xhr.setRequestHeader('Content-Type', 'application/octet-stream');

      xhr.upload.onprogress = (event) => {
        const pct = event.lengthComputable && event.total > 0
          ? (event.loaded / event.total) * 100
          : 0;
        setProgress(true, `Uploading ${file.name} — ${Math.round(pct)}%`, pct);
      };

      xhr.onerror = () => reject(new Error('Upload connection failed.'));
      xhr.onload = () => {
        let body = {};
        try { body = JSON.parse(xhr.responseText || '{}'); } catch (_) {}

        if (xhr.status >= 200 && xhr.status < 300) {
          resolve(body);
          return;
        }

        const err = new Error(body.error || ('Upload failed (HTTP ' + xhr.status + ')'));
        err.status = xhr.status;
        reject(err);
      };

      xhr.send(file);
    });
  }

  async function uploadFiles(files) {
    const queue = Array.from(files || []);
    if (!queue.length) return;

    for (const file of queue) {
      try {
        setStatus('Uploading ' + file.name + '…');
        await uploadOne(file, false);
      } catch (err) {
        if (err.status === 409 && window.confirm(`${file.name} already exists. Replace it?`)) {
          await uploadOne(file, true);
        } else {
          setProgress(false);
          setStatus(err.message || ('Upload failed: ' + file.name), true);
          throw err;
        }
      }
    }

    setProgress(false);
    await refreshList();
    setStatus(queue.length === 1 ? 'Upload complete.' : `${queue.length} uploads complete.`);
  }

  function wireManager() {
    const uploadInput = qs('#nh-drive-browser-file');
    const uploadButton = qs('#nh-drive-browser-upload');
    const folderButton = qs('#nh-drive-browser-folder');
    const upButton = qs('#nh-drive-browser-up');
    const refreshButton = qs('#nh-drive-browser-refresh');
    const drop = qs('#nh-drive-browser-drop');

    uploadButton.addEventListener('click', () => uploadInput.click());
    uploadInput.addEventListener('change', async () => {
      try { await uploadFiles(uploadInput.files); } catch (_) {}
      uploadInput.value = '';
    });

    folderButton.addEventListener('click', async () => {
      const name = window.prompt('New folder name:');
      if (!name) return;
      try {
        setStatus('Creating folder…');
        const response = await driveFetch('/mkdir', {
          method: 'POST',
          headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({path: currentPath, name})
        });
        await jsonResponse(response);
        await refreshList();
        setStatus('Folder created.');
      } catch (err) {
        setStatus(err.message || 'Could not create folder.', true);
      }
    });

    upButton.addEventListener('click', async () => {
      currentPath = parentPath(currentPath);
      try { await refreshList(); } catch (_) {}
    });

    refreshButton.addEventListener('click', async () => {
      try {
        await establishDriveSession();
        await refreshList();
      } catch (err) {
        setStatus(err.message || 'Could not refresh Drive.', true);
      }
    });

    ['dragenter', 'dragover'].forEach((name) => {
      drop.addEventListener(name, (event) => {
        event.preventDefault();
        drop.classList.add('dragging');
      });
    });

    ['dragleave', 'drop'].forEach((name) => {
      drop.addEventListener(name, (event) => {
        event.preventDefault();
        drop.classList.remove('dragging');
      });
    });

    drop.addEventListener('drop', async (event) => {
      try { await uploadFiles(event.dataTransfer.files); } catch (_) {}
    });
  }

  function mountManager() {
    if (mounted || document.getElementById('nh-drive-browser')) return true;

    const anchor = document.getElementById('nh-drive-web');
    if (!anchor) return false;

    let host = anchor.closest('[data-sharla-panel], .mysharla-panel, .portal-panel, section');
    if (!host) host = anchor.parentElement && anchor.parentElement.parentElement;
    if (!host) return false;

    const manager = document.createElement('div');
    manager.id = 'nh-drive-browser';
    manager.className = 'nh-drive-browser';
    manager.innerHTML = `
      <div class="nh-drive-browser-head">
        <div>
          <h3>Private browser files</h3>
          <p>Upload, download, rename, organize, and delete files without FTP.</p>
        </div>
        <span class="nh-drive-private-badge">🔒 Private</span>
      </div>

      <div class="nh-drive-browser-stats">
        <div><small>Total</small><strong id="nh-drive-browser-total">—</strong></div>
        <div><small>Used</small><strong id="nh-drive-browser-used">—</strong></div>
        <div><small>Free</small><strong id="nh-drive-browser-free">—</strong></div>
      </div>

      <div class="nh-drive-browser-toolbar">
        <button type="button" id="nh-drive-browser-upload">Upload files</button>
        <button type="button" id="nh-drive-browser-folder">New folder</button>
        <button type="button" id="nh-drive-browser-up">Up</button>
        <button type="button" id="nh-drive-browser-refresh">Refresh</button>
        <input type="file" id="nh-drive-browser-file" multiple hidden>
      </div>

      <div class="nh-drive-browser-path">Folder: <strong id="nh-drive-browser-path">/</strong></div>
      <div id="nh-drive-browser-drop" class="nh-drive-browser-drop">Drop files here to upload</div>

      <div id="nh-drive-browser-progress" class="nh-drive-browser-progress" hidden>
        <div id="nh-drive-browser-progress-text"></div>
        <div class="nh-drive-browser-progress-track"><span id="nh-drive-browser-progress-bar"></span></div>
      </div>

      <div id="nh-drive-browser-status" class="nh-drive-browser-status">Connecting private Drive…</div>
      <div id="nh-drive-browser-list" class="nh-drive-browser-list"></div>
      <p class="nh-drive-browser-note">Only the Discord account that owns this Drive can use this browser file manager. FTP/FTPS and SFTP remain available with the Drive password.</p>`;

    host.appendChild(manager);
    mounted = true;
    wireManager();

    establishDriveSession()
      .then(refreshList)
      .catch((err) => {
        setStatus(err.message || 'Create your Drive first, then press Refresh.', true);
      });

    return true;
  }

  function openHashDrive() {
    if (location.hash.toLowerCase() !== '#drive') return;
    const button = document.querySelector('[data-sharla-target="drive"]');
    if (button) button.click();
  }

  function start() {
    openHashDrive();
    if (mountManager()) return;

    const observer = new MutationObserver(() => {
      openHashDrive();
      if (mountManager()) observer.disconnect();
    });
    observer.observe(document.documentElement, {childList: true, subtree: true});

    setTimeout(() => observer.disconnect(), 30000);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start, {once: true});
  } else {
    start();
  }
})();
