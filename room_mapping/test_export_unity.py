"""Check export_unity.write_glb(): a coloured 2-triangle mesh reads back (via Open3D/Assimp) with the same geometry
and colours (written linear, so read back as sRGB**2.4-ish, darker).
Run: .venv/bin/python test_export_unity.py"""
import tempfile
from pathlib import Path

import numpy as np
import open3d as o3d

from export_unity import write_glb

m = o3d.geometry.TriangleMesh(o3d.utility.Vector3dVector([[0, 0, 0], [1, 0, 0], [1, 0, 1], [0, 0, 1]]),
                              o3d.utility.Vector3iVector([[0, 2, 1], [0, 3, 2]]))
m.vertex_colors = o3d.utility.Vector3dVector([[1, 0, 0], [0, 1, 0], [0, 0, 1], [0.5, 0.5, 0.5]])
m.compute_vertex_normals()
with tempfile.TemporaryDirectory() as d:
    dest = Path(d) / "t.glb"
    write_glb(dest, m)
    r = o3d.io.read_triangle_mesh(str(dest))
assert len(r.triangles) == 2, len(r.triangles)
assert np.allclose(np.sort(np.asarray(r.vertices), 0), np.sort(np.asarray(m.vertices), 0))
c = np.asarray(r.vertex_colors)
assert np.isclose(c.max(), 1) and np.isclose(c.min(), 0), c
assert np.allclose(c[np.argmin(np.abs(c - 0.214).sum(1))], 0.214, atol=0.01), c  # 0.5 sRGB -> 0.214 linear
print("ok:", len(r.triangles), "triangles,", len(c), "coloured vertices")
