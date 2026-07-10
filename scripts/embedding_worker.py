"""Persistent ONNX sentence-embedding worker used by the Java client."""

from __future__ import annotations

import argparse
import base64
import struct
import sys
import traceback
from pathlib import Path

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer


def load_runtime(model_dir: Path):
    tokenizer = Tokenizer.from_file(str(model_dir / "tokenizer.json"))
    tokenizer.enable_truncation(max_length=256)
    session = ort.InferenceSession(
        str(model_dir / "model_quint8_avx2.onnx"),
        providers=["CPUExecutionProvider"],
    )
    return tokenizer, session


def embed(texts: list[str], tokenizer: Tokenizer, session: ort.InferenceSession) -> np.ndarray:
    encodings = tokenizer.encode_batch(texts, add_special_tokens=True)
    width = max(len(encoding.ids) for encoding in encodings)
    input_ids = np.zeros((len(encodings), width), dtype=np.int64)
    attention_mask = np.zeros_like(input_ids)
    token_type_ids = np.zeros_like(input_ids)

    for row, encoding in enumerate(encodings):
        length = len(encoding.ids)
        input_ids[row, :length] = encoding.ids
        attention_mask[row, :length] = encoding.attention_mask
        if encoding.type_ids:
            token_type_ids[row, :length] = encoding.type_ids

    available = {item.name for item in session.get_inputs()}
    inputs = {"input_ids": input_ids, "attention_mask": attention_mask}
    if "token_type_ids" in available:
        inputs["token_type_ids"] = token_type_ids
    inputs = {name: value for name, value in inputs.items() if name in available}
    output = session.run(None, inputs)[0]

    if output.ndim == 3:
        mask = attention_mask[..., None].astype(np.float32)
        vectors = (output * mask).sum(axis=1) / np.maximum(mask.sum(axis=1), 1e-9)
    elif output.ndim == 2:
        vectors = output
    else:
        raise RuntimeError(f"Unsupported model output shape: {output.shape}")

    vectors = vectors.astype(np.float32)
    vectors /= np.maximum(np.linalg.norm(vectors, axis=1, keepdims=True), 1e-12)
    return vectors


def encoded_error(error: BaseException) -> str:
    message = "".join(traceback.format_exception_only(type(error), error)).strip()
    return base64.b64encode(message.encode("utf-8")).decode("ascii")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, required=True)
    args = parser.parse_args()

    try:
        tokenizer, session = load_runtime(args.model_dir)
        probe = embed(["semantic model ready"], tokenizer, session)
    except BaseException as error:
        print(f"ERROR\t{encoded_error(error)}", flush=True)
        return 1

    print(f"READY\t{probe.shape[1]}", flush=True)
    for raw_line in sys.stdin:
        try:
            fields = raw_line.rstrip("\r\n").split("\t")
            if len(fields) < 3 or fields[0] != "EMBED":
                raise ValueError("Invalid embedding request")
            expected = int(fields[1])
            texts = [base64.b64decode(value).decode("utf-8") for value in fields[2:]]
            if len(texts) != expected:
                raise ValueError("Embedding request count mismatch")
            vectors = embed(texts, tokenizer, session)
            payloads = [
                base64.b64encode(struct.pack(f"<{len(vector)}f", *vector)).decode("ascii")
                for vector in vectors
            ]
            print("VECTORS\t" + str(vectors.shape[1]) + "\t" + "\t".join(payloads), flush=True)
        except BaseException as error:
            print(f"ERROR\t{encoded_error(error)}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
