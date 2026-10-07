#!/usr/bin/env python3
"""
view.py
Opens a reconstructed model in the Open3D viewer, by recording number.

Usage:
    python view.py 1              # output/001_<date>_<time>_mesh.ply
    python view.py 1 --cloud      # the point cloud instead of the mesh
    python view.py 1 --lc         # the loop-closed model (reconstruct.py --loop-closure)
    python view.py 1 --slice      # level the model, cut off everything above 1.5 m (the ceiling), view from the top
    python view.py 1 --slice 2.2  # same, cut at 2.2 m above the floor
    python view.py some/file.ply
"""
import argparse
import subprocess
import sys
from pathlib import Path

import numpy as np

OUTPUT = Path(__file__).parent / "output"


def find_up(normals, seed=(0, -1, 0)):
    """Vertical direction = the mean normal of the floor/ceiling surfaces. The seed is camera up in the first frame
    (-y); refine from a wide cone to a narrow one so a tilted start frame (often 10-20 deg) still converges."""
    up = np.asarray(seed, float)
    for tol in (35, 20, 10, 5):
        c = normals @ up
        sel = np.abs(c) > np.cos(np.radians(tol))
        up = (normals[sel] * np.sign(c[sel])[:, None]).sum(0)
        up /= np.linalg.norm(up)
    return up


def find_floor(heights):
    """Lowest height with a big share of horizontal surface (5 cm bins), so noise under the floor is ignored."""
    counts, edges = np.histogram(heights, bins=np.arange(heights.min(), heights.max() + 0.05, 0.05))
    return edges[np.argmax(counts >= 0.2 * counts.max())]


def level(points, normals):
    """Rotation + offset that puts up on +y and the floor at y = 0."""
    up = find_up(normals)
    x = np.cross(up, [0, 0, 1])
    x /= np.linalg.norm(x)
    R = np.stack([x, up, np.cross(x, up)])  # rows: new x, y (up), z
    horiz = np.abs(normals @ up) > 0.95
    return R, find_floor(points[horiz] @ up)


def view_sliced(path, cut):
    import open3d as o3d
    mesh = o3d.io.read_triangle_mesh(str(path))
    if len(mesh.triangles):
        geom = mesh
        mesh.compute_vertex_normals()
        normals = np.asarray(mesh.vertex_normals)
    else:
        geom = o3d.io.read_point_cloud(str(path))
        small = geom.voxel_down_sample(0.05)
        small.estimate_normals()
        normals = np.asarray(small.normals)
    R, floor = level(np.asarray(geom.vertices if geom is mesh else small.points), normals)
    geom.rotate(R, center=(0, 0, 0))
    geom.translate((0, -floor, 0))
    lo, hi = geom.get_min_bound(), geom.get_max_bound()
    print(f"Room height {hi[1]:.2f} m above floor; showing 0 to {cut} m")
    geom = geom.crop(o3d.geometry.AxisAlignedBoundingBox((lo[0], -0.2, lo[2]), (hi[0], cut, hi[2])))
    centre = (lo + hi) / 2
    centre[1] = 0
    # legacy viewer: o3d.visualization.draw() segfaulted in-process on the Windows laptop (Open3D 0.20)
    o3d.visualization.draw_geometries([geom], window_name=path.name, lookat=centre, front=(0, 1, 0), up=(0, 0, -1),
                                      zoom=0.7, mesh_show_back_face=True)


def main():
    ap = argparse.ArgumentParser(usage=__doc__)
    ap.add_argument("model")
    ap.add_argument("--cloud", action="store_true")
    ap.add_argument("--lc", action="store_true")
    ap.add_argument("--slice", type=float, nargs="?", const=1.5, metavar="HEIGHT")
    args = ap.parse_args()
    arg, kind = args.model, ("lc_" if args.lc else "") + ("cloud" if args.cloud else "mesh")
    if Path(arg).exists():
        path = Path(arg)
    elif arg.isdigit():
        matches = sorted(OUTPUT.glob(f"{int(arg):03d}_????????_??????_{kind}.ply"))
        if not matches:
            sys.exit(f"No {kind} for recording {int(arg):03d} in output/. "
                     f"Build it with: .venv/bin/python reconstruct.py {arg}"
                     + (" --loop-closure" if args.lc else ""))
        path = matches[0]
    else:
        sys.exit(f"'{arg}' is not a file or a recording number")
    print(f"Opening {path}")
    if args.slice is not None:
        view_sliced(path, args.slice)
    else:
        subprocess.run([str(Path(sys.executable).parent / "open3d"), "draw", str(path)])


if __name__ == "__main__":
    main()
