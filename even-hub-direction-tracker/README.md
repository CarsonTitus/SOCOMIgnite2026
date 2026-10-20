# Even G2 Direction Tracker

An Even Hub prototype that reads the G2 IMU stream, shows raw X/Y/Z telemetry, and estimates relative turning direction on the glasses and in a companion diagnostic panel.

## Setup

Requires Node.js 20.19+ (or 22.12+), npm, the Even Realities phone app 2.2.10+, and an Even Hub developer-enabled account for real glasses.

```bash
npm install
npm run demo
```

This single command starts Vite and the tester simulator with generated IMU samples. The dashboard opens at `http://localhost:5173/?demo=1`. The tester needs the system package `libwebkit2gtk-4.1-0` and its dependencies. To test on real glasses, start only the development server with `npm run dev`, then use the `evenhub qr` command with the server's LAN address and scan the QR in Prototype Mode in the Even Realities app.

```bash
npx evenhub qr --url http://YOUR_COMPUTER_LAN_IP:5173
```

## Try the tracker

1. Open the app in the simulator or sideload it through the Even Realities app.
2. Check the raw X/Y/Z values while gently turning your head to find which axis responds to yaw.
3. Select that axis, face forward, hold still, and press **Zero / Set Forward**.
4. Turn your head and observe the relative left/right estimate. Change the axis if the sign/direction does not match your movement.
5. For cardinal output, enable the phone compass while holding the phone in the same forward direction as the glasses, or select the manual forward bearing. Zero while facing that direction, then make one known 90° right turn and press **Calibrate +90°** (or double-tap the glasses). Cardinal output combines that anchor with the relative G2 turn estimate.

On glasses, a single tap starts forward zeroing, a double-tap calibrates the cardinal scale, and a long-press switches between **Fixed North** (default) and **User-Centered North**. Fixed North keeps the map and cardinal labels steady while your heading changes. User-Centered North rotates the map and cardinal labels with your turn so your current heading stays at the top. A pair of single-tap events is also detected as a double-tap.

## Sensor limits

The Even Hub SDK documents `imuData.x/y/z` as raw floating-point IMU values. It does not document units or axis orientation. The G2 has no magnetometer, so it cannot establish north on its own. Relative mode integrates the selected axis after zeroing. Cardinal mode requires a compass or manual cardinal anchor plus a known 90° turn to calibrate scale; its estimate can drift and needs validation on the actual glasses. A phone compass is only a valid forward anchor when the phone is held in the same direction as the glasses during zeroing.

SDK and tester dependencies are pinned in `package.json` and `package-lock.json`:

- `@evenrealities/even_hub_sdk` 0.0.16
- `@evenrealities/evenhub-simulator` 0.9.5
- `@evenrealities/evenhub-cli` 0.1.14

Build the app with `npm run build`.
