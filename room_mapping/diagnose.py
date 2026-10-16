"""
diagnose.py
Compares recordings to find out why one reconstructs worse than another. Changes nothing.

    .venv/bin/python diagnose.py 7 8

For each recording it checks the metadata against the frames on disk, the frame timing, how much usable depth each
frame has, and how frame-to-model tracking goes (same settings as reconstruct.py before segments: 3 m depth,
fitness >= 0.1). Prints a summary table and writes output/diagnose/<names>.png (+ a .npz per recording).
"""
import argparse
import json
from pathlib import Path

import cv2
import numpy as np
import open3d as o3d
import open3d.core as o3c

from rgbd_io import open_recording, resolve_recording

FAR_SHARE = 0.3       # a frame with less than this share of pixels within track range counts as "far / window"
BREAK_RUN = 15        # this many rejected frames in a row (0.5 s) = tracking clearly broken
JUMP_CM, JUMP_DEG = 10.0, 8.0   # per-frame motion beyond this is implausible for a hand-held camera


def check_files(folder, K, hw, ds):
    """Metadata vs what's on disk."""
    out = {}
    meta = json.loads((folder / "meta.json").read_text()) if (folder / "meta.json").exists() else {}
    depths = sorted((folder / "depth").glob("*.png"))
    colors = sorted((folder / "color").glob("*.jpg"))
    d0 = cv2.imread(str(depths[0]), cv2.IMREAD_UNCHANGED)
    c0 = cv2.imread(str(colors[0]))
    out["device"] = meta.get("device", "laptop/PC")
    out["frames"] = f"{len(depths)} depth / {len(colors)} colour / {len(meta.get('timestamps_ms', []))} timestamps"
    out["intrinsic size"] = f"{hw[1]}x{hw[0]}"
    out["depth png"] = f"{d0.shape[1]}x{d0.shape[0]} {d0.dtype}"
    out["colour jpg"] = f"{c0.shape[1]}x{c0.shape[0]}"
    out["fx fy cx cy"] = f"{K[0,0]:.1f} {K[1,1]:.1f} {K[0,2]:.1f} {K[1,2]:.1f}"
    problems = []
    if d0.shape[:2] != tuple(hw):
        problems.append("depth size != intrinsics")
    if c0.shape[:2] != d0.shape[:2]:
        problems.append("colour not aligned to depth size")
    if d0.dtype != np.uint16:
        problems.append("depth not 16-bit")
    if abs(K[0, 2] - hw[1] / 2) > 0.1 * hw[1] or abs(K[1, 2] - hw[0] / 2) > 0.1 * hw[0]:
        problems.append("principal point far from centre")
    out["depth scale"] = f"{ds:.1f} units/m"
    out["file problems"] = ", ".join(problems) or "none"

    ts = np.array(meta.get("timestamps_ms", []), dtype=float)
    fps = meta.get("fps", 30)
    if len(ts) > 1:
        dt = np.diff(ts)
        synthetic = np.allclose(dt, 1000.0 / fps, atol=1e-3)
        out["timestamps"] = "synthetic (converted file)" if synthetic else "camera"
        out["effective fps"] = f"{(len(ts) - 1) / (ts[-1] - ts[0]) * 1000:.2f} (meta {fps})"
        out["frame gap ms"] = f"median {np.median(dt):.1f}, max {dt.max():.1f}"
        out["gaps > 1.5 frames"] = f"{int((dt > 1.5 * 1000 / fps).sum())} (~{int(np.round(dt / (1000 / fps)).sum() - len(dt))} frames missing); meta dropped {meta.get('dropped', '?')}"
    out["colour/depth offset"] = "not recorded (one timestamp per frame; colour aligned to depth by librealsense)"
    return out, ts


def track(frames, K, hw, ds, depth_max=3.0, min_fitness=0.1):
    """Frame-to-model tracking as in reconstruct.py (pre-segments). Returns per-frame arrays."""
    dev = o3c.Device("CUDA:0") if o3d.core.cuda.is_available() else o3c.Device("CPU:0")
    h, w = hw
    intr = o3c.Tensor(K, o3c.float64)
    T = np.identity(4)
    model = o3d.t.pipelines.slam.Model(0.01, 16, 80000, o3c.Tensor(T), dev)
    frame = o3d.t.pipelines.slam.Frame(h, w, intr, dev)
    raycast = o3d.t.pipelines.slam.Frame(h, w, intr, dev)
    rows, used = [], 0
    for i, (d, c) in enumerate(frames):
        valid = d > 0
        valid_share = valid.mean()
        in_range = (valid & (d >= 0.3 * ds) & (d <= depth_max * ds)).mean()
        median_m = np.median(d[valid]) / ds if valid.any() else np.nan
        frame.set_data_from_image("depth", o3d.t.geometry.Image(o3c.Tensor(d)).to(dev))
        frame.set_data_from_image("color", o3d.t.geometry.Image(o3c.Tensor(c)).to(dev))
        fit = rmse = move_cm = turn_deg = np.nan
        ok = True
        if used:
            try:
                r = model.track_frame_to_model(frame, raycast, ds, depth_max, 0.07,
                                               o3d.t.pipelines.odometry.Method.PointToPlane)
                fit, rmse = r.fitness, r.inlier_rmse
                step = r.transformation.cpu().numpy()
                move_cm = np.linalg.norm(step[:3, 3]) * 100
                turn_deg = np.degrees(np.arccos(np.clip((np.trace(step[:3, :3]) - 1) / 2, -1, 1)))
                ok = fit >= min_fitness
                if ok:
                    T = T @ step
            except RuntimeError:
                ok = False
        if ok:
            model.update_frame_pose(used, o3c.Tensor(T))
            model.integrate(frame, ds, depth_max)
            model.synthesize_model_frame(raycast, ds, 0.3, depth_max, 8.0, False)
            used += 1
        rows.append((i, valid_share, in_range, median_m, fit, rmse, move_cm, turn_deg, ok))
    return np.array(rows, dtype=float)


def summarise(rows, fps):
    i, valid, in_range, median_m, fit, rmse, move, turn, ok = rows.T
    ok = ok.astype(bool)
    rel = fit / np.maximum(in_range, 1e-6)
    far = in_range < FAR_SHARE
    out = {
        "valid depth (median)": f"{np.nanmedian(valid):.0%}",
        "within 3 m (median)": f"{np.nanmedian(in_range):.0%}",
        "median depth (median)": f"{np.nanmedian(median_m):.2f} m",
        "far / window frames": f"{far.sum()} ({far.mean():.0%})",
        "tracking fitness (median)": f"{np.nanmedian(fit):.2f}",
        "matched / in-range (median)": f"{np.nanmedian(rel):.2f}",
        "inlier rmse (median)": f"{np.nanmedian(rmse) * 1000:.2f} mm",
        "rejected frames": f"{(~ok).sum()} ({(~ok).mean():.0%})",
        "speed (median / 95%)": f"{np.nanmedian(move) * fps / 100:.2f} / {np.nanpercentile(move[ok], 95) * fps / 100:.2f} m/s",
        "turn (median / 95%)": f"{np.nanmedian(turn) * fps:.0f} / {np.nanpercentile(turn[ok], 95) * fps:.0f} deg/s",
    }
    jumps = ok & ((move > JUMP_CM) | (turn > JUMP_DEG))
    out["implausible jumps accepted"] = str(jumps.sum())
    bad, first_break = 0, None
    for k in range(len(ok)):
        bad = 0 if ok[k] else bad + 1
        if bad == BREAK_RUN:
            first_break = k - BREAK_RUN + 1
            break
    if first_break is None:
        out["first tracking break"] = "none"
    else:
        relock = next((k for k in range(first_break, len(ok)) if ok[k]), None)
        out["first tracking break"] = (f"frame {first_break} ({first_break / fps:.1f} s), "
                                       f"{in_range[max(first_break - 30, 0):first_break].mean():.0%} within 3 m in the 1 s before; "
                                       + (f"re-locked at {relock} with fitness {fit[relock]:.2f} "
                                          f"(= {rel[relock]:.2f} of in-range pixels)" if relock is not None else "never re-locked"))
    return out


def plot(results, path):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, axes = plt.subplots(len(results), 1, figsize=(14, 3.6 * len(results)), squeeze=False)
    for ax, (name, rows, fps) in zip(axes[:, 0], results):
        i, valid, in_range, median_m, fit, rmse, move, turn, ok = rows.T
        t = i / fps
        ax.fill_between(t, 0, 1, where=in_range < FAR_SHARE, color="orange", alpha=0.2, step="mid", label="far / window")
        ax.fill_between(t, 0, 1, where=ok == 0, color="red", alpha=0.25, step="mid", label="tracking rejected")
        ax.plot(t, valid, color="0.6", lw=0.8, label="valid depth share")
        ax.plot(t, in_range, color="tab:blue", lw=0.8, label="share within 3 m")
        ax.plot(t, fit, color="tab:green", lw=0.8, label="tracking fitness")
        ax.plot(t, np.clip(fit / np.maximum(in_range, 1e-6), 0, 1), color="black", lw=0.6, ls=":",
                label="matched / in-range")
        ax2 = ax.twinx()
        ax2.plot(t, turn * fps, color="tab:purple", lw=0.5, alpha=0.6, label="turn deg/s")
        ax2.set_ylim(0, 150)
        ax2.set_ylabel("deg/s")
        ax.set_ylim(0, 1)
        ax.set_xlim(t[0], t[-1])
        ax.set_title(name)
        ax.set_xlabel("seconds")
        h1, l1 = ax.get_legend_handles_labels()
        h2, l2 = ax2.get_legend_handles_labels()
        ax.legend(h1 + h2, l1 + l2, loc="lower left", fontsize=7, ncol=4)
    fig.tight_layout()
    fig.savefig(path, dpi=110)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("recordings", nargs="+", help="recording numbers or paths")
    ap.add_argument("--depth-max", type=float, default=3.0)
    args = ap.parse_args()

    out_dir = Path("output/diagnose")
    out_dir.mkdir(parents=True, exist_ok=True)
    table, results = {}, []
    for rec in args.recordings:
        folder = resolve_recording(rec)
        frames, K, hw, ds = open_recording(folder)
        files, ts = check_files(folder, K, hw, ds)
        fps = json.loads((folder / "meta.json").read_text()).get("fps", 30)
        print(f"tracking {folder.name} ...", flush=True)
        rows = track(frames, K, hw, ds, args.depth_max)
        np.savez(out_dir / f"{folder.name}.npz", rows=rows, timestamps_ms=ts)
        table[folder.name[:3]] = {**files, **summarise(rows, fps)}
        results.append((folder.name, rows, fps))

    names = list(table)
    keys = list(table[names[0]])
    w = max(map(len, keys))
    print("\n| " + " | ".join(["".ljust(w)] + names) + " |")
    print("|" + "|".join(["-" * (w + 2)] + ["---"] * len(names)) + "|")
    for k in keys:
        print("| " + " | ".join([k.ljust(w)] + [table[n].get(k, "") for n in names]) + " |")
    png = out_dir / ("_".join(names) + ".png")
    plot(results, png)
    print(f"\nplot: {png}")


if __name__ == "__main__":
    main()
