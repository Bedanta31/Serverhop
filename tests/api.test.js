const test = require('node:test');
const assert = require('node:assert/strict');

const { buildApiResponse, normaliseServerPayload } = require('../src/validation.js');
const {
  formatPlayerCapacity,
  hasValidPlayerCount,
  isDisplayableServer,
  getServerSummary,
  isLowestPlayerServer,
  sortByPlayerCount
} = require('../public/server-data.js');


test('api responses are shaped consistently', () => {
  const response = buildApiResponse(true, 'ok', { sample: true }, 200);
  assert.equal(response.success, true);
  assert.equal(response.status, 200);
  assert.equal(response.message, 'ok');
  assert.equal(response.data.sample, true);
});

test('server payloads are normalized and preserve separate ids', () => {
  const payload = {
    id: 'server-job-abc',
    players: 9,
    maxPlayers: 20,
    fps: 60,
    ping: 48,
    placeId: '98664161516921'
  };

  const result = normaliseServerPayload(payload);
  assert.equal(result.jobId, 'server-job-abc');
  assert.equal(result.placeId, '98664161516921');
  assert.equal(result.players, 9);
  assert.equal(result.maxPlayers, 20);
  assert.equal(result.fps, 60);
  assert.equal(result.ping, 48);
});

test('parses player counts from Roblox fields without treating token arrays as counts', () => {
  const onePlayer = normaliseServerPayload({
    id: 'server-playing-one',
    playing: 1,
    maxPlayers: 12,
    playerTokens: [],
    players: [],
    fps: 59.99,
    ping: 77
  });
  assert.equal(onePlayer.players, 1);
  assert.equal(onePlayer.maxPlayers, 12);
  assert.ok(Math.abs(onePlayer.occupancy - (100 / 12)) < 1e-12);
  assert.equal(formatPlayerCapacity(onePlayer), '1 / 12 players');
  assert.equal(onePlayer.fps, 59.99);
  assert.equal(onePlayer.ping, 77);

  const zeroPlayers = normaliseServerPayload({ playing: 0, players: [], maxPlayers: 12 });
  assert.equal(zeroPlayers.players, 0);
  assert.equal(zeroPlayers.occupancy, 0);
  assert.equal(formatPlayerCapacity(zeroPlayers), '0 / 12 players');

  assert.equal(normaliseServerPayload({ players: 3, maxPlayers: 12 }).players, 3);
  assert.equal(normaliseServerPayload({ playing: 0, players: 1, maxPlayers: 12 }).players, 0);

  const fullServer = normaliseServerPayload({ playing: 12, players: [], maxPlayers: 12 });
  assert.equal(fullServer.players, 12);
  assert.equal(fullServer.occupancy, 100);
  assert.equal(fullServer.isFull, true);
  assert.equal(isDisplayableServer({ ...fullServer, jobId: 'full-server-123' }), false);
});

test('keeps missing, null, array-only, and invalid player counts unknown', () => {
  const payloads = [
    { maxPlayers: 12 },
    { playing: null, players: null, maxPlayers: 12 },
    { players: [], maxPlayers: 12 },
    { playing: 'not-a-count', players: [], maxPlayers: 12 }
  ];

  for (const payload of payloads) {
    const server = normaliseServerPayload({ id: 'unknown-server', ...payload });
    assert.equal(server.players, null);
    assert.equal(server.occupancy, null);
    assert.equal(formatPlayerCapacity(server), 'Unknown / 12 players');
    assert.equal(hasValidPlayerCount(server), false);
    assert.equal(isDisplayableServer(server), true);
  }
});

test('rejects invalid capacity and preserves unavailable remote metrics', () => {
  for (const maxPlayers of [0, -1, null, 'invalid', []]) {
    const server = normaliseServerPayload({ id: 'invalid-capacity', playing: 1, maxPlayers });
    assert.equal(server.maxPlayers, null);
    assert.equal(server.occupancy, null);
    assert.equal(isDisplayableServer(server), false);
  }

  const unavailable = normaliseServerPayload({
    id: 'missing-metrics',
    playing: 1,
    maxPlayers: 12,
    fps: null,
    ping: 'not-measured'
  });
  assert.equal(unavailable.fps, null);
  assert.equal(unavailable.ping, null);

  const zeroMeasurements = normaliseServerPayload({
    id: 'zero-metrics',
    playing: 1,
    maxPlayers: 12,
    fps: 0,
    ping: 0
  });
  assert.equal(zeroMeasurements.fps, 0);
  assert.equal(zeroMeasurements.ping, 0);
});

test('sorts confirmed counts numerically and keeps unknown counts last', () => {
  const servers = [4, null, 2, 0, 1].map((players, index) => ({
    jobId: `server-${index}`,
    players,
    maxPlayers: 12
  }));

  assert.deepEqual(
    sortByPlayerCount(servers).map((server) => server.players),
    [0, 1, 2, 4, null]
  );
  assert.deepEqual(
    sortByPlayerCount(servers, true).map((server) => server.players),
    [4, 2, 1, 0, null]
  );
});

test('summarizes unique displayable servers and badges only the true numeric minimum', () => {
  const servers = [
    { jobId: 'server-zero-123', players: 0, maxPlayers: 12 },
    { jobId: 'server-one-123', players: 1, maxPlayers: 12 },
    { jobId: 'SERVER-ONE-123', players: 1, maxPlayers: 12 },
    { jobId: 'server-unknown-123', players: null, maxPlayers: 12 },
    { jobId: 'server-invalid-cap-123', players: 0, maxPlayers: null },
    { jobId: 'server-full-123', players: 12, maxPlayers: 12 }
  ];
  const summary = getServerSummary(servers);

  assert.deepEqual(summary, { availableServers: 3, lowestPlayers: 0 });
  assert.equal(isLowestPlayerServer(servers[0], summary), true);
  assert.equal(isLowestPlayerServer(servers[1], summary), false);
  assert.equal(isLowestPlayerServer(servers[3], summary), false);

  const onlyUnknown = getServerSummary([servers[3]]);
  assert.deepEqual(onlyUnknown, { availableServers: 1, lowestPlayers: null });
  assert.equal(isLowestPlayerServer(servers[3], onlyUnknown), false);
});
