# Even G2 Direction Tracker

An Even Hub prototype that reads the G2 IMU stream, shows raw X/Y/Z telemetry, and estimates relative turning direction on the glasses and in a companion diagnostic panel.

## Setup

Requires Node.js 20.19+ (or 22.12+), npm, the Even Realities phone app 2.2.10+, and an Even Hub developer-enabled account for real glasses.

```bash
npm install
npm run dev
```

To preview the glasses display with the included tester simulator, start the development server and then run `npm run simulator` in another terminal. This host needs the system package `libwebkit2gtk-4.1-0` and its dependencies. To test on real glasses, use the `evenhub qr` command with the development server's LAN address, then scan the QR in Prototype Mode in the Even Realities app.

```bash
npx evenhub qr --url http://YOUR_COMPUTER_LAN_IP:5173
```

## Try the tracker

1. Open the app in the simulator or sideload it through the Even Realities app.
2. Check the raw X/Y/Z values while gently turning your head to find which axis responds to yaw.
3. Select that axis, face forward, hold still, and press **Zero / Set Forward**.
4. Turn your head and observe the relative left/right estimate. Change the axis if the sign/direction does not match your movement.
5. For cardinal output, enable the phone compass while holding the phone in the same forward direction as the glasses, or select the manual forward bearing. Zero while facing that direction, then make one known 90° right turn and press **Calibrate +90°** (or double-tap the glasses). Cardinal output combines that anchor with the relative G2 turn estimate.

On glasses, a single tap starts the forward zeroing capture. After a known 90° right turn, double-tap or long-press to calibrate the cardinal scale; a pair of single-tap events is also detected as a double-tap.

## Sensor limits

The Even Hub SDK documents `imuData.x/y/z` as raw floating-point IMU values. It does not document units or axis orientation. The G2 has no magnetometer, so it cannot establish north on its own. Relative mode integrates the selected axis after zeroing. Cardinal mode requires a compass or manual cardinal anchor plus a known 90° turn to calibrate scale; its estimate can drift and needs validation on the actual glasses. A phone compass is only a valid forward anchor when the phone is held in the same direction as the glasses during zeroing.

SDK and tester dependencies are pinned in `package.json` and `package-lock.json`:

- `@evenrealities/even_hub_sdk` 0.0.16
- `@evenrealities/evenhub-simulator` 0.9.5
- `@evenrealities/evenhub-cli` 0.1.14

Build the app with `npm run build`.
