#!/usr/bin/env python3
"""Run Raven's actual archive reader/importer without changing its working tree."""
import argparse
import io
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import zipfile

REVISION = "2b4b41b3bbcfded912aa41f763aac82b0bfecf2a"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--raven", type=Path, default=Path.home() / "gostafa/raven")
parser.add_argument("--fixture", type=Path)
parser.add_argument("--input", type=Path)
parser.add_argument("--output", type=Path)
parser.add_argument("--expected", type=Path, help="Compare transferred content against an original archive, ignoring remapped identities")
args = parser.parse_args()
if not args.fixture and not args.input:
    parser.error("provide --fixture or --input")
if args.output and not args.input:
    parser.error("--output requires --input")

def contents(filename):
    with zipfile.ZipFile(filename) as archive:
        document = json.loads(archive.read("cards.json"))
        result = []
        for folder in document["folders"]:
            metadata = dict(folder["folder"])
            metadata.pop("id")
            cards = []
            for card in folder["cards"]:
                card = dict(card)
                card.pop("id")
                card.pop("folder_id")
                items = []
                for original in card.get("items", []):
                    item = dict(original)
                    for key in ("id", "folder_id", "card_id", "last_notified_due"):
                        item.pop(key, None)
                    path = item.pop("relative_path", "")
                    if path:
                        item["media_sha256"] = hashlib.sha256(archive.read(path)).hexdigest()
                    # Go omits empty optional values; Kotlin emits their zero values.
                    for key in ("text", "mime_type", "original_name", "byte_size", "hint"):
                        if not item.get(key):
                            item.pop(key, None)
                    items.append(item)
                card["items"] = sorted(items, key=lambda item: item["position"])
                cards.append(card)
            result.append({"folder": metadata, "cards": sorted(cards, key=lambda card: (card["created_at"], json.dumps(card, sort_keys=True)))})
        return sorted(result, key=lambda folder: json.dumps(folder, sort_keys=True, ensure_ascii=False))

if args.expected:
    if not args.input:
        parser.error("--expected requires --input")
    assert contents(args.expected) == contents(args.input), "Archive content differs from original"
with tempfile.TemporaryDirectory(prefix="raven-archive-compat-") as tmp:
    archive = subprocess.check_output(["git", "-C", str(args.raven), "archive", REVISION])
    with tarfile.open(fileobj=io.BytesIO(archive)) as source:
        source.extractall(tmp, filter="data")
    tests = Path(tmp) / "internal/shared/boltstore/adapters/bbolt/bbolt_test.go"
    with tests.open("a") as target:
        target.write(Path(__file__).with_name("raven_test.go.txt").read_text())
    env = os.environ.copy()
    for option in ("fixture", "input", "output"):
        value = getattr(args, option)
        if value:
            value = value.resolve()
            value.parent.mkdir(parents=True, exist_ok=True)
            env[f"RAVEN_ARCHIVE_{option.upper()}"] = str(value)
    subprocess.run(["go", "test", "./internal/shared/boltstore/adapters/bbolt", "-run", "^TestAndroidArchiveCompatibility$", "-count=1", "-v"], cwd=tmp, env=env, check=True)
    if args.output:
        assert contents(args.input) == contents(args.output), "Raven re-export changed transferred content"
