#!/usr/bin/env python3
"""
verify_d455_open3d.py
Confirms Open3D can discover, configure, and stream from an Intel RealSense D455,
then writes a test point cloud (.ply), color/depth PNGs, and a short .bag recording.

Usage:
    python verify_d455_open3d.py          # capture and save files
    python verify_d455_open3d.py --view   # also open a 3D viewer window

Close realsense-viewer before running: only one program can hold the camera.
"""
import sys
import numpy as np
import open3d as o3d

WARMUP_FRAMES = 30   # let auto-exposure settle
TEST_FRAMES = 60     # ~2 s at 30 fps
DEPTH_MAX_M = 6.0    # D455 useful range is roughly 0.6-6 m


def main():
    view = "--view" in sys.argv
    print(f"Open3D version: {o3d.__version__}")

    if not hasattr(o3d.t.io, "RealSenseSensor"):
        sys.exit("FAIL: this Open3D build has no RealSense support "
                 "(it was built with BUILD_LIBREALSENSE=OFF).")

    print("\n--- RealSense devices visible to Open3D ---")
    if not o3d.t.io.RealSenseSensor.list_devices():
        sys.exit("FAIL: Open3D found no RealSense device. Close realsense-viewer, "
                 "replug the camera into a USB 3 port, and retry.")

    config = o3d.t.io.RealSenseSensorConfig({
        "serial": "",                       # empty = first device found
        "color_format": "RS2_FORMAT_RGB8",
        "color_resolution": "1280,720",
        "depth_format": "RS2_FORMAT_Z16",
        "depth_resolution": "848,480",      # D455's best-quality depth mode
        "fps": "30",
    })

    rs = o3d.t.io.RealSenseSensor()
    rs.init_sensor(config, 0, "d455_test.bag")
    rs.start_capture(True)                  # True = also record to the .bag file

    md = rs.get_metadata()
    print("\n--- Stream metadata ---")
    print(f"Device:       {md.device_name}  (serial {md.serial_number})")
    print(f"Resolution:   {md.width} x {md.height} @ {md.fps} fps")
    print(f"Depth scale:  {md.depth_scale}  (raw units per metre)")
    print("Intrinsics:\n", md.intrinsics.intrinsic_matrix)

    rgbd = None
    fill_ratios = []
    try:
        for _ in range(WARMUP_FRAMES):
            rs.capture_frame(True, True)    # wait=True, align_depth_to_color=True
        for _ in range(TEST_FRAMES):
            rgbd = rs.capture_frame(True, True)
            d = rgbd.depth.as_tensor().numpy()
            fill_ratios.append(np.count_nonzero(d) / d.size)
    finally:
        rs.stop_capture()

    if rgbd is None or rgbd.depth.is_empty():
        sys.exit("FAIL: no frames received from the camera.")

    depth_m = rgbd.depth.as_tensor().numpy().squeeze().astype(np.float32) / md.depth_scale
    valid = depth_m[depth_m > 0]
    print("\n--- Depth check (last frame) ---")
    print(f"Frames captured:     {len(fill_ratios)}")
    print(f"Avg valid-pixel fill: {100 * np.mean(fill_ratios):.1f}%")
    if valid.size:
        print(f"Depth range:         {valid.min():.2f} m to {valid.max():.2f} m "
              f"(median {np.median(valid):.2f} m)")

    o3d.t.io.write_image("d455_color.png", rgbd.color)
    o3d.t.io.write_image("d455_depth.png", rgbd.depth)

    intrinsic = o3d.core.Tensor(md.intrinsics.intrinsic_matrix, dtype=o3d.core.Dtype.Float64)
    pcd = o3d.t.geometry.PointCloud.create_from_rgbd_image(
        rgbd, intrinsic, depth_scale=md.depth_scale, depth_max=DEPTH_MAX_M)
    # Open3D 0.20 scales uint8 colour by 1/255 twice here; undo the extra scaling
    if "colors" in pcd.point and pcd.point.colors.max().item() <= 1.0 / 255 + 1e-6:
        pcd.point.colors = pcd.point.colors * 255.0
    n_points = pcd.point.positions.shape[0]
    # Save colours as uint8: float colours get divided by 255 again by the legacy PLY reader (open3d draw / view.py)
    if "colors" in pcd.point:
        pcd.point.colors = (pcd.point.colors.clip(0, 1) * 255).to(o3d.core.Dtype.UInt8)
    o3d.t.io.write_point_cloud("d455_test.ply", pcd)

    print("\n--- Output ---")
    print(f"Point cloud points:  {n_points:,}")
    print("Wrote: d455_test.ply, d455_color.png, d455_depth.png, d455_test.bag")

    if n_points == 0:
        sys.exit("FAIL: point cloud is empty (check lens caps, lighting, distance).")
    print("\nPASS: Open3D is reading the D455 and producing point clouds.")

    if view:
        legacy = pcd.to_legacy()
        # RealSense frame is Y-down; flip so the cloud appears upright
        legacy.transform([[1, 0, 0, 0], [0, -1, 0, 0], [0, 0, -1, 0], [0, 0, 0, 1]])
        o3d.visualization.draw_geometries([legacy], window_name="D455 test cloud")


if __name__ == "__main__":
    main()
