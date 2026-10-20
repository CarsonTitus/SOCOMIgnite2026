# Direction Tracker: Development Guide

## What runs where

The Even Realities phone app hosts this web app and owns the Bluetooth connection to the G2. The browser code uses `@evenrealities/even_hub_sdk` to request IMU reports and update the glasses display. The G2 does not run this JavaScript itself.

```text
G2 IMU
  -> Even Realities phone app
  -> Even Hub SDK event in src/main.ts
  -> handleSample(x, y, z)
      -> relative turn estimate and dashboard telemetry
      -> renderGlasses() text update
      -> renderRadarFrame() grayscale radar image
```

The dashboard radar and the glasses radar use the same sample contact positions and turn estimate. The dashboard draws a larger canvas; the glasses receive a 288 × 144 grayscale image plus a text panel.

## Main files

- `src/main.ts` owns the SDK bridge, IMU event handling, calibration, contact positions, and both radar renderers.
- `index.html` defines the dashboard and its controls.
- `src/style.css` styles the dashboard.
- `app.json` identifies the Even Hub app and its minimum SDK/app versions.
- `package.json` pins the SDK, tester simulator, and local development commands.
- `tracker-display-preview.svg` is a static 576 × 288 layout reference. The simulator or physical glasses are needed to inspect actual SDK rendering.

## Getting a local preview

From this directory:

```bash
npm install
npm run dev
```

Open the Vite address in a browser to view the companion dashboard. Add `?demo=1` to use generated IMU samples without calling the device IMU API. For the SDK simulator, run `npm run simulator` in another terminal. Real-device testing uses Even Realities Prototype Mode and the QR URL printed by `npx evenhub qr --url http://YOUR_COMPUTER_LAN_IP:5173`.

`npm run build` runs TypeScript checking and creates the Vite build. The simulator is useful for layout iteration, but its current bridge does not emulate `imuControl`; `?demo=1` bypasses that call and drives the same sample handler with synthetic values. It does not prove that the phone app receives live G2 samples.

## IMU flow and calibration

`start()` waits for the SDK bridge, subscribes to `onEvenHubEvent`, creates the glasses containers, and requests `ImuReportPace.P200` (200 ms, about five samples per second). Each `IMU_DATA_REPORT` is checked for finite `x`, `y`, and `z` values before it is passed to `handleSample`.

`handleSample` updates the X/Y/Z dashboard values and integrates the selected axis after removing the stored zero bias. The SDK supplies raw floating-point values; their units and axis mapping are not documented here, so the axis and direction must be checked on the target hardware.

`zeroForward()` averages 1.2 seconds of samples while the wearer faces forward, then resets the relative turn estimate. The forward bearing comes from a fresh phone compass reading, if available, or the manual bearing selector. `calibrateCardinalScale()` converts the integrated value to degrees from a known 90° right turn. Cardinal output is therefore an anchored estimate and can drift; the G2 IMU by itself does not establish north.

## Radar rendering

`sampleContacts` is the current placeholder dataset. Each entry has an ID used for its pulse timing and normalized `x`/`y` coordinates:

- `x`: horizontal position; positive is right/east when facing north.
- `y`: screen-down position; negative is up/north when facing north.
- Coordinates near `-1..1` place a point within the radar range.

`radarTurnRadians()` derives the wearer-relative rotation from the integrated yaw. It uses the calibrated degrees-per-unit value after calibration and a visual 45°-per-unit default before calibration. Both radar renderers rotate the sample positions by this turn so contacts move across the display as the wearer turns. The default is for visual responsiveness only; use the known 90° calibration for a meaningful cardinal scale.

`renderDashboardRadar()` draws the rings and pulsing points into the dashboard canvas on animation frames. `renderRadarFrame()` draws the same points into an offscreen 288 × 144 canvas, converts it to grayscale bytes, and sends it to image container 1 through `bridge.updateImageRawData()` every 100 ms. `renderGlasses()` updates the adjacent text container with heading and relative-turn values.

## Connecting real contact data later

The IMU stream and contact feed are separate inputs. G2 IMU reports move the contact map relative to the wearer; they do not provide teammate positions. Replace the static `sampleContacts` source in `src/main.ts` with a small adapter that receives your future feed, validates it, and converts its positions into the normalized radar coordinates above. Keep network or Bluetooth pipeline code out of `renderDashboardRadar()` and `renderRadarFrame()`; those functions should draw the latest contact snapshot only.

A future adapter should define and document its coordinate reference, range, update cadence, stale-contact behavior, and source IDs. If the feed provides world coordinates, convert them into the radar's east/north reference before publishing the snapshot; if it provides positions relative to the wearer, do not rotate them a second time.

## Changing the UI or sensor behavior

- Change ring size, point brightness, or pulse timing in `renderDashboardRadar()` and `renderRadarFrame()`.
- Change placeholder points in `sampleContacts`.
- Change the selected yaw axis in the dashboard selector; its `change` handler resets calibration.
- Change the synthetic preview sequence in the `demoMode` interval inside `start()`. It emits `x`, `y`, and `z` values every 200 ms through the same `handleSample()` path.
- Change the glasses text or image placement in the `CreateStartUpPageContainer` setup in `start()`. Keep image updates paired with the image container's ID and name.
- Change the IMU reporting pace at `bridge.imuControl(true, ImuReportPace.P200)`; the same pace affects sensor update frequency and traffic.

The current contact points and `?demo=1` samples are demonstration data. Live IMU startup and the resulting display still need verification in the Even Realities phone app with the actual glasses.
