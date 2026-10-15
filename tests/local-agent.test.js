const test = require('node:test');
const assert = require('node:assert/strict');
const { once } = require('node:events');
const { createLocalAgent, getAgentStatus } = require('../local-agent.js');

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
    assert.equal(status.data.launchAvailable, true);

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
