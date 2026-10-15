#!/usr/bin/env python3
import fcntl
import html
import json
import mimetypes
import os
import pwd
import shutil
import time
import urllib.parse
from pathlib import Path, PurePosixPath

from aiohttp import web

STATE_ROOT = Path('/mnt/storage/nerdhalla-drive')
TOKENS_FILE = STATE_ROOT / 'browser_tokens.json'
TOKENS_LOCK = STATE_ROOT / '.browser_tokens.lock'
COOKIE_NAME = 'nhdrive'
ALLOWED_ORIGINS = {
    'https://nerdhalla.net',
    'https://www.nerdhalla.net',
}
CHUNK = 1024 * 1024
RESERVE_BYTES = 1024 * 1024
PRIVATE_WEB_ROOT = Path('/var/www/nerdhalla-drive/private')

# Only inert/browser-safe formats are rendered inline on the authenticated
# Drive origin. Active web content (HTML, SVG, JavaScript, etc.) remains
# attachment-only so uploaded files cannot execute with the Drive session.
INLINE_MIME_TYPES = {
    'application/pdf',
    'application/json',
    'text/plain',
    'text/csv',
    'text/markdown',
}
INLINE_MIME_PREFIXES = (
    'image/',
    'audio/',
    'video/',
)
BLOCKED_INLINE_MIME_TYPES = {
    'image/svg+xml',
    'text/html',
    'application/xhtml+xml',
    'application/javascript',
    'text/javascript',
}


def load_tokens():
    try:
        with open(TOKENS_FILE, 'r', encoding='utf-8') as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def revoke_token(token):
    if not token:
        return

    STATE_ROOT.mkdir(parents=True, exist_ok=True)
    with open(TOKENS_LOCK, 'a+', encoding='utf-8') as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        tokens = load_tokens()
        if token in tokens:
            tokens.pop(token, None)
            tmp = TOKENS_FILE.with_name(TOKENS_FILE.name + '.tmp.' + str(os.getpid()))
            with open(tmp, 'w', encoding='utf-8') as fh:
                json.dump(tokens, fh, indent=2, sort_keys=True)
                fh.write('\n')
                fh.flush()
                os.fsync(fh.fileno())
            os.chmod(tmp, 0o600)
            os.replace(tmp, TOKENS_FILE)
            os.chmod(TOKENS_FILE, 0o600)
        fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def token_record(token):
    if not token:
        return None
    record = load_tokens().get(token)
    if not isinstance(record, dict):
        return None
    if int(record.get('expires_at', 0) or 0) <= int(time.time()):
        return None
    username = str(record.get('username') or '').strip().lower()
    if not username:
        return None
    root = Path('/home') / username / 'files'
    if not root.is_dir():
        return None
    return record


def session_record(request):
    token = request.cookies.get(COOKIE_NAME, '')
    record = token_record(token)
    if not record:
        raise web.HTTPUnauthorized(
            text=json.dumps({'error': 'Drive session expired. Refresh My Nerdhalla.'}),
            content_type='application/json',
        )
    return token, record


def drive_root(record):
    return (Path('/home') / str(record['username']) / 'files').resolve()


def clean_relative(value, allow_root=True):
    value = str(value or '').replace('\\', '/')
    if '\x00' in value:
        raise web.HTTPBadRequest(text=json.dumps({'error': 'Invalid path.'}), content_type='application/json')
    value = value.strip('/')
    if not value:
        if allow_root:
            return PurePosixPath('.')
        raise web.HTTPBadRequest(text=json.dumps({'error': 'Choose a file or folder.'}), content_type='application/json')
    p = PurePosixPath(value)
    if p.is_absolute() or any(part in ('', '.', '..') for part in p.parts):
        raise web.HTTPBadRequest(text=json.dumps({'error': 'Invalid path.'}), content_type='application/json')
    return p


def clean_name(value):
    value = str(value or '')
    if not value or value in ('.', '..') or '/' in value or '\\' in value or '\x00' in value:
        raise web.HTTPBadRequest(text=json.dumps({'error': 'Invalid name.'}), content_type='application/json')
    return value


def safe_target(root, relative, allow_root=True):
    rel = clean_relative(relative, allow_root=allow_root)
    target = (root / rel).resolve(strict=False)
    root_text = str(root)
    target_text = str(target)
    try:
        common = os.path.commonpath([root_text, target_text])
    except ValueError:
        common = ''
    if common != root_text:
        raise web.HTTPForbidden(text=json.dumps({'error': 'Path is outside your Drive.'}), content_type='application/json')
    return target


def owner_ids(username):
    pw = pwd.getpwnam(username)
    return pw.pw_uid, pw.pw_gid


def set_owner(path, username, mode=None):
    uid, gid = owner_ids(username)
    os.chown(path, uid, gid)
    if mode is not None:
        os.chmod(path, mode)


def cors_headers(request):
    origin = request.headers.get('Origin', '')
    if origin in ALLOWED_ORIGINS:
        return {
            'Access-Control-Allow-Origin': origin,
            'Access-Control-Allow-Credentials': 'true',
            'Vary': 'Origin',
        }
    return {}


@web.middleware
async def cors_middleware(request, handler):
    if request.method == 'OPTIONS':
        response = web.Response(status=204)
    else:
        try:
            response = await handler(request)
        except web.HTTPException as exc:
            response = exc
    for key, value in cors_headers(request).items():
        response.headers[key] = value
    response.headers['Access-Control-Allow-Methods'] = 'GET, POST, OPTIONS'
    response.headers['Access-Control-Allow-Headers'] = 'Authorization, Content-Type, X-File-Name'
    response.headers['Cache-Control'] = 'no-store'
    response.headers['X-Content-Type-Options'] = 'nosniff'
    return response


def set_session_cookie(response, token, record):
    max_age = max(60, int(record.get('expires_at', 0)) - int(time.time()))
    response.set_cookie(
        COOKIE_NAME,
        token,
        max_age=max_age,
        secure=True,
        httponly=True,
        samesite='Lax',
        path='/',
    )
    return max_age


async def api_session(request):
    auth = request.headers.get('Authorization', '')
    token = auth[7:].strip() if auth.lower().startswith('bearer ') else ''
    record = token_record(token)
    if not record:
        raise web.HTTPUnauthorized(text=json.dumps({'error': 'Invalid or expired Drive handoff.'}), content_type='application/json')

    response = web.json_response({
        'ok': True,
        'username': record['username'],
        'expires_in': max(60, int(record.get('expires_at', 0)) - int(time.time())),
        'private': True,
    })
    set_session_cookie(response, token, record)
    return response


async def private_page(request):
    handoff = request.query.get('token', '').strip()
    if handoff:
        record = token_record(handoff)
        if not record:
            raise web.HTTPFound('https://nerdhalla.net/account.html#drive')
        response = web.HTTPFound('/private/')
        set_session_cookie(response, handoff, record)
        return response

    try:
        session_record(request)
    except web.HTTPUnauthorized:
        raise web.HTTPFound('https://nerdhalla.net/account.html#drive')

    index = PRIVATE_WEB_ROOT / 'index.html'
    if not index.is_file():
        raise web.HTTPServiceUnavailable(text='Private Drive interface is not installed.')
    return web.FileResponse(index)


async def private_view(request):
    _, record = session_record(request)
    root = drive_root(record)
    rel = request.query.get('path', '')
    target = safe_target(root, rel, allow_root=False)

    if not target.is_file():
        raise web.HTTPNotFound(text='File not found.')

    mime, _ = mimetypes.guess_type(target.name)
    mime = mime or 'application/octet-stream'
    if not mime.startswith('image/') or mime in BLOCKED_INLINE_MIME_TYPES:
        raise web.HTTPFound('/api/download?view=1&path=' + urllib.parse.quote(rel, safe=''))

    image_url = '/api/download?view=1&path=' + urllib.parse.quote(rel, safe='')
    title = html.escape(target.name, quote=True)
    page_html = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,minimum-scale=0.25,maximum-scale=8,user-scalable=yes,viewport-fit=cover">
<meta name="robots" content="noindex,nofollow,noarchive">
<meta http-equiv="Cache-Control" content="no-store">
<title>{title}</title>
<style>
html,body{{
  margin:0;
  width:100%;
  min-height:100%;
  background:#08060d;
}}
body{{
  overflow:auto;
  touch-action:pan-x pan-y pinch-zoom;
}}
.viewer{{
  width:100vw;
  height:100vh;
  display:flex;
  align-items:center;
  justify-content:center;
  overflow:visible;
}}
.viewer img{{
  display:block;
  width:auto;
  height:auto;
  max-width:100vw;
  max-height:100vh;
  object-fit:contain;
  user-select:none;
  -webkit-user-drag:none;
}}
</style>
</head>
<body>
<div class="viewer">
<img src="{image_url}" alt="{title}">
</div>
</body>
</html>"""
    response = web.Response(text=page_html, content_type='text/html')
    response.headers['Content-Security-Policy'] = "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'"
    return response


async def private_asset(request):
    name = request.match_info.get('name', '')
    if name not in {'app.css', 'app.js'}:
        raise web.HTTPNotFound()
    target = PRIVATE_WEB_ROOT / name
    if not target.is_file():
        raise web.HTTPNotFound()
    return web.FileResponse(target)


async def private_logout(request):
    token = request.cookies.get(COOKIE_NAME, '')
    revoke_token(token)

    # Logout must begin on the Drive origin so the browser sends nhdrive.
    # Nerdhalla's logout endpoint only accepts POST, so submit a tiny
    # auto-posting form instead of redirecting with GET.
    html = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="robots" content="noindex,nofollow">
<meta http-equiv="Cache-Control" content="no-store">
<title>Logging out…</title>
</head>
<body>
<form id="logout-form" method="post" action="https://nerdhalla.net/discord-logout.php">
<input type="hidden" name="drive" value="done">
</form>
<script>document.getElementById('logout-form').submit();</script>
<noscript>
  <button form="logout-form" type="submit">Continue logout</button>
</noscript>
</body>
</html>"""
    response = web.Response(text=html, content_type='text/html')
    response.del_cookie(COOKIE_NAME, path='/')
    return response


async def api_list(request):
    _, record = session_record(request)
    root = drive_root(record)
    rel = request.query.get('path', '')
    target = safe_target(root, rel)
    if not target.is_dir():
        raise web.HTTPNotFound(text=json.dumps({'error': 'Folder not found.'}), content_type='application/json')

    entries = []
    for item in target.iterdir():
        if item.name.startswith('.nh-upload-'):
            continue
        try:
            resolved = item.resolve(strict=False)
            if os.path.commonpath([str(root), str(resolved)]) != str(root):
                continue
            stat = item.stat()
        except Exception:
            continue
        relative = item.relative_to(root).as_posix()
        entries.append({
            'name': item.name,
            'path': relative,
            'is_dir': item.is_dir(),
            'size': 0 if item.is_dir() else int(stat.st_size),
            'modified': int(stat.st_mtime),
        })

    entries.sort(key=lambda x: (not x['is_dir'], x['name'].casefold()))
    usage = shutil.disk_usage(root)
    return web.json_response({
        'ok': True,
        'username': record['username'],
        'path': '' if clean_relative(rel).as_posix() == '.' else clean_relative(rel).as_posix(),
        'entries': entries,
        'usage': {
            'total_bytes': int(usage.total),
            'used_bytes': int(usage.used),
            'free_bytes': int(usage.free),
        },
        'private': True,
    })


async def api_upload(request):
    _, record = session_record(request)
    root = drive_root(record)
    directory = safe_target(root, request.query.get('path', ''))
    if not directory.is_dir():
        raise web.HTTPNotFound(text=json.dumps({'error': 'Upload folder not found.'}), content_type='application/json')

    name = clean_name(request.query.get('name', ''))
    target = safe_target(root, (directory.relative_to(root) / name).as_posix(), allow_root=False)
    overwrite = request.query.get('overwrite', '') == '1'

    if target.exists() and not overwrite:
        raise web.HTTPConflict(text=json.dumps({'error': 'A file with that name already exists.'}), content_type='application/json')
    if target.exists() and target.is_dir():
        raise web.HTTPConflict(text=json.dumps({'error': 'A folder with that name already exists.'}), content_type='application/json')

    available = max(0, shutil.disk_usage(root).free - RESERVE_BYTES)
    declared = request.content_length
    if declared is not None and declared > available:
        raise web.HTTPInsufficientStorage(text=json.dumps({'error': 'Not enough free Drive space.'}), content_type='application/json')

    tmp = directory / ('.nh-upload-' + os.urandom(8).hex())
    written = 0
    try:
        with open(tmp, 'xb') as fh:
            async for chunk in request.content.iter_chunked(CHUNK):
                written += len(chunk)
                if written > available:
                    raise web.HTTPInsufficientStorage(text=json.dumps({'error': 'Not enough free Drive space.'}), content_type='application/json')
                fh.write(chunk)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, target)
        set_owner(target, record['username'], 0o644)
    finally:
        try:
            if tmp.exists():
                tmp.unlink()
        except Exception:
            pass

    return web.json_response({'ok': True, 'name': name, 'size': written})


async def api_download(request):
    _, record = session_record(request)
    root = drive_root(record)
    target = safe_target(root, request.query.get('path', ''), allow_root=False)
    if not target.is_file():
        raise web.HTTPNotFound(text=json.dumps({'error': 'File not found.'}), content_type='application/json')

    quoted = urllib.parse.quote(target.name)
    mime, _ = mimetypes.guess_type(target.name)
    mime = mime or 'application/octet-stream'

    wants_view = request.query.get('view', '') == '1'
    inline_safe = (
        mime not in BLOCKED_INLINE_MIME_TYPES
        and (
            mime in INLINE_MIME_TYPES
            or any(mime.startswith(prefix) for prefix in INLINE_MIME_PREFIXES)
        )
    )

    response = web.FileResponse(target)
    response.content_type = mime

    if wants_view and inline_safe:
        response.headers['Content-Disposition'] = "inline; filename*=UTF-8''" + quoted
        response.headers['Content-Security-Policy'] = "sandbox; default-src 'none'"
    else:
        response.headers['Content-Disposition'] = "attachment; filename*=UTF-8''" + quoted

    return response


async def api_mkdir(request):
    _, record = session_record(request)
    root = drive_root(record)
    data = await request.json()
    directory = safe_target(root, data.get('path', ''))
    if not directory.is_dir():
        raise web.HTTPNotFound(text=json.dumps({'error': 'Folder not found.'}), content_type='application/json')
    name = clean_name(data.get('name', ''))
    target = safe_target(root, (directory.relative_to(root) / name).as_posix(), allow_root=False)
    if target.exists():
        raise web.HTTPConflict(text=json.dumps({'error': 'That name already exists.'}), content_type='application/json')
    target.mkdir(mode=0o755)
    set_owner(target, record['username'], 0o755)
    return web.json_response({'ok': True, 'name': name})


async def api_rename(request):
    _, record = session_record(request)
    root = drive_root(record)
    data = await request.json()
    source = safe_target(root, data.get('path', ''), allow_root=False)
    if not source.exists():
        raise web.HTTPNotFound(text=json.dumps({'error': 'Item not found.'}), content_type='application/json')
    name = clean_name(data.get('name', ''))
    target = safe_target(root, (source.parent.relative_to(root) / name).as_posix(), allow_root=False)
    if target.exists():
        raise web.HTTPConflict(text=json.dumps({'error': 'That name already exists.'}), content_type='application/json')
    source.rename(target)
    return web.json_response({'ok': True, 'name': name})


async def api_delete(request):
    _, record = session_record(request)
    root = drive_root(record)
    data = await request.json()
    target = safe_target(root, data.get('path', ''), allow_root=False)
    if not target.exists():
        raise web.HTTPNotFound(text=json.dumps({'error': 'Item not found.'}), content_type='application/json')
    if target.is_dir():
        shutil.rmtree(target)
    else:
        target.unlink()
    return web.json_response({'ok': True})


app = web.Application(
    middlewares=[cors_middleware],
    client_max_size=11 * 1024 * 1024 * 1024,
)
app.router.add_route('OPTIONS', '/api/{tail:.*}', lambda request: web.Response(status=204))
app.router.add_get('/private', private_page)
app.router.add_get('/private/', private_page)
app.router.add_get('/private/logout', private_logout)
app.router.add_get('/private/view', private_view)
app.router.add_get('/private/{name}', private_asset)
app.router.add_post('/api/session', api_session)
app.router.add_get('/api/list', api_list)
app.router.add_post('/api/upload', api_upload)
app.router.add_get('/api/download', api_download)
app.router.add_post('/api/mkdir', api_mkdir)
app.router.add_post('/api/rename', api_rename)
app.router.add_post('/api/delete', api_delete)

if __name__ == '__main__':
    web.run_app(app, host='127.0.0.1', port=5091, access_log=None)
