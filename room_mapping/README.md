# Room Mapping

3D mapping of indoor rooms with an Intel RealSense D455 and Open3D. You record a slow sweep of a room, and the
recording is turned into a coloured 3D mesh / point cloud (`.ply`). It runs fully offline.
It's developed on an Ubuntu PC, and the target is an NVIDIA Jetson Orin Nano worn by the operator, controlled from the Pixel.

## Setup (Ubuntu 22.04, x86_64)

1. **RealSense SDK** from the RealSense apt repo:
   ```bash
   sudo mkdir -p /etc/apt/keyrings
   curl -sSf https://librealsense.realsenseai.com/Debian/librealsenseai.asc | gpg --dearmor | sudo tee /etc/apt/keyrings/librealsenseai.gpg > /dev/null
   echo "deb [signed-by=/etc/apt/keyrings/librealsenseai.gpg] https://librealsense.realsenseai.com/Debian/apt-repo $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/librealsense.list
   sudo apt-get update && sudo apt-get install librealsense2-utils librealsense2-dev
   ```
2. **Python 3.12 environment** (with [uv](https://docs.astral.sh/uv/)):
   ```bash
   cd room_mapping
   uv venv --python 3.12 .venv
   uv pip install --python .venv/bin/python -r requirements.txt
   ```
3. **Camera check.** Plug the D455 into a USB 3 port using a USB 3 cable, then:
   ```bash
   rs-enumerate-devices -s                      # "Usb Type Descriptor" should be 3.x
   .venv/bin/python verify_d455_open3d.py       # should print PASS
   ```

## Usage

```bash
.venv/bin/python capture.py          # live preview: SPACE = start/stop recording, Q = quit
.venv/bin/python reconstruct.py 1                  # recording 001 → output/001_<date>_<time>_mesh.ply (+ _cloud.ply), ~10 s
.venv/bin/python reconstruct.py 1 --loop-closure   # also corrects drift → ..._lc_mesh.ply, ~1 min
.venv/bin/python view.py 1                         # open the model in the Open3D viewer (--lc for the loop-closed one)
.venv/bin/python view.py 1 --slice               # level it, cut 0.5 m off the top (--slice 1.0 etc.), view from the top
.venv/bin/python view.py 1 --measure --slice     # Ctrl+click numbered points, connect pairs for distances (side panel)
.venv/bin/python play.py 1           # play the recording back as colour | depth video
```

Recordings are saved to `recordings/NNN_YYYYMMDD_HHMMSS/`, with depth as lossless PNG and colour as JPEG (~6.5 MB/s).
A realsense-viewer `.db3`/`.bag` can be converted with `convert.py <file>`.

### Recording on a laptop (walking scans)
`capture.py` only needs the camera, not an NVIDIA GPU, so any laptop with a **USB 3 port** works for walking around.
- **Use a USB 3 / Thunderbolt cable.** Many USB-C cables are USB 2 only, and on those the D455 drops to USB 2.1.
  Check with `rs-enumerate-devices -s` (should say 3.x) or the "USB 3.2" label in realsense-viewer.
- **Ubuntu laptop:** same setup as above.
- **Windows laptop:** skip the apt step. `pip install -r requirements.txt` brings the SDK inside `pyrealsense2`.
  Use `.venv\Scripts\python capture.py` instead of `.venv/bin/python capture.py`.
- Reconstruction runs on a laptop too (it falls back to the CPU without CUDA), but it is much slower, and
  `--loop-closure` can take several minutes. Alternatively, copy `recordings/NNN_…` back to the GPU PC and reconstruct there.

**Scanning tips:** move slowly, stay within about 2.5 m of surfaces, overlap your views, and finish where you started
(that's what lets loop closure fix drift). **Close the blinds:** looking out bright windows made tracking fail
for the rest of a scan (006 lost 834 of 1294 frames; 007, same room with blinds closed, lost none).

`recordings/`, `output/` and `samples/` are gitignored. **Don't commit captured data**, because it can show people and sensitive locations.

### Walking through a scan in Unity
1. Export the model: `.venv/bin/python export_unity.py 7 --lc` writes `output/unity/<name>.glb`
   (levelled with the floor at y = 0, decimated to 300k triangles, scan colours with an unlit material; ~2 min).
2. In Unity Hub, create a **Universal 3D** project (Unity 6) **outside this repo**.
3. Window > Package Manager > + > *Add package by name*: `com.unity.cloud.gltfast`.
   The Input System package is already included in new Unity 6 projects.
4. Copy `unity/ScanLoader.cs` and `unity/PlayerController.cs` into the project's `Assets/`.
5. In the scene:
   - Empty GameObject **Scan** + `ScanLoader`; set *Scan Path* to the full path of the `.glb`.
   - Empty GameObject **Player** + `PlayerController` (adds a person-sized CharacterController).
     Optionally add a Capsule child (remove its collider) so you can see yourself in third person.
   - Set PlayerController's *Cam* to the Main Camera, and ScanLoader's *Player* to Player.
6. Press Play. WASD to walk, mouse to look, Shift to run, V for first/third person, Esc to free the mouse.

A build can load other scans without rebuilding: `./RoomWalk -scan /path/to/scan.glb`.
Scans stay outside the Unity project and the repo.

## Files

| File | Purpose |
|---|---|
| `capture.py` | Live preview + start/stop recording |
| `reconstruct.py` | Recording → 3D model (Open3D dense RGB-D SLAM, uses CUDA if available) |
| `loop_closure.py` | Drift correction for `reconstruct.py --loop-closure` (fragment pose graph) |
| `view.py` / `play.py` | View a model / play back a recording |
| `export_unity.py` | Model → levelled, decimated `.glb` for Unity |
| `unity/*.cs` | Unity scripts: load a scan at runtime, first/third-person walking |
| `convert.py` | realsense-viewer `.db3`/`.bag` → compressed recording folder |
| `rgbd_io.py` | Shared recording reader/writer |
| `verify_d455_open3d.py` | Camera + Open3D sanity check |

## Known issues

- Tracking drifts a few cm over long scans. This is a systematic bias in Open3D's frame-to-model tracking that grows with voxel size.
  `--loop-closure` corrects most of it when the scan revisits places (end your sweep where you started).
- `pyrealsense2` must match the installed SDK version (2.58.4).
