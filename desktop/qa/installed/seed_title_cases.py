"""Prepare an independent synthetic app profile for installed-window checks."""

import argparse
import json
from pathlib import Path
import shutil
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('fixture', type=Path)
    parser.add_argument('destination', type=Path)
    args = parser.parse_args()
    original = args.fixture / 'localappdata' / 'Chatbox' / 'history.json'
    record = json.loads(original.read_text(encoding='utf-8-sig'))
    if not record['username'].startswith('ui_fixture_'):
        raise SystemExit('Expected the isolated QA fixture username.')
    profile = record['profiles'][0]
    if profile['address'] != 'https://127.0.0.1:18443' or len(profile['sessions']) != 1:
        raise SystemExit('Expected the original synthetic profile A session.')
    root = args.destination
    root.mkdir(parents=True, exist_ok=False)
    shutil.copytree(args.fixture / 'localappdata', root / 'localappdata')
    (root / 'tls').mkdir()
    shutil.copy(args.fixture / 'tls/fixture-app-trust.p12', root / 'tls')
    (root / 'private').mkdir()
    shutil.copy(args.fixture / 'private/service.ready', root / 'private')
    legacy = profile['sessions'][0]
    legacy['title'] = 'Legacy field title'
    legacy['lastUsedAt'] = 600
    numbered = '\n\n'.join(f'History {i:03d} | Numbered observations for the other conversation.'
                           for i in range(1, 91))
    for stamp, question, answer, upload in [
        (500, 'Same title', numbered, None),
        (400, 'Same title', 'Second twin conversation.', None),
        (300, 'Question-derived title', 'Text history.', None),
        (200, '', 'Image-only history.', 'fixture-upload.png'),
        (100, None, None, None),
    ]:
        session = {'id': str(uuid.uuid4()), 'entries': [], 'lastUsedAt': stamp}
        if question is not None:
            entry = {'question': question, 'answer': answer, 'status': '已完成', 'replyImages': []}
            if upload:
                entry['attachmentDisplayName'] = upload
            session['entries'].append(entry)
        profile['sessions'].append(session)
    output = root / 'localappdata/Chatbox/history.json'
    output.write_text(json.dumps(record, ensure_ascii=False), encoding='utf-8')
    print(f'Prepared {len(profile["sessions"])} distinct sessions for profile A.')


if __name__ == '__main__':
    main()
