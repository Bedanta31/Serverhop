(function exposeController(root, factory) {
  const exports = factory();
  if (typeof module === 'object' && module.exports) {
    module.exports = exports;
  } else {
    root.MonsterHopperAutoHopController = exports;
  }
}(globalThis, function createControllerModule() {
  class AutoHopController {
    constructor({
      getIntervalMs,
      refreshServers,
      selectServer,
      joinServer,
      onStatus = () => {},
      onCountdown = () => {},
      onMessage = () => {},
      onJoinError = () => {},
      postLaunchRefreshDelayMs = 5000,
      nowFn = () => Date.now(),
      setTimeoutFn = (...args) => globalThis.setTimeout(...args),
      clearTimeoutFn = (timerId) => globalThis.clearTimeout(timerId)
    }) {
      this.getIntervalMs = getIntervalMs;
      this.refreshServers = refreshServers;
      this.selectServer = selectServer;
      this.joinServer = joinServer;
      this.onStatus = onStatus;
      this.onCountdown = onCountdown;
      this.onMessage = onMessage;
      this.onJoinError = onJoinError;
      this.postLaunchRefreshDelayMs = postLaunchRefreshDelayMs;
      this.nowFn = nowFn;
      this.setTimeoutFn = setTimeoutFn;
      this.clearTimeoutFn = clearTimeoutFn;
      this.enabled = false;
      this.runId = 0;
      this.timerId = null;
      this.abortController = null;
      this.pendingJoin = null;
      this.refreshResolve = null;
    }

    start() {
      if (this.enabled) {
        return false;
      }

      this.enabled = true;
      this.runId += 1;
      this.abortController = new AbortController();
      this.onStatus('running');
      this.onCountdown(null);
      void this._runAttempt(this.runId, false);
      return true;
    }

    stop() {
      if (!this.enabled) {
        return false;
      }

      this.enabled = false;
      this.runId += 1;
      this.abortController?.abort();
      this.abortController = null;
      if (this.timerId !== null) {
        this.clearTimeoutFn(this.timerId);
        this.timerId = null;
      }
      if (this.refreshResolve) {
        this.refreshResolve({ cancelled: true });
        this.refreshResolve = null;
      }
      this.onCountdown(null);
      this.onStatus('stopped');
      return true;
    }

    _isCurrent(runId) {
      return this.enabled && this.runId === runId;
    }

    async _runAttempt(runId) {
      if (!this._isCurrent(runId)) {
        return;
      }

      const signal = this.abortController.signal;
      let nextDelayMs = this._getIntervalMs();

      if (!this._isCurrent(runId)) {
        return;
      }

      let server;
      try {
        const selection = this.selectServer(signal);
        server = selection && typeof selection.then === 'function' ? await selection : selection;
      } catch (error) {
        if (!this._isCurrent(runId)) {
          return;
        }
        this.onMessage(error instanceof Error ? error.message : 'Could not choose a server.');
      }

      if (!this._isCurrent(runId)) {
        return;
      }

      if (!server) {
        this.onMessage('No eligible servers are available. Trying again after the wait.');
        this._scheduleAttempt(runId, nextDelayMs);
        return;
      }

      const launchStartedAt = this.nowFn();
      let joinPromise;
      try {
        joinPromise = Promise.resolve(this.joinServer(server, signal));
      } catch (error) {
        joinPromise = Promise.reject(error);
      }

      const trackedJoin = joinPromise.catch((error) => {
        if (this._isCurrent(runId)) {
          this.onMessage(error instanceof Error ? error.message : 'Join request failed.');
          this.onJoinError(error);
        }
        throw error;
      });
      this.pendingJoin = trackedJoin;
      const refreshPromise = this._schedulePostLaunchRefresh(runId, launchStartedAt, signal);
      try {
        await trackedJoin;
      } catch (error) {
        if (this.pendingJoin === trackedJoin) {
          this.pendingJoin = null;
        }
      }

      if (this.pendingJoin === trackedJoin) {
        this.pendingJoin = null;
      }

      const refreshOutcome = await refreshPromise;
      if (!this._isCurrent(runId) || refreshOutcome.cancelled) {
        return;
      }
      if (refreshOutcome.error) {
        this.onMessage(refreshOutcome.error instanceof Error
          ? refreshOutcome.error.message
          : 'Server refresh failed.');
      }

      const elapsedMs = Math.max(0, this.nowFn() - launchStartedAt);
      const intervalRemainingMs = Math.max(0, nextDelayMs - elapsedMs);
      const retryDelay = Number(refreshOutcome.result?.retryAfterMs || refreshOutcome.error?.retryAfterMs || 0);
      const retryAfterMs = Number.isFinite(retryDelay) ? Math.max(0, retryDelay) : 0;
      this._scheduleAttempt(runId, Math.max(intervalRemainingMs, retryAfterMs));
    }

    _getIntervalMs() {
      const intervalMs = Number(this.getIntervalMs());
      return Math.max(15000, Number.isFinite(intervalMs) ? intervalMs : 15000);
    }

    _scheduleAttempt(runId, delayMs) {
      if (!this._isCurrent(runId)) {
        return;
      }

      if (this.timerId !== null) {
        this.clearTimeoutFn(this.timerId);
      }

      let remainingMs = Math.max(0, Number(delayMs) || 0);
      if (remainingMs === 0) {
        this.onCountdown(0);
        void this._runAttempt(runId);
        return;
      }
      this.onCountdown(Math.ceil(remainingMs / 1000));

      const tick = () => {
        if (!this._isCurrent(runId)) {
          return;
        }
        remainingMs = Math.max(0, remainingMs - 1000);
        if (remainingMs === 0) {
          this.timerId = null;
          this.onCountdown(0);
          void this._runAttempt(runId);
          return;
        }

        this.onCountdown(Math.ceil(remainingMs / 1000));
        this.timerId = this.setTimeoutFn(tick, Math.min(1000, remainingMs));
      };

      this.timerId = this.setTimeoutFn(tick, Math.min(1000, remainingMs));
    }

    _schedulePostLaunchRefresh(runId, launchStartedAt, signal) {
      if (!this._isCurrent(runId)) {
        return Promise.resolve({ cancelled: true });
      }

      if (this.timerId !== null) {
        this.clearTimeoutFn(this.timerId);
      }

      const refreshAt = launchStartedAt + Math.max(0, Number(this.postLaunchRefreshDelayMs) || 5000);
      let remainingMs = Math.max(0, refreshAt - this.nowFn());
      this.onCountdown(Math.ceil(remainingMs / 1000), 'refresh');

      return new Promise((resolve) => {
        this.refreshResolve = resolve;
        const tick = () => {
          if (!this._isCurrent(runId)) {
            this.refreshResolve = null;
            resolve({ cancelled: true });
            return;
          }

          remainingMs = Math.max(0, refreshAt - this.nowFn());
          if (remainingMs > 0) {
            this.onCountdown(Math.ceil(remainingMs / 1000), 'refresh');
            this.timerId = this.setTimeoutFn(tick, Math.min(1000, remainingMs));
            return;
          }

          this.timerId = null;
          this.refreshResolve = null;
          this.onCountdown(0, 'refresh');
          Promise.resolve()
            .then(() => this.refreshServers(signal))
            .then((result) => resolve({ result }), (error) => resolve({ error }));
        };

        this.timerId = this.setTimeoutFn(tick, Math.min(1000, remainingMs));
      });
    }
  }

  return { AutoHopController };
}));
