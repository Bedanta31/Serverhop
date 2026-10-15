const test = require('node:test');
const assert = require('node:assert/strict');
const { once } = require('node:events');
const {
  createLocalAgent,
  getAgentStatus,
  getAllowedOrigins,
  startLocalAgent,
  DEFAULT_PORT
} = require('../local-agent.js');

test('starts the documented agent on loopback and serves useful status JSON', async () => {
  assert.equal(DEFAULT_PORT, 3210);
  const server = startLocalAgent({
    port: 0,
    getStatus: () => ({
      agentAvailable: true,
      operatingSystem: { platform: 'win32', release: '10.0.26100', isWindows11: true },
      robloxInstalled: false,
      launchAvailable: false,
      error: 'Roblox Player was not found.'
    })
  });
  await once(server, 'listening');
  const address = server.address();
  const baseUrl = `http://127.0.0.1:${address.port}`;

  try {
    assert.equal(address.address, '127.0.0.1');
    for (const route of ['/', '/api/status']) {
      const response = await fetch(`${baseUrl}${route}`);
      const payload = await response.json();
      assert.equal(response.status, 200);
      assert.equal(payload.success, true);
      assert.equal(payload.data.operatingSystem.isWindows11, true);
      assert.equal(payload.data.robloxInstalled, false);
      assert.equal(payload.data.launchAvailable, false);
    }
  } finally {
    await new Promise((resolve, reject) => {
      server.close((error) => error ? reject(error) : resolve());
    });
  }
});

test('reports the local agent OS and Roblox installation as separate launch conditions', () => {
  const status = getAgentStatus({
    platform: 'win32',
    release: '10.0.26100',
    robloxExecutable: 'C:\\Roblox\\RobloxPlayerBeta.exe'
  });
  assert.equal(status.agentAvailable, true);
  assert.equal(status.operatingSystem.isWindows11, true);
  assert.equal(status.robloxInstalled, true);
  assert.equal(status.launchAvailable, true);

  const unsupportedStatus = getAgentStatus({
    platform: 'linux',
    release: '6.8.0',
    robloxExecutable: null
  });
  assert.equal(unsupportedStatus.operatingSystem.isWindows11, false);
  assert.equal(unsupportedStatus.launchAvailable, false);
});

test('loads the exact configured website origin without allowing other origins', () => {
  const previousOrigins = process.env.MONSTER_HOPPER_ALLOWED_ORIGINS;
  process.env.MONSTER_HOPPER_ALLOWED_ORIGINS = 'https://username.github.io';

  try {
    const origins = getAllowedOrigins();
    assert.equal(origins.has('https://username.github.io'), true);
    assert.equal(origins.has('https://username.github.io/'), false);
    assert.equal(origins.has('https://unconfigured.example'), false);
  } finally {
    if (previousOrigins === undefined) {
      delete process.env.MONSTER_HOPPER_ALLOWED_ORIGINS;
    } else {
      process.env.MONSTER_HOPPER_ALLOWED_ORIGINS = previousOrigins;
    }
  }
});

test('serves the local agent status and launch API to a configured GitHub Pages origin', async () => {
  const selectionReceived = [];
  const app = createLocalAgent({
    allowedOrigins: new Set(['https://username.github.io']),
    getStatus: () => ({
      agentAvailable: true,
      operatingSystem: { platform: 'win32', release: '10.0.26100', isWindows11: true },
      robloxInstalled: true,
      launchAvailable: true,
      error: null
    }),
    join: async (selection) => {
      selectionReceived.push(selection);
      return {
        ok: true,
        status: 'launch_requested',
        joinConfirmed: false
      };
    }
  });
  const server = app.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const baseUrl = `http://127.0.0.1:${server.address().port}`;

  try {
    const statusResponse = await fetch(`${baseUrl}/api/status`, {
      headers: { Origin: 'https://username.github.io' }
    });
    const status = await statusResponse.json();
    assert.equal(statusResponse.status, 200);
    assert.equal(statusResponse.headers.get('access-control-allow-origin'), 'https://username.github.io');
    assert.equal(status.data.operatingSystem.isWindows11, true);
    assert.equal(status.data.robloxInstalled, true);
    assert.equal(status.data.launchAvailable, true);

    const unconfiguredResponse = await fetch(`${baseUrl}/api/status`, {
      headers: { Origin: 'https://unconfigured.example' }
    });
    assert.equal(unconfiguredResponse.status, 403);
    assert.equal(unconfiguredResponse.headers.get('access-control-allow-origin'), null);

    const unconfiguredPreflight = await fetch(`${baseUrl}/api/join`, {
      method: 'OPTIONS',
      headers: {
        Origin: 'https://unconfigured.example',
        'Access-Control-Request-Method': 'POST',
        'Access-Control-Request-Headers': 'content-type'
      }
    });
    assert.equal(unconfiguredPreflight.status, 403);
    assert.equal(unconfiguredPreflight.headers.get('access-control-allow-origin'), null);

    const preflightResponse = await fetch(`${baseUrl}/api/join`, {
      method: 'OPTIONS',
      headers: {
        Origin: 'https://username.github.io',
        'Access-Control-Request-Method': 'POST',
        'Access-Control-Request-Headers': 'content-type',
        'Access-Control-Request-Private-Network': 'true'
      }
    });
    assert.equal(preflightResponse.status, 204);
    assert.equal(preflightResponse.headers.get('access-control-allow-private-network'), 'true');
    assert.match(preflightResponse.headers.get('vary'), /Access-Control-Request-Private-Network/i);

    const launchResponse = await fetch(`${baseUrl}/api/join`, {
      method: 'POST',
      headers: {
        Origin: 'https://username.github.io',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        placeId: '98664161516921',
        serverJobId: 'valid-server-id-123'
      })
    });
    const launch = await launchResponse.json();
    assert.equal(launchResponse.status, 200);
    assert.equal(launch.success, true);
    assert.equal(launch.data.status, 'launch_requested');
    assert.equal(launch.data.joinConfirmed, false);
    assert.deepEqual(selectionReceived, [{
      placeId: '98664161516921',
      serverJobId: 'valid-server-id-123'
    }]);
  } finally {
    await new Promise((resolve, reject) => {
      server.close((error) => error ? reject(error) : resolve());
    });
  }
});

test('does not call the launcher when local capability is unavailable', async () => {
  let launchCount = 0;
  const app = createLocalAgent({
    allowedOrigins: new Set(),
    getStatus: () => ({
      agentAvailable: true,
      operatingSystem: { platform: 'linux', release: '6.8.0', isWindows11: false },
      robloxInstalled: false,
      launchAvailable: false,
      error: 'Monster Hopper launching requires the local agent to run on Windows 11.'
    }),
    join: async () => {
      launchCount += 1;
      return { ok: true };
    }
  });
  const server = app.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const baseUrl = `http://127.0.0.1:${server.address().port}`;

  try {
    const response = await fetch(`${baseUrl}/api/join`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        placeId: '98664161516921',
        serverJobId: 'valid-server-id-123'
      })
    });
    const payload = await response.json();
    assert.equal(response.status, 409);
    assert.equal(payload.data.status, 'local_launch_unavailable');
    assert.equal(launchCount, 0);
  } finally {
    await new Promise((resolve, reject) => {
      server.close((error) => error ? reject(error) : resolve());
    });
  }
});
