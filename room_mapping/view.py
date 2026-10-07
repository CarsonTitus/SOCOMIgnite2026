#!/usr/bin/env python3
"""
view.py
Opens a reconstructed model in the Open3D viewer, by recording number.

Usage:
    python view.py 1              # output/001_<date>_<time>_mesh.ply
    python view.py 1 --cloud      # the point cloud instead of the mesh
    python view.py 1 --lc         # the loop-closed model (reconstruct.py --loop-closure)
    python view.py 1 --slice      # level the model, cut 0.5 m off the top (the ceiling), view from the top
    python view.py 1 --slice 1.0  # same, cut 1.0 m off the top
    python view.py 1 --measure    # Ctrl+click surfaces for distances (from the viewpoint and between clicks);
                                  # 1 = orbit, 2 = fly (WASD, Q up, Z down). Combine with --slice to remove the ceiling
    python view.py some/file.ply
"""
import argparse
import subprocess
import sys
from pathlib import Path

import numpy as np

OUTPUT = Path(__file__).parent / "output"


def find_up(normals, seed=(0, -1, 0)):
    """Vertical direction = the mean normal of the floor/ceiling surfaces. The seed is camera up in the first frame
    (-y); refine from a wide cone to a narrow one so a tilted start frame (often 10-20 deg) still converges."""
    up = np.asarray(seed, float)
    for tol in (35, 20, 10, 5):
        c = normals @ up
        sel = np.abs(c) > np.cos(np.radians(tol))
        up = (normals[sel] * np.sign(c[sel])[:, None]).sum(0)
        up /= np.linalg.norm(up)
    return up


def floor_and_ceiling(heights):
    """Lowest and highest heights with a big share of horizontal surface (5 cm bins), so stray points above the
    ceiling or under the floor are ignored."""
    counts, edges = np.histogram(heights, bins=np.arange(heights.min(), heights.max() + 0.05, 0.05))
    big = np.flatnonzero(counts >= 0.2 * counts.max())
    return edges[big[0]], edges[big[-1] + 1]


def level(points, normals):
    """Rotation that puts up on +y, and the floor and ceiling heights along it."""
    up = find_up(normals)
    x = np.cross(up, [0, 0, 1])
    x /= np.linalg.norm(x)
    R = np.stack([x, up, np.cross(x, up)])  # rows: new x, y (up), z
    horiz = np.abs(normals @ up) > 0.95
    return R, *floor_and_ceiling(points[horiz] @ up)


def load_level(path, off_top=None):
    """Load a model, level it (floor at y = 0, up = +y) and, with off_top, cut that many metres off below the ceiling."""
    import open3d as o3d
    mesh = o3d.io.read_triangle_mesh(str(path))
    if len(mesh.triangles):
        geom = mesh
        mesh.compute_vertex_normals()
        normals = np.asarray(mesh.vertex_normals)
    else:
        geom = o3d.io.read_point_cloud(str(path))
        small = geom.voxel_down_sample(0.05)
        small.estimate_normals()
        normals = np.asarray(small.normals)
    R, floor, ceiling = level(np.asarray(geom.vertices if geom is mesh else small.points), normals)
    geom.rotate(R, center=(0, 0, 0))
    geom.translate((0, -floor, 0))
    print(f"Ceiling {ceiling - floor:.2f} m above floor")
    if off_top is not None:
        lo, hi = geom.get_min_bound(), geom.get_max_bound()
        cut = ceiling - floor - off_top
        print(f"Showing 0 to {cut:.2f} m")
        geom = geom.crop(o3d.geometry.AxisAlignedBoundingBox((lo[0], -0.2, lo[2]), (hi[0], cut, hi[2])))
    return geom


def view_top_down(path, geom):
    import open3d as o3d
    centre = geom.get_center()
    centre[1] = 0
    # legacy viewer: o3d.visualization.draw() segfaulted in-process on the Windows laptop (Open3D 0.20)
    o3d.visualization.draw_geometries([geom], window_name=path.name, lookat=centre, front=(0, 1, 0), up=(0, 0, -1),
                                      zoom=0.7, mesh_show_back_face=True)


def view_measure(path, geom):
    """Ctrl+click surfaces to drop numbered points; connect any two from the side panel to measure between them.
    1 = orbit, 2 = fly (WASD, Q up, Z down)."""
    import open3d as o3d
    from open3d.visualization import gui, rendering
    app = gui.Application.instance
    app.initialize()
    win = app.create_window(f"{path.name} - Ctrl+click to measure", 1600, 900)
    em = win.theme.font_size
    scene = gui.SceneWidget()
    scene.scene = rendering.Open3DScene(win.renderer)
    scene.scene.set_background([1, 1, 1, 1])
    mat = rendering.MaterialRecord()
    mat.shader = "defaultUnlit"  # show the scanned colours as recorded
    scene.scene.add_geometry("model", geom, mat)
    bounds = scene.scene.bounding_box
    scene.setup_camera(60, bounds, bounds.get_center())
    red = rendering.MaterialRecord()
    red.shader = "defaultUnlit"
    red.base_color = (1, 0, 0, 1)
    blue = rendering.MaterialRecord()
    blue.shader = "unlitLine"
    blue.base_color = (0, 0.3, 1, 1)
    blue.line_width = 3

    info = gui.Label("Ctrl+click = add point   1 = orbit   2 = fly (WASD move, Q up, Z down, drag to look)")
    status = gui.Label("")
    panel = gui.ScrollableVert(0.4 * em, gui.Margins(0.6 * em, 0.6 * em, 0.6 * em, 0.6 * em))
    chain = gui.Checkbox("Chain: connect each new point to the previous")
    a_edit, b_edit = gui.NumberEdit(gui.NumberEdit.INT), gui.NumberEdit(gui.NumberEdit.INT)
    connect_btn, undo_btn, clear_btn = gui.Button("Connect"), gui.Button("Remove last point"), gui.Button("Clear all")
    points_text, links_text = gui.Label(""), gui.Label("")
    row = gui.Horiz(0.4 * em)
    for w in (gui.Label("Connect"), a_edit, gui.Label("to"), b_edit, connect_btn):
        row.add_child(w)
    buttons = gui.Horiz(0.4 * em)
    buttons.add_child(undo_btn)
    buttons.add_child(clear_btn)
    for w in (chain, row, buttons, status, gui.Label("POINTS    #   from view   height"), points_text,
              gui.Label("MEASUREMENTS"), links_text):
        panel.add_child(w)
    win.add_child(scene)
    win.add_child(info)
    win.add_child(panel)

    def on_layout(ctx):
        r, pw = win.content_rect, 32 * em
        scene.frame = gui.Rect(r.x, r.y, r.width - pw, r.height)
        panel.frame = gui.Rect(r.get_right() - pw, r.y, pw, r.height)
        info.frame = gui.Rect(r.x + 8, r.y + 8, r.width - pw - 16,
                              info.calc_preferred_size(ctx, gui.Widget.Constraints()).height)
    win.set_on_layout(on_layout)

    points = []  # (xyz, distance from viewpoint when clicked, number label)
    links = []   # (i, j, distance, horizontal, vertical, distance label)

    def refresh():
        points_text.text = "\n".join(f"{i + 1:>4}    {d:6.2f} m    {p[1]:5.2f} m"
                                     for i, (p, d, _) in enumerate(points)) or "(none)"
        links_text.text = "\n".join(f"{i + 1} - {j + 1}:   {d:.2f} m   (horiz {h:.2f}, vert {v:.2f})"
                                    for i, j, d, h, v, _ in links) or "(none)"
        win.set_needs_layout()

    def redraw_lines():
        """One LineSet for all connections, so removing points/links never leaves stray lines."""
        scene.scene.remove_geometry("links")
        if links:
            pts = [points[k][0] for i, j, *_ in links for k in (i, j)]
            idx = [[2 * n, 2 * n + 1] for n in range(len(links))]
            scene.scene.add_geometry("links", o3d.geometry.LineSet(o3d.utility.Vector3dVector(pts),
                                                                   o3d.utility.Vector2iVector(idx)), blue)

    def connect(i, j):
        if not (0 <= i < len(points) and 0 <= j < len(points)) or i == j:
            status.text = f"Pick two different points between 1 and {len(points)}"
            return
        p, q = points[i][0], points[j][0]
        d = np.linalg.norm(q - p)
        lbl = scene.add_3d_label((p + q) / 2, f"{d:.2f} m")
        lbl.color = gui.Color(0, 0.3, 1)
        links.append((i, j, d, np.linalg.norm((q - p)[[0, 2]]), abs(q[1] - p[1]), lbl))
        status.text = f"{i + 1} - {j + 1}: {d:.2f} m"
        print(status.text)
        redraw_lines()
        refresh()

    def add_point(point, eye):
        n = len(points)
        scene.scene.add_geometry(f"pt{n}", o3d.geometry.TriangleMesh.create_sphere(0.03).translate(point), red)
        lbl = scene.add_3d_label(point + (0, 0.08, 0), str(n + 1))
        lbl.color = gui.Color(1, 0, 0)
        points.append((point, np.linalg.norm(point - eye), lbl))
        status.text = f"Point {n + 1}: {points[-1][1]:.2f} m from viewpoint, {point[1]:.2f} m above floor"
        print(status.text)
        a_edit.int_value, b_edit.int_value = max(n, 1), n + 1  # ready to connect the two newest
        if chain.checked and n > 0:
            connect(n - 1, n)
        refresh()

    def remove_last():
        if not points:
            return
        n = len(points) - 1
        for link in [l for l in links if n in l[:2]]:
            scene.remove_3d_label(link[-1])
            links.remove(link)
        scene.scene.remove_geometry(f"pt{n}")
        scene.remove_3d_label(points.pop()[2])
        redraw_lines()
        status.text = f"Removed point {n + 1}"
        refresh()

    def clear():
        while points:
            remove_last()
        status.text = "Cleared"

    connect_btn.set_on_clicked(lambda: connect(int(a_edit.int_value) - 1, int(b_edit.int_value) - 1))
    undo_btn.set_on_clicked(remove_last)
    clear_btn.set_on_clicked(clear)
    refresh()

    def on_mouse(e):
        if not (e.type == gui.MouseEvent.Type.BUTTON_DOWN and e.is_modifier_down(gui.KeyModifier.CTRL)):
            return gui.Widget.EventCallbackResult.IGNORED
        x, y = e.x - scene.frame.x, e.y - scene.frame.y

        def on_depth(depth):
            d = np.asarray(depth)
            z = d[min(y, d.shape[0] - 1), min(x, d.shape[1] - 1)]  # view-space depth in metres, inf = nothing
            if not np.isfinite(z):
                app.post_to_main_thread(win, lambda: setattr(status, "text", "No surface there"))
                return
            cam = scene.scene.camera
            w, h = scene.frame.width, scene.frame.height
            # pixel -> ray in camera space via the inverse projection (camera looks down -z), then scale to the depth
            ndc = np.array([2 * (x + 0.5) / w - 1, 1 - 2 * (y + 0.5) / h, 0.5, 1.0])  # any point on the ray; 1.0 can be at infinity
            ray = np.linalg.inv(np.asarray(cam.get_projection_matrix())) @ ndc
            ray = ray[:3] / ray[3]
            model = np.asarray(cam.get_model_matrix())  # camera -> world
            point = (model @ np.append(ray * (abs(z) / -ray[2]), 1.0))[:3]
            eye = model[:3, 3]
            if not np.all(np.isfinite(point)):
                app.post_to_main_thread(win, lambda: setattr(status, "text", "Couldn't place that point, try again"))
                return
            app.post_to_main_thread(win, lambda: add_point(point, eye))
        scene.scene.scene.render_to_depth_image(on_depth)
        return gui.Widget.EventCallbackResult.HANDLED
    scene.set_on_mouse(on_mouse)

    def on_key(e):
        if e.type == gui.KeyEvent.Type.DOWN and e.key in (gui.KeyName.ONE, gui.KeyName.TWO):
            scene.set_view_controls(gui.SceneWidget.Controls.ROTATE_CAMERA if e.key == gui.KeyName.ONE
                                    else gui.SceneWidget.Controls.FLY)
            return gui.Widget.EventCallbackResult.HANDLED
        return gui.Widget.EventCallbackResult.IGNORED
    scene.set_on_key(on_key)
    app.run()


def main():
    ap = argparse.ArgumentParser(usage=__doc__)
    ap.add_argument("model")
    ap.add_argument("--cloud", action="store_true")
    ap.add_argument("--lc", action="store_true")
    ap.add_argument("--slice", type=float, nargs="?", const=0.5, metavar="METRES_OFF_TOP")
    ap.add_argument("--measure", action="store_true")
    args = ap.parse_args()
    arg, kind = args.model, ("lc_" if args.lc else "") + ("cloud" if args.cloud else "mesh")
    if Path(arg).exists():
        path = Path(arg)
    elif arg.isdigit():
        matches = sorted(OUTPUT.glob(f"{int(arg):03d}_????????_??????_{kind}.ply"))
        if not matches:
            sys.exit(f"No {kind} for recording {int(arg):03d} in output/. "
                     f"Build it with: .venv/bin/python reconstruct.py {arg}"
                     + (" --loop-closure" if args.lc else ""))
        path = matches[0]
    else:
        sys.exit(f"'{arg}' is not a file or a recording number")
    print(f"Opening {path}")
    if args.measure:
        view_measure(path, load_level(path, args.slice))
    elif args.slice is not None:
        view_top_down(path, load_level(path, args.slice))
    else:
        subprocess.run([str(Path(sys.executable).parent / "open3d"), "draw", str(path)])


if __name__ == "__main__":
    main()
