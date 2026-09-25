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

Writes <out>/<name>_cloud.ply, <name>_mesh.ply and <name>_poses.npy (camera trajectory).
"""
import argparse
import time
from pathlib import Path

import cv2
import numpy as np
import open3d as o3d
import open3d.core as o3c

from rgbd_io import open_recording, resolve_recording


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("recording", help="recording number (e.g. 1 or 001) or path")
    ap.add_argument("--out", default="output")
    ap.add_argument("--voxel", type=float, default=0.01, help="TSDF voxel size in metres")
    ap.add_argument("--depth-min", type=float, default=0.3)
    ap.add_argument("--depth-max", type=float, default=3.0, help="ignore depth beyond this (m); noise grows with distance²")
    ap.add_argument("--block-count", type=int, default=80000)
    ap.add_argument("--min-fitness", type=float, default=0.1,
                    help="skip frames whose tracking fitness is below this")
    ap.add_argument("--every", type=int, default=1, help="use every Nth frame")
    ap.add_argument("--method", choices=["point2plane", "hybrid"], default="point2plane",
                    help="tracking: depth geometry only, or depth + colour")
    ap.add_argument("--depth-diff", type=float, default=0.07, help="max point match distance for tracking (m)")
    ap.add_argument("--bilateral", type=int, default=0, help="bilateral depth smoothing kernel size (0 = off)")
    args = ap.parse_args()

    device = o3c.Device("CUDA:0" if o3c.cuda.is_available() else "CPU:0")
    args.recording = resolve_recording(args.recording)
    frames, K, (h, w), depth_scale = open_recording(args.recording)
    intrinsic = o3c.Tensor(K, o3c.float64)
    print(f"Device: {device}   depth {w}x{h}   depth_scale {depth_scale:.1f}   voxel {args.voxel} m")

    T = o3c.Tensor(np.identity(4))
    model = o3d.t.pipelines.slam.Model(args.voxel, 16, args.block_count, T, device)
    method = {"point2plane": o3d.t.pipelines.odometry.Method.PointToPlane,
              "hybrid": o3d.t.pipelines.odometry.Method.Hybrid}[args.method]
    use_color = args.method == "hybrid"
    frame = o3d.t.pipelines.slam.Frame(h, w, intrinsic, device)
    raycast = o3d.t.pipelines.slam.Frame(h, w, intrinsic, device)

    poses, used, lost, t0 = [], 0, 0, time.time()
    for i, (depth, color) in enumerate(frames):
        if i % args.every:
            continue
        if args.bilateral:
            smoothed = cv2.bilateralFilter(depth.astype(np.float32), args.bilateral, 30.0, 5.0)
            depth = np.where(depth > 0, smoothed, 0).astype(np.uint16)  # keep no-data pixels as no-data
        depth_img = o3d.t.geometry.Image(o3c.Tensor(depth)).to(device)
        color_img = o3d.t.geometry.Image(o3c.Tensor(color)).to(device)
        if use_color:  # hybrid tracking needs float colour, and integration then needs float depth too
            depth_img, color_img = depth_img.to(o3c.float32), color_img.to(o3c.float32, scale=1 / 255)
        frame.set_data_from_image("depth", depth_img)
        frame.set_data_from_image("color", color_img)

        if used > 0:
            try:
                res = model.track_frame_to_model(frame, raycast, depth_scale, args.depth_max,
                                                 args.depth_diff, method)
            except RuntimeError:
                lost += 1
                continue
            if res.fitness < args.min_fitness:
                lost += 1
                continue
            T = T @ res.transformation

        model.update_frame_pose(used, T)
        model.integrate(frame, depth_scale, args.depth_max)
        model.synthesize_model_frame(raycast, depth_scale, args.depth_min, args.depth_max, 8.0, use_color)
        poses.append(T.cpu().numpy())
        used += 1
        if used % 100 == 0:
            print(f"  frame {i:5d}  integrated {used}  lost {lost}  {used / (time.time() - t0):.1f} fps")

    print(f"Integrated {used} frames, skipped {lost} (tracking lost) in {time.time() - t0:.0f} s")
    poses = np.array(poses)
    travel = np.linalg.norm(np.diff(poses[:, :3, 3], axis=0), axis=1).sum() if len(poses) > 1 else 0
    reach = np.linalg.norm(poses[:, :3, 3] - poses[0, :3, 3], axis=1).max() if len(poses) else 0
    print(f"Camera path length: {travel:.2f} m (includes jitter)   max distance from start: {reach:.2f} m")

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    name = Path(args.recording).stem
    pcd = model.extract_pointcloud().to(o3c.Device("CPU:0"))
    mesh = model.extract_trianglemesh().to(o3c.Device("CPU:0"))
    o3d.t.io.write_point_cloud(str(out / f"{name}_cloud.ply"), pcd)
    o3d.t.io.write_triangle_mesh(str(out / f"{name}_mesh.ply"), mesh)
    np.save(out / f"{name}_poses.npy", poses)
    print(f"Points: {pcd.point.positions.shape[0]:,}   Mesh triangles: {mesh.triangle.indices.shape[0]:,}")
    print(f"Wrote {out}/{name}_cloud.ply, {name}_mesh.ply, {name}_poses.npy")


if __name__ == "__main__":
    main()
