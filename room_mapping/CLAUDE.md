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
- `view.py`: open a model by number (`view.py 1`, `--cloud` for the point cloud; uses the loop-closed model when it exists, `--plain` for the original,
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
3. `.venv/bin/python view.py <number>`: view it (loop-closed by default: the plain model curves in toward the ceiling)

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

## Status and handoff (2026-10-14)
Work moves between the Windows laptop (recording; has the D455) and the Ubuntu tower (GPU). Claude Code sessions and
memory don't follow between machines, so this section is the handoff.

- **Use the loop-closed model** for viewing, measuring and exporting (now the default in `view.py` / `export_unity.py`;
  `--plain` for the original). On 007 the plain model curves in toward the ceiling; the loop-closed one looks right.
- **Scans so far:** 007 (one room, blinds closed) is the good one: all 1354 frames tracked; loop-closed end gap 0.22 m.
  006 lost 834/1294 frames looking out bright windows. Recordings live only on the laptop (gitignored).
  Not yet checked against a tape measure.
- **Unity walkthrough (issue #37):** `export_unity.py` + `unity/*.cs` pushed, progress commented on the issue.
  C# not yet compiled; Unity is to be set up on the tower.
- **D455 → Pixel 9 Pro XL over USB-C works** (2026-10-14), with the team's all-USB-3.2 C-to-C cable + dongle
  (the camera didn't work on a non-3.2 setup). Tested with Dot3D Free 6.4.2: USB permission prompt, live feed, scan,
  no disconnects over 1 min scan + 5 min running; battery 19% → 18% in low-power mode. Dot3D Free only saves `.dpX`,
  DotProduct's format: tagged records + ~60 blocks of ~350 KB that are compressed/encrypted (entropy 7.995; not
  zlib/deflate/lzma/bz2/LZ4/zstd). Not usable without Dot3D Pro export, so don't spend more time on it.

## Next steps
1. **Android D455 recorder (in progress, build on the tower):** a separate small app first, not inside PSYOP-VISR.
   - Live colour + depth preview, start/stop; record into the same folder layout as `capture.py` / `rgbd_io.py`
     (depth 16-bit PNG, colour JPEG, `intrinsic.json`, `meta.json` with depth_scale, fps, timestamps, dropped)
     at 848x480 depth / 640x480 colour @ 30 fps, so `reconstruct.py --loop-closure`, `view.py --measure` and
     `export_unity.py` work unchanged on phone recordings.
   - Use librealsense's Android support (`wrappers/android`; match SDK 2.58.x). Check whether a prebuilt `.aar` is
     available for that version, otherwise build it with the NDK.
   - The tower has never built Android apps: needs Android Studio (or JDK 17 + Android SDK command-line tools),
     the NDK if librealsense is built from source, and platform-tools (`adb`). The user will install what's needed.
   - Install/debug over **Wireless debugging** (`adb pair` / `adb connect`), because the phone's USB-C port is in use
     by the camera.
   - Getting recordings off the phone: the laptop doesn't show the phone over USB (work laptop); Google Drive worked.
     Expect ~440 MB per minute of recording.
2. Unity on the tower: create the project per README, test `unity/*.cs` with 007, check vertex colours (glTFast unlit).
3. Tape-measure check of 007 (room dimensions, a door, a table) against `view.py 7 --measure`.
4. Multi-room / hallway scan ending at the start, to test loop closure over a longer path (blinds closed, walk slowly,
   keep features in view; may need `--voxel 0.02` or more `--block-count` memory).
5. Later: live reconstruction on the Jetson, control from the Pixel over the hotspot; a fix for the frame-to-model bias.
