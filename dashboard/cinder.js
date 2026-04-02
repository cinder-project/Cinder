const refreshMs = 2000;

const ui = {
  state: document.getElementById('state-badge'),
  uptime: document.getElementById('uptime-badge'),
  tps: document.getElementById('tps'),
  p50: document.getElementById('mspt-p50'),
  p95: document.getElementById('mspt-p95'),
  p99: document.getElementById('mspt-p99'),
  players: document.getElementById('players'),
  cpuTemp: document.getElementById('cpu-temp'),
  memory: document.getElementById('memory'),
  disk: document.getElementById('disk'),
  logs: document.getElementById('log-lines'),
  updatedAt: document.getElementById('updated-at'),
};

function fmtNumber(value, digits = 3) {
  const n = Number(value);
  if (!Number.isFinite(n)) {
    return '0';
  }
  return n.toFixed(digits);
}

function fmtBytes(bytes) {
  const b = Number(bytes);
  if (!Number.isFinite(b) || b <= 0) {
    return '0 B';
  }

  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
  let value = b;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  return `${value.toFixed(value >= 100 ? 0 : 1)} ${units[index]}`;
}

function setHealthClass(el, state) {
  el.classList.remove('is-good', 'is-warn', 'is-bad');
  el.classList.add(state);
}

function applyStateStyles(payload) {
  const tps = Number(payload.tps || 0);
  const p99 = Number(payload.mspt?.p99 || 0);
  const cpuTemp = Number(payload.cpuTempC || 0);

  setHealthClass(ui.tps, tps >= 19.0 ? 'is-good' : tps >= 17.0 ? 'is-warn' : 'is-bad');
  setHealthClass(ui.p99, p99 <= 50 ? 'is-good' : p99 <= 65 ? 'is-warn' : 'is-bad');
  setHealthClass(ui.cpuTemp, cpuTemp <= 75 ? 'is-good' : cpuTemp <= 83 ? 'is-warn' : 'is-bad');
}

function applyMetrics(payload) {
  ui.state.textContent = `state: ${payload.state || 'unknown'}`;
  ui.uptime.textContent = `uptime: ${Math.max(0, Number(payload.uptimeSeconds || 0))}s`;

  ui.tps.textContent = fmtNumber(payload.tps, 3);
  ui.p50.textContent = fmtNumber(payload.mspt?.p50, 3);
  ui.p95.textContent = fmtNumber(payload.mspt?.p95, 3);
  ui.p99.textContent = fmtNumber(payload.mspt?.p99, 3);
  ui.players.textContent = String(payload.players ?? 0);
  ui.cpuTemp.textContent = `${fmtNumber(payload.cpuTempC, 1)} C`;

  ui.memory.textContent = `${fmtBytes(payload.memory?.usedBytes)} / ${fmtBytes(payload.memory?.maxBytes)}`;
  ui.disk.textContent = `${fmtBytes(payload.disk?.usedBytes)} / ${fmtBytes(payload.disk?.totalBytes)}`;

  const lines = Array.isArray(payload.lastLogLines) ? payload.lastLogLines : [];
  ui.logs.textContent = lines.length ? lines.join('\n') : 'no log lines available';

  const now = new Date();
  ui.updatedAt.textContent = `updated: ${now.toLocaleTimeString()}`;

  applyStateStyles(payload);
}

async function fetchMetrics() {
  const response = await fetch('/api/metrics', {
    cache: 'no-store',
    headers: { 'Accept': 'application/json' },
  });

  if (!response.ok) {
    throw new Error(`metrics HTTP ${response.status}`);
  }

  return response.json();
}

async function tick() {
  try {
    const payload = await fetchMetrics();
    applyMetrics(payload);
  } catch (err) {
    ui.updatedAt.textContent = `updated: error`;
    ui.logs.textContent = `failed to fetch metrics: ${err}`;
    setHealthClass(ui.tps, 'is-bad');
  }
}

void tick();
setInterval(tick, refreshMs);
