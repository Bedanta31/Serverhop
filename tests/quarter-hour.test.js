const test = require('node:test');
const assert = require('node:assert/strict');

const { getQuarterHourKey, getNextQuarterHourBoundary, shouldResetHistoryAt } = require('../src/quarterHour.js');

test('computes quarter-hour keys accurately', () => {
  const date = new Date(2026, 9, 12, 10, 30, 0, 0);
  assert.equal(getQuarterHourKey(date), '2026-10-12T10:30');
});

test('calculates the next actual quarter-hour boundary', () => {
  const date = new Date(2026, 9, 12, 10, 7, 42, 0);
  const boundary = getNextQuarterHourBoundary(date);
  assert.equal(boundary.getHours(), 10);
  assert.equal(boundary.getMinutes(), 15);
});

test('resets at :00, :15, :30, and :45 boundaries', () => {
  const times = [
    new Date(2026, 9, 12, 10, 0, 0, 0),
    new Date(2026, 9, 12, 10, 15, 0, 0),
    new Date(2026, 9, 12, 10, 30, 0, 0),
    new Date(2026, 9, 12, 10, 45, 0, 0)
  ];

  for (const value of times) {
    assert.equal(shouldResetHistoryAt(value), true);
  }

  assert.equal(shouldResetHistoryAt(new Date(2026, 9, 12, 10, 12, 0, 0)), false);
});
