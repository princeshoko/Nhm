#!/usr/bin/env bash
set -euo pipefail

cd /root/sharla
STAMP="$(date +%Y%m%d_%H%M%S)"
cp -a sharla.py "sharla.py.before_secret_achievements_$STAMP"
cp -a sharla_web_portal.py "sharla_web_portal.py.before_secret_achievements_$STAMP"

cat > secret_achievements.py <<'PY_SECRET'
import time
from collections import Counter

import discord


SECRET_ACHIEVEMENTS = [
    {
        "key": "secret_touch_grass",
        "name": "Touch Grass",
        "emoji": "🌱",
        "description": "Welcome back. The outside world survived you.",
        "rarity": "Rare",
        "reward": 25,
    },
    {
        "key": "secret_loot_goblin",
        "name": "Loot Goblin",
        "emoji": "👹",
        "description": "You weren’t waiting for loot. Loot was waiting for you.",
        "rarity": "Epic",
        "reward": 50,
    },
    {
        "key": "secret_inventory_problem",
        "name": "Do I Have a Problem?",
        "emoji": "📦",
        "description": "No. You have an inventory management opportunity.",
        "rarity": "Rare",
        "reward": 25,
    },
    {
        "key": "secret_one_of_everything",
        "name": "One of Everything",
        "emoji": "🧺",
        "description": "Gotta collect ’em—wait, wrong franchise.",
        "rarity": "Legendary",
        "reward": 75,
    },
    {
        "key": "secret_all_duplicates",
        "name": "Oops, All Duplicates",
        "emoji": "♻️",
        "description": "Surely the eleventh one will be different.",
        "rarity": "Epic",
        "reward": 50,
    },
    {
        "key": "secret_favorite_customer",
        "name": "Sharla’s Favorite Customer",
        "emoji": "💸",
        "description": "Sharla would like to personally thank your questionable financial decisions.",
        "rarity": "Legendary",
        "reward": 75,
    },
    {
        "key": "secret_nothing_happened",
        "name": "Absolutely Nothing Happened",
        "emoji": "🥔",
        "description": "Against all odds, you accomplished almost nothing.",
        "rarity": "Rare",
        "reward": 25,
    },
    {
        "key": "secret_chosen_one",
        "name": "The Chosen One… Apparently",
        "emoji": "✨",
        "description": "From garbage to greatness in record time.",
        "rarity": "Mythic",
        "reward": 150,
    },
    {
        "key": "secret_why_are_you_like_this",
        "name": "Why Are You Like This?",
        "emoji": "🤨",
        "description": "Sharla has noticed your behavior and has several questions.",
        "rarity": "Uncommon",
        "reward": 15,
    },
    {
        "key": "secret_secret_hunter",
        "name": "I Was Told There’d Be a Secret",
        "emoji": "🕵️",
        "description": "Congratulations. Your reward for finding secrets is another secret.",
        "rarity": "Mythic",
        "reward": 150,
    },
]

SECRET_BY_KEY = {item["key"]: item for item in SECRET_ACHIEVEMENTS}
SECRET_KEYS = set(SECRET_BY_KEY)
META_SECRET_KEY = "secret_secret_hunter"
RARITY_ORDER = ["Common", "Uncommon", "Rare", "Legendary", "Mythic"]


def _pool(bot):
    db = getattr(bot, "db", None)
    return getattr(db, "pool", None) if db else None


async def ensure_secret_achievement_tables(bot):
    pool = _pool(bot)
    if pool is None:
        return False

    async with pool.acquire() as conn:
        try:
            async with conn.cursor() as cur:
                await cur.execute(
                    """
                    CREATE TABLE IF NOT EXISTS secret_achievement_progress (
                        user_id BIGINT UNSIGNED NOT NULL,
                        last_command_at BIGINT UNSIGNED NOT NULL DEFAULT 0,
                        loot_goblin_count INT UNSIGNED NOT NULL DEFAULT 0,
                        shop_purchase_count INT UNSIGNED NOT NULL DEFAULT 0,
                        common_streak INT UNSIGNED NOT NULL DEFAULT 0,
                        previous_loot_rarity VARCHAR(20) NULL,
                        command_name VARCHAR(100) NULL,
                        command_window_start BIGINT UNSIGNED NOT NULL DEFAULT 0,
                        command_window_count INT UNSIGNED NOT NULL DEFAULT 0,
                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        PRIMARY KEY (user_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """
                )
                await cur.execute(
                    """
                    CREATE TABLE IF NOT EXISTS secret_achievement_meta (
                        meta_key VARCHAR(100) NOT NULL,
                        meta_value VARCHAR(255) NULL,
                        updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        PRIMARY KEY (meta_key)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """
                )
            await conn.commit()
            return True
        except Exception:
            await conn.rollback()
            raise


async def _ensure_progress_row(bot, user_id, seed_last_command=0):
    pool = _pool(bot)
    if pool is None:
        return
    async with pool.acquire() as conn:
        try:
            async with conn.cursor() as cur:
                await cur.execute(
                    """
                    INSERT IGNORE INTO secret_achievement_progress
                        (user_id, last_command_at)
                    VALUES (%s, %s)
                    """,
                    (int(user_id), max(0, int(seed_last_command))),
                )
            await conn.commit()
        except Exception:
            await conn.rollback()
            raise


async def _get_progress(bot, user_id):
    pool = _pool(bot)
    if pool is None:
        return None

    await _ensure_progress_row(bot, user_id)

    async with pool.acquire() as conn:
        async with conn.cursor() as cur:
            await cur.execute(
                """
                SELECT
                    last_command_at,
                    loot_goblin_count,
                    shop_purchase_count,
                    common_streak,
                    previous_loot_rarity,
                    command_name,
                    command_window_start,
                    command_window_count
                FROM secret_achievement_progress
                WHERE user_id=%s
                """,
                (int(user_id),),
            )
            row = await cur.fetchone()

    if not row:
        return None

    return {
        "last_command_at": int(row[0] or 0),
        "loot_goblin_count": int(row[1] or 0),
        "shop_purchase_count": int(row[2] or 0),
        "common_streak": int(row[3] or 0),
        "previous_loot_rarity": row[4],
        "command_name": row[5],
        "command_window_start": int(row[6] or 0),
        "command_window_count": int(row[7] or 0),
    }


async def _send_unlock(notify_target, definition, reward):
    if notify_target is None:
        return

    embed = discord.Embed(
        title="🕵️ Secret Achievement Discovered!",
        description=(
            f"{definition['emoji']} **{definition['name']}**\n"
            f"*{definition['description']}*\n\n"
            f"🏷️ **Rarity:** `{definition['rarity']}`\n"
            f"🪙 **Horse Token Value:** `+{reward}`"
        ),
        color=discord.Color.gold(),
    )

    try:
        if hasattr(notify_target, "send"):
            await notify_target.send(embed=embed)
        elif hasattr(notify_target, "followup"):
            await notify_target.followup.send(embed=embed, ephemeral=True)
    except Exception:
        # Achievement persistence matters more than a failed notification.
        pass


async def _refresh_in_memory_tokens(bot, user_id, reward):
    if reward <= 0:
        return
    data = getattr(bot, "user_metrics", {}).get(int(user_id))
    if data is None:
        return
    data["horse_tokens"] = int(data.get("horse_tokens", 0) or 0) + int(reward)
    db = getattr(bot, "db", None)
    if db is not None and hasattr(db, "schedule_save"):
        db.schedule_save(bot.user_metrics)


async def unlock_secret(bot, user_id, key, notify_target=None, grandfather=False, check_meta=True):
    definition = SECRET_BY_KEY.get(key)
    if definition is None or getattr(bot, "db", None) is None:
        return False

    recorded = await bot.db.get_user_achievements(int(user_id))
    if key in recorded:
        return False

    reward = 0 if grandfather else int(definition["reward"])
    inserted = await bot.db.record_achievement(int(user_id), key, reward)
    if not inserted:
        return False

    await _refresh_in_memory_tokens(bot, user_id, reward)
    await _send_unlock(notify_target, definition, reward)

    if check_meta and key != META_SECRET_KEY:
        await _maybe_unlock_meta(bot, user_id, notify_target, grandfather)

    return True


async def _maybe_unlock_meta(bot, user_id, notify_target=None, grandfather=False):
    recorded = await bot.db.get_user_achievements(int(user_id))
    discovered = sum(
        1
        for key in SECRET_KEYS
        if key != META_SECRET_KEY and key in recorded
    )
    if discovered >= 5 and META_SECRET_KEY not in recorded:
        return await unlock_secret(
            bot,
            user_id,
            META_SECRET_KEY,
            notify_target,
            grandfather=grandfather,
            check_meta=False,
        )
    return False


def _inventory_facts(user_data):
    inventory = list((user_data or {}).get("inventory", []) or [])
    rarities = set()
    normalized = []

    for raw in inventory:
        text = str(raw or "").strip()
        rarity = "Common"
        name = text
        if text.startswith("[") and "]" in text:
            left, name = text.split("]", 1)
            rarity = left[1:].strip() or "Common"
            name = name.strip()
        rarities.add(rarity)
        normalized.append((rarity, name.casefold()))

    counts = Counter(normalized)
    largest_stack = max(counts.values(), default=0)
    return len(inventory), rarities, largest_stack


async def check_secret_state_achievements(bot, user_id, notify_target=None, grandfather=False):
    data = getattr(bot, "user_metrics", {}).get(int(user_id))
    if not data:
        return []

    total_items, rarities, largest_stack = _inventory_facts(data)
    qualified = []

    if total_items >= 100:
        qualified.append("secret_inventory_problem")
    if all(rarity in rarities for rarity in RARITY_ORDER):
        qualified.append("secret_one_of_everything")
    if largest_stack >= 10:
        qualified.append("secret_all_duplicates")

    unlocked = []
    for key in qualified:
        if await unlock_secret(
            bot,
            user_id,
            key,
            notify_target,
            grandfather=grandfather,
        ):
            unlocked.append(key)
    return unlocked


async def record_secret_command(bot, user_id, command_name, notify_target=None):
    if _pool(bot) is None:
        return []

    now = int(time.time())
    progress = await _get_progress(bot, user_id)
    if progress is None:
        return []

    unlocked = []
    last_command_at = progress["last_command_at"]
    if last_command_at > 0 and now - last_command_at >= 30 * 86400:
        if await unlock_secret(bot, user_id, "secret_touch_grass", notify_target):
            unlocked.append("secret_touch_grass")

    clean_name = str(command_name or "unknown").strip().casefold()[:100]
    same_command = clean_name and clean_name == str(progress["command_name"] or "").casefold()
    in_window = progress["command_window_start"] > 0 and now - progress["command_window_start"] <= 600

    if same_command and in_window:
        window_start = progress["command_window_start"]
        window_count = progress["command_window_count"] + 1
    else:
        window_start = now
        window_count = 1

    pool = _pool(bot)
    async with pool.acquire() as conn:
        try:
            async with conn.cursor() as cur:
                await cur.execute(
                    """
                    UPDATE secret_achievement_progress
                    SET last_command_at=%s,
                        command_name=%s,
                        command_window_start=%s,
                        command_window_count=%s
                    WHERE user_id=%s
                    """,
                    (now, clean_name, window_start, window_count, int(user_id)),
                )
            await conn.commit()
        except Exception:
            await conn.rollback()
            raise

    if window_count >= 25:
        if await unlock_secret(bot, user_id, "secret_why_are_you_like_this", notify_target):
            unlocked.append("secret_why_are_you_like_this")

    return unlocked


async def record_secret_loot(bot, user_id, rarity, previous_last_claim, current_time=None, cooldown=43200, notify_target=None):
    if _pool(bot) is None:
        return []

    now = int(current_time or time.time())
    progress = await _get_progress(bot, user_id)
    if progress is None:
        return []

    rarity = str(rarity or "Common")
    unlocked = []

    goblin_count = progress["loot_goblin_count"]
    previous_last_claim = int(previous_last_claim or 0)
    if previous_last_claim > 0:
        ready_at = previous_last_claim + int(cooldown)
        lateness = now - ready_at
        if 0 <= lateness <= 60:
            goblin_count += 1

    common_streak = progress["common_streak"] + 1 if rarity == "Common" else 0
    previous_rarity = str(progress["previous_loot_rarity"] or "")

    pool = _pool(bot)
    async with pool.acquire() as conn:
        try:
            async with conn.cursor() as cur:
                await cur.execute(
                    """
                    UPDATE secret_achievement_progress
                    SET loot_goblin_count=%s,
                        common_streak=%s,
                        previous_loot_rarity=%s
                    WHERE user_id=%s
                    """,
                    (goblin_count, common_streak, rarity, int(user_id)),
                )
            await conn.commit()
        except Exception:
            await conn.rollback()
            raise

    if goblin_count >= 10:
        if await unlock_secret(bot, user_id, "secret_loot_goblin", notify_target):
            unlocked.append("secret_loot_goblin")

    if common_streak >= 5:
        if await unlock_secret(bot, user_id, "secret_nothing_happened", notify_target):
            unlocked.append("secret_nothing_happened")

    if previous_rarity == "Common" and rarity == "Mythic":
        if await unlock_secret(bot, user_id, "secret_chosen_one", notify_target):
            unlocked.append("secret_chosen_one")

    return unlocked


async def record_secret_shop_purchase(bot, user_id, notify_target=None):
    if _pool(bot) is None:
        return False

    await _ensure_progress_row(bot, user_id)
    pool = _pool(bot)
    async with pool.acquire() as conn:
        try:
            async with conn.cursor() as cur:
                await cur.execute(
                    """
                    UPDATE secret_achievement_progress
                    SET shop_purchase_count=shop_purchase_count+1
                    WHERE user_id=%s
                    """,
                    (int(user_id),),
                )
                await cur.execute(
                    "SELECT shop_purchase_count FROM secret_achievement_progress WHERE user_id=%s",
                    (int(user_id),),
                )
                row = await cur.fetchone()
            await conn.commit()
        except Exception:
            await conn.rollback()
            raise

    count = int(row[0] or 0) if row else 0
    if count >= 25:
        return await unlock_secret(bot, user_id, "secret_favorite_customer", notify_target)
    return False


async def grandfather_secret_achievements(bot):
    if not await ensure_secret_achievement_tables(bot):
        return {"ran": False, "users": 0, "unlocked": 0}

    pool = _pool(bot)
    async with pool.acquire() as conn:
        async with conn.cursor() as cur:
            await cur.execute(
                "SELECT meta_value FROM secret_achievement_meta WHERE meta_key='grandfather_v1_done'"
            )
            already = await cur.fetchone()

    if already:
        return {"ran": False, "users": 0, "unlocked": 0}

    now = int(time.time())
    users = 0
    unlocked_total = 0

    for user_id in list(getattr(bot, "user_metrics", {}).keys()):
        await _ensure_progress_row(bot, user_id, seed_last_command=now)
        newly = await check_secret_state_achievements(
            bot,
            user_id,
            None,
            grandfather=True,
        )
        users += 1
        unlocked_total += len(newly)

    async with pool.acquire() as conn:
        try:
            async with conn.cursor() as cur:
                await cur.execute(
                    """
                    INSERT INTO secret_achievement_meta (meta_key, meta_value)
                    VALUES ('grandfather_v1_done', %s)
                    ON DUPLICATE KEY UPDATE meta_value=VALUES(meta_value)
                    """,
                    (str(now),),
                )
            await conn.commit()
        except Exception:
            await conn.rollback()
            raise

    return {"ran": True, "users": users, "unlocked": unlocked_total}


def discord_secret_cards(recorded):
    recorded = recorded or {}
    cards = []
    for index, definition in enumerate(SECRET_ACHIEVEMENTS, start=1):
        row = recorded.get(definition["key"])
        if row:
            cards.append({
                "key": definition["key"],
                "name": definition["name"],
                "emoji": definition["emoji"],
                "description": definition["description"],
                "unlocked": True,
                "current": None,
                "target": None,
                "percent": 100.0,
                "category": "Secret",
                "secret": True,
                "rarity": definition["rarity"],
                "reward": int(definition["reward"]),
                "unlocked_at": row.get("unlocked_at") if isinstance(row, dict) else None,
            })
        else:
            cards.append({
                "key": f"secret_hidden_slot_{index}",
                "name": "???",
                "emoji": "❔",
                "description": "Secret Achievement",
                "unlocked": False,
                "current": None,
                "target": None,
                "percent": 0.0,
                "category": "Secret",
                "secret": True,
                "rarity": "???",
                "reward": None,
                "unlocked_at": None,
            })
    return cards


def merge_secret_portal_achievements(existing, recorded):
    existing = list(existing or [])
    recorded = recorded if isinstance(recorded, dict) else {}
    result = [item for item in existing if str(item.get("key", "")) not in SECRET_KEYS]

    for index, definition in enumerate(SECRET_ACHIEVEMENTS, start=1):
        row = recorded.get(definition["key"])
        if isinstance(row, dict):
            item = {
                "key": definition["key"],
                "name": definition["name"],
                "emoji": definition["emoji"],
                "description": definition["description"],
                "rarity": definition["rarity"],
                "reward_tokens": int(definition["reward"]),
                "secret": True,
                "unlocked": True,
            }
            item.update(row)
            # Keep the public-facing catalog value even when a grandfathered
            # ledger row correctly has reward_tokens=0.
            item["horse_token_value"] = int(definition["reward"])
            result.append(item)
        else:
            result.append({
                "key": f"secret_hidden_slot_{index}",
                "name": "???",
                "emoji": "❔",
                "description": "Secret Achievement",
                "rarity": "???",
                "reward_tokens": None,
                "horse_token_value": None,
                "secret": True,
                "unlocked": False,
            })

    return result
PY_SECRET

cat > /tmp/patch_sharla_secret_achievements.py <<'PY_PATCH'
from pathlib import Path

p = Path('sharla.py')
s = p.read_text()

# 1) Import secret achievement engine.
marker = 'from sharla_web_portal import install_sharla_web_portal\n'
addition = '''from secret_achievements import (\n    SECRET_ACHIEVEMENTS,\n    check_secret_state_achievements,\n    discord_secret_cards,\n    grandfather_secret_achievements,\n    record_secret_command,\n    record_secret_loot,\n    record_secret_shop_purchase,\n)\n'''
if addition not in s:
    if marker not in s:
        raise SystemExit('import marker not found')
    s = s.replace(marker, marker + addition, 1)

# 2) Secret state checks after the normal achievement checker.
old = '''    return newly_unlocked\n\n\nasync def grandfather_existing_achievements():\n'''
new = '''    try:\n        await check_secret_state_achievements(\n            bot,\n            user_id,\n            notify_target,\n        )\n    except Exception:\n        logger.exception(\n            "Secret state achievement check failed for user %s",\n            user_id,\n        )\n\n    return newly_unlocked\n\n\nasync def grandfather_existing_achievements():\n'''
if new not in s:
    if old not in s:
        raise SystemExit('normal achievement return marker not found')
    s = s.replace(old, new, 1)

# 3) One-time rollout grandfathering after existing achievement grandfathering.
old = '''    try:\n        await grandfather_existing_achievements()\n    except Exception:\n        logger.exception(\n            "Achievement grandfather initialization failed."\n        )\n'''
new = '''    try:\n        await grandfather_existing_achievements()\n    except Exception:\n        logger.exception(\n            "Achievement grandfather initialization failed."\n        )\n\n    try:\n        result = await grandfather_secret_achievements(bot)\n        if result.get("ran"):\n            logger.info(\n                "[SECRET ACHIEVEMENTS] Grandfathered %s secret achievement(s) across %s user(s).",\n                result.get("unlocked", 0),\n                result.get("users", 0),\n            )\n    except Exception:\n        logger.exception(\n            "Secret achievement grandfather initialization failed."\n        )\n'''
if new not in s:
    if old not in s:
        raise SystemExit('grandfather startup marker not found')
    s = s.replace(old, new, 1)

# 4) Record history-based loot secrets in slash loot.
old = '''    await check_and_reward_achievements(\n        user_id,\n        interaction,\n    )\n\n    await record_challenge_event(\n        user_id,\n        interaction,\n        **get_loot_challenge_increments(rarity),\n    )\n'''
new = '''    await check_and_reward_achievements(\n        user_id,\n        interaction,\n    )\n\n    try:\n        await record_secret_loot(\n            bot,\n            user_id,\n            rarity,\n            last_claim,\n            current_time=current_time,\n            cooldown=cooldown,\n            notify_target=interaction,\n        )\n    except Exception:\n        logger.exception(\n            "Secret loot achievement tracking failed for user %s",\n            user_id,\n        )\n\n    await record_challenge_event(\n        user_id,\n        interaction,\n        **get_loot_challenge_increments(rarity),\n    )\n'''
if new not in s:
    if old not in s:
        raise SystemExit('slash loot marker not found')
    s = s.replace(old, new, 1)

# 5) Record history-based loot secrets in prefix loot (second matching block with ctx).
old = '''    await check_and_reward_achievements(\n        user_id,\n        ctx,\n    )\n\n    await record_challenge_event(\n        user_id,\n        ctx,\n        **get_loot_challenge_increments(rarity),\n    )\n'''
new = '''    await check_and_reward_achievements(\n        user_id,\n        ctx,\n    )\n\n    try:\n        await record_secret_loot(\n            bot,\n            user_id,\n            rarity,\n            last_claim,\n            current_time=current_time,\n            cooldown=cooldown,\n            notify_target=ctx,\n        )\n    except Exception:\n        logger.exception(\n            "Secret loot achievement tracking failed for user %s",\n            user_id,\n        )\n\n    await record_challenge_event(\n        user_id,\n        ctx,\n        **get_loot_challenge_increments(rarity),\n    )\n'''
if new not in s:
    if old not in s:
        raise SystemExit('prefix loot marker not found')
    s = s.replace(old, new, 1)

# 6) Track lifetime shop purchases after a successful purchase.
old = '''        await check_and_reward_achievements(\n            self.user_id,\n            interaction,\n        )\n\n        await record_challenge_event(\n            self.user_id,\n            interaction,\n            shop_purchases=1,\n        )\n'''
new = '''        await check_and_reward_achievements(\n            self.user_id,\n            interaction,\n        )\n\n        try:\n            await record_secret_shop_purchase(\n                bot,\n                self.user_id,\n                interaction,\n            )\n        except Exception:\n            logger.exception(\n                "Secret shop achievement tracking failed for user %s",\n                self.user_id,\n            )\n\n        await record_challenge_event(\n            self.user_id,\n            interaction,\n            shop_purchases=1,\n        )\n'''
if new not in s:
    if old not in s:
        raise SystemExit('shop purchase marker not found')
    s = s.replace(old, new, 1)

# 7) Achievement view accepts permanent ledger records and appends hidden slots.
old = '''        user_data,\n        requester_id,\n    ):\n'''
new = '''        user_data,\n        requester_id,\n        achievement_records=None,\n    ):\n'''
# target only inside AchievementPaginationView by anchoring from class section
idx = s.find('class AchievementPaginationView(discord.ui.View):')
if idx < 0:
    raise SystemExit('AchievementPaginationView not found')
sub = s[idx:]
if 'achievement_records=None' not in sub[:1200]:
    pos = sub.find(old)
    if pos < 0:
        raise SystemExit('achievement view signature marker not found')
    sub = sub[:pos] + sub[pos:].replace(old, new, 1)
    s = s[:idx] + sub

old = '''        self.requester_id = requester_id\n\n        achievement_data = get_achievement_data(\n'''
new = '''        self.requester_id = requester_id\n        self.achievement_records = achievement_records or {}\n\n        achievement_data = get_achievement_data(\n'''
if new not in s:
    if old not in s:
        raise SystemExit('achievement records assignment marker not found')
    s = s.replace(old, new, 1)

old = '''        self.achievements = achievement_data[\n            "achievements"\n        ]\n\n        self.unlocked = achievement_data[\n            "unlocked"\n        ]\n\n        self.total = achievement_data[\n            "total"\n        ]\n'''
new = '''        secret_cards = discord_secret_cards(\n            self.achievement_records\n        )\n\n        self.achievements = (\n            achievement_data["achievements"]\n            + secret_cards\n        )\n\n        self.unlocked = (\n            achievement_data["unlocked"]\n            + sum(1 for item in secret_cards if item["unlocked"])\n        )\n\n        self.total = (\n            achievement_data["total"]\n            + len(secret_cards)\n        )\n'''
# replace twice: constructor and make_embed. Exact old occurs twice.
count = s.count(old)
if count:
    s = s.replace(old, new)
else:
    if 'secret_cards = discord_secret_cards(' not in s:
        raise SystemExit('achievement data block marker not found')

# 8) Render locked secret slots without leaking progress/value/rarity.
marker = '''        for achievement in page_achievements:\n            if achievement["unlocked"]:\n'''
insert = '''        for achievement in page_achievements:\n            if achievement.get("secret") and not achievement["unlocked"]:\n                embed.add_field(\n                    name="❔ ??? — 🔒 SECRET",\n                    value=(\n                        "*Secret Achievement*\\n"\n                        "🏷️ **Rarity:** `???`\\n"\n                        "🪙 **Horse Token Value:** `???`"\n                    ),\n                    inline=False,\n                )\n                continue\n\n            if achievement["unlocked"]:\n'''
if insert not in s:
    if marker not in s:
        raise SystemExit('achievement render loop marker not found')
    s = s.replace(marker, insert, 1)

old = '''            reward = get_achievement_reward(\n                achievement["key"]\n            )\n\n            embed.add_field(\n'''
new = '''            reward = achievement.get("reward")\n            if reward is None:\n                reward = get_achievement_reward(\n                    achievement["key"]\n                )\n\n            extra_lines = []\n            if achievement.get("rarity"):\n                extra_lines.append(\n                    f"🏷️ **Rarity:** `{achievement['rarity']}`"\n                )\n\n            unlocked_at = achievement.get("unlocked_at")\n            if unlocked_at:\n                if hasattr(unlocked_at, "strftime"):\n                    unlocked_at = unlocked_at.strftime("%Y-%m-%d")\n                else:\n                    unlocked_at = str(unlocked_at).split(" ", 1)[0]\n                extra_lines.append(\n                    f"📅 **Earned:** `{unlocked_at}`"\n                )\n\n            extra_text = (\n                "\\n" + "\\n".join(extra_lines)\n                if extra_lines\n                else ""\n            )\n\n            embed.add_field(\n'''
if new not in s:
    if old not in s:
        raise SystemExit('reward render marker not found')
    s = s.replace(old, new, 1)

old = '''                    f"🪙 **Reward:** `{reward}` Horse Tokens\\n"\n                    f"*Category: "\n                    f"{achievement['category']}*"\n'''
new = '''                    f"🪙 **Horse Token Value:** `{reward}`\\n"\n                    f"*Category: "\n                    f"{achievement['category']}*"\n                    f"{extra_text}"\n'''
if new not in s:
    if old not in s:
        raise SystemExit('achievement field value marker not found')
    s = s.replace(old, new, 1)

# 9) Fetch permanent ledger records before constructing slash achievement view.
old = '''    view = AchievementPaginationView(\n        target_user,\n        user_data,\n        interaction.user.id,\n    )\n'''
new = '''    achievement_records = {}\n    if getattr(bot, "db", None):\n        try:\n            achievement_records = await bot.db.get_user_achievements(\n                target_user.id\n            )\n        except Exception:\n            logger.exception(\n                "Could not load achievement dates for user %s",\n                target_user.id,\n            )\n\n    view = AchievementPaginationView(\n        target_user,\n        user_data,\n        interaction.user.id,\n        achievement_records,\n    )\n'''
if new not in s:
    if old not in s:
        raise SystemExit('slash achievement view marker not found')
    s = s.replace(old, new, 1)

# 10) Prefix achievement view.
old = '''    view = AchievementPaginationView(\n        target_user,\n        user_data,\n        ctx.author.id,\n    )\n'''
new = '''    achievement_records = {}\n    if getattr(bot, "db", None):\n        try:\n            achievement_records = await bot.db.get_user_achievements(\n                target_user.id\n            )\n        except Exception:\n            logger.exception(\n                "Could not load achievement dates for user %s",\n                target_user.id,\n            )\n\n    view = AchievementPaginationView(\n        target_user,\n        user_data,\n        ctx.author.id,\n        achievement_records,\n    )\n'''
if new not in s:
    if old not in s:
        raise SystemExit('prefix achievement view marker not found')
    s = s.replace(old, new, 1)

# 11) Successful command history listeners. Insert before Nerdhalla web link listener.
marker = '@bot.listen("on_command_completion")\nasync def _nerdhalla_sharla_prefix_web_links(ctx):\n'
addition = '''@bot.listen("on_command_completion")\nasync def _secret_achievement_prefix_command_tracker(ctx):\n    try:\n        command_name = str(\n            getattr(ctx.command, "qualified_name", "") or ""\n        )\n        await record_secret_command(\n            bot,\n            ctx.author.id,\n            command_name,\n            ctx.channel,\n        )\n    except Exception:\n        logger.exception(\n            "Secret prefix command tracking failed for user %s",\n            getattr(getattr(ctx, "author", None), "id", "unknown"),\n        )\n\n\n@bot.listen("on_app_command_completion")\nasync def _secret_achievement_slash_command_tracker(interaction, command):\n    try:\n        command_name = str(\n            getattr(command, "qualified_name", None)\n            or getattr(command, "name", "")\n            or ""\n        )\n        await record_secret_command(\n            bot,\n            interaction.user.id,\n            command_name,\n            interaction.channel,\n        )\n    except Exception:\n        logger.exception(\n            "Secret slash command tracking failed for user %s",\n            getattr(getattr(interaction, "user", None), "id", "unknown"),\n        )\n\n\n'''
if addition not in s:
    if marker not in s:
        raise SystemExit('command listener marker not found')
    s = s.replace(marker, addition + marker, 1)

p.write_text(s)
print('patched', p)

from pathlib import Path
p=Path('sharla_web_portal.py')
s=p.read_text()

portal_import='''from secret_achievements import (
    merge_secret_portal_achievements,
    record_secret_loot,
    record_secret_shop_purchase,
)
'''
if portal_import not in s:
    marker='import discord\n'
    if marker not in s:
        raise SystemExit('portal import marker not found')
    s=s.replace(marker, marker+portal_import, 1)


def portal_section(text, name):
    start=text.find(f'async def {name}(request):')
    if start < 0:
        raise SystemExit(f'{name} function not found')
    end=text.find('\nasync def ', start+1)
    if end < 0:
        end=len(text)
    return start, end, text[start:end]


# Merge the ten secret cards into /portal/me without disturbing the newer
# earned-date/shop/homeworld fields surrounding the achievement ledger.
start, end, sec = portal_section(s, 'portal_me')
if '    rows = {}\n' not in sec:
    marker='    achievements = []\n'
    if marker not in sec:
        raise SystemExit('portal achievements list marker not found')
    sec=sec.replace(marker, marker+'    rows = {}\n', 1)

if 'merge_secret_portal_achievements(' not in sec:
    anchor='    profile = {\n'
    if anchor not in sec:
        raise SystemExit('portal profile marker not found')
    merge='''    achievements = merge_secret_portal_achievements(
        achievements,
        rows,
    )

'''
    sec=sec.replace(anchor, merge+anchor, 1)

s=s[:start]+sec+s[end:]


# Count app/web loot claims toward the history-based secret achievements.
# Insert at function scope, after the normal Discord-equivalent bookkeeping,
# rather than inside its nested try/if blocks.
start, end, sec = portal_section(s, 'portal_loot')
if 'record_secret_loot(' not in sec:
    anchors=(
        '    # Progression rewards may have changed tokens.\n',
        '    # Achievement/challenge rewards may have changed the token balance.\n',
    )
    pos=-1
    for anchor in anchors:
        pos=sec.find(anchor)
        if pos >= 0:
            break
    if pos < 0:
        pos=sec.rfind('    await _persist(bot)\n')
    if pos < 0:
        raise SystemExit('portal loot final bookkeeping marker not found')

    block='''    try:
        await record_secret_loot(
            bot,
            user_id,
            rarity,
            last_claim,
            current_time=now,
            cooldown=cooldown,
            notify_target=None,
        )
    except Exception:
        pass

'''
    sec=sec[:pos]+block+sec[pos:]

s=s[:start]+sec+s[end:]


# Web-shop purchase tracking is intentionally skipped here.
# The core secret achievement engine and Discord shop tracking are installed
# first. This avoids modifying the newer portal_shop_purchase function until
# its exact live layout is inspected separately.

p.write_text(s)
print('patched portal')

PY_PATCH

python3 /tmp/patch_sharla_secret_achievements.py
python3 -m py_compile sharla.py sharla_web_portal.py secret_achievements.py

echo "===== PATCH MARKERS ====="
grep -n -E 'record_secret_|discord_secret_cards|merge_secret_portal|SECRET ACHIEVEMENTS' sharla.py sharla_web_portal.py | head -100

echo "===== RESTART ====="
systemctl restart sharla
sleep 3
systemctl is-active sharla
journalctl -u sharla -n 60 --no-pager

echo "===== SECRET TABLES ====="
# Database table creation happens inside Sharla on first ready/connect.
# Logs above will show the grandfather summary if it ran.