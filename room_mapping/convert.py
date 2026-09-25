#!/usr/bin/env python3
"""
convert.py
Converts a realsense-viewer recording (.db3 / .bag) into a compressed capture.py-style folder
(depth: lossless PNG, colour: JPEG aligned to depth). The original file is not touched.

Usage:
    python convert.py recordings/20260923_095336.db3     # -> next free number, e.g. recordings/004/
"""
import sys
import time
from pathlib import Path

from rgbd_io import FrameWriter, next_recording_folder, read_rs_file


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    src = Path(sys.argv[1])
    dst = next_recording_folder(src.parent)

    frames, K, size_hw, depth_scale = read_rs_file(src)
    writer = FrameWriter(dst, K, size_hw, depth_scale, 30)
    for i, (depth, color) in enumerate(frames):
        writer.add(depth, color, i * 1000 / 30)
        while writer.pending() > 200:  # reading is faster than writing; don't overflow the queue
            time.sleep(0.01)
    writer.close()
    print(f"Wrote {writer.count} frames to {dst} (dropped {writer.meta['dropped']})")


if __name__ == "__main__":
    main()
