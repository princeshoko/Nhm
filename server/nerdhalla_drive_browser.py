import fcntl
import json
import os
import re
import secrets
import time
from pathlib import Path

from aiohttp import web

STATE_ROOT = Path('/mnt/storage/nerdhalla-drive')
ACCOUNTS_FILE = STATE_ROOT / 'accounts.json'
TOKENS_FILE = STATE_ROOT / 'browser_tokens.json'
TOKENS_LOCK = STATE_ROOT / '.browser_tokens.lock'
TOKEN_TTL = 30 * 60
USERNAME_RE = re.compile(r'^[a-z][a-z0-9_-]{2,31}$')


def _load_json(path, default):
    try:
        with open(path, 'r', encoding='utf-8') as fh:
            data = json.load(fh)
        return data
    except FileNotFoundError:
        return default
    except Exception:
        return default


def _account_candidates(data):
    if isinstance(data, list):
        for item in data:
            if isinstance(item, dict):
                yield item
        return

    if not isinstance(data, dict):
        return

    accounts = data.get('accounts')
    if isinstance(accounts, dict):
        for key, value in accounts.items():
            if isinstance(value, dict):
                item = dict(value)
                item.setdefault('discord_id', str(key))
                yield item
    elif isinstance(accounts, list):
        for value in accounts:
            if isinstance(value, dict):
                yield value

    for key, value in data.items():
        if key in {'accounts', 'version', 'updated_at'}:
            continue
        if isinstance(value, dict):
            item = dict(value)
            if str(key).isdigit():
                item.setdefault('discord_id', str(key))
            yield item


def _find_account(user_id):
    data = _load_json(ACCOUNTS_FILE, {})
    wanted = str(user_id)

    if isinstance(data, dict):
        direct = data.get(wanted)
        if isinstance(direct, dict):
            item = dict(direct)
            item.setdefault('discord_id', wanted)
            return item

    for item in _account_candidates(data):
        candidate = str(
            item.get('discord_id')
            or item.get('user_id')
            or item.get('discord_user_id')
            or ''
        )
        if candidate == wanted:
            return item

    return None


def _write_tokens(tokens):
    STATE_ROOT.mkdir(parents=True, exist_ok=True)
    tmp = TOKENS_FILE.with_name(TOKENS_FILE.name + '.tmp.' + secrets.token_hex(4))
    with open(tmp, 'w', encoding='utf-8') as fh:
        json.dump(tokens, fh, indent=2, sort_keys=True)
        fh.write('\n')
        fh.flush()
        os.fsync(fh.fileno())
    os.chmod(tmp, 0o600)
    os.replace(tmp, TOKENS_FILE)
    os.chmod(TOKENS_FILE, 0o600)


def _issue_token(user_id, username):
    now = int(time.time())
    expires = now + TOKEN_TTL
    token = secrets.token_urlsafe(36)

    STATE_ROOT.mkdir(parents=True, exist_ok=True)
    with open(TOKENS_LOCK, 'a+', encoding='utf-8') as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        tokens = _load_json(TOKENS_FILE, {})
        if not isinstance(tokens, dict):
            tokens = {}

        cleaned = {}
        for key, value in tokens.items():
            if not isinstance(value, dict):
                continue
            if int(value.get('expires_at', 0) or 0) <= now:
                continue
            cleaned[key] = value

        cleaned[token] = {
            'discord_id': str(user_id),
            'username': username,
            'issued_at': now,
            'expires_at': expires,
        }
        _write_tokens(cleaned)
        fcntl.flock(lock.fileno(), fcntl.LOCK_UN)

    return token, expires


async def portal_drive_browser_token(request):
    authenticate = request.app['drive_browser_authenticate']
    user_id = await authenticate(request)

    account = _find_account(user_id)
    if not account:
        return web.json_response(
            {'error': 'Create your Nerdhalla Drive first.'},
            status=404,
        )

    username = str(account.get('username') or '').strip().lower()
    if not USERNAME_RE.fullmatch(username):
        return web.json_response(
            {'error': 'Your Drive account has an invalid username.'},
            status=500,
        )

    root = Path('/home') / username / 'files'
    if not root.is_dir():
        return web.json_response(
            {'error': 'Your Drive storage is not mounted right now.'},
            status=503,
        )

    token, expires = _issue_token(user_id, username)
    return web.json_response({
        'ok': True,
        'token': token,
        'expires_at': expires,
        'expires_in': TOKEN_TTL,
        'api_base': 'https://drive.nerdhalla.net/api',
        'username': username,
        'private': True,
    })


def install_drive_browser(bot, app, authenticate):
    app['drive_browser_authenticate'] = authenticate
    path = '/portal/me/drive/browser-token'
    existing = {route.resource.canonical for route in app.router.routes()}
    if path not in existing:
        app.router.add_get(path, portal_drive_browser_token)
