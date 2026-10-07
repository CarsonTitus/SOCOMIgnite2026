"""Check view.level(): a tilted, shifted box room comes out with the floor at y = 0 and the ceiling
found at its height.
Run: .venv/bin/python test_view.py"""
import numpy as np
from view import level

rng = np.random.default_rng(0)
n, H = 20000, 2.6
floor = np.c_[rng.uniform(0, 5, n), np.zeros(n), rng.uniform(0, 4, n)]
ceiling = floor + (0, H, 0)
wall = np.c_[rng.uniform(0, 5, n), rng.uniform(0, H, n), np.zeros(n)]
pts = np.vstack([floor, ceiling, wall])
nrm = np.vstack([np.tile((0, 1, 0), (n, 1)), np.tile((0, -1, 0), (n, 1)), np.tile((0, 0, 1), (n, 1))]).astype(float)

# camera-style frame: y down, start frame tilted 15 deg, room 1.6 m below the camera
a = np.radians(15)
tilt = np.array([[1, 0, 0], [0, np.cos(a), -np.sin(a)], [0, np.sin(a), np.cos(a)]])
flip = np.diag([1, -1, -1])
M = tilt @ flip
pts, nrm = (pts - (0, 1.6, 0)) @ M.T, nrm @ M.T

R, fl, ce = level(pts, nrm)
y = (pts @ R.T)[:, 1] - fl
assert abs(np.median(y[:n])) < 0.03, np.median(y[:n])
assert abs(ce - fl - H) < 0.1, ce - fl
assert abs(np.median(y[n:2 * n]) - H) < 0.03, np.median(y[n:2 * n])
print("ok: floor", round(float(np.median(y[:n])), 3), "ceiling", round(float(np.median(y[n:2 * n])), 3))
