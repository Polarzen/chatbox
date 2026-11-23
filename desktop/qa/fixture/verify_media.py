"""Optional local AstrBot fixture validator. Write only sanitized protocol evidence."""

import io
import json
import re
import sys
import wave
from email import policy
from email.parser import BytesParser
from pathlib import Path


def require(condition):
    if not condition:
        raise ValueError("Invalid fixture request")


def receipt_path(directory, profile, identifier):
    require(isinstance(identifier, str))
    require(re.fullmatch(r"upload-" + profile.lower() + r"-[0-9a-f-]{36}", identifier))
    return directory / identifier


def upload(directory, profile, identifier, content_type, body):
    require("\r" not in content_type and "\n" not in content_type)
    message = BytesParser(policy=policy.default).parsebytes(
        b"Content-Type: " + content_type.encode("ascii") + b"\r\n\r\n" + body
    )
    require(message.get_content_type() == "multipart/form-data" and not message.defects)
    part, = message.iter_parts()
    require(part.get_content_disposition() == "form-data")
    require(part.get_param("name", header="content-disposition") == "file" and part.get_filename())
    data = part.get_payload(decode=True)
    mime = part.get_content_type()
    if mime == "audio/wav":
        with wave.open(io.BytesIO(data)) as audio:
            frames = audio.getnframes()
            require(frames > 0 and audio.getframerate() > 0)
            require(len(audio.readframes(frames)) == frames * audio.getsampwidth() * audio.getnchannels())
            details = f" frames={frames} rate={audio.getframerate()} channels={audio.getnchannels()}"
        kind = "record"
    else:
        require((mime == "image/png" and data.startswith(b"\x89PNG\r\n\x1a\n"))
                or (mime == "image/jpeg" and data.startswith(b"\xff\xd8\xff")))
        details = ""
        kind = "image"
    receipt_path(directory, profile, identifier).write_text(kind, encoding="ascii")
    print(f"upload={identifier} mime={mime} bytes={len(data)}{details}")


def chat(directory, profile, body):
    message = json.loads(body)["message"]
    if isinstance(message, str):
        require(message)
        print("segments=plain")
        return
    require(isinstance(message, list) and message)
    segments = []
    references = []
    for segment in message:
        kind = segment["type"]
        if kind == "plain":
            require(isinstance(segment["text"], str) and segment["text"])
        else:
            require(kind in ("record", "image"))
            require(isinstance(segment["filename"], str) and segment["filename"])
            identifier = segment["attachment_id"]
            require(identifier not in references)
            require(receipt_path(directory, profile, identifier).read_text(encoding="ascii") == kind)
            references.append(identifier)
        segments.append(kind)
    print(f"segments={','.join(segments)} uploads={','.join(references)}")


def main():
    operation, profile, directory, identifier, content_type = sys.argv[1:]
    require(profile in ("A", "B"))
    body = sys.stdin.buffer.read()
    if operation == "upload":
        upload(Path(directory), profile, identifier, content_type, body)
    else:
        require(operation == "chat")
        chat(Path(directory), profile, body)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, TypeError, KeyError, AttributeError, OSError, EOFError, wave.Error):
        sys.exit(1)
