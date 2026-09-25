#!/usr/bin/env python3
"""
play.py
Plays back a recording (capture.py folder, .db3 or .bag) as video: colour | depth side by side.

Usage:
    python play.py 1                                  # recording number
    python play.py recordings/001_20260923_095336     # or a path
Keys: SPACE = pause/resume, . = step one frame while paused, Q/Esc = quit
"""
import sys

import cv2

from capture import preview_image
from rgbd_io import open_recording


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    frames, _, _, depth_scale = open_recording(sys.argv[1])
    paused = False
    for i, (depth, color) in enumerate(frames):
        status = f"frame {i}  ({i / 30:.1f} s)   SPACE = pause   Q = quit"
        cv2.imshow("D455 playback", preview_image(depth, color, depth_scale, status, False))
        while True:
            key = cv2.waitKey(0 if paused else 33) & 0xFF
            if key in (ord("q"), 27):
                return
            if key == ord(" "):
                paused = not paused
            if not paused or key == ord("."):
                break
    cv2.waitKey(0)


if __name__ == "__main__":
    main()
