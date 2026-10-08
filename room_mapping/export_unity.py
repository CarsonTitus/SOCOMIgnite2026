#!/usr/bin/env python3
"""
export_unity.py
Exports a room model as a .glb for Unity: levelled (floor at y = 0, +Y up, metres), decimated for real-time use,
with the scanned colours as vertex colours (glTF COLOR_0).

Usage:
    python export_unity.py 7                    # output/007_..._mesh.ply -> output/unity/007_..._mesh.glb
    python export_unity.py 7 --lc               # the loop-closed model
    python export_unity.py 7 --triangles 500000
    python export_unity.py some/file.ply
"""
import argparse
import json
import struct
import time
from pathlib import Path

import numpy as np
import open3d as o3d

from view import OUTPUT, find_model, load_level


def write_glb(dest, mesh):
    """Minimal glTF 2.0 binary: one mesh with POSITION, NORMAL, COLOR_0, uint32 indices and an unlit material. (Open3D 0.20's .glb
    writer produced buffer views that point outside the buffer, so its files don't load.)"""
    srgb = np.asarray(mesh.vertex_colors)
    linear = np.where(srgb <= 0.04045, srgb / 12.92, ((srgb + 0.055) / 1.055) ** 2.4)  # glTF COLOR_0 is linear
    arrays = [np.asarray(mesh.vertices, np.float32), np.asarray(mesh.vertex_normals, np.float32),
              linear.astype(np.float32), np.asarray(mesh.triangles, np.uint32).ravel()]
    blob, views, accessors = b"", [], []
    for i, a in enumerate(arrays):
        views.append({"buffer": 0, "byteOffset": len(blob), "byteLength": a.nbytes,
                      "target": 34963 if i == 3 else 34962})  # ELEMENT_ARRAY_BUFFER / ARRAY_BUFFER
        acc = {"bufferView": i, "componentType": 5125 if i == 3 else 5126, "count": len(a),
               "type": "SCALAR" if i == 3 else "VEC3"}
        if i == 0:
            acc["min"], acc["max"] = a.min(0).tolist(), a.max(0).tolist()  # required for POSITION
        accessors.append(acc)
        blob += a.tobytes()  # every array is 4-byte aligned already
    gltf = {"asset": {"version": "2.0", "generator": "room_mapping/export_unity.py"},
            "scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0, "name": dest.stem}],
            "meshes": [{"name": dest.stem, "primitives": [
                {"attributes": {"POSITION": 0, "NORMAL": 1, "COLOR_0": 2}, "indices": 3, "mode": 4, "material": 0}]}],
            # unlit: the scan's colours already contain the room's lighting; double-sided: scans have one-sided walls
            "materials": [{"name": "scan", "doubleSided": True, "extensions": {"KHR_materials_unlit": {}},
                           "pbrMetallicRoughness": {"baseColorFactor": [1, 1, 1, 1], "metallicFactor": 0,
                                                    "roughnessFactor": 1}}],
            "extensionsUsed": ["KHR_materials_unlit"],
            "buffers": [{"byteLength": len(blob)}], "bufferViews": views, "accessors": accessors}
    js = json.dumps(gltf, separators=(",", ":")).encode()
    js += b" " * (-len(js) % 4)
    blob += b"\x00" * (-len(blob) % 4)
    with open(dest, "wb") as f:
        f.write(struct.pack("<4sII", b"glTF", 2, 12 + 8 + len(js) + 8 + len(blob)))
        f.write(struct.pack("<I4s", len(js), b"JSON") + js)
        f.write(struct.pack("<I4s", len(blob), b"BIN\x00") + blob)


def main():
    ap = argparse.ArgumentParser(usage=__doc__)
    ap.add_argument("model", help="recording number or .ply path")
    ap.add_argument("--lc", action="store_true", help="use the loop-closed model")
    ap.add_argument("--triangles", type=int, default=300_000, help="target triangle count")
    ap.add_argument("--out", default=str(OUTPUT / "unity"))
    args = ap.parse_args()

    path = find_model(args.model, args.lc)
    mesh = load_level(path)
    if not isinstance(mesh, o3d.geometry.TriangleMesh):
        raise SystemExit("Unity export needs a mesh, not a point cloud")
    n0 = len(mesh.triangles)
    t0 = time.time()
    if n0 > args.triangles:
        mesh = mesh.simplify_quadric_decimation(target_number_of_triangles=args.triangles)
    mesh.remove_unreferenced_vertices()
    mesh.compute_vertex_normals()
    print(f"Triangles {n0:,} -> {len(mesh.triangles):,} in {time.time() - t0:.0f} s")

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    dest = out / (path.stem + ".glb")
    write_glb(dest, mesh)
    lo, hi = mesh.get_min_bound(), mesh.get_max_bound()
    print(f"Size {hi[0] - lo[0]:.2f} x {hi[2] - lo[2]:.2f} m, height {hi[1] - lo[1]:.2f} m (floor at y = 0)")
    print(f"Wrote {dest} ({dest.stat().st_size / 1e6:.0f} MB)")


if __name__ == "__main__":
    main()
