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
.venv/bin/python reconstruct.py 1    # recording 001 → output/001_<date>_<time>_mesh.ply (+ _cloud.ply)
.venv/bin/python view.py 1           # open the model in the Open3D viewer
.venv/bin/python play.py 1           # play the recording back as colour | depth video
```

Recordings are saved to `recordings/NNN_YYYYMMDD_HHMMSS/`, with depth as lossless PNG and colour as JPEG (~6.5 MB/s).
A realsense-viewer `.db3`/`.bag` can be converted with `convert.py <file>`.

**Scanning tips:** move slowly, stay within about 2.5 m of surfaces, and overlap your views.

`recordings/`, `output/` and `samples/` are gitignored. **Don't commit captured data**, because it can show people and sensitive locations.

## Files

| File | Purpose |
|---|---|
| `capture.py` | Live preview + start/stop recording |
| `reconstruct.py` | Recording → 3D model (Open3D dense RGB-D SLAM, uses CUDA if available) |
| `view.py` / `play.py` | View a model / play back a recording |
| `convert.py` | realsense-viewer `.db3`/`.bag` → compressed recording folder |
| `rgbd_io.py` | Shared recording reader/writer |
| `verify_d455_open3d.py` | Camera + Open3D sanity check |

## Known issues

- Tracking drifts a few cm over long scans, especially when surfaces are more than 3 m away. Loop closure is planned.
- `pyrealsense2` must match the installed SDK version (2.58.4).
