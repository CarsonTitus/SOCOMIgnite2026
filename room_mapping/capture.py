#!/usr/bin/env python3
"""
capture.py
Live D455 preview with start/stop recording to a compressed RGB-D folder
(depth: lossless 16-bit PNG, colour: JPEG aligned to depth), numbered recordings/001_<date>_<time>, 002_..., ... See rgbd_io.py for the layout.

Usage:
    python capture.py                   # preview window: SPACE = start/stop recording, Q/Esc = quit
    python capture.py --seconds 20      # no window: record 20 s then exit (for testing / headless)

Then build the model with:
    python reconstruct.py <number>
"""
import argparse
import time
from pathlib import Path

import cv2
import numpy as np
import pyrealsense2 as rs

from rgbd_io import FrameWriter, next_recording_folder

DEPTH_W, DEPTH_H = 848, 480   # D455's best-quality depth mode
COLOR_W, COLOR_H = 640, 480   # reconstruction works at depth resolution, so more colour is wasted
FPS = 30
WARMUP_FRAMES = 30            # let auto-exposure settle
PREVIEW_RANGE_M = (0.3, 4.0)  # fixed colour scale so the preview doesn't "swim"


def start_camera():
    pipe, cfg = rs.pipeline(), rs.config()
    cfg.enable_stream(rs.stream.depth, DEPTH_W, DEPTH_H, rs.format.z16, FPS)
    cfg.enable_stream(rs.stream.color, COLOR_W, COLOR_H, rs.format.rgb8, FPS)
    profile = pipe.start(cfg)
    sensor = profile.get_device().first_depth_sensor()
    if sensor.supports(rs.option.emitter_enabled):
        sensor.set_option(rs.option.emitter_enabled, 1)  # IR dot pattern helps on blank walls
    intr = profile.get_stream(rs.stream.depth).as_video_stream_profile().get_intrinsics()
    K = np.array([[intr.fx, 0, intr.ppx], [0, intr.fy, intr.ppy], [0, 0, 1]])
    return pipe, K, 1.0 / sensor.get_depth_scale()


def preview_image(depth, color, depth_scale, status, recording):
    lo, hi = PREVIEW_RANGE_M
    d = np.clip((depth / depth_scale - lo) / (hi - lo), 0, 1)
    d = cv2.applyColorMap((d * 255).astype(np.uint8), cv2.COLORMAP_TURBO)
    d[depth == 0] = 0  # no-data pixels shown black
    img = np.hstack([cv2.cvtColor(color, cv2.COLOR_RGB2BGR), d])
    cv2.rectangle(img, (0, 0), (img.shape[1], 36), (0, 0, 0), -1)
    if recording:
        cv2.circle(img, (18, 18), 9, (0, 0, 255), -1)
    cv2.putText(img, status, (36 if recording else 10, 25), cv2.FONT_HERSHEY_SIMPLEX, 0.65,
                (255, 255, 255), 1, cv2.LINE_AA)
    return img


def folder_size_mb(folder):
    return sum(f.stat().st_size for f in Path(folder).rglob("*") if f.is_file()) / 1e6


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default="recordings")
    ap.add_argument("--seconds", type=float, help="record this long with no window, then exit")
    args = ap.parse_args()
    headless = args.seconds is not None

    pipe, K, depth_scale = start_camera()
    align = rs.align(rs.stream.depth)
    print(f"Camera started: depth {DEPTH_W}x{DEPTH_H}, colour {COLOR_W}x{COLOR_H} @ {FPS} fps")
    for _ in range(WARMUP_FRAMES):
        pipe.wait_for_frames()

    writer, rec_start, last_fn, cam_dropped = None, 0.0, None, 0

    def start_recording():
        nonlocal writer, rec_start, last_fn, cam_dropped
        folder = next_recording_folder(args.out)
        writer = FrameWriter(folder, K, (DEPTH_H, DEPTH_W), depth_scale, FPS)
        rec_start, last_fn, cam_dropped = time.time(), None, 0
        print(f"● Recording to {folder}")

    def stop_recording():
        nonlocal writer
        if writer.pending():
            print(f"  flushing {writer.pending()} frames to disk...")
        writer.meta["dropped"] += cam_dropped
        writer.close()
        dur = time.time() - rec_start
        mb = folder_size_mb(writer.folder)
        print(f"■ Stopped: {writer.count} frames, {dur:.1f} s, {mb:.0f} MB ({mb / max(dur, 1e-6):.1f} MB/s), "
              f"dropped {writer.meta['dropped']}")
        print(f"  Reconstruct with: .venv/bin/python reconstruct.py {int(writer.folder.name.split('_')[0])}")
        writer = None

    if headless:
        start_recording()
    else:
        cv2.namedWindow("D455 capture", cv2.WINDOW_AUTOSIZE)

    try:
        while True:
            fs = align.process(pipe.wait_for_frames())
            d, c = fs.get_depth_frame(), fs.get_color_frame()
            if not d or not c:
                continue
            depth, color = np.asanyarray(d.get_data()), np.asanyarray(c.get_data())

            if writer:
                fn = d.get_frame_number()
                if last_fn is not None and fn > last_fn + 1:
                    cam_dropped += fn - last_fn - 1
                last_fn = fn
                writer.add(depth.copy(), color.copy(), d.get_timestamp())

            if headless:
                if time.time() - rec_start >= args.seconds:
                    break
                continue

            if writer:
                el = time.time() - rec_start
                status = (f"REC {int(el // 60):02d}:{int(el % 60):02d}   {writer.count} frames   "
                          f"dropped {writer.meta['dropped'] + cam_dropped}   SPACE = stop")
            else:
                status = "READY   SPACE = start recording   Q = quit"
            cv2.imshow("D455 capture", preview_image(depth, color, depth_scale, status, writer is not None))

            key = cv2.waitKey(1) & 0xFF
            if key == ord(" "):
                stop_recording() if writer else start_recording()
            elif key in (ord("q"), 27) or cv2.getWindowProperty("D455 capture", cv2.WND_PROP_VISIBLE) < 1:
                break
    except KeyboardInterrupt:
        pass
    finally:
        if writer:
            stop_recording()
        pipe.stop()
        cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
