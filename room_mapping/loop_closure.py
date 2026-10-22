"""
loop_closure.py
Pose-graph loop closure for reconstruct.py --loop-closure.

Frame-to-model tracking drifts a few mm per frame. This corrects it after recording:
  1. Split the tracked frames into fragments (~30 frames, 1 s each) and build a small TSDF model of each.
  2. Register fragment pairs with multi-scale colored ICP, starting from the tracked poses. Neighbouring
     fragments give odometry edges; non-neighbouring pairs that overlap (the camera came back to a place it
     had already seen) give loop-closure edges.
  3. Optimise the pose graph (Levenberg-Marquardt), pruning loop edges that disagree with the rest.
  4. Push each fragment's correction to its frames and re-integrate everything into one model.

Loop candidates are found by ICP from the tracked poses, not by global feature matching, so this corrects
drift of up to a few tens of cm. It is not meant for recovering from tracking that got completely lost.
"""
import numpy as np
import open3d as o3d
import open3d.core as o3c

from rgbd_io import open_recording

reg = o3d.pipelines.registration

REG_VOXEL = 0.02          # finest registration scale (m); coarser scales are 2x and 4x this
MIN_OVERLAP = 0.3         # ICP fitness needed to accept a loop-closure edge
MAX_JUMP_M = 0.5          # reject loop edges that move a fragment further than this from the tracked pose...
MAX_JUMP_DEG = 20.0       # ...or rotate it more than this (drift is small; big jumps are false matches)
# Drift builds up slowly, so fragments close in time can't be far off: the allowed jump grows with the time between
# them. On 008, ICP between pieces 2-8 s apart "found" 20-42 cm shifts with ~1 deg turn (sliding along flat walls),
# which doubled the floor and ceiling; real corrections there were under 7 cm. 007's whole-scan end gap was 22 cm.
JUMP_BASE = (0.05, 2.0)   # m, deg allowed between fragments right next to each other in time...
JUMP_RATE = (0.01, 0.3)   # ...plus this much per second between them


def new_volume(voxel, block_count, device):
    return o3d.t.geometry.VoxelBlockGrid(
        ("tsdf", "weight", "color"), (o3c.float32, o3c.float32, o3c.float32), ((1), (1), (3)),
        voxel, 16, block_count, device)


def integrate(vbg, depth, color, intrinsic, pose, depth_scale, depth_max, device):
    """Integrates one frame. pose is camera-to-world (4x4 numpy)."""
    d = o3d.t.geometry.Image(o3c.Tensor(depth)).to(device)
    c = o3d.t.geometry.Image(o3c.Tensor(color)).to(device)
    extrinsic = o3c.Tensor(np.linalg.inv(pose))
    blocks = vbg.compute_unique_block_coordinates(d, intrinsic, extrinsic, depth_scale, depth_max)
    vbg.integrate(blocks, d, c, intrinsic, intrinsic, extrinsic, depth_scale, depth_max)


def fix_colors(attrs):
    """Makes a tensor geometry's colours 0-1. Extraction normally returns 0-1 (averaging can overshoot 1.0 by a
    rounding error); guard against the 0-255 and double-/255 cases too."""
    if "colors" not in attrs or attrs.colors.shape[0] == 0:
        return
    top = attrs.colors.max().item()
    if top > 2.0:                       # 0-255
        attrs.colors = attrs.colors / 255.0
    elif top <= 1.0 / 255 + 1e-6:      # scaled by 1/255 twice
        attrs.colors = attrs.colors * 255.0
    attrs.colors = attrs.colors.clip(0.0, 1.0)


def to_legacy_cloud(vbg):
    pcd = vbg.extract_point_cloud().cpu()
    fix_colors(pcd.point)
    return pcd.to_legacy()


def build_fragments(recording, frame_ids, poses, K, depth_scale, depth_max, voxel, fragment_size, device):
    """Returns (fragment clouds, index into frame_ids of each fragment's first frame)."""
    intrinsic = o3c.Tensor(K, o3c.float64)
    slot = {f: i for i, f in enumerate(frame_ids)}
    starts = list(range(0, len(frame_ids), fragment_size))
    clouds, vbg, current = [], None, -1
    frames, *_ = open_recording(recording)
    for fid, (depth, color) in enumerate(frames):
        if fid not in slot:
            continue
        i = slot[fid]
        k = i // fragment_size
        if k != current:
            if vbg is not None:
                clouds.append(to_legacy_cloud(vbg))
            vbg, current = new_volume(voxel, 20000, device), k
        rel = np.linalg.inv(poses[starts[k]]) @ poses[i]
        integrate(vbg, depth, color, intrinsic, rel, depth_scale, depth_max, device)
    clouds.append(to_legacy_cloud(vbg))
    return clouds, starts


def multiscale_icp(src, tgt, init, coarsest=4):
    """Coarse-to-fine colored ICP from REG_VOXEL * coarsest down to REG_VOXEL. Returns (transformation, fitness,
    information matrix)."""
    T, fitness = init, 0.0
    for v in [REG_VOXEL * 2 ** k for k in range(int(np.log2(coarsest)), -1, -1)]:
        s, t = src.voxel_down_sample(v), tgt.voxel_down_sample(v)
        for p in (s, t):
            p.estimate_normals(o3d.geometry.KDTreeSearchParamHybrid(radius=v * 2, max_nn=30))
        crit = reg.ICPConvergenceCriteria(relative_fitness=1e-6, relative_rmse=1e-6, max_iteration=50)
        try:
            r = reg.registration_colored_icp(s, t, v * 1.5, T, reg.TransformationEstimationForColoredICP(), crit)
        except RuntimeError:  # too few correspondences for colored ICP
            try:
                r = reg.registration_icp(s, t, v * 1.5, T, reg.TransformationEstimationPointToPlane(), crit)
            except RuntimeError:  # too few for any ICP (e.g. a fragment that saw almost nothing): no match
                return init, 0.0, np.identity(6)
        T, fitness = r.transformation, r.fitness
    info = reg.get_information_matrix_from_point_clouds(s, t, REG_VOXEL * 1.5, T)
    return T, fitness, info


def pose_change(a, b):
    """Translation (m) and rotation (deg) between two 4x4 transforms."""
    d = np.linalg.inv(a) @ b
    angle = np.degrees(np.arccos(np.clip((np.trace(d[:3, :3]) - 1) / 2, -1, 1)))
    return np.linalg.norm(d[:3, 3]), angle


def optimize_poses(recording, frame_ids, poses, K, depth_scale, depth_max, voxel,
                   fragment_size=30, device=o3c.Device("CPU:0"), fps=30):
    """Returns loop-closed camera-to-world poses for frame_ids, plus a stats dict."""
    poses = np.asarray(poses)
    clouds, starts = build_fragments(recording, frame_ids, poses, K, depth_scale, depth_max, voxel,
                                     fragment_size, device)
    n = len(clouds)
    frag_pose = [poses[s] for s in starts]

    graph = reg.PoseGraph()
    for p in frag_pose:
        graph.nodes.append(reg.PoseGraphNode(p))

    loops, checked, edges = 0, 0, []
    for s in range(n):
        for t in range(s + 1, n):
            init = np.linalg.inv(frag_pose[t]) @ frag_pose[s]  # maps fragment s into fragment t's frame
            adjacent = t == s + 1
            if not adjacent:
                # cheap overlap check at the tracked poses before running full ICP
                coarse = reg.evaluate_registration(clouds[s].voxel_down_sample(0.05),
                                                   clouds[t].voxel_down_sample(0.05), 0.1, init)
                if coarse.fitness < MIN_OVERLAP / 2:
                    continue
            checked += 1
            T, fitness, info = multiscale_icp(clouds[s], clouds[t], init)
            jump_m, jump_deg = pose_change(init, T)
            seconds = (frame_ids[starts[t]] - frame_ids[starts[s]]) / fps
            plausible = (jump_m < min(MAX_JUMP_M, JUMP_BASE[0] + JUMP_RATE[0] * seconds) and
                         jump_deg < min(MAX_JUMP_DEG, JUMP_BASE[1] + JUMP_RATE[1] * seconds))
            if adjacent:
                if fitness < MIN_OVERLAP or not plausible:  # fall back to the tracked relative pose
                    T = init
                    info = reg.get_information_matrix_from_point_clouds(
                        clouds[s].voxel_down_sample(REG_VOXEL), clouds[t].voxel_down_sample(REG_VOXEL),
                        REG_VOXEL * 1.5, T)
                graph.edges.append(reg.PoseGraphEdge(s, t, T, info, uncertain=False))
            elif fitness >= MIN_OVERLAP and plausible:
                graph.edges.append(reg.PoseGraphEdge(s, t, T, info, uncertain=True))
                edges.append((s, t, fitness, jump_m, jump_deg, info))
                loops += 1

    level = o3d.utility.get_verbosity_level()
    o3d.utility.set_verbosity_level(o3d.utility.VerbosityLevel.Error)
    reg.global_optimization(
        graph, reg.GlobalOptimizationLevenbergMarquardt(), reg.GlobalOptimizationConvergenceCriteria(),
        reg.GlobalOptimizationOption(max_correspondence_distance=REG_VOXEL * 1.5, edge_prune_threshold=0.25,
                                     preference_loop_closure=5.0, reference_node=0))
    o3d.utility.set_verbosity_level(level)
    kept = sum(1 for e in graph.edges if e.uncertain)

    # apply each fragment's correction to all of its frames
    corrected = np.empty_like(poses)
    for i in range(len(poses)):
        k = i // fragment_size
        corrected[i] = graph.nodes[k].pose @ np.linalg.inv(frag_pose[k]) @ poses[i]

    end_shift_m, end_shift_deg = pose_change(poses[-1], corrected[-1])
    stats = {"fragments": n, "pairs_checked": checked, "loops_found": loops, "loops_kept": kept,
             "end_shift_m": end_shift_m, "end_shift_deg": end_shift_deg, "loop_edges": edges}
    return corrected, stats


PLACE_VOXEL = 0.05        # global (feature) registration scale for joining segments (m)
PLACE_MIN_FITNESS = 0.3   # share of a segment's points that must land on the placed model, from a global match...
NEAR_MIN_FITNESS = 0.1    # ...or from near where the camera was last seen (a new segment often sees mostly new area)
MAX_SPEED_M = 0.05        # handheld motion limits per frame (1.5 m/s, 120 deg/s at 30 fps), plus a margin below:
MAX_TURN_DEG = 4.0        # a placement needing faster motion across the gap is a false match. On 008 the global
MOTION_MARGIN = (0.3, 20.0)  # match put the room's mirror image 166 deg round after a 0.5 s gap.
GRAVITY_MAX_DEG = 5.0     # placements must keep the accelerometer's 'up' within this of the first segment's
PRIOR_LIMIT = (1.0, 45.0)  # a segment started from a bridged pose may land this far (m, deg) from it: bridges drift


def global_match(src, tgt):
    """FPFH + RANSAC registration (no initial guess needed). Returns a 4x4 transform mapping src onto tgt."""
    feats = []
    for p in (src, tgt):
        d = p.voxel_down_sample(PLACE_VOXEL)
        d.estimate_normals(o3d.geometry.KDTreeSearchParamHybrid(radius=PLACE_VOXEL * 2, max_nn=30))
        f = reg.compute_fpfh_feature(d, o3d.geometry.KDTreeSearchParamHybrid(radius=PLACE_VOXEL * 5, max_nn=100))
        feats.append((d, f))
    (s, sf), (t, tf) = feats
    dist = PLACE_VOXEL * 1.5
    r = reg.registration_ransac_based_on_feature_matching(
        s, t, sf, tf, True, dist, reg.TransformationEstimationPointToPoint(False), 3,
        [reg.CorrespondenceCheckerBasedOnEdgeLength(0.9), reg.CorrespondenceCheckerBasedOnDistance(dist)],
        reg.RANSACConvergenceCriteria(1000000, 0.999))
    return r.transformation


def place_segments(clouds, last_poses, gaps, priors=None, prior_gaps=None, ups=None):
    """Joins separately tracked segments into one world frame. clouds[k] is segment k in its own frame (its first
    camera pose = identity), last_poses[k] its last camera pose in that frame, gaps[k] the frames skipped before it,
    priors[k] (optional) a guess of its first camera pose in segment k-1's frame (from bridging odometry), made
    prior_gaps[k] frames before the segment started. ups[k] (optional, from the IMU) is 'up' in segment k's frame: a
    placement that tilts it more than GRAVITY_MAX_DEG from segment 0's is rejected.
    Each segment is registered (multi-scale ICP) against everything placed so far: from the prior, from where the
    previous segment lost the camera, and from a global feature match. A result is only kept if it lies within reach
    of the prior (or, without one, if the camera could physically have moved there during the gap) and enough of the
    segment lands on the model. Returns (world pose or None) and (overlap, note) per segment."""
    world, results = [np.identity(4)], [(1.0, "")]
    placed = o3d.geometry.PointCloud(clouds[0])
    for k in range(1, len(clouds)):
        prev = world[k - 1]
        last_seen = prev @ last_poses[k - 1] if prev is not None else None
        prior = prev @ priors[k] if priors and priors[k] is not None and prev is not None else None
        if prior is not None:
            n = prior_gaps[k] if prior_gaps else 0
            anchor, limit = prior, (PRIOR_LIMIT[0] + MAX_SPEED_M * n, PRIOR_LIMIT[1] + MAX_TURN_DEG * n)
        else:
            anchor = last_seen
            limit = (MOTION_MARGIN[0] + MAX_SPEED_M * gaps[k], MOTION_MARGIN[1] + MAX_TURN_DEG * gaps[k])
        bounded = anchor is not None and limit[1] < 180
        inits = [(x, 16) for x in (prior, last_seen) if x is not None] + [(global_match(clouds[k], placed), 4)]
        best = (None, 0.0, "no plausible match")
        for init, coarsest in inits:
            T, fitness, _ = multiscale_icp(clouds[k], placed, init, coarsest)
            if ups and ups[0] is not None and ups[k] is not None:
                tilt = np.degrees(np.arccos(np.clip(np.dot(T[:3, :3] @ ups[k], ups[0]), -1, 1)))
                if tilt > GRAVITY_MAX_DEG:
                    continue
            note = "global match"
            if bounded:
                m, deg = pose_change(anchor, T)
                if m > limit[0] or deg > limit[1]:
                    continue
                note = f"{m * 100:.0f} cm, {deg:.0f} deg from the {'bridged' if prior is not None else 'last seen'} pose"
            need = NEAR_MIN_FITNESS if bounded else PLACE_MIN_FITNESS
            if fitness >= need and fitness > best[1]:
                best = (T, fitness, note)
        T, fitness, note = best
        world.append(T)
        results.append((fitness, note))
        if T is not None:
            placed += o3d.geometry.PointCloud(clouds[k]).transform(T)
            placed = placed.voxel_down_sample(REG_VOXEL)
    return world, results


def integrate_all(recording, frame_ids, poses, K, depth_scale, depth_max, voxel, block_count, device):
    """Builds the final model from all frames at the given poses. Returns (point cloud, mesh) as tensor geometry."""
    intrinsic = o3c.Tensor(K, o3c.float64)
    slot = {f: i for i, f in enumerate(frame_ids)}
    vbg = new_volume(voxel, block_count, device)
    frames, *_ = open_recording(recording)
    for fid, (depth, color) in enumerate(frames):
        if fid in slot:
            integrate(vbg, depth, color, intrinsic, poses[slot[fid]], depth_scale, depth_max, device)
    pcd, mesh = vbg.extract_point_cloud().cpu(), vbg.extract_triangle_mesh().cpu()
    fix_colors(pcd.point)
    fix_colors(mesh.vertex)
    return pcd, mesh
