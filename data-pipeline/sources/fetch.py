from __future__ import annotations

import ssl
import urllib.request
from pathlib import Path

from tqdm import tqdm


KAIKKI_TURKISH_URL = "https://kaikki.org/dictionary/Turkish/kaikki.org-dictionary-Turkish.jsonl"
KAIKKI_ENGLISH_URL = "https://kaikki.org/dictionary/English/kaikki.org-dictionary-English.jsonl"


def _context(insecure: bool) -> ssl.SSLContext | None:
    if not insecure:
        try:
            import certifi
            return ssl.create_default_context(cafile=certifi.where())
        except ImportError:
            return None
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


def download(url: str, dest: Path, *, force: bool = False, insecure: bool = False) -> Path:
    if dest.exists() and not force:
        return dest

    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")

    req = urllib.request.Request(url, headers={"User-Agent": "translate-pipeline/0.1"})
    with urllib.request.urlopen(req, context=_context(insecure)) as resp:
        total = int(resp.headers.get("Content-Length", 0)) or None
        with open(tmp, "wb") as f, tqdm(
            total=total, unit="B", unit_scale=True, unit_divisor=1024, desc=dest.name
        ) as bar:
            while True:
                chunk = resp.read(1 << 16)
                if not chunk:
                    break
                f.write(chunk)
                bar.update(len(chunk))

    tmp.replace(dest)
    return dest


def fetch_turkish(cache_dir: Path, *, force: bool = False, insecure: bool = False) -> Path:
    return download(KAIKKI_TURKISH_URL, cache_dir / "kaikki-turkish.jsonl",
                    force=force, insecure=insecure)


def fetch_english(cache_dir: Path, *, force: bool = False, insecure: bool = False) -> Path:
    return download(KAIKKI_ENGLISH_URL, cache_dir / "kaikki-english.jsonl",
                    force=force, insecure=insecure)
