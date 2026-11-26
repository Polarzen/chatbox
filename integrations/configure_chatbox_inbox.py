"""List key IDs and WebChat creators, or grant a specific pair on this AstrBot host."""
import argparse
import json
import os
import re
import sqlite3
import tempfile
from contextlib import closing
from pathlib import Path

USER = re.compile(r"chatbox_[0-9a-f]{32}")
MAX_GRANTS = 64 * 1024


def inventory(data):
    database = data / "data_v4.db"
    with closing(sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True)) as db:
        keys = db.execute("""
            SELECT key_id, name FROM api_keys WHERE revoked_at IS NULL
              AND (expires_at IS NULL OR julianday(expires_at) > julianday('now'))
            ORDER BY name, key_id
        """).fetchall()
        creators = db.execute("""
            SELECT creator, COUNT(*) FROM platform_sessions
            WHERE platform_id='webchat' AND is_group=0 GROUP BY creator ORDER BY creator
        """).fetchall()
    return dict(keys), {user: count for user, count in creators if USER.fullmatch(user)}


def grant(data, key, users):
    if not users:
        raise ValueError("请选择至少一个 Chat Box 身份。")
    key_ids, creators = inventory(data)
    if key not in key_ids or any(user not in creators for user in users):
        raise ValueError("请从本实例列出的有效密钥 ID 和已有会话身份中选择。")
    path = data / "chatbox-inbox-grants.json"
    # Serialize independent invocations so two simultaneous grants cannot lose an update.
    lock = path.with_suffix(".lock")
    lock.open("x").close()
    try:
        if path.exists():
            with path.open("rb") as f:
                raw = f.read(MAX_GRANTS + 1)
            if len(raw) > MAX_GRANTS:
                raise ValueError("现有授权文件过大，请检查后重试。")
            grants = json.loads(raw.decode("utf-8-sig"))
        else:
            grants = {}
        if not isinstance(grants, dict) or not all(
            isinstance(group, list) and all(isinstance(item, str) and USER.fullmatch(item) for item in group)
            for group in grants.values()
        ):
            raise ValueError("现有授权文件格式无效，请先检查。")
        grants[key] = sorted(set(grants.get(key, [])).union(users))
        payload = (json.dumps(grants, ensure_ascii=False, indent=2) + "\n").encode()
        if len(payload) > MAX_GRANTS:
            raise ValueError("授权文件超过大小限制。")
        with tempfile.NamedTemporaryFile(dir=data, delete=False) as tmp:
            staging = Path(tmp.name)
        try:
            staging.write_bytes(payload)
            os.replace(staging, path)
        finally:
            staging.unlink(missing_ok=True)
    finally:
        lock.unlink()
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("data", type=Path, help="AstrBot instance data directory containing data_v4.db")
    parser.add_argument("--grant", metavar="KEY_ID", help="public key ID from the list below")
    parser.add_argument("--user", action="append", default=[], help="exact Chat Box creator (repeatable)")
    args = parser.parse_args()
    try:
        if args.grant:
            path = grant(args.data, args.grant, args.user)
            print(f"已保存到 {path}")
        else:
            if args.user:
                parser.error("--user 需与 --grant 一起使用。")
            keys, creators = inventory(args.data)
            print("有效密钥 ID  名称")
            for key, name in keys.items():
                print(key, name)
            print("Chat Box 身份  私聊会话数")
            for user, count in creators.items():
                print(user, count)
    except (OSError, sqlite3.Error, ValueError, RecursionError) as error:
        parser.exit(1, f"配置失败：{error}\n")


if __name__ == "__main__":
    main()
