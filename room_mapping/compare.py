"""Score a model against a reference model of the same room: align (global + ICP), then the share of the model's
surface within 3 / 5 cm of the reference. Usage: compare.py ref.ply model.ply [model2.ply ...]"""
import sys, numpy as np, open3d as o3d
sys.path.insert(0, ".")
import loop_closure as lc
reg = o3d.pipelines.registration
def load(p):
    m = o3d.io.read_triangle_mesh(p)
    pc = o3d.geometry.PointCloud(m.vertices); pc.colors = m.vertex_colors
    return pc.voxel_down_sample(0.02)
o3d.utility.random.seed(0)
ref = load(sys.argv[1])
ref.estimate_normals(o3d.geometry.KDTreeSearchParamHybrid(radius=0.06, max_nn=30))
for p in sys.argv[2:]:
    m = load(p)
    best = None
    for trial in range(3):
        T0 = lc.global_match(m, ref)
        r = reg.registration_icp(m, ref, 0.1, T0, reg.TransformationEstimationPointToPlane())
        r = reg.registration_icp(m, ref, 0.03, r.transformation, reg.TransformationEstimationPointToPlane())
        if best is None or r.fitness > best.fitness: best = r
    d = np.asarray(m.transform(best.transformation).compute_point_cloud_distance(ref))
    print(f"{p.split('/')[-2]}/{p.split('/')[-1]}: within 3 cm {np.mean(d < 0.03):.1%}, 5 cm {np.mean(d < 0.05):.1%}, 10 cm {np.mean(d < 0.10):.1%}", flush=True)
