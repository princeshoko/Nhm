#!/usr/bin/env python3
import asyncio
import base64
import hashlib
import hmac
import json
import os
import pwd
import re
import secrets
import shutil
import stat
import time
from pathlib import Path
from urllib.parse import quote

from aiohttp import web

REGISTRY = Path('/mnt/storage/nerdhalla-drive/accounts.json')
SECRET_B64 = os.environ.get('NERDHALLA_DRIVE_WEB_SECRET_B64', '')
USERNAME_RE = re.compile(r'^[a-z][a-z0-9_-]{2,31}$')
MAX_TOKEN_LIFETIME = 3700
DOWNLOAD_TICKET_LIFETIME = 120
CHUNK = 1024 * 1024


def b64url_decode(value: str) -> bytes:
    pad = '=' * (-len(value) % 4)
    return base64.urlsafe_b64decode(value + pad)


def b64url_encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b'=').decode('ascii')


def secret_bytes() -> bytes:
    if not SECRET_B64:
        raise RuntimeError('Drive web secret is not configured')
    try:
        secret = base64.b64decode(SECRET_B64, validate=True)
    except Exception as exc:
        raise RuntimeError('Drive web secret is invalid') from exc
    if len(secret) < 16:
        raise RuntimeError('Drive web secret is too short')
    return secret


SECRET = secret_bytes()


def json_error(message: str, status: int = 400):
    return web.json_response({'ok': False, 'error': message}, status=status)


def verify_signed_token(token: str, expected_kind: str):
    try:
        version, payload_b64, signature = token.split('.', 2)
    except ValueError:
        raise web.HTTPUnauthorized(text='Invalid token')
    if version != 'v1':
        raise web.HTTPUnauthorized(text='Invalid token version')
    signing_input = f'{version}.{payload_b64}'.encode('ascii')
    expected = hmac.new(SECRET, signing_input, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, signature):
        raise web.HTTPUnauthorized(text='Invalid token signature')
    try:
        payload = json.loads(b64url_decode(payload_b64).decode('utf-8'))
    except Exception:
        raise web.HTTPUnauthorized(text='Invalid token payload')
    now = int(time.time())
    exp = int(payload.get('exp', 0))
    iat = int(payload.get('iat', now))
    if exp < now or exp > now + MAX_TOKEN_LIFETIME or iat > now + 60:
        raise web.HTTPUnauthorized(text='Token expired')
    if payload.get('kind', 'manage') != expected_kind:
        raise web.HTTPUnauthorized(text='Wrong token type')
    uid = str(payload.get('uid', ''))
    if not uid.isdigit():
        raise web.HTTPUnauthorized(text='Invalid user')
    return payload


def sign_payload(payload: dict) -> str:
    raw = json.dumps(payload, separators=(',', ':'), sort_keys=True).encode('utf-8')
    payload_b64 = b64url_encode(raw)
    signing_input = f'v1.{payload_b64}'.encode('ascii')
    signature = hmac.new(SECRET, signing_input, hashlib.sha256).hexdigest()
    return f'v1.{payload_b64}.{signature}'


def bearer_token(request: web.Request) -> str:
    auth = request.headers.get('Authorization', '')
    if not auth.lower().startswith('bearer '):
        raise web.HTTPUnauthorized(text='Missing authorization')
    return auth[7:].strip()


def load_registry():
    try:
        with REGISTRY.open('r', encoding='utf-8') as fh:
            return json.load(fh)
    except FileNotFoundError:
        return {}
    except Exception as exc:
        raise web.HTTPServiceUnavailable(text=f'Drive registry unavailable: {exc}')


def account_for_uid(uid: str):
    data = load_registry()
    candidates = []
    if isinstance(data, dict):
        direct = data.get(uid)
        if isinstance(direct, dict):
            candidates.append(direct)
        accounts = data.get('accounts')
        if isinstance(accounts, dict):
            direct = accounts.get(uid)
            if isinstance(direct, dict):
                candidates.append(direct)
            candidates.extend(v for v in accounts.values() if isinstance(v, dict))
        elif isinstance(accounts, list):
            candidates.extend(v for v in accounts if isinstance(v, dict))
        candidates.extend(v for v in data.values() if isinstance(v, dict))
    elif isinstance(data, list):
        candidates.extend(v for v in data if isinstance(v, dict))

    seen = set()
    for account in candidates:
        ident = id(account)
        if ident in seen:
            continue
        seen.add(ident)
        account_uid = str(
            account.get('discord_id',
                account.get('user_id',
                    account.get('discord_user_id',
                        account.get('id', '')))))
        if account_uid and account_uid != uid:
            continue
        username = str(account.get('username', '')).strip().lower()
        if USERNAME_RE.fullmatch(username):
            if account_uid == uid or (isinstance(data, dict) and data.get(uid) is account):
                return account

    if isinstance(data, dict) and uid in data and isinstance(data[uid], dict):
        username = str(data[uid].get('username', '')).strip().lower()
        if USERNAME_RE.fullmatch(username):
            return data[uid]
    return None


def user_context(uid: str):
    account = account_for_uid(uid)
    if not account:
        raise web.HTTPNotFound(text='No Nerdhalla Drive exists for this account')
    username = str(account.get('username', '')).strip().lower()
    if not USERNAME_RE.fullmatch(username):
        raise web.HTTPServiceUnavailable(text='Drive registry contains an invalid username')
    root = Path('/home') / username / 'files'
    if not root.is_dir():
        raise web.HTTPServiceUnavailable(text='Drive storage directory is unavailable')
    if not os.path.ismount(root):
        raise web.HTTPServiceUnavailable(text='Drive storage is not mounted')
    try:
        pw = pwd.getpwnam(username)
    except KeyError:
        raise web.HTTPServiceUnavailable(text='Drive Linux account is unavailable')
    return account, username, root, pw.pw_uid, pw.pw_gid


def manager_payload(request: web.Request):
    payload = verify_signed_token(bearer_token(request), 'manage')
    account, username, root, uid, gid = user_context(str(payload['uid']))
    return payload, account, username, root, uid, gid


def clean_rel(value: str, allow_empty: bool = True) -> str:
    value = (value or '').strip().replace('\\', '/')
    while value.startswith('/'):
        value = value[1:]
    parts = []
    for part in value.split('/'):
        if part in ('', '.'):
            continue
        if part == '..' or part.startswith('.') or '\x00' in part:
            raise web.HTTPBadRequest(text='Invalid path')
        if '/' in part or '\\' in part:
            raise web.HTTPBadRequest(text='Invalid path')
        parts.append(part)
    if not allow_empty and not parts:
        raise web.HTTPBadRequest(text='Path is required')
    return '/'.join(parts)


def safe_name(name: str) -> str:
    name = (name or '').strip()
    if not name or name in ('.', '..') or name.startswith('.'):
        raise web.HTTPBadRequest(text='Invalid name')
    if '/' in name or '\\' in name or '\x00' in name or any(ord(ch) < 32 or ord(ch) == 127 for ch in name):
        raise web.HTTPBadRequest(text='Invalid name')
    if len(name.encode('utf-8')) > 240:
        raise web.HTTPBadRequest(text='Name is too long')
    return name


def checked_path(root: Path, rel: str, must_exist: bool = True) -> Path:
    rel = clean_rel(rel)
    target = root.joinpath(*([p for p in rel.split('/') if p]))
    try:
        root_real = root.resolve(strict=True)
        if must_exist:
            target_real = target.resolve(strict=True)
        else:
            parent_real = target.parent.resolve(strict=True)
            target_real = parent_real / target.name
    except FileNotFoundError:
        raise web.HTTPNotFound(text='Path not found')
    if os.path.commonpath([str(root_real), str(target_real)]) != str(root_real):
        raise web.HTTPForbidden(text='Path escapes Drive')
    current = root_real
    for part in target_real.relative_to(root_real).parts:
        current = current / part
        if current.exists() and current.is_symlink():
            raise web.HTTPForbidden(text='Symlinks are not available in the browser manager')
    return target_real


def item_json(root: Path, path: Path):
    st = path.stat()
    rel = path.relative_to(root).as_posix()
    return {
        'name': path.name,
        'path': rel,
        'type': 'dir' if path.is_dir() else 'file',
        'size': 0 if path.is_dir() else st.st_size,
        'mtime': int(st.st_mtime),
    }


async def health(request):
    return web.json_response({'ok': True, 'service': 'nerdhalla-drive-web'})


async def session_info(request):
    _, account, username, root, _, _ = manager_payload(request)
    usage = shutil.disk_usage(root)
    return web.json_response({
        'ok': True,
        'username': username,
        'web_url': f'https://drive.nerdhalla.net/u/{quote(username)}/',
        'total_bytes': usage.total,
        'used_bytes': usage.used,
        'free_bytes': usage.free,
        'storage_gb': account.get('storage_gb', 10),
    })


async def list_dir(request):
    _, _, username, root, _, _ = manager_payload(request)
    rel = clean_rel(request.query.get('path', ''))
    directory = checked_path(root, rel, must_exist=True)
    if not directory.is_dir():
        raise web.HTTPBadRequest(text='Not a directory')
    items = []
    for entry in directory.iterdir():
        if entry.name.startswith('.') or entry.is_symlink():
            continue
        try:
            items.append(item_json(root, entry))
        except (FileNotFoundError, PermissionError):
            continue
    items.sort(key=lambda x: (x['type'] != 'dir', x['name'].lower()))
    usage = shutil.disk_usage(root)
    return web.json_response({
        'ok': True,
        'username': username,
        'path': rel,
        'items': items,
        'usage': {
            'total_bytes': usage.total,
            'used_bytes': usage.used,
            'free_bytes': usage.free,
        },
    })


async def upload(request):
    _, _, username, root, uid, gid = manager_payload(request)
    rel_dir = clean_rel(request.query.get('path', ''))
    directory = checked_path(root, rel_dir, must_exist=True)
    if not directory.is_dir():
        raise web.HTTPBadRequest(text='Upload target is not a directory')

    reader = await request.multipart()
    part = await reader.next()
    while part is not None and part.name != 'file':
        part = await reader.next()
    if part is None or not part.filename:
        return json_error('No file was provided')

    filename = safe_name(part.filename)
    target = directory / filename
    if target.exists() and target.is_dir():
        return json_error('A folder already uses that name', 409)
    if target.exists() and target.is_symlink():
        return json_error('Symlink targets cannot be replaced', 403)

    usage = shutil.disk_usage(root)
    available = usage.free
    temp = directory / f'.nh-upload-{secrets.token_hex(12)}.part'
    written = 0
    try:
        with temp.open('xb') as fh:
            os.chown(temp, uid, gid)
            os.chmod(temp, 0o644)
            while True:
                chunk = await part.read_chunk(CHUNK)
                if not chunk:
                    break
                written += len(chunk)
                if written > available:
                    raise web.HTTPInsufficientStorage(text='Not enough free Drive space')
                fh.write(chunk)
        os.replace(temp, target)
        os.chown(target, uid, gid)
        os.chmod(target, 0o644)
    except Exception:
        try:
            temp.unlink(missing_ok=True)
        except Exception:
            pass
        raise

    return web.json_response({
        'ok': True,
        'message': f'Uploaded {filename}',
        'item': item_json(root, target),
    })


async def mkdir(request):
    _, _, _, root, uid, gid = manager_payload(request)
    try:
        body = await request.json()
    except Exception:
        return json_error('Invalid JSON')
    rel = clean_rel(str(body.get('path', '')))
    name = safe_name(str(body.get('name', '')))
    directory = checked_path(root, rel, must_exist=True)
    if not directory.is_dir():
        return json_error('Parent is not a directory')
    target = directory / name
    if target.exists():
        return json_error('That name already exists', 409)
    target.mkdir(mode=0o755)
    os.chown(target, uid, gid)
    return web.json_response({'ok': True, 'item': item_json(root, target)})


async def rename_item(request):
    _, _, _, root, _, _ = manager_payload(request)
    try:
        body = await request.json()
    except Exception:
        return json_error('Invalid JSON')
    rel = clean_rel(str(body.get('path', '')), allow_empty=False)
    name = safe_name(str(body.get('name', '')))
    source = checked_path(root, rel, must_exist=True)
    if source == root:
        return json_error('Cannot rename Drive root')
    target = source.parent / name
    checked_path(root, target.relative_to(root).as_posix(), must_exist=False)
    if target.exists():
        return json_error('That name already exists', 409)
    source.rename(target)
    return web.json_response({'ok': True, 'item': item_json(root, target)})


async def delete_item(request):
    _, _, _, root, _, _ = manager_payload(request)
    try:
        body = await request.json()
    except Exception:
        return json_error('Invalid JSON')
    rel = clean_rel(str(body.get('path', '')), allow_empty=False)
    target = checked_path(root, rel, must_exist=True)
    if target == root:
        return json_error('Cannot delete Drive root')
    if target.is_symlink():
        return json_error('Symlinks cannot be managed here', 403)
    if target.is_dir():
        shutil.rmtree(target)
    else:
        target.unlink()
    return web.json_response({'ok': True})


async def download_ticket(request):
    payload, _, _, root, _, _ = manager_payload(request)
    try:
        body = await request.json()
    except Exception:
        return json_error('Invalid JSON')
    rel = clean_rel(str(body.get('path', '')), allow_empty=False)
    target = checked_path(root, rel, must_exist=True)
    if not target.is_file() or target.is_symlink():
        return json_error('Only regular files can be downloaded')
    now = int(time.time())
    ticket = sign_payload({
        'kind': 'download',
        'uid': str(payload['uid']),
        'path': rel,
        'iat': now,
        'exp': now + DOWNLOAD_TICKET_LIFETIME,
        'nonce': secrets.token_urlsafe(10),
    })
    return web.json_response({'ok': True, 'url': '/api/download?ticket=' + quote(ticket, safe='')})


async def download(request):
    token = request.query.get('ticket', '')
    payload = verify_signed_token(token, 'download')
    _, _, root, _, _ = user_context(str(payload['uid']))
    rel = clean_rel(str(payload.get('path', '')), allow_empty=False)
    target = checked_path(root, rel, must_exist=True)
    if not target.is_file() or target.is_symlink():
        raise web.HTTPNotFound(text='File not found')
    response = web.FileResponse(path=target)
    ascii_name = re.sub(r'[^A-Za-z0-9._-]+', '_', target.name).strip('_') or 'download'
    encoded_name = quote(target.name, safe='')
    response.headers['Content-Disposition'] = (
        f'attachment; filename="{ascii_name}"; filename*=UTF-8\'\'{encoded_name}'
    )
    response.headers['Cache-Control'] = 'private, no-store'
    response.headers['X-Content-Type-Options'] = 'nosniff'
    return response


@web.middleware
async def headers_middleware(request, handler):
    try:
        response = await handler(request)
    except web.HTTPException as exc:
        if request.path.startswith('/') and request.path != '/health':
            message = exc.text or exc.reason
            response = web.json_response({'ok': False, 'error': message}, status=exc.status)
        else:
            raise
    response.headers['Cache-Control'] = 'no-store'
    response.headers['X-Content-Type-Options'] = 'nosniff'
    response.headers['Referrer-Policy'] = 'no-referrer'
    return response


app = web.Application(
    client_max_size=11 * 1024 * 1024 * 1024,
    middlewares=[headers_middleware],
)
app.router.add_get('/health', health)
app.router.add_get('/session', session_info)
app.router.add_get('/list', list_dir)
app.router.add_post('/upload', upload)
app.router.add_post('/mkdir', mkdir)
app.router.add_post('/rename', rename_item)
app.router.add_post('/delete', delete_item)
app.router.add_post('/download-ticket', download_ticket)
app.router.add_get('/download', download)

if __name__ == '__main__':
    web.run_app(app, host='127.0.0.1', port=8766, access_log=None)