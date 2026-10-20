import { spawn } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const binSuffix = process.platform === 'win32' ? '.cmd' : '';
const vite = resolve(root, 'node_modules', '.bin', `vite${binSuffix}`);
const simulator = resolve(root, 'node_modules', '.bin', `evenhub-simulator${binSuffix}`);
const url = 'http://localhost:5173/?demo=1';

const spawnOptions = {
  cwd: root,
  stdio: 'inherit',
  // Keep each tool and its native descendants in a process group so stopping
  // the demo also closes the simulator window instead of leaving stale UI up.
  detached: process.platform !== 'win32',
};
const server = spawn(vite, ['--host', '0.0.0.0', '--strictPort'], spawnOptions);
let tester;
let stopping = false;

function stopProcessTree(child) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  if (process.platform === 'win32') {
    child.kill('SIGTERM');
    return;
  }
  try {
    process.kill(-child.pid, 'SIGTERM');
  } catch (error) {
    if (error.code !== 'ESRCH') throw error;
  }
}

function stop(code = 0) {
  if (stopping) return;
  stopping = true;
  stopProcessTree(tester);
  stopProcessTree(server);
  process.exitCode = code;
}

process.on('SIGINT', () => stop());
process.on('SIGTERM', () => stop());
server.on('error', (error) => {
  console.error(`Could not start Vite: ${error.message}`);
  stop(1);
});
server.on('exit', (code) => {
  if (!stopping && code !== 0) stop(code ?? 1);
});

for (let attempt = 0; attempt < 80 && !stopping; attempt += 1) {
  try {
    const response = await fetch(url);
    if (response.ok) break;
  } catch {
    // Vite is still starting.
  }
  if (attempt === 79) {
    console.error('Vite did not become ready on port 5173.');
    stop(1);
  }
  await delay(250);
}

if (stopping) process.exit();
console.log(`Opening the tester with sample IMU data: ${url}`);
tester = spawn(simulator, [url], spawnOptions);
tester.on('error', (error) => {
  console.error(`Could not start the Even Hub tester: ${error.message}`);
  stop(1);
});
tester.on('exit', (code) => stop(code ?? 0));
