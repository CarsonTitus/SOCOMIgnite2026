import {
  ImuReportPace,
  OsEventTypeList,
  CreateStartUpPageContainer,
  ImageContainerProperty,
  ImageRawDataUpdate,
  TextContainerProperty,
  TextContainerUpgrade,
  waitForEvenAppBridge,
  type EvenAppBridge,
} from '@evenrealities/even_hub_sdk';
import './style.css';

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const dot = $('connection-dot');
const connectionLabel = $('connection-label');
const calibrateButton = $('calibrate') as HTMLButtonElement;
const directionLabel = $('direction-label');
const directionHint = $('direction-hint');
const angleValue = $('angle-value');
const dashboardRadar = $('dashboard-radar') as HTMLCanvasElement;
const dashboardRadarContext = dashboardRadar.getContext('2d')!;
const axisValues = { x: $('axis-x'), y: $('axis-y'), z: $('axis-z') };
const meters = { x: $('meter-x'), y: $('meter-y'), z: $('meter-z') };
const calibrationDot = $('calibration-dot');
const calibrationLabel = $('calibration-label');
const sampleAge = $('sample-age');
const radarModeLabel = $('radar-mode');
const yawAxisSelect = $('yaw-axis') as HTMLSelectElement;
const zAxisNote = $('z-axis-note');
const phoneCompassStatus = $('phone-compass-status');
const forwardBearingSelect = $('forward-bearing') as HTMLSelectElement;
const cardinalValue = $('cardinal-value');
const compassButton = $('enable-compass') as HTMLButtonElement;
const scaleButton = $('calibrate-turn') as HTMLButtonElement;

type CompassEvent = DeviceOrientationEvent & { webkitCompassHeading?: number };
type CompassPermissionApi = typeof DeviceOrientationEvent & {
  requestPermission?: (absolute?: boolean) => Promise<'granted' | 'denied'>;
};

let bridge: EvenAppBridge | undefined;
let unsubscribe: (() => void) | undefined;
let gyroBias: number | undefined;
let yawEstimate = 0;
let lastYawSampleAt = 0;
let latestAxes: { x: number; y: number; z: number } | undefined;
let latestPhoneHeading: number | undefined;
let phoneHeadingAt = 0;
let forwardBearing: number | undefined;
let degreesPerYawUnit: number | undefined;
let collectingBias = false;
let biasSamples: number[] = [];
let sampleTimestamp = 0;
let imuRequestAt = 0;
let lastHudText = '';
let hudUpdateTimer = 0;
let pendingGlassesTap = 0;
let bridgeStatusMessage: string | undefined;
type RadarOrientation = 'fixed-north' | 'user-centered';
let radarOrientation: RadarOrientation = 'fixed-north';
const demoMode = new URLSearchParams(location.search).get('demo') === '1';
let demoImuTimer = 0;
const radarCanvas = document.createElement('canvas');
radarCanvas.width = 288;
radarCanvas.height = 144;
const radarContext = radarCanvas.getContext('2d', { willReadFrequently: true })!;
const sampleContacts = [
  { id: '01', x: -0.48, y: -0.15 },
  { id: '02', x: 0.30, y: -0.50 },
  { id: '03', x: 0.47, y: 0.28 },
];
let radarUpdating = false;
let radarTimer = 0;
let dashboardRadarFrame = 0;

function handleGlassesTap() {
  if (pendingGlassesTap) {
    window.clearTimeout(pendingGlassesTap);
    pendingGlassesTap = 0;
    calibrateCardinalScale();
    return;
  }
  pendingGlassesTap = window.setTimeout(() => {
    pendingGlassesTap = 0;
    void zeroForward();
  }, 320);
}

function toggleRadarOrientation() {
  radarOrientation = radarOrientation === 'fixed-north' ? 'user-centered' : 'fixed-north';
  radarModeLabel.textContent = radarOrientation === 'fixed-north' ? 'FIXED NORTH' : 'USER-CENTERED NORTH';
  renderGlasses();
  renderRadarFrame();
}

function renderDirection() {
  if (gyroBias === undefined) {
    directionLabel.textContent = 'WAITING FOR GLASSES';
    directionHint.textContent = bridgeStatusMessage ?? 'Open this app in Even Realities to start the IMU stream.';
    angleValue.textContent = '—';
    scaleButton.disabled = true;
    return;
  }
  const estimate = yawEstimate;
  const directionEstimate = degreesPerYawUnit !== undefined && degreesPerYawUnit < 0 ? -estimate : estimate;
  const score = Math.abs(directionEstimate) < 0.08 ? 0 : directionEstimate;
  angleValue.textContent = `${score > 0 ? '+' : ''}${score.toFixed(2)}`;
  if (score === 0) {
    directionLabel.textContent = 'FORWARD';
    directionHint.textContent = 'Near your zero point. Keep the glasses still briefly when calibrating.';
  } else if (score > 0) {
    directionLabel.textContent = 'LOOKING RIGHT';
    directionHint.textContent = `Positive ${yawAxisSelect.value.toUpperCase()}-axis motion since calibration.`;
  } else {
    directionLabel.textContent = 'LOOKING LEFT';
    directionHint.textContent = `Negative ${yawAxisSelect.value.toUpperCase()}-axis motion since calibration.`;
  }
  if (forwardBearing !== undefined && degreesPerYawUnit !== undefined) {
    const bearing = normalizeBearing(forwardBearing + yawEstimate * degreesPerYawUnit);
    cardinalValue.textContent = `${bearingName(bearing)} · ${Math.round(bearing)}°`;
  } else {
    cardinalValue.textContent = forwardBearing === undefined ? 'SET A FORWARD BEARING' : 'CALIBRATE A 90° TURN';
  }
  scaleButton.disabled = gyroBias === undefined || Math.abs(yawEstimate) < 0.02;
}

function radarTurnRadians(): number {
  if (radarOrientation === 'fixed-north') return 0;
  const displayDegreesPerUnit = degreesPerYawUnit ?? 45;
  return (yawEstimate * displayDegreesPerUnit * Math.PI) / 180;
}

function radarPoint(east: number, north: number, turnRadians: number) {
  return {
    east: east * Math.cos(turnRadians) - north * Math.sin(turnRadians),
    north: east * Math.sin(turnRadians) + north * Math.cos(turnRadians),
  };
}

function renderDashboardRadar(now: number) {
  const ctx = dashboardRadarContext;
  const size = dashboardRadar.width;
  const cx = size / 2;
  const cy = size / 2;
  const radius = 205;
  ctx.clearRect(0, 0, size, size);
  ctx.fillStyle = '#09100d';
  ctx.fillRect(0, 0, size, size);
  ctx.strokeStyle = '#35483b';
  ctx.lineWidth = 1;
  for (const scale of [1, 0.75, 0.5, 0.25]) {
    ctx.beginPath();
    ctx.arc(cx, cy, radius * scale, 0, Math.PI * 2);
    ctx.stroke();
  }
  const turnRadians = radarTurnRadians();
  const eastAxis = radarPoint(1, 0, turnRadians);
  const northAxis = radarPoint(0, 1, turnRadians);
  ctx.beginPath();
  ctx.moveTo(cx - eastAxis.east * radius, cy + eastAxis.north * radius);
  ctx.lineTo(cx + eastAxis.east * radius, cy - eastAxis.north * radius);
  ctx.moveTo(cx - northAxis.east * radius, cy + northAxis.north * radius);
  ctx.lineTo(cx + northAxis.east * radius, cy - northAxis.north * radius);
  ctx.stroke();
  ctx.font = '11px "DM Mono", monospace';
  ctx.textAlign = 'center';
  for (const cardinal of [
    { name: 'N', east: 0, north: 1 },
    { name: 'E', east: 1, north: 0 },
    { name: 'S', east: 0, north: -1 },
    { name: 'W', east: -1, north: 0 },
  ]) {
    const point = radarPoint(cardinal.east, cardinal.north, turnRadians);
    ctx.fillStyle = cardinal.name === 'N' ? '#b4f275' : '#84958a';
    ctx.fillText(cardinal.name, cx + point.east * (radius - 8), cy - point.north * (radius - 8) + 4);
  }
  for (const contact of sampleContacts) {
    const point = radarPoint(contact.x, -contact.y, turnRadians);
    const x = cx + point.east * radius;
    const y = cy - point.north * radius;
    const pulse = 0.7 + 0.3 * Math.sin(now / 240 + Number(contact.id));
    ctx.fillStyle = `rgba(190,255,135,${pulse * 0.16})`;
    ctx.beginPath();
    ctx.arc(x, y, 13, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = `rgba(190,255,135,${pulse})`;
    ctx.beginPath();
    ctx.arc(x, y, 4, 0, Math.PI * 2);
    ctx.fill();
  }
  dashboardRadarFrame = window.requestAnimationFrame(renderDashboardRadar);
}

function renderGlasses() {
  if (!bridge) return;
  const directionEstimate = degreesPerYawUnit !== undefined && degreesPerYawUnit < 0 ? -yawEstimate : yawEstimate;
  const score = Math.abs(directionEstimate) < 0.08 ? 0 : directionEstimate;
  const heading = forwardBearing !== undefined && degreesPerYawUnit !== undefined
    ? `${bearingName(normalizeBearing(forwardBearing + yawEstimate * degreesPerYawUnit))}  ${Math.round(normalizeBearing(forwardBearing + yawEstimate * degreesPerYawUnit))}°`
    : 'WAITING FOR IMU';
  const relative = gyroBias === undefined ? '—' : `${score > 0 ? '+' : ''}${score.toFixed(2)}`;
  const orientation = radarOrientation === 'fixed-north' ? 'FIXED N' : 'HEAD UP';
  const content = `RADAR · ${orientation}\nHEADING  ${heading}\nREL TURN  ${relative}`;
  if (content === lastHudText) return;
  lastHudText = content;
  void bridge.textContainerUpgrade(new TextContainerUpgrade({ containerID: 2, content }));
}

function renderRadarFrame() {
  if (!bridge || radarUpdating) return;
  radarUpdating = true;
  const ctx = radarContext;
  const width = radarCanvas.width;
  const height = radarCanvas.height;
  const cx = width / 2;
  const cy = height / 2 + 4;
  const radius = 57;
  const now = performance.now();
  const turnRadians = radarTurnRadians();
  ctx.clearRect(0, 0, width, height);
  ctx.fillStyle = '#000';
  ctx.fillRect(0, 0, width, height);
  ctx.strokeStyle = '#525252';
  ctx.lineWidth = 1;
  for (const scale of [1, 0.66, 0.33]) {
    ctx.beginPath();
    ctx.arc(cx, cy, radius * scale, 0, Math.PI * 2);
    ctx.stroke();
  }
  const eastAxis = radarPoint(1, 0, turnRadians);
  const northAxis = radarPoint(0, 1, turnRadians);
  ctx.beginPath();
  ctx.moveTo(cx - eastAxis.east * radius, cy + eastAxis.north * radius);
  ctx.lineTo(cx + eastAxis.east * radius, cy - eastAxis.north * radius);
  ctx.moveTo(cx - northAxis.east * radius, cy + northAxis.north * radius);
  ctx.lineTo(cx + northAxis.east * radius, cy - northAxis.north * radius);
  ctx.stroke();
  for (const contact of sampleContacts) {
    const point = radarPoint(contact.x, -contact.y, turnRadians);
    const x = cx + point.east * radius;
    const y = cy - point.north * radius;
    const pulse = 0.75 + 0.25 * Math.sin(now / 260 + Number(contact.id));
    ctx.fillStyle = `rgba(255,255,255,${pulse})`;
    ctx.beginPath();
    ctx.arc(x, y, 3.2, 0, Math.PI * 2);
    ctx.fill();
  }
  ctx.fillStyle = '#bcbcbc';
  ctx.font = '7px monospace';
  ctx.textAlign = 'center';
  const northMarker = radarPoint(0, 1, turnRadians);
  ctx.fillText('N', cx + northMarker.east * (radius + 4), cy - northMarker.north * (radius + 3));
  const rgba = ctx.getImageData(0, 0, width, height).data;
  const grayscale = new Array<number>(width * height);
  for (let pixel = 0; pixel < grayscale.length; pixel += 1) {
    const offset = pixel * 4;
    grayscale[pixel] = Math.round((rgba[offset] + rgba[offset + 1] + rgba[offset + 2]) / 3);
  }
  void bridge.updateImageRawData(new ImageRawDataUpdate({
    containerID: 1, containerName: 'direction-radar', imageData: grayscale,
  })).finally(() => { radarUpdating = false; });
}

function handleSample(x: number, y: number, z: number) {
  bridgeStatusMessage = undefined;
  connectionLabel.textContent = demoMode ? 'DEMO IMU STREAM' : 'G2 IMU STREAM ACTIVE';
  dot.classList.add('streaming');
  const now = performance.now();
  latestAxes = { x, y, z };
  sampleTimestamp = Date.now();
  sampleAge.textContent = 'LIVE · RECEIVED JUST NOW';
  for (const [axis, value] of Object.entries({ x, y, z }) as [keyof typeof axisValues, number][]) {
    axisValues[axis].textContent = value.toFixed(2);
    meters[axis].style.width = `${Math.min(100, Math.abs(value) * 4)}%`;
  }
  const axis = yawAxisSelect.value as 'x' | 'y' | 'z';
  const currentRate = latestAxes[axis];
  if (collectingBias) biasSamples.push(currentRate);
  if (!collectingBias && gyroBias !== undefined && lastYawSampleAt > 0) {
    const seconds = Math.min((now - lastYawSampleAt) / 1000, 0.5);
    yawEstimate += (currentRate - gyroBias) * seconds;
  }
  lastYawSampleAt = now;
  renderDirection();
  if (!collectingBias) calibrateButton.disabled = false;
  if (gyroBias === undefined) {
    calibrationDot.classList.remove('set');
    calibrationLabel.textContent = 'HOLD STILL · FORWARD NOT SET';
  } else {
    calibrationDot.classList.add('set');
    calibrationLabel.textContent = `ZEROED · ${axis.toUpperCase()} AXIS`;
  }
  window.clearTimeout(hudUpdateTimer);
  hudUpdateTimer = window.setTimeout(renderGlasses, 90);
}

async function zeroForward() {
  if (!latestAxes) return;
  const axis = yawAxisSelect.value as 'x' | 'y' | 'z';
  collectingBias = true;
  biasSamples = [];
  calibrateButton.disabled = true;
  calibrationLabel.textContent = 'HOLD STILL · ZEROING SENSOR';
  await new Promise((resolve) => window.setTimeout(resolve, 1200));
  collectingBias = false;
  if (!biasSamples.length) {
    calibrationLabel.textContent = 'NO IMU SAMPLES · TRY AGAIN';
    calibrateButton.disabled = false;
    return;
  }
  gyroBias = biasSamples.reduce((sum, sample) => sum + sample, 0) / biasSamples.length;
  yawEstimate = 0;
  lastYawSampleAt = performance.now();
  degreesPerYawUnit = undefined;
  const phoneSampleFresh = latestPhoneHeading !== undefined && Date.now() - phoneHeadingAt < 5000;
  forwardBearing = phoneSampleFresh
    ? latestPhoneHeading
    : bearingFromName(forwardBearingSelect.value);
  renderDirection();
  renderGlasses();
  calibrationDot.classList.add('set');
  calibrationLabel.textContent = `ZEROED · ${axis.toUpperCase()} AXIS · ${bearingName(forwardBearing ?? 0)}`;
}

calibrateButton.addEventListener('click', () => void zeroForward());

function calibrateCardinalScale() {
  if (Math.abs(yawEstimate) < 0.02 || forwardBearing === undefined) return;
  degreesPerYawUnit = 90 / yawEstimate;
  renderDirection();
  renderGlasses();
  calibrationLabel.textContent = `CARDINAL SCALE SET · ${bearingName(forwardBearing)} + 90°`;
}

scaleButton.addEventListener('click', calibrateCardinalScale);

function normalizeBearing(degrees: number): number {
  return (degrees % 360 + 360) % 360;
}

function bearingName(degrees: number): string {
  const names = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];
  return names[Math.round(normalizeBearing(degrees) / 45) % names.length];
}

function bearingFromName(name: string): number {
  return ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'].indexOf(name) * 45;
}

function handlePhoneCompass(event: Event) {
  const sample = event as CompassEvent;
  let bearing = sample.webkitCompassHeading;
  if (typeof bearing !== 'number' && sample.absolute && typeof sample.alpha === 'number') {
    // DeviceOrientation alpha advances counter-clockwise from north; convert to clockwise bearing.
    bearing = 360 - sample.alpha;
  }
  if (typeof bearing !== 'number' || !Number.isFinite(bearing)) return;
  latestPhoneHeading = normalizeBearing(bearing);
  phoneHeadingAt = Date.now();
  phoneCompassStatus.textContent = `PHONE COMPASS · ${bearingName(latestPhoneHeading)} ${Math.round(latestPhoneHeading)}°`;
}

compassButton.addEventListener('click', async () => {
  const permissionApi = DeviceOrientationEvent as CompassPermissionApi;
  try {
    if (typeof permissionApi.requestPermission === 'function') {
      const permission = await permissionApi.requestPermission(true);
      if (permission !== 'granted') throw new Error('Phone compass permission was not granted.');
    }
    window.addEventListener('deviceorientationabsolute', handlePhoneCompass as EventListener);
    window.addEventListener('deviceorientation', handlePhoneCompass as EventListener);
    compassButton.disabled = true;
    compassButton.textContent = 'LISTENING';
    phoneCompassStatus.textContent = 'PHONE COMPASS · WAITING FOR SENSOR';
  } catch (error) {
    phoneCompassStatus.textContent = error instanceof Error ? error.message.toUpperCase() : 'PHONE COMPASS UNAVAILABLE';
  }
});

yawAxisSelect.addEventListener('change', () => {
  gyroBias = undefined;
  yawEstimate = 0;
  lastYawSampleAt = 0;
  forwardBearing = undefined;
  degreesPerYawUnit = undefined;
  zAxisNote.textContent = yawAxisSelect.value === 'z' ? 'RAW · SELECTED' : 'RAW';
  renderDirection();
  renderGlasses();
});

forwardBearingSelect.addEventListener('change', () => {
  if (gyroBias === undefined) return;
  forwardBearing = bearingFromName(forwardBearingSelect.value);
  renderDirection();
  renderGlasses();
  calibrationLabel.textContent = `FORWARD ANCHOR · ${bearingName(forwardBearing)}`;
});

async function start() {
  try {
    bridge = await waitForEvenAppBridge();
    unsubscribe = bridge.onEvenHubEvent((event) => {
      const system = event.sysEvent;
      const eventType = event.textEvent?.eventType ?? system?.eventType ??
        (event.textEvent || system ? OsEventTypeList.CLICK_EVENT : undefined);
      if (eventType === OsEventTypeList.CLICK_EVENT) {
        handleGlassesTap();
        return;
      }
      if (eventType === OsEventTypeList.DOUBLE_CLICK_EVENT) {
        window.clearTimeout(pendingGlassesTap);
        pendingGlassesTap = 0;
        calibrateCardinalScale();
        return;
      }
      if (eventType === OsEventTypeList.LONG_PRESS_EVENT) {
        window.clearTimeout(pendingGlassesTap);
        pendingGlassesTap = 0;
        toggleRadarOrientation();
        return;
      }
      if (system?.eventType !== OsEventTypeList.IMU_DATA_REPORT || !system.imuData) return;
      const { x, y, z } = system.imuData;
      if ([x, y, z].some((value) => typeof value !== 'number' || !Number.isFinite(value))) return;
      handleSample(x!, y!, z!);
    });

    try {
      const created = await bridge.createStartUpPageContainer(new CreateStartUpPageContainer({
        containerTotalNum: 3,
        imageObject: [new ImageContainerProperty({
          xPosition: 0, yPosition: 72, width: 288, height: 144,
          containerID: 1, containerName: 'direction-radar', zOrderIndex: 1,
        })],
        textObject: [
          new TextContainerProperty({
            xPosition: 0, yPosition: 0, width: 576, height: 288,
            borderWidth: 0, borderColor: 0, paddingLength: 0,
            containerID: 3, containerName: 'input-layer', zOrderIndex: 0,
            content: ' ', isEventCapture: 1,
          }),
          new TextContainerProperty({
            xPosition: 300, yPosition: 68, width: 270, height: 220,
            borderWidth: 0, borderColor: 0, borderRadius: 0, paddingLength: 0,
            containerID: 2, containerName: 'radar-status', zOrderIndex: 2,
            content: 'RADAR  ·  DEMO\n\nHEADING\nWAITING FOR IMU\n\nRELATIVE TURN\n—',
            isEventCapture: 0,
          }),
        ],
      }));
      if (created !== 0) throw new Error(`Could not create glasses display (result ${created}).`);
    } catch (error) {
      bridgeStatusMessage = error instanceof Error ? `${error.message} Starting IMU stream anyway.` : 'Glasses display unavailable. Starting IMU stream anyway.';
      console.warn('Could not create the glasses display; continuing with IMU startup.', error);
    }

    renderGlasses();
    void renderRadarFrame();
    radarTimer = window.setInterval(renderRadarFrame, 100);

    dot.classList.add('connected');
    connectionLabel.textContent = 'EVEN HUB CONNECTED';
    if (demoMode) {
      gyroBias = 0;
      yawEstimate = 0;
      lastYawSampleAt = performance.now();
      forwardBearing = bearingFromName('N');
      degreesPerYawUnit = 45;
      renderDirection();
      renderGlasses();
      connectionLabel.textContent = 'DEMO IMU STREAM';
      demoImuTimer = window.setInterval(() => {
        const elapsed = performance.now();
        const seconds = elapsed / 1000;
        handleSample(
          0.025 * Math.sin(seconds * 0.7),
          0.018 * Math.cos(seconds * 0.45),
          0.1 * Math.cos(seconds / 8),
        );
      }, 200);
      return;
    }
    const started = await bridge.imuControl(true, ImuReportPace.P200);
    if (!started) throw new Error('The Even app did not start the G2 IMU stream.');
    imuRequestAt = Date.now();
    connectionLabel.textContent = 'WAITING FOR IMU DATA';
  } catch (error) {
    bridgeStatusMessage = error instanceof Error ? error.message : 'Even Hub bridge is unavailable.';
    connectionLabel.textContent = 'BRIDGE ERROR';
    renderDirection();
    console.info('Even Hub bridge is not active in this browser preview.', error);
  }
}

window.setInterval(() => {
  if (!sampleTimestamp || Date.now() - sampleTimestamp > 1500) {
    sampleAge.textContent = sampleTimestamp ? 'NO RECENT IMU SAMPLE' : 'WAITING FOR IMU';
    if (!sampleTimestamp && imuRequestAt && Date.now() - imuRequestAt > 5000) {
      dot.classList.remove('streaming');
      connectionLabel.textContent = 'NO IMU DATA RECEIVED';
      bridgeStatusMessage = 'The phone opened the IMU request, but no sensor samples arrived. Reopen Prototype mode and reconnect the glasses.';
      renderDirection();
    }
    if (sampleTimestamp && Date.now() - sampleTimestamp > 4000) {
      dot.classList.remove('streaming');
      connectionLabel.textContent = 'IMU STREAM PAUSED';
    }
  }
}, 500);

window.addEventListener('pagehide', () => {
  unsubscribe?.();
  if (!demoMode) void bridge?.imuControl(false);
  window.clearInterval(demoImuTimer);
  window.cancelAnimationFrame(dashboardRadarFrame);
  window.clearTimeout(pendingGlassesTap);
  window.clearInterval(radarTimer);
  window.removeEventListener('deviceorientationabsolute', handlePhoneCompass as EventListener);
  window.removeEventListener('deviceorientation', handlePhoneCompass as EventListener);
});

dashboardRadarFrame = window.requestAnimationFrame(renderDashboardRadar);
void start();
