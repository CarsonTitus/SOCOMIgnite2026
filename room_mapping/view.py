#!/usr/bin/env python3
"""
view.py
Opens a reconstructed model in the Open3D viewer, by recording number.

Usage:
    python view.py 1            # output/001_<date>_<time>_mesh.ply
    python view.py 1 --cloud    # the point cloud instead of the mesh
    python view.py 1 --lc       # the loop-closed model (reconstruct.py --loop-closure)
    python view.py some/file.ply
"""
import subprocess
import sys
from pathlib import Path

OUTPUT = Path(__file__).parent / "output"


def main():
    args = [a for a in sys.argv[1:] if a not in ("--cloud", "--lc")]
    if len(args) != 1:
        sys.exit(__doc__)
    arg, kind = args[0], "cloud" if "--cloud" in sys.argv else "mesh"
    if "--lc" in sys.argv:
        kind = "lc_" + kind
    if Path(arg).exists():
        path = Path(arg)
    elif arg.isdigit():
        matches = sorted(OUTPUT.glob(f"{int(arg):03d}_????????_??????_{kind}.ply"))
        if not matches:
            sys.exit(f"No {kind} for recording {int(arg):03d} in output/. "
                     f"Build it with: .venv/bin/python reconstruct.py {arg}"
                     + (" --loop-closure" if kind.startswith("lc_") else ""))
        path = matches[0]
    else:
        sys.exit(f"'{arg}' is not a file or a recording number")
    print(f"Opening {path}")
    subprocess.run([str(Path(sys.executable).parent / "open3d"), "draw", str(path)])


if __name__ == "__main__":
    main()
