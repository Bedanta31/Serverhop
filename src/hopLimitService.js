const { DAILY_HOP_LIMIT } = require('./config.js');

function getUtcDateKey(date = new Date()) {
  const year = date.getUTCFullYear();
  const month = String(date.getUTCMonth() + 1).padStart(2, '0');
  const day = String(date.getUTCDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function getNextUtcReset(date = new Date()) {
  return new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate() + 1, 0, 0, 0, 0));
}

class HopLimitService {
  constructor({ limit = DAILY_HOP_LIMIT, now = () => new Date() } = {}) {
    this.limit = limit;
    this.now = now;
    this.count = 0;
    this.pending = new Set();
    this.utcDate = getUtcDateKey(this.now());
    this.nextReservationId = 1;
  }

  _ensureCurrentDay() {
    const utcDate = getUtcDateKey(this.now());
    if (this.utcDate !== utcDate) {
      this.utcDate = utcDate;
      this.count = 0;
      this.pending.clear();
    }
  }

  getStatus() {
    this._ensureCurrentDay();
    const effectiveUsed = this.count + this.pending.size;
    return {
      limit: this.limit,
      count: this.count,
      pending: this.pending.size,
      remaining: Math.max(0, this.limit - effectiveUsed),
      utcDate: this.utcDate,
      nextResetAt: getNextUtcReset(this.now()).toISOString(),
      timezone: 'UTC',
      limitReached: effectiveUsed >= this.limit
    };
  }

  reserve() {
    const status = this.getStatus();
    if (status.limitReached) {
      return {
        ok: false,
        reservationId: null,
        status
      };
    }

    const reservationId = String(this.nextReservationId);
    this.nextReservationId += 1;
    this.pending.add(reservationId);

    return {
      ok: true,
      reservationId,
      status: this.getStatus()
    };
  }

  commit(reservationId) {
    this._ensureCurrentDay();
    if (!this.pending.has(reservationId)) {
      return this.getStatus();
    }

    this.pending.delete(reservationId);
    this.count = Math.min(this.limit, this.count + 1);
    return this.getStatus();
  }

  release(reservationId) {
    this._ensureCurrentDay();
    this.pending.delete(reservationId);
    return this.getStatus();
  }
}

module.exports = {
  HopLimitService,
  getUtcDateKey,
  getNextUtcReset,
  hopLimitService: new HopLimitService()
};
