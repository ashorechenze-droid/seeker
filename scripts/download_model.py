"""Download the exact local embedding model files required by SimpleRAG."""

from __future__ import annotations

import argparse
import os
import shutil
from pathlib import Path

# Set the mirror before importing huggingface_hub so its global endpoint uses it.
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")

from huggingface_hub import hf_hub_download


REPOSITORY = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
FILES = (
    "tokenizer.json",
    "tokenizer_config.json",
    "special_tokens_map.json",
    "config.json",
    "onnx/model_quint8_avx2.onnx",
)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", type=Path, default=Path("models/multilingual-minilm"))
    args = parser.parse_args()
    target = args.target.resolve()
    target.mkdir(parents=True, exist_ok=True)
    cache = target.parent / ".download-cache"

    print(f"Mirror: {os.environ['HF_ENDPOINT']}")
    print(f"Downloading multilingual semantic model to {target}")
    try:
        for filename in FILES:
            downloaded = Path(hf_hub_download(repo_id=REPOSITORY, filename=filename, cache_dir=cache))
            destination = target / ("model_quint8_avx2.onnx" if filename.startswith("onnx/") else filename)
            shutil.copy2(downloaded, destination)
            print(f"  {destination.name}: {destination.stat().st_size / 1024 / 1024:.1f} MB")
    except BaseException:
        print(f"Download cache retained for resume: {cache}")
        raise
    shutil.rmtree(cache, ignore_errors=True)
    print("Semantic model is ready.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
