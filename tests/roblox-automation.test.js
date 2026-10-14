const test = require('node:test');
const assert = require('node:assert/strict');

const { requestJoin } = require('../src/joinService.js');
const { avoidDuplicateAttempts } = require('../src/serverSelection.js');
const serverService = require('../src/serverService.js');
const { getServerSummary, normaliseServerPayload } = require('../public/server-data.js');
const capturedRobloxResponse = require('./fixtures/roblox-public-server-response.json');

test('normalizes the captured Roblox public-server response from playing, not players array', async () => {
  const originalFetch = global.fetch;
  const originalCache = { ...serverService.cache, servers: [...serverService.cache.servers] };
  const originalInFlight = serverService.inFlight;
  serverService.cache = {
    servers: [],
    refreshedAt: null,
    source: 'empty',
    partial: false,
    lastError: null,
    rateLimitUntil: 0
  };
  global.fetch = async () => ({
    ok: true,
    status: 200,
    headers: new Headers(),
    json: async () => capturedRobloxResponse
  });

  try {
    const result = await serverService.fetchPublicServers({ force: true, limit: 10, maxPages: 1 });
    assert.equal(result.servers.length, 1);
    assert.equal(result.servers[0].players, 1);
    assert.equal(result.servers[0].maxPlayers, 12);
    assert.equal(normaliseServerPayload(result.servers[0]).players, 1);
    assert.deepEqual(getServerSummary(result.servers), { availableServers: 1, lowestPlayers: 1 });
  } finally {
    global.fetch = originalFetch;
    serverService.cache = originalCache;
    serverService.inFlight = originalInFlight;
  }
});

test('refreshing server data updates the normalized minimum summary', async () => {
  const originalFetch = global.fetch;
  const originalCache = { ...serverService.cache, servers: [...serverService.cache.servers] };
  const originalInFlight = serverService.inFlight;
  const responses = [
    { data: [{ id: 'server-one-123', playing: 1, maxPlayers: 12, players: [] }], nextPageCursor: null },
    {
      data: [
        { id: 'server-zero-123', playing: 0, maxPlayers: 12, players: [] },
        { id: 'server-two-123', playing: 2, maxPlayers: 12, players: [] }
      ],
      nextPageCursor: null
    }
  ];
  serverService.cache = {
    servers: [],
    refreshedAt: null,
    source: 'empty',
    partial: false,
    lastError: null,
    rateLimitUntil: 0
  };
  global.fetch = async () => ({
    ok: true,
    status: 200,
    headers: new Headers(),
    json: async () => responses.shift()
  });

  try {
    const firstRefresh = await serverService.fetchPublicServers({ force: true, limit: 10, maxPages: 1 });
    assert.deepEqual(getServerSummary(firstRefresh.servers), { availableServers: 1, lowestPlayers: 1 });

    const secondRefresh = await serverService.fetchPublicServers({ force: true, limit: 10, maxPages: 1 });
    assert.deepEqual(getServerSummary(secondRefresh.servers), { availableServers: 2, lowestPlayers: 0 });
  } finally {
    global.fetch = originalFetch;
    serverService.cache = originalCache;
    serverService.inFlight = originalInFlight;
  }
});

test('keeps unknown player counts in the public list and sorts them after confirmed counts', async () => {
  const originalFetch = global.fetch;
  const originalCache = { ...serverService.cache, servers: [...serverService.cache.servers] };
  const originalInFlight = serverService.inFlight;
  serverService.cache = {
    servers: [],
    refreshedAt: null,
    source: 'empty',
    partial: false,
    lastError: null,
    rateLimitUntil: 0
  };
  global.fetch = async () => ({
    ok: true,
    status: 200,
    headers: new Headers(),
    json: async () => ({
      data: [
        { id: 'server-four-players', playing: 4, maxPlayers: 12, players: [] },
        { id: 'server-unknown', maxPlayers: 12, players: [] },
        { id: 'server-two-players', playing: 2, maxPlayers: 12, players: [] },
        { id: 'server-zero-players', playing: 0, maxPlayers: 12, players: [] },
        { id: 'server-one-player', playing: 1, maxPlayers: 12, players: [] }
      ],
      nextPageCursor: null
    })
  });

  try {
    const result = await serverService.fetchPublicServers({ force: true, limit: 10, maxPages: 1 });
    assert.deepEqual(result.servers.map((server) => server.players), [0, 1, 2, 4, null]);
  } finally {
    global.fetch = originalFetch;
    serverService.cache = originalCache;
    serverService.inFlight = originalInFlight;
  }
});

test('handles Roblox 429 rate limiting with a retry delay', async () => {
  const originalFetch = global.fetch;
  const originalCache = { ...serverService.cache, servers: [...serverService.cache.servers] };
  const originalInFlight = serverService.inFlight;
  let requestCount = 0;
  global.fetch = async () => {
    requestCount += 1;
    return {
      ok: false,
      status: 429,
      headers: new Headers({ 'Retry-After': '2' })
    };
  };

  try {
    const result = await serverService.fetchPublicServers({ force: true, limit: 100, maxPages: 1 });
    assert.equal(result.partial, true);
    assert.equal(result.retryAfterMs, 2000);
    const cooldownResult = await serverService.fetchPublicServers({ force: true, limit: 100, maxPages: 1 });
    assert.equal(cooldownResult.partial, true);
    assert.ok(cooldownResult.retryAfterMs > 0);
    assert.equal(requestCount, 1);
  } finally {
    global.fetch = originalFetch;
    serverService.cache = originalCache;
    serverService.inFlight = originalInFlight;
  }
});

test('keeps a good server cache when a refresh returns no eligible servers', async () => {
  const originalFetch = global.fetch;
  const originalCache = { ...serverService.cache, servers: [...serverService.cache.servers] };
  const originalInFlight = serverService.inFlight;

  serverService.cache = {
    servers: [{ jobId: 'cached-server-1', players: 1, maxPlayers: 10, occupancy: 10, fps: 0, ping: 0, isFull: false }],
    refreshedAt: Date.now(),
    source: 'fresh',
    partial: false,
    lastError: null,
    rateLimitUntil: 0
  };

  global.fetch = async () => ({
    ok: true,
    status: 200,
    headers: new Headers(),
    json: async () => ({
      data: [
        { id: 'full-server-1', playing: 10, maxPlayers: 10 },
        { id: 'bad value!', playing: 1, maxPlayers: 10 }
      ],
      nextPageCursor: null
    })
  });

  try {
    const result = await serverService.fetchPublicServers({ force: true, limit: 100, maxPages: 1 });
    assert.equal(result.partial, true);
    assert.equal(result.fromCache, true);
    assert.deepEqual(result.servers.map((server) => server.jobId), ['cached-server-1']);
  } finally {
    global.fetch = originalFetch;
    serverService.cache = originalCache;
    serverService.inFlight = originalInFlight;
  }
});

test('deduplicates, excludes invalid servers, and sorts by lowest player count', async () => {
  const originalFetch = global.fetch;
  const originalCache = { ...serverService.cache, servers: [...serverService.cache.servers] };
  const originalInFlight = serverService.inFlight;

  serverService.cache = {
    servers: [],
    refreshedAt: null,
    source: 'empty',
    partial: false,
    lastError: null,
    rateLimitUntil: 0
  };

  global.fetch = async () => ({
    ok: true,
    status: 200,
    headers: new Headers(),
    json: async () => ({
      data: [
        { id: 'server-with-8-players', playing: 8, maxPlayers: 20 },
        { id: 'server-with-2-players', playing: 2, maxPlayers: 20 },
        { id: 'SERVER-WITH-2-PLAYERS', playing: 2, maxPlayers: 20 },
        { id: 'full-server-2', playing: 20, maxPlayers: 20 },
        { id: '98664161516921', playing: 1, maxPlayers: 20 }
      ],
      nextPageCursor: null
    })
  });

  try {
    const result = await serverService.fetchPublicServers({ force: true, limit: 100, maxPages: 1 });
    assert.equal(result.partial, false);
    assert.deepEqual(result.servers.map((server) => server.jobId), ['server-with-2-players', 'server-with-8-players']);
  } finally {
    global.fetch = originalFetch;
    serverService.cache = originalCache;
    serverService.inFlight = originalInFlight;
  }
});

test('rejects invalid launch requests before opening Roblox', () => {
  assert.throws(() => requestJoin({ placeId: 'bad', serverJobId: 'ok' }), /Invalid Place ID/i);
  assert.throws(() => requestJoin({ placeId: '98664161516921', serverJobId: 'bad value!' }), /Invalid server Job ID/i);
});

test('prevents overlapping auto-hop candidates by ignoring already pending servers', () => {
  const pendingAttempts = new Set(['server-1']);
  const candidates = [{ id: 'server-1', players: 2, maxPlayers: 20 }, { id: 'server-2', players: 4, maxPlayers: 20 }];
  const result = avoidDuplicateAttempts(candidates, pendingAttempts);
  assert.deepEqual(result.map((server) => server.id), ['server-2']);
});
