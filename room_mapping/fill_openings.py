#!/usr/bin/env python3
"""
fill_openings.py  (EXPERIMENTAL: only as good as the model; on bent models the walls, and so the fill, change
from build to build)
Fills glass in a finished model with flat, tinted patches (the idea of Mirror3D, arXiv 2106.06629: glass and
mirrors give wrong depth, so replace them with the plane of the wall they sit in).

    .venv/bin/python fill_openings.py 14                 # loop-closed model of recording 014 in output/
    .venv/bin/python fill_openings.py 14 --dir output/wu/014_0.1_f15

Writes <name>_lc_filled_mesh.ply next to the model (view.py N --filled opens it). Changes nothing else.

Glass shows the D455 the world outside (depth far beyond the wall) or nothing, and the model is built from depth up
to 3 m, so glass leaves the wall empty. A window wall can be almost all glass (014: floor-to-ceiling panes between
mullions), so this doesn't look for holes enclosed by wall. Instead every empty 5 cm cell of each wall plane, between
floor and ceiling, is checked against the recording: from the frames that could see it with nothing of the model in
the way, what did the camera measure there?
  - far beyond the wall, or no depth           -> glass: filled (light blue)
  - a surface within ~2.5 m behind the wall    -> doorway / opening into space the scan covers: left open
  - the wall itself                            -> wall just outside the model's 3 m range: left as is
Glass regions the camera walked through are left open (doorways), and so are pieces under MIN_REGION_M2. Each
region is then filled out to its bounding rectangle (panes are rectangles), except cells that were seen as wall or
as open to mapped space, so the panes come out whole instead of as scattered 5 cm cells.
Glossy screens (wall TVs) give wrong depth the same way, so they get filled too, as the flat surfaces they are.
"""
import argparse
import json
from pathlib import Path

import cv2
import numpy as np
import open3d as o3d

import view
from rgbd_io import resolve_recording

CELL = 0.05             # wall grid cell (m)
WALL_DIST = 0.03        # RANSAC inlier distance to a wall plane (m)
MIN_WALL_POINTS = 1500  # at 3 cm sampling; window walls have only their frames and mullions
FRAME_STEP = 10         # look through every Nth frame
MAX_RANGE = 6.0         # ignore views of a cell from further than this (m)
BEHIND_NEAR = 2.5       # a measured surface this close behind the wall means there's mapped space there (m)
SURFACE_TOL = 0.25      # measured depth this close to the wall's = the wall itself (m)
MIN_VIEWS = 3           # clear views a cell needs before it's judged
GLASS_SHARE = 0.7       # share of a cell's clear views that saw far/nothing through it
MIN_REGION_M2 = 0.15
TINT = (0.62, 0.80, 1.0)


def find_walls(cloud, up):
    """Big vertical planes, as (normal, offset, inlier points), largest first."""
    walls, rest = [], cloud
    for _ in range(15):
        if len(rest.points) < MIN_WALL_POINTS:
            break
        (a, b, c, d), idx = rest.segment_plane(WALL_DIST, 3, 1000)
        if len(idx) < MIN_WALL_POINTS:
            break
        n = np.array([a, b, c])
        if abs(n @ up) < 0.15:
            walls.append((n, d, np.asarray(rest.points)[idx]))
        rest = rest.select_by_index(idx, invert=True)
    return walls


def wall_grid(n, d, pts, up, floor, ceiling):
    """The wall's frame (origin on the plane at floor height, along-wall axis, up axis), grid origin and
    occupancy grid (cells with model surface), spanning the wall's length and floor to ceiling."""
    along = np.cross(up, n)
    along /= np.linalg.norm(along)
    vert = np.cross(n, along)
    origin = -d * n
    origin = origin + (floor - origin @ vert) * vert
    uv = np.c_[(pts - origin) @ along, (pts - origin) @ vert]
    lo = np.array([uv[:, 0].min(), 0.0])
    shape = (int(np.ptp(uv[:, 0]) / CELL) + 1, int((ceiling - floor) / CELL) + 1)
    ij = np.floor((uv - lo) / CELL).astype(int)
    keep = (ij[:, 1] >= 0) & (ij[:, 1] < shape[1])
    occ = np.zeros(shape, np.uint8)
    occ[ij[keep, 0], ij[keep, 1]] = 1
    occ = cv2.dilate(occ, np.ones((3, 3), np.uint8))  # surface within a cell of here is wall/frame, not glass
    return (origin, along, vert), lo, occ


def look_through(centres, views, K, size, depth_scale, scene):
    """Per cell: number of clear views, and how many saw far/nothing, a surface close behind, or the wall itself."""
    w, h = size
    clear_n, far, near, surface = (np.zeros(len(centres), int) for _ in range(4))
    for P, depth in views:
        R, t = P[:3, :3], P[:3, 3]
        pc = (centres - t) @ R  # world -> camera
        dist = np.linalg.norm(pc, axis=1)
        z = pc[:, 2]
        u = np.round(K[0, 0] * pc[:, 0] / np.maximum(z, 1e-6) + K[0, 2]).astype(int)
        v = np.round(K[1, 1] * pc[:, 1] / np.maximum(z, 1e-6) + K[1, 2]).astype(int)
        idx = np.flatnonzero((z > 0.3) & (dist < MAX_RANGE) & (u >= 0) & (u < w) & (v >= 0) & (v < h))
        if not len(idx):
            continue
        dirs = (centres[idx] - t) / dist[idx, None]
        rays = np.c_[np.repeat(t[None], len(idx), 0), dirs].astype(np.float32)
        hit = scene.cast_rays(o3d.core.Tensor(rays))["t_hit"].numpy()
        idx = idx[hit >= dist[idx] - 0.05]  # nothing of the model between the camera and the cell (inf = no hit)
        measured = depth[v[idx], u[idx]] / depth_scale
        expected = z[idx]
        clear_n[idx] += 1
        far[idx] += (measured == 0) | (measured > expected + BEHIND_NEAR)
        near[idx] += (measured > expected + 0.5) & (measured <= expected + BEHIND_NEAR)
        surface[idx] += np.abs(measured - expected) < SURFACE_TOL
    return clear_n, far, near, surface


def walked_through(cells_uv, frame, n, cams):
    """True if the camera was on the far side of the wall (the side most of the path isn't on), in front of the
    region: it can only have got there through the opening."""
    origin, along, vert = frame
    rel = cams - origin
    u, v, s = rel @ along, rel @ vert, rel @ n
    main = np.sign(np.median(s))
    lo, hi = cells_uv.min(axis=0) - 0.3, cells_uv.max(axis=0) + 0.3
    near = (u >= lo[0]) & (u <= hi[0]) & (v <= hi[1]) & (np.abs(s) < 1.5)
    return bool(np.any(near & (np.sign(s) == -main)))


def patch(cells_uv, frame):
    """Two triangles per cell, on the wall plane, tinted."""
    origin, along, vert = frame
    corners = np.array([[-0.5, -0.5], [0.5, -0.5], [0.5, 0.5], [-0.5, 0.5]]) * CELL
    c = cells_uv[:, None, :] + corners[None]                     # cells x 4 x 2
    verts = origin + c[..., :1] * along + c[..., 1:] * vert      # cells x 4 x 3
    base = 4 * np.arange(len(cells_uv))[:, None]
    tris = np.vstack([base + [0, 1, 2], base + [0, 2, 3]])
    m = o3d.geometry.TriangleMesh(o3d.utility.Vector3dVector(verts.reshape(-1, 3)), o3d.utility.Vector3iVector(tris))
    m.vertex_colors = o3d.utility.Vector3dVector(np.tile(TINT, (len(m.vertices), 1)))
    return m


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("recording", help="recording number or path")
    ap.add_argument("--dir", default="output", help="folder with the built model")
    args = ap.parse_args()
    recording = Path(resolve_recording(args.recording))
    name, out = recording.stem, Path(args.dir)
    kind = "lc_" if (out / f"{name}_lc_mesh.ply").exists() else ""
    mesh = o3d.io.read_triangle_mesh(str(out / f"{name}_{kind}mesh.ply"))
    poses = np.load(out / f"{name}_{kind}poses.npy")
    frame_ids = np.load(out / f"{name}_frames.npy")
    intr = json.load(open(recording / "intrinsic.json"))
    m = intr["intrinsic_matrix"]
    K = np.array([[m[0], 0, m[6]], [0, m[4], m[7]], [0, 0, 1]])
    depth_scale = json.load(open(recording / "meta.json"))["depth_scale"]
    views = [(P, cv2.imread(str(recording / "depth" / f"{f:06d}.png"), cv2.IMREAD_UNCHANGED))
             for f, P in list(zip(frame_ids, poses))[::FRAME_STEP]]

    o3d.utility.random.seed(0)
    mesh.compute_vertex_normals()
    cloud = o3d.geometry.PointCloud(mesh.vertices)
    cloud.normals = mesh.vertex_normals
    cloud = cloud.voxel_down_sample(0.03)
    normals = np.asarray(cloud.normals)
    up = view.find_up(normals)
    floor, ceiling = view.floor_and_ceiling(np.asarray(cloud.points)[np.abs(normals @ up) > 0.95] @ up)
    scene = o3d.t.geometry.RaycastingScene()
    scene.add_triangles(o3d.t.geometry.TriangleMesh.from_legacy(mesh))
    cams = poses[:, :3, 3]

    walls = find_walls(cloud, up)
    print(f"{len(walls)} walls, floor to ceiling {ceiling - floor:.2f} m, {len(views)} views")
    filled, total_m2 = o3d.geometry.TriangleMesh(), 0.0
    for wi, (n, d, pts) in enumerate(walls):
        frame, lo, occ = wall_grid(n, d, pts, up, floor, ceiling)
        origin, along, vert = frame
        empty = np.argwhere(occ == 0)
        cells = lo + (empty + 0.5) * CELL
        centres = origin + cells[:, :1] * along + cells[:, 1:] * vert
        seen, far, near, surface = look_through(centres, views, K, (intr["width"], intr["height"]), depth_scale, scene)
        glass = (seen >= MIN_VIEWS) & (far >= GLASS_SHARE * np.maximum(seen, 1))
        grid = np.zeros(occ.shape, np.uint8)
        grid[empty[glass, 0], empty[glass, 1]] = 1
        grid = cv2.morphologyEx(grid, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))  # bridge single noisy cells
        grid[occ == 1] = 0
        count, labels = cv2.connectedComponents(grid, connectivity=4)
        opened = (seen >= MIN_VIEWS) & (near > np.maximum(far, surface))
        print(f"  wall {wi + 1} ({np.ptp(pts @ along):.1f} m long): {len(empty)} empty cells, {int((seen >= MIN_VIEWS).sum())} "
              f"seen clearly: {int(glass.sum())} glass, {int(opened.sum())} open to mapped space")
        blocked = np.zeros(occ.shape, bool)  # empty cells seen as wall or as open space: never filled
        blocked[empty[:, 0], empty[:, 1]] = (seen >= MIN_VIEWS) & ~glass & (np.maximum(near, surface) >= far)
        for k in range(1, count):
            ij = np.argwhere(labels == k)
            if len(ij) * CELL ** 2 >= MIN_REGION_M2:
                (i0, j0), (i1, j1) = ij.min(axis=0), ij.max(axis=0)
                box = np.zeros(occ.shape, bool)
                box[i0:i1 + 1, j0:j1 + 1] = True
                ij = np.argwhere(box & (occ == 0) & ~blocked)
            region = lo + (ij + 0.5) * CELL
            area = len(region) * CELL ** 2
            if area < MIN_REGION_M2:
                continue
            size = f"{np.ptp(region[:, 0]) + CELL:.2f} x {np.ptp(region[:, 1]) + CELL:.2f} m, " \
                   f"{region[:, 1].min() - CELL / 2:.2f}-{region[:, 1].max() + CELL / 2:.2f} m above floor"
            if walked_through(region, frame, n, cams):
                print(f"    {size}: walked through -> doorway, left open")
                continue
            filled += patch(region, frame)
            total_m2 += area
            print(f"    {size}: filled ({area:.2f} m²)")
    out_path = out / f"{name}_{kind}filled_mesh.ply"
    o3d.io.write_triangle_mesh(str(out_path), mesh + filled)
    print(f"Filled {total_m2:.1f} m² of glass. Wrote {out_path}")


if __name__ == "__main__":
    main()
