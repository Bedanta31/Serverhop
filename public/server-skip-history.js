(function exposeServerSkipHistory(root, factory) {
  const exports = factory();
  if (typeof module === 'object' && module.exports) {
    module.exports = exports;
  } else {
    root.MonsterHopperServerSkipHistory = exports;
  }
}(globalThis, function createServerSkipHistoryModule() {
  const RESET_DELAY_MS = 15 * 60 * 1000;
  const HISTORY_KEY = 'monster-hopper-history';

  function normalizeId(value) {
    return String(value ?? '').trim().replace(/\s+/g, '').toLowerCase();
  }

  function createEmptyHistory() {
    return {
      attemptedIds: [],
      attemptedSet: new Set(),
      visitedIds: [],
      currentWindowKey: null,
      attemptCount: 0,
      resetAt: null
    };
  }

  class ServerSkipHistory {
    constructor({
      storage,
      nowFn = () => Date.now(),
      setTimeoutFn = (...args) => globalThis.setTimeout(...args),
      clearTimeoutFn = (timerId) => globalThis.clearTimeout(timerId),
      onReset = () => {},
      key = HISTORY_KEY
    }) {
      this.storage = storage;
      this.nowFn = nowFn;
      this.setTimeoutFn = setTimeoutFn;
      this.clearTimeoutFn = clearTimeoutFn;
      this.onReset = onReset;
      this.key = key;
      this.timerId = null;
      this.generation = 0;
      this.history = this._load();

      if (this.history.resetAt === null && (this.history.attemptedSet.size || this.history.visitedIds.length)) {
        this._clearTracking(false);
        this._save();
      } else if (this.history.resetAt !== null) {
        if (this.history.resetAt <= this.nowFn()) {
          this._clearTracking(false);
          this._save();
        } else {
          this._scheduleReset();
        }
      }
    }

    _load() {
      try {
        const raw = this.storage.getItem(this.key);
        if (!raw) {
          return createEmptyHistory();
        }
        const parsed = JSON.parse(raw);
        const attemptedIds = Array.isArray(parsed.attemptedIds)
          ? [...new Set(parsed.attemptedIds.map(normalizeId).filter(Boolean))]
          : [];
        const resetAt = Number(parsed.resetAt);
        return {
          attemptedIds,
          attemptedSet: new Set(attemptedIds),
          visitedIds: Array.isArray(parsed.visitedIds)
            ? [...new Set(parsed.visitedIds.map(normalizeId).filter(Boolean))]
            : [],
          currentWindowKey: parsed.currentWindowKey || null,
          attemptCount: Number.isSafeInteger(Number(parsed.attemptCount)) && Number(parsed.attemptCount) >= 0
            ? Number(parsed.attemptCount)
            : 0,
          resetAt: Number.isFinite(resetAt) && resetAt > 0 ? resetAt : null
        };
      } catch (error) {
        return createEmptyHistory();
      }
    }

    _save() {
      const attemptedIds = [...this.history.attemptedSet].sort();
      this.history.attemptedIds = attemptedIds;
      this.storage.setItem(this.key, JSON.stringify({
        attemptedIds,
        visitedIds: [...new Set(this.history.visitedIds.map(normalizeId).filter(Boolean))],
        currentWindowKey: this.history.currentWindowKey,
        attemptCount: this.history.attemptCount,
        resetAt: this.history.resetAt
      }));
    }

    recordSuccessfulHop(jobId, currentWindowKey = null, initiatedAt = this.nowFn()) {
      this.ensureCurrent();
      const normalizedId = normalizeId(jobId);
      if (!normalizedId) {
        return false;
      }

      this.history.attemptedSet.add(normalizedId);
      this.history.attemptedIds = [...this.history.attemptedSet];
      this.history.currentWindowKey = currentWindowKey;
      this.history.attemptCount += 1;
      this.history.resetAt = initiatedAt + RESET_DELAY_MS;
      this._save();
      this._scheduleReset();
      return true;
    }

    ensureCurrent() {
      if (this.history.resetAt === null) {
        return false;
      }
      if (this.history.resetAt <= this.nowFn()) {
        this._clearTracking(true);
        return true;
      }
      if (this.timerId === null) {
        this._scheduleReset();
      }
      return false;
    }

    _scheduleReset() {
      if (this.timerId !== null) {
        this.clearTimeoutFn(this.timerId);
        this.timerId = null;
      }

      this.generation += 1;
      const generation = this.generation;
      const tick = () => {
        if (generation !== this.generation) {
          return;
        }
        this.timerId = null;
        const remaining = this.history.resetAt - this.nowFn();
        if (remaining > 0) {
          this.timerId = this.setTimeoutFn(tick, Math.min(remaining, 2147483647));
          return;
        }
        this._clearTracking(true);
      };

      const remaining = Math.max(0, this.history.resetAt - this.nowFn());
      this.timerId = this.setTimeoutFn(tick, Math.min(remaining, 2147483647));
    }

    _clearTracking(notify) {
      if (this.timerId !== null) {
        this.clearTimeoutFn(this.timerId);
        this.timerId = null;
      }
      this.generation += 1;
      this.history.attemptedIds = [];
      this.history.attemptedSet.clear();
      this.history.visitedIds = [];
      this.history.currentWindowKey = null;
      this.history.attemptCount = 0;
      this.history.resetAt = null;
      this._save();
      if (notify) {
        this.onReset();
      }
    }

    dispose() {
      if (this.timerId !== null) {
        this.clearTimeoutFn(this.timerId);
        this.timerId = null;
      }
      this.generation += 1;
    }
  }

  return { ServerSkipHistory, RESET_DELAY_MS, HISTORY_KEY };
}));
