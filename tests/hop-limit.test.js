const test = require('node:test');
const assert = require('node:assert/strict');

const { HopLimitService, getNextUtcReset, getUtcDateKey } = require('../src/hopLimitService.js');

test('uses UTC dates and UTC midnight reset boundaries', () => {
  const date = new Date('2026-10-13T23:59:30.000Z');
  assert.equal(getUtcDateKey(date), '2026-10-13');
  assert.equal(getNextUtcReset(date).toISOString(), '2026-10-14T00:00:00.000Z');
});

test('reserves and commits only up to the configured daily limit', () => {
  const service = new HopLimitService({
    limit: 2,
    now: () => new Date('2026-10-13T12:00:00.000Z')
  });

  const first = service.reserve();
  assert.equal(first.ok, true);
  assert.equal(first.status.remaining, 1);
  assert.equal(service.commit(first.reservationId).count, 1);

  const second = service.reserve();
  assert.equal(second.ok, true);
  assert.equal(service.commit(second.reservationId).count, 2);

  const blocked = service.reserve();
  assert.equal(blocked.ok, false);
  assert.equal(blocked.status.limitReached, true);
  assert.equal(blocked.status.remaining, 0);
});

test('released reservations do not consume daily hops', () => {
  const service = new HopLimitService({
    limit: 1,
    now: () => new Date('2026-10-13T12:00:00.000Z')
  });

  const reservation = service.reserve();
  assert.equal(reservation.ok, true);
  assert.equal(service.release(reservation.reservationId).count, 0);
  assert.equal(service.reserve().ok, true);
});

test('counter resets when the UTC date changes', () => {
  let current = new Date('2026-10-13T23:59:59.000Z');
  const service = new HopLimitService({ limit: 1, now: () => current });
  const reservation = service.reserve();
  service.commit(reservation.reservationId);
  assert.equal(service.getStatus().limitReached, true);

  current = new Date('2026-10-14T00:00:00.000Z');
  const status = service.getStatus();
  assert.equal(status.utcDate, '2026-10-14');
  assert.equal(status.count, 0);
  assert.equal(status.limitReached, false);
});
