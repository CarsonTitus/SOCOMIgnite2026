# room_mapping — Wearable Post-Raid Indoor Room Mapping

Part of the SOCOMIgnite2026 repo (the rest of the repo is the PSYOP-VISR Android app for the Pixel + G2 glasses).
All commands below run from this `room_mapping/` folder.

## Purpose
A wearable system for mapping indoor rooms in 3D after a raid, using depth-camera reconstruction.

## Hardware
- **Depth camera:** Intel RealSense D455, connected over USB (must be USB 3 for full depth/color streams)
- **Compute (future deployment target):** NVIDIA Jetson Orin Nano 8GB
- **Operator interface:** Pixel 9 Pro XL, connected over a local WiFi hotspot
- **Dev machine:** Ubuntu 22.04 x86_64 PC with NVIDIA GPU, used for development before porting to the Jetson

## Software
- **SDK:** librealsense2 from the RealSense AI apt repo (`librealsense.realsenseai.com`),
  signing key at `/etc/apt/keyrings/librealsenseai.gpg`
- **Reconstruction:** Open3D, capturing through `o3d.t.io.RealSenseSensor`
- **Output:** point clouds / meshes saved as `.ply` / `.pcd`
- **Runtime constraint:** fully offline, with no internet or cloud dependencies at runtime
- **Python env:** `.venv/` in this folder, Python 3.12 via uv, packages from `requirements.txt`

## Files
- `verify_d455_open3d.py`: sanity check that Open3D can open and stream from the D455
- `recordings/`, `output/`, `samples/`: local data, gitignored. **Never commit captured data**: it is large and may show
  people or sensitive locations.
- `recordings/`: raw camera recordings. realsense-viewer 2.58 saves ROS2 `.db3` (SQLite) files, not ROS1 `.bag`,
  and Open3D's `RSBagReader` can't read them. Read them with `pyrealsense2` playback instead.
- `samples/`: Open3D sample data for testing the viewer
- `reconstruct.py`: recording (.db3/.bag) → room model using Open3D tensor dense SLAM (CUDA if available).
  Writes `output/<name>_cloud.ply`, `_mesh.ply`, `_poses.npy`. Run: `.venv/bin/python reconstruct.py <recording>`
- `output/`: reconstruction results
- `capture.py`: live preview (SPACE = start/stop, Q = quit) → `recordings/NNN_YYYYMMDD_HHMMSS/` (e.g. 001_20260923_095336) folder with depth PNG and colour JPEG
  at 848×480 depth / 640×480 colour @ 30 fps, about 6.5 MB/s. `--seconds N` records headless.
- `convert.py`: realsense-viewer .db3/.bag → compressed recording folder (about 14× smaller, same reconstruction)
- `play.py`: play back a recording as colour | depth video
- `view.py`: open a model by number (`view.py 1`, `--cloud` for the point cloud, `--lc` for the loop-closed model,
  `--slice [M]` levels it from floor/ceiling normals, cuts M m (default 0.5) off below the ceiling and opens top-down; checked by `test_view.py`)
  `--measure`: Open3D gui window, Ctrl+click drops numbered points (distance from viewpoint, height), side panel
  connects pairs (distance, horizontal, vertical); 1 = orbit, 2 = fly (WASD, Q up, Z down)
- `export_unity.py`: model → `output/unity/<name>.glb` for Unity (levelled via view.load_level, quadric-decimated,
  sRGB → linear COLOR_0, KHR_materials_unlit). Writes the glb itself: Open3D 0.20's glb writer made unreadable files.
  Checked by `test_export_unity.py`. `unity/*.cs`: runtime loader (glTFast) + first/third-person controller (issue #37)
- `loop_closure.py`: fragment pose-graph loop closure used by `reconstruct.py --loop-closure` (writes `*_lc_*` outputs)
- `play.py`, `reconstruct.py` and `view.py` all accept a recording number (`1`/`001`) or a path
- `rgbd_io.py`: shared recording reader/writer (folder, .db3, .bag). Folder layout is Open3D's standard RGB-D dataset format.

## Workflow
1. `.venv/bin/python capture.py`: record a slow sweep of the room
2. `.venv/bin/python reconstruct.py <number> --loop-closure`: build the model (drop the flag for a fast preview)
3. `.venv/bin/python view.py <number> --lc`: view it

## Gotchas
- Open3D 0.20 `t.PointCloud.create_from_rgbd_image` with uint8 colour scales colours by 1/255 twice, so they come out black. `verify_d455_open3d.py` corrects for this.
- Tracking drift is a **systematic bias in Open3D's frame-to-model tracking** (t.pipelines.slam), not camera noise.
  On a stationary recording (003) the camera "moves" about 7-9 voxels in a consistent direction near the (+1,+1,+1)
  grid diagonal: 14 cm at 2 cm voxels, 7.7 cm at 1 cm, 4.7 cm at 5 mm. This looks like a half-voxel raycast offset.
  Frame-to-frame RGB-D odometry is worse (18-44 cm), so frame-to-model stays. The fix is `--loop-closure`
  (loop_closure.py): 003 drift 7.7 → 3.2 cm (15-frame chunks); on room sweep 001 the end-to-start surface gap
  went from a median 2.9 cm to 0.9 cm (30-frame chunks, the default; 61 s). Bias inside a chunk remains.
  Bilateral pre-filter: no effect. Hybrid (colour) tracking in the SLAM model: loses tracking. 5 mm voxels on 001
  need 11.5 GB GPU memory, too much for the Jetson; keep 1 cm.
- Windows in view break tracking: on 006 frame-to-model tracking failed looking out bright windows and never recovered
  (834/1294 frames lost); `--depth-max 6` didn't help (465 tracked). Blinds closed (007): 0 lost. Tracking can't re-acquire after a loss.
- `o3d.visualization.draw()` segfaulted in-process on the Windows laptop; `view.py --slice` uses legacy `draw_geometries`.
- `Scene.render_to_depth_image` (0.20, Windows/Vulkan) returns view-space depth in metres with inf for background,
  not 0-1, and `Camera.unproject` gave NaN; `--measure` builds the ray from the inverse projection matrix instead.
  The gui window exits with code 127 on close (Python finalisation error); harmless.
- `pyrealsense2` (pip) must match the apt SDK version (currently 2.58.4).

## Notes
- Keep code portable to the Jetson (ARM64, JetPack/L4T). Avoid x86-only dependencies.
- Open3D's pip wheels may not include RealSense support on every platform. Check this on the Jetson.

## Next steps
1. Loop closure is done. Possible next step: a fix for the frame-to-model bias itself (e.g. compensate the half-voxel offset)
2. Live reconstruction on the Jetson (no raw recording), control from the Pixel over the hotspot
