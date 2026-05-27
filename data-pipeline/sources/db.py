from __future__ import annotations

import sqlite3
from collections.abc import Iterable
from pathlib import Path

from tqdm import tqdm

from .parse import Row


SCHEMA = """
PRAGMA journal_mode = OFF;
PRAGMA synchronous = OFF;
PRAGMA temp_store = MEMORY;
PRAGMA mmap_size = 268435456;

CREATE TABLE entries (
    id           INTEGER PRIMARY KEY,
    source_word  TEXT    NOT NULL,
    source_lang  TEXT    NOT NULL,
    target_word  TEXT    NOT NULL,
    target_lang  TEXT    NOT NULL,
    pos          TEXT,
    category     TEXT    NOT NULL,
    definition   TEXT,
    sense_order  INTEGER NOT NULL DEFAULT 0
);

CREATE VIRTUAL TABLE entries_fts USING fts4(
    source_word,
    content='entries',
    tokenize=unicode61 "remove_diacritics=2"
);
"""

FINALIZE = """
CREATE INDEX idx_entries_source ON entries(source_word, source_lang);
CREATE INDEX idx_entries_target ON entries(target_word, target_lang);

INSERT INTO entries_fts(rowid, source_word) SELECT id, source_word FROM entries;
INSERT INTO entries_fts(entries_fts) VALUES('optimize');

PRAGMA journal_mode = DELETE;
PRAGMA synchronous = NORMAL;
"""


def build(rows: Iterable[Row], out_path: Path, *, batch_size: int = 10000) -> int:
    if out_path.exists():
        out_path.unlink()
    out_path.parent.mkdir(parents=True, exist_ok=True)

    conn = sqlite3.connect(out_path)
    try:
        conn.executescript(SCHEMA)

        insert = (
            "INSERT INTO entries "
            "(source_word, source_lang, target_word, target_lang, pos, category, definition, sense_order) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        )

        seen: set[tuple[str, str, str, str]] = set()
        batch: list[tuple] = []
        total = 0

        with tqdm(desc="insert", unit="rows", unit_scale=True) as bar:
            for row in rows:
                key = (row.source_word, row.source_lang, row.target_word, row.target_lang)
                if key in seen:
                    continue
                seen.add(key)
                batch.append((
                    row.source_word, row.source_lang,
                    row.target_word, row.target_lang,
                    row.pos, row.category, row.definition, row.sense_order,
                ))
                if len(batch) >= batch_size:
                    conn.executemany(insert, batch)
                    bar.update(len(batch))
                    total += len(batch)
                    batch.clear()
            if batch:
                conn.executemany(insert, batch)
                bar.update(len(batch))
                total += len(batch)

        conn.commit()
        conn.executescript(FINALIZE)
        conn.commit()
        conn.execute("VACUUM")
        conn.commit()
        return total
    finally:
        conn.close()


def smoke_test(db_path: Path, queries: list[str]) -> None:
    conn = sqlite3.connect(db_path)
    try:
        for q in queries:
            cur = conn.execute(
                "SELECT e.source_word, e.target_word, e.source_lang, e.category, e.pos "
                "FROM entries_fts f JOIN entries e ON e.id = f.rowid "
                "WHERE entries_fts MATCH ? "
                "ORDER BY e.source_lang, e.category, e.sense_order "
                "LIMIT 10",
                (f"{q}*",),
            )
            rows = cur.fetchall()
            print(f"\n=== {q!r} ({len(rows)} hits) ===")
            for r in rows:
                src, tgt, lang, cat, pos = r
                print(f"  [{lang}] {src} → {tgt}  ({cat}, {pos or '-'})")
    finally:
        conn.close()
