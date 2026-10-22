#!/usr/bin/env python3
"""
capture.py
Live D455 preview with start/stop recording to a compressed RGB-D folder
(depth: lossless 16-bit PNG, colour: JPEG aligned to depth), numbered recordings/001_<date>_<time>, 002_..., ... See rgbd_io.py for the layout.

Usage:
    python capture.py                   # preview window: SPACE = start/stop recording, Q/Esc = quit
    python capture.py --seconds 20      # no window: record 20 s then exit (for testing / headless)
    python capture.py --no-imu          # don't record the D455's gyro/accelerometer (imu.csv)

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
    return pipe, K, 1.0 / sensor.get_depth_scale(), profile


def start_imu(profile, on_sample):
    """Starts the D455's motion sensor (gyro + accelerometer) with a callback on_sample(t_ms, "g"|"a", xyz).
    Returns (sensor, info for meta.json), or (None, None) if the camera has no IMU."""
    motion = next((s for s in profile.get_device().query_sensors() if s.is_motion_sensor()), None)
    if motion is None:
        return None, None
    profiles = [p for p in motion.get_stream_profiles() if p.format() == rs.format.motion_xyz32f]
    gyro = min((p for p in profiles if p.stream_type() == rs.stream.gyro), key=lambda p: p.fps())
    accel = min((p for p in profiles if p.stream_type() == rs.stream.accel), key=lambda p: p.fps())
    ext = gyro.get_extrinsics_to(profile.get_stream(rs.stream.depth))
    kinds = {rs.stream.gyro: "g", rs.stream.accel: "a"}

    def callback(f):
        m = f.as_motion_frame().get_motion_data()
        on_sample(f.get_timestamp(), kinds[f.get_profile().stream_type()], (m.x, m.y, m.z))

    motion.open([gyro, accel])
    motion.start(callback)
    info = {"gyro_hz": gyro.fps(), "accel_hz": accel.fps(),
            # librealsense stores rotation column-major; this is the row-major 3x3 taking IMU axes to depth axes
            "to_depth": {"rotation": np.array(ext.rotation).reshape(3, 3).T.tolist(),
                         "translation": list(ext.translation)}}
    return motion, info


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
    ap.add_argument("--no-imu", action="store_true", help="don't record the gyro/accelerometer")
    args = ap.parse_args()
    headless = args.seconds is not None

    pipe, K, depth_scale, profile = start_camera()
    align = rs.align(rs.stream.depth)
    print(f"Camera started: depth {DEPTH_W}x{DEPTH_H}, colour {COLOR_W}x{COLOR_H} @ {FPS} fps")
    for _ in range(WARMUP_FRAMES):
        pipe.wait_for_frames()

    writer, rec_start, last_fn, cam_dropped = None, 0.0, None, 0

    def on_imu(t_ms, kind, xyz):
        w = writer   # read once: the main thread may swap it
        if w is not None:
            w.add_imu(t_ms, kind, xyz)

    imu, imu_info = None, None
    if not args.no_imu:
        try:
            imu, imu_info = start_imu(profile, on_imu)
        except RuntimeError as e:  # never lose a recording over the IMU
            print(f"IMU: could not start ({e}); recording without it")
    print(f"IMU: gyro {imu_info['gyro_hz']} Hz, accel {imu_info['accel_hz']} Hz" if imu else "IMU: not recording")

    def start_recording():
        nonlocal writer, rec_start, last_fn, cam_dropped
        folder = next_recording_folder(args.out)
        w = FrameWriter(folder, K, (DEPTH_H, DEPTH_W), depth_scale, FPS)
        if imu_info:
            w.meta["imu"] = dict(imu_info)
        writer = w
        rec_start, last_fn, cam_dropped = time.time(), None, 0
        print(f"REC: Recording to {folder}")

    def stop_recording():
        nonlocal writer
        if writer.pending():
            print(f"  flushing {writer.pending()} frames to disk...")
        writer.meta["dropped"] += cam_dropped
        writer.close()
        dur = time.time() - rec_start
        mb = folder_size_mb(writer.folder)
        print(f"STOP: Stopped: {writer.count} frames, {dur:.1f} s, {mb:.0f} MB ({mb / max(dur, 1e-6):.1f} MB/s), "
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
        if imu:
            imu.stop()
            imu.close()
        pipe.stop()
        cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
