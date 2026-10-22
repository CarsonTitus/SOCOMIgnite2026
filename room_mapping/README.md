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
.venv/bin/python view.py 1                         # open the model (loop-closed if built; --plain for the original)
.venv/bin/python view.py 1 --slice               # level it, cut 0.5 m off the top (--slice 1.0 etc.), view from the top
.venv/bin/python view.py 1 --measure --slice     # Ctrl+click numbered points, connect pairs for distances (side panel)
.venv/bin/python play.py 1           # play the recording back as colour | depth video
.venv/bin/python diagnose.py 7 8     # compare recordings: timing, usable depth, tracking quality → output/diagnose/007_008.png
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

**Windows and far space:** frames with under 30% of the image within 3 m (`--weak-usable`) aren't tracked against the
model or built into it; the camera pose is carried across them ("bridged", up to 3 s) by colour + depth frame-to-frame
odometry and, if the recording has IMU data, the gyro. If the model doesn't match when depth comes back, a new segment
starts from the bridged pose and is registered against the earlier geometry (`place_segments`; with an IMU, joins that
would tilt the room are rejected). `--depth-max` sets the model range (default 3 m; 5 m made 008 noisy and
shattered), `--track-depth-max` the tracking range (3 m). `--no-bridge` / `--no-imu` turn those parts off.

**IMU:** `capture.py` records the D455's gyro + accelerometer to `imu.csv` (in the recording folder; `--no-imu` to skip).
`reconstruct.py` prints an "IMU check" line comparing the gyro with tracking: it should differ by far less than the
tracking turn per frame. The Android app doesn't record the IMU yet.

**Scanning tips:** move slowly, stay within about 2.5 m of surfaces, overlap your views, and finish where you started
(that's what lets loop closure fix drift). **Close the blinds:** looking out bright windows made tracking fail
for the rest of a scan (006 lost 834 of 1294 frames; 007, same room with blinds closed, lost none).

`recordings/`, `output/` and `samples/` are gitignored. **Don't commit captured data**, because it can show people and sensitive locations.

### Walking through a scan in Unity
1. Export the model: `.venv/bin/python export_unity.py 7` writes `output/unity/<name>.glb`
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

### Recording on the Pixel (Android app, in progress)
`android/` is a small standalone app (not part of PSYOP-VISR) that does what `capture.py` does: live colour | depth
preview and REC/STOP. Recordings use the same folder layout and settings, so `reconstruct.py`, `view.py` and
`export_unity.py` work on them unchanged. The phone needs an all-USB-3.2 C-to-C cable to the D455.

**Build (Ubuntu, no Android Studio needed).** Toolchain under `~/Android`: JDK 17 (Temurin), SDK command-line tools,
then `sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;28.0.13004108" "cmake;3.22.1"`.
```bash
cd android
./build_librealsense_aar.sh              # once: builds librealsense 2.58.4's AAR from source (~5 min) into app/libs/
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
JAVA_HOME=$(ls -d ~/Android/jdk-17*) ./gradlew assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
```

**Install over WiFi** (the phone's USB-C port is used by the camera). On the phone: Settings > System >
Developer options > *Wireless debugging* > *Pair device with pairing code*. Then, with the phone and PC on the same network:
```bash
ADB=~/Android/Sdk/platform-tools/adb     # the apt adb is too old for wireless pairing
$ADB pair <ip>:<pairing port>            # enter the 6-digit code
$ADB connect <ip>:<port shown on the Wireless debugging screen>
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
```

**Use.** Open *D455 Recorder*, plug in the camera, allow USB and camera access. Wait for READY, then tap REC/STOP.
Recordings go to `Android/data/com.socom.d455recorder/files/recordings/NNN_…` on the phone (~440 MB per minute).
Copy one to the PC, renumbering so it doesn't clash with PC recordings:
```bash
$ADB pull /sdcard/Android/data/com.socom.d455recorder/files/recordings/001_20261014_101500 recordings/008_20261014_101500
```
`meta.json` has `"device": "android"`. Watch the *dropped* count on screen: if the phone can't keep up, the queue fills and frames are dropped.

## Files

| File | Purpose |
|---|---|
| `capture.py` | Live preview + start/stop recording |
| `reconstruct.py` | Recording → 3D model (Open3D dense RGB-D SLAM, uses CUDA if available) |
| `loop_closure.py` | Drift correction for `reconstruct.py --loop-closure` (fragment pose graph) |
| `diagnose.py` | Compare recordings: metadata vs frames, timing, usable depth, tracking quality (+ plot) |
| `compare.py` | Score a model against a reference model of the same room (share of surface within 3 / 5 / 10 cm) |
| `view.py` / `play.py` | View a model / play back a recording |
| `export_unity.py` | Model → levelled, decimated `.glb` for Unity |
| `unity/*.cs` | Unity scripts: load a scan at runtime, first/third-person walking |
| `android/` | Pixel recorder app (same recording format as `capture.py`) |
| `convert.py` | realsense-viewer `.db3`/`.bag` → compressed recording folder |
| `rgbd_io.py` | Shared recording reader/writer |
| `verify_d455_open3d.py` | Camera + Open3D sanity check |

## Known issues

- Tracking drifts a few cm over long scans. This is a systematic bias in Open3D's frame-to-model tracking that grows with voxel size.
  `--loop-closure` corrects most of it when the scan revisits places (end your sweep where you started).
- `pyrealsense2` must match the installed SDK version (2.58.4).
