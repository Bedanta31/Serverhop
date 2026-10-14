const test = require('node:test');
const assert = require('node:assert/strict');

const { AutoHopController } = require('../public/auto-hop-controller.js');
const { ServerSkipHistory, RESET_DELAY_MS, HISTORY_KEY } = require('../public/server-skip-history.js');

class FakeTimers {
  constructor() {
    this.now = 0;
    this.nextId = 1;
    this.timers = new Map();
  }

  setTimeout = (callback, delay) => {
    const id = this.nextId++;
    this.timers.set(id, { callback, at: this.now + delay });
    return id;
  };

  clearTimeout = (id) => this.timers.delete(id);

  async advance(ms) {
    const end = this.now + ms;
    while (true) {
      const next = [...this.timers.entries()].sort((a, b) => a[1].at - b[1].at)[0];
      if (!next || next[1].at > end) {
        break;
      }
      this.timers.delete(next[0]);
      this.now = next[1].at;
      next[1].callback();
      await flushPromises();
    }
    this.now = end;
    await flushPromises();
  }
}

function flushPromises() {
  return new Promise((resolve) => setImmediate(resolve));
}

function createStorage() {
  const values = new Map();
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value)),
    values
  };
}

function createHistory(timers, storage = createStorage(), onReset = () => {}) {
  return new ServerSkipHistory({
    storage,
    nowFn: () => timers.now,
    setTimeoutFn: timers.setTimeout,
    clearTimeoutFn: timers.clearTimeout,
    onReset
  });
}

test('manual successful hop resets only skip tracking after 15 minutes', async () => {
  const timers = new FakeTimers();
  const storage = createStorage();
  storage.setItem('monster-hopper-daily-hop-counter-v1', JSON.stringify({ count: 7 }));
  const history = createHistory(timers, storage);

  assert.equal(history.recordSuccessfulHop('manual-server', '2026-10-13T23:00'), true);
  history.history.visitedIds.push('visited-server');
  history.history.attemptCount += 1;
  assert.equal(history.history.resetAt, RESET_DELAY_MS);

  await timers.advance(RESET_DELAY_MS - 1);
  assert.deepEqual([...history.history.attemptedSet], ['manual-server']);
  await timers.advance(1);

  assert.deepEqual([...history.history.attemptedSet], []);
  assert.deepEqual(history.history.visitedIds, []);
  assert.equal(history.history.attemptCount, 0);
  assert.equal(history.history.resetAt, null);
  assert.deepEqual(JSON.parse(storage.getItem('monster-hopper-daily-hop-counter-v1')), { count: 7 });
  history.dispose();
});

test('each later successful hop replaces the previous reset deadline without duplicating timers', async () => {
  const timers = new FakeTimers();
  const history = createHistory(timers);
  history.recordSuccessfulHop('first-server');
  await timers.advance(5 * 60 * 1000);
  history.recordSuccessfulHop('second-server');

  assert.equal(timers.timers.size, 1);
  assert.equal(history.history.resetAt, timers.now + RESET_DELAY_MS);
  await timers.advance(10 * 60 * 1000);
  assert.deepEqual([...history.history.attemptedSet], ['first-server', 'second-server']);
  await timers.advance(5 * 60 * 1000);
  assert.equal(history.history.attemptedSet.size, 0);
  history.dispose();
});

test('restores the persisted reset deadline after a page reload', async () => {
  const timers = new FakeTimers();
  const storage = createStorage();
  const beforeReload = createHistory(timers, storage);
  beforeReload.recordSuccessfulHop('persisted-server');
  await timers.advance(8 * 60 * 1000);
  beforeReload.dispose();

  const afterReload = createHistory(timers, storage);
  assert.equal(afterReload.history.attemptedSet.has('persisted-server'), true);
  assert.equal(timers.timers.size, 1);
  await timers.advance(7 * 60 * 1000);
  assert.equal(afterReload.history.attemptedSet.size, 0);
  afterReload.dispose();
});

test('automatic successful hop schedules the same persistent server-skip reset', async () => {
  const timers = new FakeTimers();
  const history = createHistory(timers);
  const controller = new AutoHopController({
    getIntervalMs: () => 20000,
    refreshServers: async () => ({}),
    selectServer: () => ({ jobId: 'automatic-server' }),
    joinServer: (server) => history.recordSuccessfulHop(server.jobId),
    nowFn: () => timers.now,
    setTimeoutFn: timers.setTimeout,
    clearTimeoutFn: timers.clearTimeout
  });

  controller.start();
  await flushPromises();
  assert.equal(history.history.attemptedSet.has('automatic-server'), true);
  controller.stop();

  await timers.advance(RESET_DELAY_MS);
  assert.equal(history.history.attemptedSet.size, 0);
  history.dispose();
});

test('history without a persisted deadline is discarded instead of becoming an indefinite skip', () => {
  const timers = new FakeTimers();
  const storage = createStorage();
  storage.setItem(HISTORY_KEY, JSON.stringify({
    attemptedIds: ['old-server'],
    visitedIds: ['old-visit'],
    attemptCount: 1
  }));

  const history = createHistory(timers, storage);
  assert.equal(history.history.attemptedSet.size, 0);
  assert.deepEqual(history.history.visitedIds, []);
  assert.equal(timers.timers.size, 0);
  history.dispose();
});

test('expired history restored on startup clears before UI callbacks are needed', () => {
  const timers = new FakeTimers();
  timers.now = 1000;
  const storage = createStorage();
  storage.setItem(HISTORY_KEY, JSON.stringify({
    attemptedIds: ['expired-server'],
    visitedIds: ['expired-visit'],
    attemptCount: 2,
    resetAt: 500
  }));
  const history = createHistory(timers, storage);
  assert.equal(history.history.attemptedSet.size, 0);
  assert.equal(history.history.resetAt, null);
  assert.equal(timers.timers.size, 0);
  history.dispose();
});
