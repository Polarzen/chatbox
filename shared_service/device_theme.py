"""Personal appearance shared by the active members of one paired-device instance."""

import base64
import binascii
import json
import re

if __package__:
    from . import service as core
else:
    import service as core

MAX_JPEG = 512 * 1024
MAX_EDGE = 1600
MAX_REVISION = 2**63 - 1


def migrate(db):
    db.execute("""CREATE TABLE IF NOT EXISTS device_theme(
                  id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL, theme TEXT)""")
    db.execute("INSERT OR IGNORE INTO device_theme VALUES(1,0,NULL)")


def bounded_jpeg(data):
    # Read the frame dimensions before the compressed scan. Importers on both
    # platforms re-encode as ordinary baseline or progressive JPEG.
    if not (data.startswith(b"\xff\xd8") and data.endswith(b"\xff\xd9")):
        return False
    pos = 2
    frame = False
    end = len(data) - 2
    while pos < end and data[pos] == 0xff:
        while pos < end and data[pos] == 0xff:
            pos += 1
        if pos + 3 > end:
            return False
        marker = data[pos]
        pos += 1
        length = int.from_bytes(data[pos:pos+2], "big")
        if length < 2 or pos + length > end:
            return False
        if marker in (0xc0, 0xc1, 0xc2):
            if frame or length < 11 or data[pos+2] != 8:
                return False
            height = int.from_bytes(data[pos+3:pos+5], "big")
            width = int.from_bytes(data[pos+5:pos+7], "big")
            channels = data[pos+7]
            if (not (1 <= width <= MAX_EDGE and 1 <= height <= MAX_EDGE)
                    or channels not in (1, 3) or length != 8 + 3 * channels):
                return False
            frame = True
        elif marker == 0xda:
            return frame and length >= 8 and pos + length < end
        pos += length
    return False


def encode_theme(theme):
    if theme is None:
        return None
    core.fields(theme, ("dark", "accent", "wallpaper", "blur"))
    if type(theme["dark"]) is not bool or type(theme["blur"]) is not int or not 0 <= theme["blur"] <= 100:
        raise core.ApiError(400, "主题模式或模糊程度无效。")
    accent = theme["accent"]
    if accent is not None:
        if not isinstance(accent, str) or not re.fullmatch(r"#[0-9a-fA-F]{6}", accent):
            raise core.ApiError(400, "主题色必须是六位 RGB 颜色。")
        accent = accent.upper()
    wallpaper = theme["wallpaper"]
    if wallpaper is not None:
        if not isinstance(wallpaper, str):
            raise core.ApiError(400, "背景图片格式无效。")
        if len(wallpaper) > 4 * ((MAX_JPEG + 2) // 3):
            raise core.ApiError(413, "背景图片超过 512 KiB。")
        try:
            image = base64.b64decode(wallpaper, validate=True)
        except (ValueError, binascii.Error) as exc:
            raise core.ApiError(400, "背景图片编码无效。") from exc
        if len(image) > MAX_JPEG:
            raise core.ApiError(413, "背景图片超过 512 KiB。")
        if not bounded_jpeg(image):
            raise core.ApiError(400, "请选择最长边不超过 1600 像素的 JPEG 背景。")
        wallpaper = base64.b64encode(image).decode("ascii")
    return json.dumps(dict(dark=theme["dark"], accent=accent, wallpaper=wallpaper, blur=theme["blur"]),
                      separators=(",", ":"))


def read(db, params):
    since = None
    if params:
        core.fields(params, ("since",))
        value = params["since"][0]
        if not re.fullmatch(r"[0-9]{1,19}", value) or int(value) > MAX_REVISION:
            raise core.ApiError(400, "主题版本无效。")
        since = int(value)
    revision = db.execute("SELECT revision FROM device_theme WHERE id=1").fetchone()[0]
    if revision == since:
        return {"revision": revision}
    theme = db.execute("SELECT theme FROM device_theme WHERE id=1").fetchone()[0]
    return {"revision": revision, "theme": json.loads(theme) if theme is not None else None}


def update(db, body):
    core.fields(body, ("expectedRevision", "theme"))
    expected = body["expectedRevision"]
    if type(expected) is not int or not 0 <= expected <= MAX_REVISION:
        raise core.ApiError(400, "主题版本无效。")
    desired = encode_theme(body["theme"])
    revision, current = db.execute("SELECT revision,theme FROM device_theme WHERE id=1").fetchone()
    if desired == current:
        return {"revision": revision}
    if expected != revision:
        raise core.ApiError(409, "主题已在其他设备更改，请重新载入。")
    db.execute("UPDATE device_theme SET revision=revision+1,theme=? WHERE id=1", (desired,))
    return {"revision": revision + 1}
