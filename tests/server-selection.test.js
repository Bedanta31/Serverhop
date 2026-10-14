const test = require('node:test');
const assert = require('node:assert/strict');

const {
  pickLowestPlayerServer,
  excludeFullServers,
  avoidDuplicateAttempts,
  normalizeServerId,
  filterAttemptedServers,
  getQuarterHourKey
} = require('../src/serverSelection.js');

test('picks the lowest-player eligible server', () => {
  const servers = [
    { id: 'a', players: 18, maxPlayers: 50 },
    { id: 'b', players: 4, maxPlayers: 12 },
    { id: 'c', players: 10, maxPlayers: 12 },
    { id: 'd', players: 0, maxPlayers: 20 }
  ];

  const result = pickLowestPlayerServer(servers);
  assert.equal(result.id, 'd');
});

test('excludes full servers from the candidate pool', () => {
  const servers = [
    { id: 'full1', players: 12, maxPlayers: 12 },
    { id: 'open1', players: 7, maxPlayers: 20 },
    { id: 'open2', players: 2, maxPlayers: 10 }
  ];

  const result = excludeFullServers(servers);
  assert.deepEqual(result.map(server => server.id), ['open1', 'open2']);
});

test('normalizes attempted server ids before filtering', () => {
  const attempted = ['  Job-123  ', 'JOB-456', 'job-123'];
  const candidates = [
    { id: 'job-123', players: 4, maxPlayers: 10 },
    { id: 'job-456', players: 5, maxPlayers: 10 },
    { id: 'job-789', players: 6, maxPlayers: 10 }
  ];

  const result = avoidDuplicateAttempts(candidates, attempted);
  assert.deepEqual(result.map(server => server.id), ['job-789']);
  assert.equal(normalizeServerId('  AB C  '), 'abc');
});

test('filterAttemptedServers keeps eligible results when the skip toggle is off', () => {
  const attempted = ['job-1', 'job-2'];
  const candidates = [
    { jobId: 'job-1', players: 2, maxPlayers: 10 },
    { jobId: 'job-3', players: 3, maxPlayers: 10 },
    { jobId: 'job-4', players: 4, maxPlayers: 10 }
  ];

  assert.deepEqual(filterAttemptedServers(candidates, attempted, false).map(server => server.jobId), ['job-1', 'job-3', 'job-4']);
  assert.deepEqual(filterAttemptedServers(candidates, attempted, true).map(server => server.jobId), ['job-3', 'job-4']);
});

test('quarter-hour keys are stable and normalized by time window', () => {
  const date = new Date('2026-10-12T12:16:00');
  assert.equal(getQuarterHourKey(date), '2026-10-12T12:15');
  assert.notEqual(getQuarterHourKey(new Date('2026-10-12T12:14:00')), getQuarterHourKey(new Date('2026-10-12T12:15:00')));
});

test('auto-hop never selects a server excluded by the attempted filter', () => {
  const attempted = ['job-123'];
  const candidates = [
    { jobId: 'job-123', players: 2, maxPlayers: 10 },
    { jobId: 'job-456', players: 3, maxPlayers: 10 },
    { jobId: 'job-789', players: 5, maxPlayers: 10 }
  ];

  const filtered = filterAttemptedServers(candidates, attempted, true);
  assert.deepEqual(filtered.map(server => server.jobId), ['job-456', 'job-789']);
});

test('all attempted servers are treated as an empty visible pool', () => {
  const attempted = ['job-1', 'job-2'];
  const candidates = [
    { jobId: 'job-1', players: 1, maxPlayers: 10 },
    { jobId: 'job-2', players: 2, maxPlayers: 10 }
  ];

  const filtered = filterAttemptedServers(candidates, attempted, true);
  assert.deepEqual(filtered, []);
});
