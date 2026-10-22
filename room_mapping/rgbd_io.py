"""
rgbd_io.py
Shared reading/writing of RGB-D recordings.

Recording folder layout (Open3D's standard RGB-D dataset format):
    <name>/
        color/000000.jpg ...   colour, aligned to the depth camera (JPEG)
        depth/000000.png ...   16-bit depth in raw units (lossless PNG)
        intrinsic.json         Open3D PinholeCameraIntrinsic (depth camera)
        meta.json              depth_scale, fps, frame timestamps, dropped-frame count (+ "imu" info if recorded)
        imu.csv                optional: t_ms,sensor,x,y,z  (sensor g = gyro rad/s, a = accel m/s^2, in the IMU's
                               axes; meta["imu"]["to_depth"] rotates them into the depth camera's; same clock as
                               the frame timestamps)
"""
import json
from datetime import datetime
import queue
import threading
from pathlib import Path

import cv2
import numpy as np

JPEG_QUALITY = 90
PNG_COMPRESSION = 1  # 0-9; 1 is fast and still ~3x smaller than raw


def next_recording_folder(root="recordings"):
    """Returns root/NNN_YYYYMMDD_HHMMSS, numbered one higher than the highest existing recording."""
    root = Path(root)
    nums = [int(p.name.split("_")[0]) for p in root.glob("*")
            if p.is_dir() and p.name.split("_")[0].isdigit()] if root.exists() else []
    return root / f"{max(nums, default=0) + 1:03d}_{datetime.now():%Y%m%d_%H%M%S}"


class FrameWriter:
    """Writes aligned depth/colour frames to a recording folder on a background thread."""

    def __init__(self, folder, K, size_hw, depth_scale, fps):
        self.folder = Path(folder)
        (self.folder / "color").mkdir(parents=True, exist_ok=True)
        (self.folder / "depth").mkdir(parents=True, exist_ok=True)
        h, w = size_hw
        with open(self.folder / "intrinsic.json", "w") as f:
            json.dump({"width": w, "height": h,
                       "intrinsic_matrix": [K[0, 0], 0, 0, 0, K[1, 1], 0, K[0, 2], K[1, 2], 1]}, f)
        self.meta = {"recorded_at": datetime.now().isoformat(timespec="seconds"),
                     "depth_scale": depth_scale, "fps": fps, "timestamps_ms": [], "dropped": 0}
        self.count = 0
        self._imu, self._imu_lock = [], threading.Lock()
        self._q = queue.Queue(maxsize=300)  # ~10 s of buffer at 30 fps
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def add(self, depth, color_rgb, timestamp_ms):
        try:
            self._q.put_nowait((self.count, depth, color_rgb))
        except queue.Full:
            self.meta["dropped"] += 1
            return
        self.meta["timestamps_ms"].append(timestamp_ms)
        self.count += 1

    def add_imu(self, timestamp_ms, sensor, xyz):
        """sensor: "g" (gyro) or "a" (accel). Safe to call from librealsense's callback thread."""
        with self._imu_lock:
            self._imu.append((timestamp_ms, sensor, xyz[0], xyz[1], xyz[2]))

    def _run(self):
        while True:
            item = self._q.get()
            if item is None:
                return
            i, depth, color = item
            cv2.imwrite(str(self.folder / "depth" / f"{i:06d}.png"), depth,
                        [cv2.IMWRITE_PNG_COMPRESSION, PNG_COMPRESSION])
            cv2.imwrite(str(self.folder / "color" / f"{i:06d}.jpg"),
                        cv2.cvtColor(color, cv2.COLOR_RGB2BGR),
                        [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY])

    def pending(self):
        return self._q.qsize()

    def close(self):
        self._q.put(None)
        self._thread.join()
        with self._imu_lock:
            imu = sorted(self._imu)
        if imu:
            with open(self.folder / "imu.csv", "w") as f:
                f.write("t_ms,sensor,x,y,z\n")
                f.writelines(f"{t:.3f},{k},{x:.6f},{y:.6f},{z:.6f}\n" for t, k, x, y, z in imu)
            self.meta.setdefault("imu", {})["samples"] = len(imu)
        with open(self.folder / "meta.json", "w") as f:
            json.dump(self.meta, f)


def read_imu(folder):
    """Returns (gyro, accel, R_imu_to_depth) for a recording folder, or None if it has no IMU data. gyro/accel are
    float arrays of rows (t_ms, x, y, z), in the depth camera's axes (x right, y down, z forward)."""
    folder = Path(folder)
    if not (folder / "imu.csv").exists():
        return None
    rows = np.genfromtxt(folder / "imu.csv", delimiter=",", skip_header=1, dtype=None, encoding=None)
    R = np.array(json.load(open(folder / "meta.json")).get("imu", {}).get("to_depth", {}).get("rotation", np.eye(3)))
    out = []
    for k in ("g", "a"):
        a = np.array([(r[0], r[2], r[3], r[4]) for r in rows if r[1] == k], dtype=float).reshape(-1, 4)
        a[:, 1:] = a[:, 1:] @ R.T
        out.append(a)
    return out[0], out[1], R


def read_folder(folder):
    """Returns (frame generator, K, (h, w), depth_scale) for a recording folder."""
    folder = Path(folder)
    intr = json.load(open(folder / "intrinsic.json"))
    m = intr["intrinsic_matrix"]
    K = np.array([[m[0], 0, m[6]], [0, m[4], m[7]], [0, 0, 1]])
    depth_scale = json.load(open(folder / "meta.json"))["depth_scale"]
    depths = sorted((folder / "depth").glob("*.png"))

    def gen():
        for d in depths:
            depth = cv2.imread(str(d), cv2.IMREAD_UNCHANGED)
            color = cv2.imread(str(folder / "color" / (d.stem + ".jpg")))
            yield depth, cv2.cvtColor(color, cv2.COLOR_BGR2RGB)

    return gen(), K, (intr["height"], intr["width"]), depth_scale


def read_rs_file(path):
    """Returns (frame generator, K, (h, w), depth_scale) for a .db3/.bag file, colour aligned to depth."""
    import pyrealsense2 as rs

    pipe, cfg = rs.pipeline(), rs.config()
    cfg.enable_device_from_file(str(path), repeat_playback=False)
    cfg.enable_stream(rs.stream.depth)
    cfg.enable_stream(rs.stream.color)
    profile = pipe.start(cfg)
    profile.get_device().as_playback().set_real_time(False)  # don't drop frames

    depth_scale = 1.0 / profile.get_device().first_depth_sensor().get_depth_scale()
    intr = profile.get_stream(rs.stream.depth).as_video_stream_profile().get_intrinsics()
    K = np.array([[intr.fx, 0, intr.ppx], [0, intr.fy, intr.ppy], [0, 0, 1]])
    align = rs.align(rs.stream.depth)

    def gen():
        last = None
        try:
            while True:
                ok, fs = pipe.try_wait_for_frames(1000)
                if not ok:
                    break
                fs = align.process(fs)
                d, c = fs.get_depth_frame(), fs.get_color_frame()
                if not d or not c or d.get_frame_number() == last:
                    continue
                last = d.get_frame_number()
                yield np.asanyarray(d.get_data()).copy(), np.asanyarray(c.get_data()).copy()
        finally:
            pipe.stop()

    return gen(), K, (intr.height, intr.width), depth_scale


def resolve_recording(arg, root="recordings"):
    """Accepts a path, or just a recording number ("1", "001") and finds root/001_<date>_<time>."""
    if Path(arg).exists():
        return Path(arg)
    if arg.isdigit():
        matches = sorted(Path(root).glob(f"{int(arg):03d}_*"))
        if matches:
            return matches[0]
    raise SystemExit(f"No recording found for '{arg}' (looked for a path, or {root}/{arg:0>3}_*)")


def open_recording(path):
    """Opens a recording folder, .db3 or .bag (or a recording number)."""
    path = resolve_recording(str(path))
    return read_folder(path) if path.is_dir() else read_rs_file(path)
