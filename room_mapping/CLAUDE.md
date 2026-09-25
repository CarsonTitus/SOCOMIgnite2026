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
- `view.py`: open a model by number (`view.py 1`, `--cloud` for the point cloud)
- `play.py`, `reconstruct.py` and `view.py` all accept a recording number (`1`/`001`) or a path
- `rgbd_io.py`: shared recording reader/writer (folder, .db3, .bag). Folder layout is Open3D's standard RGB-D dataset format.

## Workflow
1. `.venv/bin/python capture.py`: record a slow sweep of the room
2. `.venv/bin/python reconstruct.py <number>`: build the model
3. `.venv/bin/python view.py <number>`: view it

## Gotchas
- Open3D 0.20 `t.PointCloud.create_from_rgbd_image` with uint8 colour scales colours by 1/255 twice, so they come out black. `verify_d455_open3d.py` corrects for this.
- Tracking drift (2026-09-23 stationary test, scene about 3.2 m away): 1 cm voxel drifted about 8 cm in 10 s; 5 mm voxel + depth-max 3 m about 5 cm.
  Raw depth was stable (about 4 mm change), so the drift is frame-to-model tracking noise building up. Bilateral pre-filter: no effect.
  Hybrid (colour) tracking: loses tracking immediately. 5 mm voxel on the full room scan peaked at 11.5 GB GPU memory, too much for
  the Jetson's 8 GB shared memory. Keep 1 cm there. Next steps: scan within about 2.5 m; add pose-graph/loop closure.
- `pyrealsense2` (pip) must match the apt SDK version (currently 2.58.4).

## Notes
- Keep code portable to the Jetson (ARM64, JetPack/L4T). Avoid x86-only dependencies.
- Open3D's pip wheels may not include RealSense support on every platform. Check this on the Jetson.

## Next steps
1. Loop closure / pose-graph optimisation to fix tracking drift (see Gotchas)
2. Live reconstruction on the Jetson (no raw recording), control from the Pixel over the hotspot
