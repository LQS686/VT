#!/usr/bin/env python3
"""RapidOCR 截图识别：输出 UTF-8 TSV（score, x1,y1,x2,y2, text）。"""
import sys

from rapidocr_onnxruntime import RapidOCR


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: ocr.py <image>", file=sys.stderr)
        return 2
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    engine = RapidOCR()
    result, _ = engine(sys.argv[1])
    if not result:
        return 0
    for box, text, score in result:
        xs = [p[0] for p in box]
        ys = [p[1] for p in box]
        print(
            f"{float(score):.3f}\t"
            f"{int(min(xs))},{int(min(ys))},{int(max(xs))},{int(max(ys))}\t{text}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
