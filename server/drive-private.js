(() => {
  'use strict';

  const API = '/api';
  let currentPath = '';

  const $ = (sel) => document.querySelector(sel);

  function humanBytes(value) {
    let n = Number(value || 0);
    if (!Number.isFinite(n) || n < 0) n = 0;
    const units = ['B','KB','MB','GB','TB'];
    let i = 0;
    while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
    return (i === 0 ? Math.round(n) : n.toFixed(n >= 10 ? 1 : 2)) + ' ' + units[i];
  }

  function formatDate(epoch) {
    const n = Number(epoch || 0);
    if (!n) return '—';
    return new Date(n * 1000).toLocaleString();
  }

  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
      .replace(/"/g,'&quot;').replace(/'/g,'&#039;');
  }

  function parentPath(path) {
    const parts = String(path || '').split('/').filter(Boolean);
    parts.pop();
    return parts.join('/');
  }

  function setStatus(message, bad = false) {
    const el = $('#status');
    el.textContent = message || '';
    el.classList.toggle('bad', !!bad);
  }

  function setProgress(show, label = '', percent = 0) {
    $('#progress-wrap').hidden = !show;
    $('#progress-text').textContent = label;
    $('#progress-bar').style.width = Math.max(0, Math.min(100, percent)) + '%';
  }

  async function json(response) {
    let body = {};
    try { body = await response.json(); } catch (_) {}
    if (!response.ok) {
      const err = new Error(body.error || ('HTTP ' + response.status));
      err.status = response.status;
      throw err;
    }
    return body;
  }

  async function api(path, options = {}) {
    const response = await fetch(API + path, Object.assign({
      credentials: 'same-origin',
      cache: 'no-store'
    }, options));
    if (response.status === 401) {
      location.href = 'https://nerdhalla.net/account.html#drive';
      throw new Error('Drive session expired.');
    }
    return response;
  }

  function render(data) {
    currentPath = data.path || '';
    const username = String(data.username || 'user');
    const directoryPath = '/' + username + '/' + (currentPath ? currentPath + '/' : '');
    $('#drive-title').textContent = 'Index of ' + directoryPath;
    $('#drive-path').textContent = directoryPath;
    const usage = data.usage || {};
    $('#drive-total').textContent = humanBytes(usage.total_bytes);
    $('#drive-used').textContent = humanBytes(usage.used_bytes);
    $('#drive-free').textContent = humanBytes(usage.free_bytes);

    const body = $('#directory-body');
    const rows = [];

    if (currentPath) {
      rows.push(`
        <div class="dir-row parent-row">
          <div class="name-cell">
            <span class="icon">↩</span>
            <button type="button" class="name-link folder" data-action="parent">Parent Directory</button>
          </div>
          <div class="meta modified">—</div>
          <div class="meta size">—</div>
          <div></div>
        </div>`);
    }

    const entries = Array.isArray(data.entries) ? data.entries : [];
    for (let i = 0; i < entries.length; i++) {
      const item = entries[i];
      const icon = item.is_dir ? '📁' : '📄';
      const size = item.is_dir ? '—' : humanBytes(item.size);
      rows.push(`
        <div class="dir-row">
          <div class="name-cell">
            <span class="icon">${icon}</span>
            <button type="button" class="name-link ${item.is_dir ? 'folder' : ''}" data-action="open" data-index="${i}">${esc(item.name)}</button>
          </div>
          <div class="meta modified">${esc(formatDate(item.modified))}</div>
          <div class="meta size">${esc(size)}</div>
          <div class="actions">
            ${item.is_dir ? '' : `<a class="mini" href="${API}/download?path=${encodeURIComponent(item.path)}">Download</a>`}
            <button type="button" class="mini" data-action="rename" data-index="${i}">Rename</button>
            <button type="button" class="mini danger" data-action="delete" data-index="${i}">Delete</button>
          </div>
        </div>`);
    }

    if (!rows.length) {
      body.innerHTML = '<div class="empty">This directory is empty.</div>';
    } else {
      body.innerHTML = rows.join('');
    }

    body.onclick = async (event) => {
      const target = event.target.closest('[data-action]');
      if (!target) return;
      const action = target.dataset.action;

      if (action === 'parent') {
        currentPath = parentPath(currentPath);
        await refresh();
        return;
      }

      const item = entries[Number(target.dataset.index)];
      if (!item) return;

      if (action === 'open') {
        if (item.is_dir) {
          currentPath = item.path;
          await refresh();
        } else {
          const viewUrl = API + '/download?view=1&path=' + encodeURIComponent(item.path);
          window.open(viewUrl, '_blank', 'noopener');
        }
        return;
      }

      if (action === 'rename') {
        const next = prompt('Rename to:', item.name);
        if (!next || next === item.name) return;
        try {
          setStatus('Renaming…');
          await json(await api('/rename', {
            method:'POST',
            headers:{'Content-Type':'application/json'},
            body:JSON.stringify({path:item.path,name:next})
          }));
          await refresh();
          setStatus('Renamed.');
        } catch (err) {
          setStatus(err.message || 'Rename failed.', true);
        }
        return;
      }

      if (action === 'delete') {
        const kind = item.is_dir ? 'folder and everything inside it' : 'file';
        if (!confirm('Delete this ' + kind + '?\n\n' + item.name)) return;
        try {
          setStatus('Deleting…');
          await json(await api('/delete', {
            method:'POST',
            headers:{'Content-Type':'application/json'},
            body:JSON.stringify({path:item.path})
          }));
          await refresh();
          setStatus('Deleted.');
        } catch (err) {
          setStatus(err.message || 'Delete failed.', true);
        }
      }
    };
  }

  async function refresh() {
    setStatus('Loading directory…');
    const data = await json(await api('/list?path=' + encodeURIComponent(currentPath)));
    render(data);
    setStatus('Private Drive ready.');
  }

  async function uploadOne(file, overwrite = false) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      const url = API + '/upload?path=' + encodeURIComponent(currentPath) +
        '&name=' + encodeURIComponent(file.name) +
        '&overwrite=' + (overwrite ? '1' : '0');

      xhr.open('POST', url, true);
      xhr.withCredentials = true;
      xhr.setRequestHeader('Content-Type', 'application/octet-stream');

      xhr.upload.onprogress = (event) => {
        const pct = event.lengthComputable && event.total > 0 ? (event.loaded / event.total) * 100 : 0;
        setProgress(true, 'Uploading ' + file.name + ' — ' + Math.round(pct) + '%', pct);
      };
      xhr.onerror = () => reject(new Error('Upload connection failed.'));
      xhr.onload = () => {
        let body = {};
        try { body = JSON.parse(xhr.responseText || '{}'); } catch (_) {}
        if (xhr.status >= 200 && xhr.status < 300) return resolve(body);
        if (xhr.status === 401) location.href = 'https://nerdhalla.net/account.html#drive';
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
        if (err.status === 409 && confirm(file.name + ' already exists. Replace it?')) {
          await uploadOne(file, true);
        } else {
          setProgress(false);
          setStatus(err.message || 'Upload failed.', true);
          throw err;
        }
      }
    }
    setProgress(false);
    await refresh();
    setStatus(queue.length === 1 ? 'Upload complete.' : queue.length + ' uploads complete.');
  }

  $('#upload-button').onclick = () => $('#file-input').click();
  $('#file-input').onchange = async () => {
    try { await uploadFiles($('#file-input').files); } catch (_) {}
    $('#file-input').value = '';
  };

  $('#folder-button').onclick = async () => {
    const name = prompt('New folder name:');
    if (!name) return;
    try {
      setStatus('Creating folder…');
      await json(await api('/mkdir', {
        method:'POST',
        headers:{'Content-Type':'application/json'},
        body:JSON.stringify({path:currentPath,name})
      }));
      await refresh();
      setStatus('Folder created.');
    } catch (err) {
      setStatus(err.message || 'Could not create folder.', true);
    }
  };

  $('#up-button').onclick = async () => {
    currentPath = parentPath(currentPath);
    try { await refresh(); } catch (err) { setStatus(err.message || 'Could not open parent directory.', true); }
  };

  $('#refresh-button').onclick = async () => {
    try { await refresh(); } catch (err) { setStatus(err.message || 'Could not refresh.', true); }
  };

  const drop = $('#drop-zone');
  ['dragenter','dragover'].forEach((name) => {
    drop.addEventListener(name, (event) => {
      event.preventDefault();
      drop.classList.add('dragging');
    });
  });
  ['dragleave','drop'].forEach((name) => {
    drop.addEventListener(name, (event) => {
      event.preventDefault();
      drop.classList.remove('dragging');
    });
  });
  drop.addEventListener('drop', async (event) => {
    try { await uploadFiles(event.dataTransfer.files); } catch (_) {}
  });

  refresh().catch((err) => setStatus(err.message || 'Could not load Drive.', true));
})();
