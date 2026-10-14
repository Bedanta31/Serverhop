const test = require('node:test');
const assert = require('node:assert/strict');

const { AutoHopController } = require('../public/auto-hop-controller.js');

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
      if (!next || next[1].at > end) break;
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

function makeController(overrides = {}) {
  const timers = new FakeTimers();
  const calls = { refresh: 0, select: 0, join: [], countdown: [], statuses: [], messages: [] };
  let servers = [{ jobId: 'server-1' }];
  const controller = new AutoHopController({
    getIntervalMs: () => 20000,
    refreshServers: async () => {
      calls.refresh += 1;
      return {};
    },
    selectServer: () => {
      calls.select += 1;
      return servers[0] || null;
    },
    joinServer: (server) => calls.join.push(server.jobId),
    onStatus: (status) => calls.statuses.push(status),
    onCountdown: (value) => calls.countdown.push(value),
    onMessage: (message) => calls.messages.push(message),
    nowFn: () => timers.now,
    setTimeoutFn: timers.setTimeout,
    clearTimeoutFn: timers.clearTimeout,
    ...overrides
  });
  return { controller, timers, calls, setServers: (value) => { servers = value; } };
}

test('starts the first join immediately and starts the post-launch refresh countdown', async () => {
  const events = [];
  const { controller } = makeController({
    selectServer: () => ({ jobId: 'first' }),
    joinServer: (server) => events.push(`join:${server.jobId}`),
    onCountdown: (value, phase) => { if (value !== null) events.push(`${phase || 'attempt'}:${value}`); }
  });

  controller.start();
  await flushPromises();
  assert.deepEqual(events, ['join:first', 'refresh:5']);
  controller.stop();
});

test('refreshes five seconds after launch and selects from refreshed results after the interval', async () => {
  const { controller, timers, calls, setServers } = makeController({
    refreshServers: async () => {
      calls.refresh += 1;
      setServers([{ jobId: 'refreshed-server' }]);
      return {};
    }
  });
  controller.start();
  await flushPromises();
  await timers.advance(5000);
  assert.equal(calls.refresh, 1);
  assert.deepEqual(calls.join, ['server-1']);
  await timers.advance(15000);
  assert.deepEqual(calls.join, ['server-1', 'refreshed-server']);
  controller.stop();
});

test('refreshes at five seconds while a join request is pending and keeps the interval anchored to launch', async () => {
  let finishJoin;
  const { controller, timers, calls } = makeController({
    joinServer: (server) => {
      calls.join.push(server.jobId);
      if (calls.join.length === 1) return new Promise((resolve) => { finishJoin = resolve; });
      return undefined;
    }
  });
  controller.start();
  await timers.advance(5000);
  assert.equal(calls.refresh, 1);
  await timers.advance(5000);
  assert.deepEqual(calls.join, ['server-1']);
  finishJoin();
  await flushPromises();
  await timers.advance(10000);
  assert.deepEqual(calls.join, ['server-1', 'server-1']);
  controller.stop();
});

test('Stop cancels the scheduled post-launch refresh', async () => {
  const { controller, timers, calls } = makeController();
  controller.start();
  await flushPromises();
  controller.stop();
  await timers.advance(5000);
  assert.deepEqual(calls.join, ['server-1']);
  assert.equal(calls.refresh, 0);
  assert.equal(timers.timers.size, 0);
});

test('Stop during async selection prevents a join and duplicate starts are ignored', async () => {
  let finishSelection;
  const { controller, calls } = makeController({
    selectServer: () => new Promise((resolve) => { finishSelection = resolve; })
  });
  assert.equal(controller.start(), true);
  assert.equal(controller.start(), false);
  controller.stop();
  finishSelection({ jobId: 'stale' });
  await flushPromises();
  assert.deepEqual(calls.join, []);
});

test('429 cooldown delays the next automatic attempt after the post-launch refresh', async () => {
  const { controller, timers, calls } = makeController({
    refreshServers: async () => {
      calls.refresh += 1;
      return calls.refresh === 1 ? { retryAfterMs: 5000 } : {};
    }
  });
  controller.start();
  await flushPromises();
  await timers.advance(5000);
  assert.equal(calls.refresh, 1);
  await timers.advance(14000);
  assert.deepEqual(calls.join, ['server-1']);
  await timers.advance(1000);
  assert.deepEqual(calls.join, ['server-1', 'server-1']);
  controller.stop();
});

test('a stopped run cannot continue after its old join response resolves', async () => {
  let finishJoin;
  const { controller, timers, calls } = makeController({
    joinServer: (server) => {
      calls.join.push(server.jobId);
      if (calls.join.length === 1) return new Promise((resolve) => { finishJoin = resolve; });
    }
  });
  controller.start();
  await timers.advance(2000);
  controller.stop();
  finishJoin();
  await flushPromises();
  assert.deepEqual(calls.join, ['server-1']);
  assert.deepEqual(calls.statuses, ['running', 'stopped']);
});

test('enforces a 15-second minimum even when the configured interval is shorter', async () => {
  const { controller, timers, calls } = makeController({
    getIntervalMs: () => 1000
  });
  controller.start();
  await flushPromises();
  await timers.advance(14999);
  assert.deepEqual(calls.join, ['server-1']);
  await timers.advance(1);
  assert.deepEqual(calls.join, ['server-1', 'server-1']);
  controller.stop();
});
