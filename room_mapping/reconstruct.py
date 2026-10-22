#!/usr/bin/env python3
"""
reconstruct.py
Builds a 3D model of a room from a RealSense recording (a capture.py folder, a .db3 from
realsense-viewer 2.58+, or a .bag), using Open3D's dense RGB-D SLAM (frame-to-model tracking + TSDF fusion).

Usage:
    python reconstruct.py 1                                   # recording number
    python reconstruct.py recordings/001_20260923_095336      # or a path
    python reconstruct.py rec.db3
    python reconstruct.py rec.db3 --voxel 0.01 --depth-max 4.0 --out output/

    python reconstruct.py 1 --loop-closure                    # also correct drift (slower, see loop_closure.py)

Writes <out>/<name>_cloud.ply, <name>_mesh.ply and <name>_poses.npy (camera trajectory), plus _frames.npy (frame number
of each pose), _track.npy (per-frame tracking state) and, with an IMU, _up.npy (measured up direction).
With --loop-closure, also writes <name>_lc_cloud.ply, <name>_lc_mesh.ply and <name>_lc_poses.npy.
"""
import argparse
import json
import time
from pathlib import Path

import cv2
import numpy as np
import open3d as o3d
import open3d.core as o3c

import loop_closure
from rgbd_io import open_recording, read_imu, resolve_recording

RELOCK_FRAMES = 15   # after this many untrackable frames in a row (0.5 s) the pose is stale: start a new segment
MIN_SEGMENT = 30     # segments shorter than this (1 s) are too small to place reliably, so they're dropped
START_USABLE = 0.3   # a new segment starts on a frame with at least this share of depth in tracking range
TRACK_STATES = ("tracked", "bridged", "new segment after bridge", "lost")   # codes 0-3 in <name>_track.npy
BRIDGE_DEPTH_MAX = 5.0   # bridging odometry may use far depth too: colour keeps it from sliding
BRIDGE_METHOD = "Hybrid"
IMU_MAX_DISAGREE = 2.0   # deg per frame: a bridge step turning differently from the gyro takes the gyro's rotation
IMU_WINDOW_MS = 150      # accelerometer samples within this of a frame are averaged for its gravity direction
MAX_BRIDGE = 90      # bridge at most this many frames (3 s) with frame-to-frame odometry before giving up on the pose
BRIDGE_MIN_FITNESS = 0.05  # frame-to-frame odometry matching less than this share of pixels is not trusted (008 dips to 0.095)


def so3_exp(w):
    """Rotation matrix for rotation vector w (rad)."""
    th = np.linalg.norm(w)
    if th < 1e-12:
        return np.identity(3)
    k = w / th
    Kx = np.array([[0, -k[2], k[1]], [k[2], 0, -k[0]], [-k[1], k[0], 0]])
    return np.identity(3) + np.sin(th) * Kx + (1 - np.cos(th)) * Kx @ Kx


def gyro_rotation(gyro, t0, t1):
    """Camera rotation from time t0 to t1 (ms) from gyro rows (t_ms, wx, wy, wz) in camera axes: the orientation at
    t1 expressed in the camera frame at t0 (the same convention as odometry's relative pose)."""
    t = gyro[:, 0]
    a, b = np.searchsorted(t, t0, side="right"), np.searchsorted(t, t1, side="left")
    edges = np.concatenate([[t0], t[a:b], [t1]])
    rates = gyro[np.minimum(np.arange(a, b + 1), len(t) - 1), 1:]  # each sub-interval uses the sample ending it
    R = np.identity(3)
    for dt, w in zip(np.diff(edges) / 1000.0, rates):
        R = R @ so3_exp(w * dt)
    return R


def angle_deg(R):
    return np.degrees(np.arccos(np.clip((np.trace(R) - 1) / 2, -1, 1)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("recording", help="recording number (e.g. 1 or 001) or path")
    ap.add_argument("--out", default="output")
    ap.add_argument("--voxel", type=float, default=0.01, help="TSDF voxel size in metres")
    ap.add_argument("--depth-min", type=float, default=0.3)
    ap.add_argument("--depth-max", type=float, default=3.0,
                    help="build the model from depth up to this far (m); noise grows with distance²")
    ap.add_argument("--track-depth-max", type=float, default=3.0,
                    help="track the camera with depth up to this far (m). Far depth is too noisy to track on: "
                         "5 m lost 661 frames of 008 vs 247 at 3 m")
    ap.add_argument("--block-count", type=int, default=80000)
    ap.add_argument("--min-fitness", type=float, default=0.4,
                    help="skip frames where less than this share of the usable (in-range) depth matches the model")
    ap.add_argument("--weak-usable", type=float, default=0.3,
                    help="frames with less than this share of pixels in tracking range are not tracked against the "
                         "model (008: with only a floor in range it slid ~1 m/s sideways) nor integrated; their pose "
                         "is bridged with frame-to-frame colour+depth odometry")
    ap.add_argument("--reacquire-fitness", type=float, default=0.6,
                    help="after a bridge, the model match must cover this share of the usable depth to be trusted")
    ap.add_argument("--no-bridge", action="store_true",
                    help="old behaviour: a tracking loss ends the segment (no frame-to-frame bridging)")
    ap.add_argument("--no-imu", action="store_true", help="ignore the recording's gyro/accelerometer (imu.csv)")
    ap.add_argument("--every", type=int, default=1, help="use every Nth frame")
    ap.add_argument("--method", choices=["point2plane", "hybrid"], default="point2plane",
                    help="tracking: depth geometry only, or depth + colour")
    ap.add_argument("--depth-diff", type=float, default=0.07, help="max point match distance for tracking (m)")
    ap.add_argument("--bilateral", type=int, default=0, help="bilateral depth smoothing kernel size (0 = off)")
    ap.add_argument("--loop-closure", action="store_true",
                    help="after tracking, correct drift with a fragment pose graph and rebuild the model")
    ap.add_argument("--fragment-size", type=int, default=30, help="frames per loop-closure fragment (smaller = more drift corrected, slower)")
    args = ap.parse_args()

    o3d.utility.random.seed(0)  # segment joining uses RANSAC: same recording + settings -> same model
    device = o3c.Device("CUDA:0" if o3c.cuda.is_available() else "CPU:0")
    args.recording = resolve_recording(args.recording)
    frames, K, (h, w), depth_scale = open_recording(args.recording)
    intrinsic = o3c.Tensor(K, o3c.float64)
    print(f"Device: {device}   depth {w}x{h}   depth_scale {depth_scale:.1f}   voxel {args.voxel} m")
    # IMU (if recorded): the gyro carries rotation across bridges (it doesn't care what the camera sees), and the
    # accelerometer's gravity keeps segments from being joined tilted
    imu = None if args.no_imu or not Path(args.recording).is_dir() else read_imu(args.recording)
    if imu is not None:
        gyro, accel, _ = imu
        stamps = np.array(json.load(open(Path(args.recording) / "meta.json"))["timestamps_ms"])
        print(f"IMU: {len(gyro)} gyro, {len(accel)} accel samples")
    else:
        gyro = accel = stamps = None

    def up_at(i):
        """Measured 'up' (opposite gravity) in camera axes at frame i, or None."""
        if accel is None or not len(accel):
            return None
        near = np.abs(accel[:, 0] - stamps[i]) <= IMU_WINDOW_MS
        if not near.any():
            return None
        a = accel[near, 1:].mean(axis=0)
        return a / np.linalg.norm(a)

    def mean_up(ids, poses):
        """Average measured 'up' over frames, in the frame the poses are given in, or None without an IMU."""
        ups = [P[:3, :3] @ u for f, P in zip(ids, poses) if (u := up_at(f)) is not None]
        if not ups:
            return None
        u = np.sum(ups, axis=0)
        return u / np.linalg.norm(u)

    method = {"point2plane": o3d.t.pipelines.odometry.Method.PointToPlane,
              "hybrid": o3d.t.pipelines.odometry.Method.Hybrid}[args.method]
    use_color = args.method == "hybrid"
    frame = o3d.t.pipelines.slam.Frame(h, w, intrinsic, device)
    raycast = o3d.t.pipelines.slam.Frame(h, w, intrinsic, device)

    # Tracking runs in segments: after a real loss the stored pose is stale, and tracking from it either never
    # recovers or locks on in the wrong place. So a loss that can't be bridged ends the segment, a new one starts
    # from scratch, and place_segments() joins them afterwards by registering their geometry.
    #
    # Bridging (008, frames ~970-1050): turning toward far space leaves too little depth in range to track against
    # the model, and when enough comes back the camera is looking at a part of the room the model doesn't have yet,
    # so it can't re-acquire either. Frame-to-frame odometry with colour and far depth carries the pose across. If
    # the model still doesn't match when depth returns, the bridge has drifted or the view is new ground: a new
    # segment starts there, with the bridged pose as its prior, and place_segments() registers it against the
    # geometry so far (on 009 a 37-frame window bridge drifted so far that everything after it was misplaced).
    segments = []          # each: {"ids": frame numbers, "poses": camera poses in the segment's own frame, "cloud"}
    model, seg, miss, bridging, prev, prev_i = None, None, 0, 0, None, None
    gyro_only = 0
    used, lost, bridged, reseeds, t0 = 0, 0, 0, 0, time.time()
    track_log = []         # per frame: (frame, state, usable share, matched share of usable); states in TRACK_STATES
    odo = o3d.t.pipelines.odometry
    criteria = [odo.OdometryConvergenceCriteria(20), odo.OdometryConvergenceCriteria(10),
                odo.OdometryConvergenceCriteria(5)]

    pending = None         # (bridged pose in the last kept segment's frame, frame number) when a bridge broke

    def close_segment():
        """Ends the current segment. Returns True if it was kept (long enough to place)."""
        nonlocal model, seg, lost, bridging, pending
        kept = bool(seg) and len(seg["ids"]) >= MIN_SEGMENT
        if kept:
            pcd = model.extract_pointcloud().cpu()
            loop_closure.fix_colors(pcd.point)
            cloud = pcd.to_legacy().voxel_down_sample(loop_closure.REG_VOXEL)
            # join segments on the accurate near geometry only: with --depth-max 5 the far, noisy surfaces made
            # 008's segments fail to match. Keep points within tracking range of some camera position.
            pts = np.asarray(cloud.points)
            cams = np.array(seg["poses"])[::10, :3, 3]
            near = np.zeros(len(pts), bool)
            for c in cams:
                near |= np.einsum("ij,ij->i", pts - c, pts - c) <= args.track_depth_max ** 2
            seg["cloud"] = cloud.select_by_index(np.flatnonzero(near))
            segments.append(seg)
        elif seg:
            lost += len(seg["ids"])  # too short to place reliably
            if seg["prior"] is not None:  # keep the guess going through it, relative to the last kept segment
                pending = (seg["prior"] @ T.cpu().numpy(), seg["prior_frame"])
        model, seg, bridging = None, None, 0
        return kept


    def new_segment(i, prior=None):
        """prior: (pose, frame) guess for this segment's start in the previous segment's frame, from bridging."""
        nonlocal model, seg, T, pending
        if prior is None and pending is not None:
            prior = pending      # a bridge broke before this segment could start: its last pose is still a guess
        pending = None
        T = o3c.Tensor(np.identity(4))
        model = o3d.t.pipelines.slam.Model(args.voxel, 16, args.block_count, T, device)
        seg = {"ids": [], "poses": [], "prior": None if prior is None else prior[0],
               "prior_frame": None if prior is None else prior[1], "prior_gap": 0 if prior is None else i - prior[1]}

    def bridge_step(cur, i):
        """Camera motion from the previous frame to this one, or None if unreliable: colour + depth odometry, and
        with an IMU the gyro's rotation (as the starting guess, as the answer when the odometry disagrees, and on
        its own, with no translation, when the odometry fails)."""
        nonlocal gyro_only
        init = np.identity(4)
        R_g = gyro_rotation(gyro, stamps[prev_i], stamps[i]) if gyro is not None and len(gyro) else None
        if R_g is not None:
            init[:3, :3] = R_g
        try:
            res = odo.rgbd_odometry_multi_scale(cur, prev, intrinsic, o3c.Tensor(init), depth_scale,
                                                BRIDGE_DEPTH_MAX, criteria, getattr(odo.Method, BRIDGE_METHOD))
            step = res.transformation.cpu().numpy() if res.fitness >= BRIDGE_MIN_FITNESS else None
        except RuntimeError:
            step = None
        if R_g is None:
            return None if step is None else o3c.Tensor(step)
        if step is None:
            step = np.identity(4)
            gyro_only += 1
        if angle_deg(step[:3, :3].T @ R_g) > IMU_MAX_DISAGREE:
            step[:3, :3] = R_g
        return o3c.Tensor(step)

    for i, (depth, color) in enumerate(frames):
        if i % args.every:
            continue
        if args.bilateral:
            smoothed = cv2.bilateralFilter(depth.astype(np.float32), args.bilateral, 30.0, 5.0)
            depth = np.where(depth > 0, smoothed, 0).astype(np.uint16)  # keep no-data pixels as no-data
        depth_img = o3d.t.geometry.Image(o3c.Tensor(depth)).to(device)
        color_img = o3d.t.geometry.Image(o3c.Tensor(color)).to(device)
        cur = None if args.no_bridge else o3d.t.geometry.RGBDImage(color_img.to(o3c.float32, scale=1 / 255), depth_img)
        if use_color:  # hybrid tracking needs float colour, and integration then needs float depth too
            depth_img, color_img = depth_img.to(o3c.float32), color_img.to(o3c.float32, scale=1 / 255)
        frame.set_data_from_image("depth", depth_img)
        frame.set_data_from_image("color", color_img)

        # Open3D's fitness is matched pixels / all pixels, so it can't exceed the in-range share: looking into
        # a far room (008: ~20% in range) it fell under an absolute 0.1 while tracking was still fine, and after
        # that a wrong match at 0.10 (11% of a 96%-in-range frame) "re-locked" and tilted half the room.
        # Good tracking matches 0.55-1.0 of the usable pixels (median 0.94), so judge it relative to that.
        usable = np.count_nonzero((depth >= args.depth_min * depth_scale) &
                                  (depth <= args.track_depth_max * depth_scale)) / depth.size
        rel = np.nan
        if seg is None:
            if usable < START_USABLE:
                lost += 1
                prev, prev_i = cur, i
                track_log.append((i, 3, usable, rel))
                continue
            new_segment(i)
        else:
            ok = usable >= args.weak_usable
            if ok:
                if bridging:  # the raycast is from the last integrated pose: redo it from the bridged one
                    model.update_frame_pose(len(seg["ids"]), T)
                    model.synthesize_model_frame(raycast, depth_scale, args.depth_min, args.track_depth_max, 8.0,
                                                 use_color)
                try:
                    res = model.track_frame_to_model(frame, raycast, depth_scale, args.track_depth_max,
                                                     args.depth_diff, method)
                    rel = res.fitness / usable
                    ok = rel >= (args.reacquire_fitness if bridging else args.min_fitness)
                except RuntimeError:
                    ok = False
            if ok:
                T = T @ res.transformation
            else:
                step = bridge_step(cur, i) if cur is not None and prev is not None else None
                if step is not None and bridging < MAX_BRIDGE:
                    T = T @ step
                    prev, prev_i = cur, i
                    if bridging and usable >= START_USABLE:
                        # enough depth again but the model doesn't match it: new segment, bridged pose as prior
                        reseeds += 1
                        track_log.append((i, 2, usable, rel))
                        prior = (T.cpu().numpy(), i)
                        new_segment(i, prior if close_segment() else None)
                    else:
                        bridging += 1
                        bridged += 1
                        track_log.append((i, 1, usable, rel))
                        continue
                else:
                    lost += 1
                    miss += 1
                    prev, prev_i = cur, i
                    track_log.append((i, 3, usable, rel))
                    if args.no_bridge and miss < RELOCK_FRAMES:
                        continue
                    was_bridging = bridging
                    if close_segment() and was_bridging:
                        pending = (T.cpu().numpy(), i)
                    continue
        if not track_log or track_log[-1][0] != i:
            track_log.append((i, 0, usable, rel))
        miss, bridging, prev, prev_i = 0, 0, cur, i

        model.update_frame_pose(len(seg["ids"]), T)
        model.integrate(frame, depth_scale, args.depth_max)
        model.synthesize_model_frame(raycast, depth_scale, args.depth_min, args.track_depth_max, 8.0, use_color)
        seg["poses"].append(T.cpu().numpy())
        seg["ids"].append(i)
        used += 1
        if used % 100 == 0:
            print(f"  frame {i:5d}  integrated {used}  bridged {bridged}  lost {lost}  segments {len(segments) + 1}  "
                  f"{used / (time.time() - t0):.1f} fps")
    only_model = model if not segments else None  # one unbroken segment: reuse its model, no rebuild needed
    close_segment()
    if not segments:
        raise SystemExit("Tracking never held for a full second: nothing to build.")

    if len(segments) > 1:
        print(f"Tracking was lost {len(segments) - 1}x: joining {len(segments)} segments "
              f"(frames {', '.join(f"{s['ids'][0]}-{s['ids'][-1]}" for s in segments)})")
        gaps = [0] + [b["ids"][0] - a["ids"][-1] - 1 for a, b in zip(segments, segments[1:])]
        world, results = loop_closure.place_segments([s["cloud"] for s in segments],
                                                     [s["poses"][-1] for s in segments], gaps,
                                                     [s.get("prior") for s in segments],
                                                     [s.get("prior_gap", 0) for s in segments],
                                                     [mean_up(s["ids"], s["poses"]) for s in segments])
        for k, (Wk, (f, note)) in enumerate(zip(world, results)):
            if k:
                print(f"  segment {k + 1} (after {gaps[k]} lost frames): "
                      f"{f'placed, {note}, overlap {f:.2f}' if Wk is not None else 'NOT placed, left out: ' + note}")
        for s, Wk in zip(segments, world):
            s["world"] = Wk
        dropped = [s for s in segments if s["world"] is None]
        lost += sum(len(s["ids"]) for s in dropped)
        segments = [s for s in segments if s["world"] is not None]
    else:
        segments[0]["world"] = np.identity(4)
    frame_ids = [i for s in segments for i in s["ids"]]
    poses = np.array([s["world"] @ p for s in segments for p in s["poses"]])
    order = np.argsort(frame_ids)
    frame_ids, poses = [frame_ids[k] for k in order], poses[order]

    print(f"Integrated {len(frame_ids)} frames, bridged {bridged} (pose only), skipped {lost} (tracking lost), "
          f"re-anchored after a bridge {reseeds}x, in {time.time() - t0:.0f} s")
    print_path_stats(poses)
    if gyro is not None and len(gyro):
        # sanity check of the IMU's axes and clock against tracking, on consecutive tracked frames
        diffs, turns = [], []
        for k in range(1, len(frame_ids)):
            if frame_ids[k] == frame_ids[k - 1] + 1:
                R_t = poses[k - 1][:3, :3].T @ poses[k][:3, :3]
                diffs.append(angle_deg(R_t.T @ gyro_rotation(gyro, stamps[frame_ids[k - 1]], stamps[frame_ids[k]])))
                turns.append(angle_deg(R_t))
        print(f"IMU check: gyro vs tracking differ by {np.median(diffs):.2f} deg per frame (median; tracking turns "
              f"{np.median(turns):.2f}){'  <- check the IMU axes/clock' if np.median(diffs) > 0.5 * np.median(turns) else ''}"
              f"; bridged on gyro alone {gyro_only}x")

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    name = Path(args.recording).stem
    if only_model is not None:
        pcd = only_model.extract_pointcloud().to(o3c.Device("CPU:0"))
        mesh = only_model.extract_trianglemesh().to(o3c.Device("CPU:0"))
        del only_model
    else:
        pcd, mesh = loop_closure.integrate_all(args.recording, frame_ids, poses, K, depth_scale, args.depth_max,
                                               args.voxel, args.block_count, device)
    write_cloud(out / f"{name}_cloud.ply", pcd)
    o3d.t.io.write_triangle_mesh(str(out / f"{name}_mesh.ply"), mesh)
    np.save(out / f"{name}_poses.npy", poses)
    np.save(out / f"{name}_track.npy", np.array(track_log, dtype=float))
    np.save(out / f"{name}_frames.npy", np.array(frame_ids))  # frame number of each pose in _poses / _lc_poses
    up = mean_up(frame_ids, poses)
    if up is not None:
        np.save(out / f"{name}_up.npy", up)  # measured 'up' in the model's frame (for levelling)
    print(f"Points: {pcd.point.positions.shape[0]:,}   Mesh triangles: {mesh.triangle.indices.shape[0]:,}")
    print(f"Wrote {out}/{name}_cloud.ply, {name}_mesh.ply, {name}_poses.npy")

    if args.loop_closure:
        del model  # free GPU memory before rebuilding
        t1 = time.time()
        print(f"\nLoop closure: fragments of {args.fragment_size} frames...")
        lc_poses, st = loop_closure.optimize_poses(args.recording, frame_ids, poses, K, depth_scale, args.track_depth_max,
                                                   args.voxel, args.fragment_size, device)
        print(f"  {st['fragments']} fragments, {st['pairs_checked']} pairs registered, "
              f"{st['loops_found']} loop closures found, {st['loops_kept']} kept after optimisation")
        print(f"  correction at end of path: {100 * st['end_shift_m']:.1f} cm, {st['end_shift_deg']:.2f} deg")
        print_path_stats(lc_poses)
        pcd, mesh = loop_closure.integrate_all(args.recording, frame_ids, lc_poses, K, depth_scale, args.depth_max,
                                               args.voxel, args.block_count, device)
        write_cloud(out / f"{name}_lc_cloud.ply", pcd)
        o3d.t.io.write_triangle_mesh(str(out / f"{name}_lc_mesh.ply"), mesh)
        np.save(out / f"{name}_lc_poses.npy", lc_poses)
        print(f"  Points: {pcd.point.positions.shape[0]:,}   Mesh triangles: {mesh.triangle.indices.shape[0]:,}")
        print(f"Wrote {out}/{name}_lc_cloud.ply, {name}_lc_mesh.ply, {name}_lc_poses.npy "
              f"(loop closure took {time.time() - t1:.0f} s)")


def write_cloud(path, pcd):
    """Write a point cloud with uint8 colours. Float colours are saved as 0-1 floats, which the legacy PLY reader
    (used by `open3d draw` / view.py) divides by 255 again, so the cloud shows up nearly black."""
    if "colors" in pcd.point:
        pcd = pcd.clone()
        pcd.point.colors = (pcd.point.colors.clip(0, 1) * 255).to(o3c.uint8)
    o3d.t.io.write_point_cloud(str(path), pcd)


def print_path_stats(poses):
    t = poses[:, :3, 3]
    travel = np.linalg.norm(np.diff(t, axis=0), axis=1).sum() if len(t) > 1 else 0
    reach = np.linalg.norm(t - t[0], axis=1).max() if len(t) else 0
    end = np.linalg.norm(t[-1] - t[0]) if len(t) else 0
    print(f"Camera path length: {travel:.2f} m (includes jitter)   max distance from start: {reach:.2f} m   "
          f"end vs start: {end:.2f} m")


if __name__ == "__main__":
    main()
