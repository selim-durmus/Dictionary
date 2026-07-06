"""
Add Turkish→Turkish dictionary definitions from the TDK Güncel Türkçe Sözlük.

The Kaikki dumps only give us Turkish words defined *in English* (tr→en). This step adds the
missing tr→tr layer: when a Turkish word has no English translation, the app can now show its
Turkish meaning instead. The Güncel Türkçe Sözlük (12th ed., ~99k headwords) also carries a large
body of archaic / Ottoman-era loanwords tagged ``eskimiş`` (Arabic/Persian origin), so this
doubles as light "old Turkish" coverage without a separate Ottoman source.

Source data: the `ogun/guncel-turkce-sozluk` mirror of sozluk.gov.tr (MIT-licensed packaging;
content © TDK). One NDJSON object per headword — same shape as the sozluk.gov.tr /gts API:

    {"madde": "müstağni", "lisan": "Arapça mustaġnī",
     "anlamlarListe": [
        {"anlam_sira": "1", "anlam": "► doygun",
         "ozelliklerListe": [{"tur": "3", "tam_adi": "sıfat"}, {"tur": "4", "tam_adi": "eskimiş"}]},
        {"anlam_sira": "2", "anlam": "Nazlı davranan", "ozelliklerListe": [...]}]}

Each *sense* becomes one tr→tr row: ``target_word`` is the headline (first sentence, short enough
for a result row), the full text lives in ``definition``, and ``pos`` carries the Turkish word
class + usage labels (e.g. "sıfat, eskimiş"). Rows are tagged ``source='tdk'``; being
same-language they sort just below any real tr→en translation (see EntryDao.tierExpr), which is
exactly the "İngilizcesi yoksa Türkçe açıklama" behaviour we want.

No API key, no model. Runs against the already-built DB in place (like translate_gap / panlex), so
it does NOT require re-parsing the multi-GB Kaikki dumps.

Usage:
    python -m ingest_tdk                 # download dump if needed, insert, rebuild FTS + VACUUM
    python -m ingest_tdk --limit 500     # pilot on the first N headwords
    python -m ingest_tdk --sample 20     # print sample rows as they're inserted
"""
from __future__ import annotations

import argparse
import json
import tarfile
import urllib.request
from pathlib import Path

from tqdm import tqdm

from translate_gap import ensure_source_column, finalize_db

DUMP_URL = (
    "https://raw.githubusercontent.com/ogun/guncel-turkce-sozluk/master/"
    "sozluk/v12/v12.gts.json.tar.gz"
)

HERE = Path(__file__).parent
CACHE = HERE / "cache"
DEFAULT_DB = HERE / "build" / "dictionary.db"
TARBALL = CACHE / "tdk-gts-v12.json.tar.gz"
GTS_JSON = CACHE / "gts.json"

INSERT = (
    "INSERT OR IGNORE INTO entries "
    "(source_word, source_lang, target_word, target_lang, pos, category, definition, "
    " sense_order, source) "
    "VALUES (?, 'tr', ?, 'tr', ?, 'General', ?, ?, 'tdk')"
)

# `tur` codes on ozelliklerListe: 3 = word class (isim/sıfat/fiil…), 4 = usage register
# (eskimiş/mecaz/argo…). We surface both in `pos`; the "eskimiş" label is what flags an old word.
TUR_WORD_CLASS = "3"
TUR_USAGE = "4"

HEAD_MAX_LEN = 120


def ensure_dump() -> None:
    """Download + extract the GTS NDJSON dump into cache/ (gitignored) if missing."""
    if GTS_JSON.exists():
        return
    CACHE.mkdir(parents=True, exist_ok=True)
    if not TARBALL.exists():
        print(f"Downloading {DUMP_URL} -> {TARBALL.name} …")
        tmp = TARBALL.with_suffix(TARBALL.suffix + ".part")
        urllib.request.urlretrieve(DUMP_URL, tmp)
        tmp.replace(TARBALL)
    print(f"Extracting {TARBALL.name} …")
    with tarfile.open(TARBALL, "r:gz") as tf:
        member = next(m for m in tf.getmembers() if m.name.endswith(".json"))
        member.name = GTS_JSON.name  # flatten any leading path
        tf.extract(member, CACHE)


def iter_maddeler(path: Path):
    """Yield parsed headword objects from the NDJSON dump (one per line)."""
    total_bytes = path.stat().st_size
    with open(path, "rb") as f, tqdm(
        total=total_bytes, unit="B", unit_scale=True, unit_divisor=1024, desc="parse tdk"
    ) as bar:
        for line in f:
            bar.update(len(line))
            line = line.strip()
            if not line:
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError:
                continue


def head_of(text: str) -> str:
    """First sentence of a definition, trimmed + ellipsized — mirrors parse._first_sentence."""
    head = text.split(". ", 1)[0] if ". " in text else text
    head = head.rstrip(".").strip()
    if len(head) > HEAD_MAX_LEN:
        head = head[: HEAD_MAX_LEN - 1].rstrip() + "…"
    return head


def build_pos(ozellikler: list) -> str | None:
    """Turkish word class + usage labels, deduped in order → 'sıfat, eskimiş' (None if empty)."""
    parts: list[str] = []
    for o in ozellikler or []:
        if not isinstance(o, dict):
            continue
        if o.get("tur") not in (TUR_WORD_CLASS, TUR_USAGE):
            continue
        name = (o.get("tam_adi") or "").strip()
        if name and name not in parts:
            parts.append(name)
    return ", ".join(parts) or None


def rows_for(entry: dict):
    """Emit (source_word, target_word, pos, definition, sense_order) tuples for one headword."""
    word = entry.get("madde")
    if not isinstance(word, str) or not word.strip():
        return
    word = word.strip()
    for i, sense in enumerate(entry.get("anlamlarListe") or []):
        if not isinstance(sense, dict):
            continue
        text = (sense.get("anlam") or "").strip()
        if not text:
            continue
        head = head_of(text)
        pos = build_pos(sense.get("ozelliklerListe"))
        definition = text if text != head else None
        try:
            order = int(sense.get("anlam_sira")) - 1
        except (TypeError, ValueError):
            order = i
        yield (word, head, pos, definition, max(order, 0))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", type=Path, default=DEFAULT_DB)
    ap.add_argument("--limit", type=int, default=None, help="Only ingest the first N headwords.")
    ap.add_argument("--sample", type=int, default=10, help="Print the first N inserted rows.")
    args = ap.parse_args()

    ensure_dump()
    if not args.db.exists():
        raise SystemExit(f"{args.db} not found — build the dictionary first.")

    conn = None
    try:
        import sqlite3

        conn = sqlite3.connect(args.db)
        conn.execute("PRAGMA journal_mode = WAL")
        ensure_source_column(conn)

        inserted = words = 0
        samples_left = args.sample
        batch: list[tuple] = []
        for n, entry in enumerate(iter_maddeler(GTS_JSON)):
            if args.limit is not None and n >= args.limit:
                break
            emitted = False
            for word, head, pos, definition, order in rows_for(entry):
                emitted = True
                if samples_left > 0:
                    label = f" [{pos}]" if pos else ""
                    print(f"  · {word}{label} → {head}")
                    samples_left -= 1
                batch.append((word, head, pos, definition, order))
                if len(batch) >= 5000:
                    conn.executemany(INSERT, batch)
                    conn.commit()
                    inserted += len(batch)
                    batch.clear()
            if emitted:
                words += 1
        if batch:
            conn.executemany(INSERT, batch)
            conn.commit()
            inserted += len(batch)

        total = conn.execute("SELECT COUNT(*) FROM entries WHERE source = 'tdk'").fetchone()[0]
        print(f"\nInserted {inserted:,} tr→tr rows across {words:,} headwords "
              f"({total:,} tdk rows now present).")
        print("Rebuilding FTS index + VACUUM…")
        finalize_db(conn)
        print("Done.")
    finally:
        if conn is not None:
            conn.close()


if __name__ == "__main__":
    main()
