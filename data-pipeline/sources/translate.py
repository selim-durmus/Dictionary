"""
OPUS-MT (Helsinki-NLP / Tatoeba) machine translation via CTranslate2.

Used at *build time only* to fill the en→en gloss gap with Turkish translations; the
shipped app stays fully offline. The model is CC-BY-4.0 — see LICENSES.md at the repo
root for the required attribution.

Heavy deps (``ctranslate2``, ``transformers``, ``sentencepiece``, and ``torch`` for the
one-time conversion) are imported lazily so the rest of the pipeline runs without them.
"""
from __future__ import annotations

import subprocess
from pathlib import Path

# Forward (gap fill) and reverse (round-trip confidence check) models.
EN_TR_MODEL = "Helsinki-NLP/opus-mt-tc-big-en-tr"
TR_EN_MODEL = "Helsinki-NLP/opus-mt-tc-big-tr-en"


def ensure_ct2_model(hf_model: str, out_dir: Path, *, quantization: str = "int8") -> Path:
    """Convert a Hugging Face Marian model to CTranslate2 ``quantization`` format, once.

    Idempotent: if ``out_dir/model.bin`` already exists we assume the conversion is done.
    The converter downloads the HF weights on first run and needs ``torch`` installed.
    """
    if (out_dir / "model.bin").exists():
        return out_dir
    out_dir.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        "ct2-transformers-converter",
        "--model", hf_model,
        "--output_dir", str(out_dir),
        "--quantization", quantization,
        "--force",
    ]
    print(f"==> Converting {hf_model} → {out_dir} ({quantization})")
    subprocess.run(cmd, check=True)
    return out_dir


class OpusTranslator:
    """Thin batched wrapper around a CTranslate2 Marian translator + its HF tokenizer."""

    def __init__(
        self,
        ct2_dir: Path,
        hf_model: str,
        *,
        device: str = "cpu",
        compute_type: str = "int8",
        beam_size: int = 1,
        inter_threads: int = 4,
        intra_threads: int = 2,
    ) -> None:
        import ctranslate2
        import transformers

        self._beam = beam_size
        self._tokenizer = transformers.AutoTokenizer.from_pretrained(hf_model)
        # inter_threads runs several batches in parallel — the big CPU lever on short inputs
        # (the default of 1 leaves most cores idle). intra_threads parallelizes within a batch.
        self._translator = ctranslate2.Translator(
            str(ct2_dir), device=device, compute_type=compute_type,
            inter_threads=inter_threads, intra_threads=intra_threads,
        )

    def translate(self, texts: list[str], *, max_batch_size: int = 64) -> list[str]:
        """Translate a list of strings, preserving order. Internally length-sorted by CT2."""
        if not texts:
            return []
        token_lists = [
            self._tokenizer.convert_ids_to_tokens(self._tokenizer.encode(t)) for t in texts
        ]
        results = self._translator.translate_batch(
            token_lists,
            beam_size=self._beam,
            max_batch_size=max_batch_size,
            batch_type="examples",
        )
        out: list[str] = []
        for res in results:
            hypothesis = res.hypotheses[0]
            ids = self._tokenizer.convert_tokens_to_ids(hypothesis)
            out.append(self._tokenizer.decode(ids, skip_special_tokens=True).strip())
        return out
