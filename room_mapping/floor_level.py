"""
floor_level.py
Removes slow height and tilt drift from a camera path using the floor as the reference. Used by
reconstruct.py --loop-closure after the pose graph (turn off with --no-floor-level).

Open3D's frame-to-model tracking creeps steadily (see CLAUDE.md), and loop closure doesn't pin the floor down: the
1 s fragments it compares share mostly walls, and the floor is usually seen 2 m+ away at a grazing angle. On 015 the
floor seen by each frame landed 22 cm lower at the end of the scan than at the start, smoothly with time
(correlation -0.93), not with viewing distance, so the model showed stacked floors.

Every frame that sees the floor measures the camera's real tilt and height above it, and a room has one flat floor.
So: measure the floor plane in each sampled frame (at its current pose), smooth the tilt and height errors over
time (drift is slow), interpolate to every frame, and correct the poses (tilt about the camera, then height). Frames
before the first / after the last floor sighting keep the nearest correction. Skipped when the floor is in under
20% of the sampled frames. Assumes one floor level: not for stairs or ramps.
"""
import cv2
import numpy as np
import open3d as o3d

SAMPLE_EVERY = 5        # frames
STRIDE = 4              # pixel subsampling of each depth image
HORIZONTAL = 0.95       # |normal . up| for floor-like surface (~18 deg)
FLOOR_BAND = (-0.3, 0.35)  # a frame's floor points must lie this far below/above the scan's floor level (m)
MIN_FLOOR_POINTS = 300
MIN_FLOOR_FRAMES = 10
MIN_FLOOR_SHARE = 0.2   # of sampled frames. Below this the corrections are mostly interpolated across long gaps and
                        # can make things worse: 014 (floor in 12%, the window passes looked up) dropped from 52% to
                        # 45% within 10 cm of 013; 010/011/013/015 (30-45%) all improved
SMOOTH_S = 2.0          # running-median window for the errors (s)


def _camera_points(depth, K, depth_scale, depth_min, depth_max):
    h, w = depth.shape
    v, u = np.mgrid[0:h:STRIDE, 0:w:STRIDE]
    z = depth[::STRIDE, ::STRIDE] / depth_scale
    ok = (z > depth_min) & (z < depth_max)
    return np.c_[(u[ok] - K[0, 2]) * z[ok] / K[0, 0], (v[ok] - K[1, 2]) * z[ok] / K[1, 1], z[ok]]


def _rotation_between(a, b):
    v, c = np.cross(a, b), float(a @ b)
    if np.linalg.norm(v) < 1e-12:
        return np.identity(3)
    V = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
    return np.identity(3) + V + V @ V / (1 + c)


def _smooth(t, values, window):
    """Running median over time, per column."""
    out = np.empty_like(values)
    for i, ti in enumerate(t):
        sel = np.abs(t - ti) <= window / 2
        out[i] = np.median(values[sel], axis=0)
    return out


def level_to_floor(recording_frames, frame_ids, poses, K, depth_scale, depth_min=0.3, depth_max=3.0, fps=30):
    """recording_frames: iterable of (depth, color) for every frame of the recording, in order.
    Returns (corrected poses, stats dict) or (poses unchanged, stats with "skipped") if the floor is seen too little."""
    poses = np.asarray(poses)
    slot = {f: i for i, f in enumerate(frame_ids)}
    wanted = set(frame_ids[::SAMPLE_EVERY])
    up = -np.mean(poses[:, :3, 1], axis=0)  # camera y points down: start from the average camera up
    up /= np.linalg.norm(up)

    # floor-like points of each sampled frame, in camera axes, plus a global height histogram to find the floor level
    samples = []
    for f, (depth, _) in enumerate(recording_frames):
        if f not in wanted:
            continue
        i = slot[f]
        pc = _camera_points(depth, K, depth_scale, depth_min, depth_max)
        if len(pc) < MIN_FLOOR_POINTS:
            continue
        q = o3d.geometry.PointCloud(o3d.utility.Vector3dVector(pc))
        q.estimate_normals(o3d.geometry.KDTreeSearchParamHybrid(radius=0.08, max_nn=30))
        nw = np.asarray(q.normals) @ poses[i][:3, :3].T
        horiz = np.abs(nw @ up) > HORIZONTAL
        if horiz.sum() >= MIN_FLOOR_POINTS:
            samples.append((i, pc[horiz]))
    if not samples:
        return poses, {"skipped": "no horizontal surfaces seen"}
    heights = np.concatenate([(p @ poses[i][:3, :3].T + poses[i][:3, 3]) @ up for i, p in samples])
    counts, edges = np.histogram(heights, bins=np.arange(heights.min(), heights.max() + 0.05, 0.05))
    floor = edges[np.flatnonzero(counts >= 0.2 * counts.max())[0]]  # lowest big horizontal layer

    # per sampled frame: its floor plane's normal and height in the world, at its current pose
    meas = []  # (pose index, tilt rotation vector, height error)
    for i, pc in samples:
        pw = pc @ poses[i][:3, :3].T + poses[i][:3, 3]
        h = pw @ up - floor
        sel = (h > FLOOR_BAND[0]) & (h < FLOOR_BAND[1])
        if sel.sum() < MIN_FLOOR_POINTS:
            continue
        p = pw[sel]
        c = p.mean(axis=0)
        n = np.linalg.svd(p - c, full_matrices=False)[2][2]
        n = n if n @ up > 0 else -n
        if n @ up < np.cos(np.radians(10)):  # not a level surface (a ramp, or a bad fit)
            continue
        R = _rotation_between(n, up)          # what levels this frame's floor
        rotvec = np.cross(n, up)
        rotvec = rotvec / max(np.linalg.norm(rotvec), 1e-12) * np.arccos(np.clip(n @ up, -1, 1))
        # height of this frame's floor after levelling it about the camera
        cam = poses[i][:3, 3]
        h_after = np.median(((p - cam) @ R.T + cam) @ up) - floor
        meas.append((i, rotvec, h_after))
    share = len(meas) / max(len(wanted), 1)
    if len(meas) < MIN_FLOOR_FRAMES or share < MIN_FLOOR_SHARE:
        return poses, {"skipped": f"skipped, floor seen in only {len(meas)} of {len(wanted)} sampled frames ({share:.0%}; "
                                  f"needs {MIN_FLOOR_SHARE:.0%})"}

    idx = np.array([m[0] for m in meas])
    t = np.array([frame_ids[i] for i in idx]) / fps
    rot = _smooth(t, np.array([m[1] for m in meas]), SMOOTH_S)
    hgt = _smooth(t, np.array([[m[2]] for m in meas]), SMOOTH_S)[:, 0]
    hgt -= np.median(hgt)  # keep the model where it was on average
    t_all = np.array(frame_ids) / fps
    rot_all = np.stack([np.interp(t_all, t, rot[:, k]) for k in range(3)], axis=1)
    hgt_all = np.interp(t_all, t, hgt)

    out = poses.copy()
    for i in range(len(poses)):
        R = o3d.geometry.get_rotation_matrix_from_axis_angle(rot_all[i])
        out[i][:3, :3] = R @ poses[i][:3, :3]          # tilt about the camera centre
        out[i][:3, 3] = poses[i][:3, 3] - hgt_all[i] * up
    tilt = np.degrees(np.linalg.norm(rot, axis=1))
    stats = {"floor_frames": len(meas), "height_range_m": float(np.ptp(hgt)), "tilt_max_deg": float(tilt.max()),
             "tilt_median_deg": float(np.median(tilt))}
    return out, stats
