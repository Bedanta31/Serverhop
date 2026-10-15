const GAME_PLACE_ID = '98664161516921';
const DAILY_HOP_LIMIT = 50;
const DAILY_HOP_COUNTER_KEY = 'monster-hopper-daily-hop-counter-v1';
const POST_LAUNCH_REFRESH_DELAY_MS = 5000;
const MIN_HOP_INTERVAL_SECONDS = 15;
const LOCAL_AGENT_URL = 'http://127.0.0.1:3210';
const API_BASE_URL = String(window.MONSTER_HOPPER_CONFIG?.apiBaseUrl || '').replace(/\/+$/, '');
const { AutoHopController } = window.MonsterHopperAutoHopController;
const { ServerSkipHistory } = window.MonsterHopperServerSkipHistory;
const {
  normaliseServerPayload,
  hasValidPlayerCount,
  isDisplayableServer,
  sortByPlayerCount,
  deduplicateServers,
  getServerSummary,
  isLowestPlayerServer,
  formatPlayerCapacity
} = window.MonsterHopperServerData;

function normalizeServerId(value) {
  return String(value ?? '').trim().replace(/\s+/g, '').toLowerCase();
}

function buildAttemptedSet(values = []) {
  const set = new Set();
  for (const value of values || []) {
    const normalized = normalizeServerId(value);
    if (normalized) {
      set.add(normalized);
    }
  }
  return set;
}

const state = {
  servers: [],
  page: 1,
  pageSize: 6,
  sortMode: 'lowestPlayers',
  searchTerm: '',
  autoHop: {
    enabled: false,
    intervalMs: MIN_HOP_INTERVAL_SECONDS * 1000,
    attemptedIds: new Set()
  },
  history: null,
  dailyHop: loadDailyHopCounter(),
  refreshMeta: {
    status: 'idle',
    lastUpdated: null,
    source: 'none'
  }
};

const refs = {};
let autoHopController;
let refreshPromise = null;
let pendingJoinPromise = null;
let postLaunchRefreshTimerId = null;
let dailyResetTimerId = null;
let profileAvatarTimerId = null;
let refreshRequestSeq = 0;
let refreshInProgress = false;
let serverSkipHistory;

function apiUrl(path) {
  return `${API_BASE_URL}${path}`;
}

document.addEventListener('DOMContentLoaded', () => {
  cacheElements();
  serverSkipHistory = new ServerSkipHistory({
    storage: window.localStorage,
    onReset: () => {
      renderStats();
      renderServerList();
    }
  });
  state.history = serverSkipHistory.history;
  createAutoHopController();
  bindEvents();
  ensureHistoryWindow();
  ensureDailyHopWindow();
  updateDailyHopDisplay();
  renderStats();
  loadBackendHopLimit().catch(() => {});
  scheduleDailyResetCheck();
  void refreshProfileAvatar();
  refreshServerList({ manual: true }).catch(() => {});
});

function cacheElements() {
  refs.connectionStatusText = document.getElementById('connectionStatusText');
  refs.refreshButton = document.getElementById('refreshButton');
  refs.settingsButton = document.getElementById('settingsButton');
  refs.settingsDialog = document.getElementById('settingsDialog');
  refs.settingsCloseButton = document.getElementById('settingsCloseButton');
  refs.autoHopMessage = document.getElementById('autoHopMessage');
  refs.intervalInput = document.getElementById('intervalInput');
  refs.startAutoHopButton = document.getElementById('startAutoHopButton');
  refs.stopAutoHopButton = document.getElementById('stopAutoHopButton');
  refs.lowestPlayerFirstToggle = document.getElementById('lowestPlayerFirstToggle');
  refs.skipAttemptedToggle = document.getElementById('skipAttemptedToggle');
  refs.pauseOnFailureToggle = document.getElementById('pauseOnFailureToggle');
  refs.countdownDisplay = document.getElementById('countdownDisplay');
  refs.serverList = document.getElementById('serverList');
  refs.searchInput = document.getElementById('searchInput');
  refs.sortSelect = document.getElementById('sortSelect');
  refs.refreshStatusText = document.getElementById('refreshStatusText');
  refs.previousPageButton = document.getElementById('previousPageButton');
  refs.nextPageButton = document.getElementById('nextPageButton');
  refs.pageIndicator = document.getElementById('pageIndicator');
  refs.statServersLoaded = document.getElementById('statServersAvailable');
  refs.statLowestPlayers = document.getElementById('statLowestPlayers');
  refs.statAutoHopState = document.getElementById('statAutoHopState');
  refs.statDailyHops = document.getElementById('statDailyHops');
  refs.dailyHopResetDisplay = document.getElementById('dailyHopResetDisplay');
  refs.hopLimitMessage = document.getElementById('hopLimitMessage');
  refs.profileAvatar = document.getElementById('profileAvatar');
  refs.profileAvatarFallback = document.getElementById('profileAvatarFallback');
}

function bindEvents() {
  refs.refreshButton.addEventListener('click', () => refreshServerList({ manual: true }).catch(() => {}));
  refs.settingsButton.addEventListener('click', () => refs.settingsDialog.showModal());
  refs.settingsCloseButton.addEventListener('click', () => refs.settingsDialog.close());
  refs.startAutoHopButton.addEventListener('click', startAutoHop);
  refs.stopAutoHopButton.addEventListener('click', stopAutoHop);
  refs.skipAttemptedToggle.addEventListener('change', () => {
    if (!refreshInProgress) {
      renderServerList();
      renderStats();
    }
  });
  refs.searchInput.addEventListener('input', (event) => {
    state.searchTerm = event.target.value.trim().toLowerCase();
    state.page = 1;
    if (!refreshInProgress) {
      renderServerList();
      renderStats();
    }
  });
  refs.sortSelect.addEventListener('change', (event) => {
    state.sortMode = event.target.value;
    if (!refreshInProgress) {
      renderServerList();
      renderStats();
    }
  });
  refs.previousPageButton.addEventListener('click', () => {
    if (!refreshInProgress && state.page > 1) {
      state.page -= 1;
      renderServerList();
    }
  });
  refs.nextPageButton.addEventListener('click', () => {
    const maxPages = Math.max(1, Math.ceil(getVisibleServers().length / state.pageSize));
    if (!refreshInProgress && state.page < maxPages) {
      state.page += 1;
      renderServerList();
    }
  });
  refs.intervalInput.addEventListener('input', () => {
    const value = Number(refs.intervalInput.value);
    const intervalSeconds = Number.isFinite(value)
      ? Math.max(MIN_HOP_INTERVAL_SECONDS, Math.min(120, value))
      : MIN_HOP_INTERVAL_SECONDS;
    state.autoHop.intervalMs = intervalSeconds * 1000;
  });
  refs.intervalInput.addEventListener('change', getConfiguredIntervalMs);
}

function getConfiguredIntervalMs() {
  const value = Number(refs.intervalInput.value);
  const intervalSeconds = Number.isFinite(value)
    ? Math.max(MIN_HOP_INTERVAL_SECONDS, Math.min(120, value))
    : MIN_HOP_INTERVAL_SECONDS;
  refs.intervalInput.value = String(intervalSeconds);
  state.autoHop.intervalMs = intervalSeconds * 1000;
  return state.autoHop.intervalMs;
}

function createAutoHopController() {
  autoHopController = new AutoHopController({
    getIntervalMs: getConfiguredIntervalMs,
    refreshServers: (signal) => refreshServerList({ manual: true, signal }),
    selectServer: () => {
      ensureHistoryWindow();
      return sortByPlayerCount(getEligibleServerPool().filter(isEligibleServer))[0] || null;
    },
    joinServer: (server, signal) => {
      return sendJoinRequest(server, { signal, scheduleRefreshAfterLaunch: false });
    },
    postLaunchRefreshDelayMs: POST_LAUNCH_REFRESH_DELAY_MS,
    onStatus: updateAutoHopStatus,
    onCountdown: (seconds, phase = 'attempt') => {
      refs.countdownDisplay.textContent = seconds === null
        ? (state.autoHop.enabled ? 'Starting now' : 'No countdown')
        : (phase === 'refresh' ? `Refreshing list in ${seconds} seconds` : `Next attempt in ${seconds} seconds`);
    },
    onMessage: (message) => {
      refs.autoHopMessage.textContent = message;
    },
    onJoinError: (error) => {
      if (isDailyLimitReached()) {
        stopAutoHop();
        return;
      }
      if (error.stopAutoHop || refs.pauseOnFailureToggle.checked) {
        stopAutoHop();
      }
    }
  });
}

function updateAutoHopStatus(status) {
  state.autoHop.enabled = status === 'running';
  const message = state.autoHop.enabled ? 'Auto-Hop is running.' : 'Auto-Hop is stopped.';
  refs.statAutoHopState.textContent = state.autoHop.enabled ? 'Running' : 'Stopped';
  refs.autoHopMessage.textContent = message;
  refs.connectionStatusText.textContent = message;
  renderStats();
}

function getUtcDateKey(date = new Date()) {
  const year = date.getUTCFullYear();
  const month = String(date.getUTCMonth() + 1).padStart(2, '0');
  const day = String(date.getUTCDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function getNextUtcReset(date = new Date()) {
  return new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate() + 1, 0, 0, 0, 0));
}

function loadDailyHopCounter() {
  const fallback = { utcDate: getUtcDateKey(), count: 0, limit: DAILY_HOP_LIMIT, backendCount: 0 };
  try {
    const raw = localStorage.getItem(DAILY_HOP_COUNTER_KEY);
    if (!raw) {
      return fallback;
    }

    const parsed = JSON.parse(raw);
    const utcDate = typeof parsed.utcDate === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(parsed.utcDate)
      ? parsed.utcDate
      : fallback.utcDate;
    const count = Number(parsed.count);
    return {
      utcDate,
      count: Number.isInteger(count) && count >= 0 ? Math.min(DAILY_HOP_LIMIT, count) : 0,
      limit: DAILY_HOP_LIMIT,
      backendCount: 0
    };
  } catch (error) {
    return fallback;
  }
}

function saveDailyHopCounter() {
  localStorage.setItem(DAILY_HOP_COUNTER_KEY, JSON.stringify({
    utcDate: state.dailyHop.utcDate,
    count: Math.max(0, Math.min(DAILY_HOP_LIMIT, Number(state.dailyHop.count || 0))),
    limit: DAILY_HOP_LIMIT
  }));
}

function ensureDailyHopWindow(date = new Date()) {
  const utcDate = getUtcDateKey(date);
  if (!state.dailyHop || state.dailyHop.utcDate !== utcDate) {
    state.dailyHop = { utcDate, count: 0, limit: DAILY_HOP_LIMIT, backendCount: 0 };
    saveDailyHopCounter();
  }
  return state.dailyHop;
}

function syncHopLimitFromServer(hopLimit) {
  if (!hopLimit || typeof hopLimit !== 'object') {
    return;
  }

  ensureDailyHopWindow();
  if (hopLimit.utcDate === state.dailyHop.utcDate) {
    const backendCount = Number(hopLimit.count || 0);
    state.dailyHop.backendCount = Number.isFinite(backendCount) ? Math.max(0, backendCount) : 0;
    state.dailyHop.count = Math.max(Number(state.dailyHop.count || 0), state.dailyHop.backendCount);
    state.dailyHop.limit = Number(hopLimit.limit || DAILY_HOP_LIMIT);
    saveDailyHopCounter();
    updateDailyHopDisplay();
  }
}

async function loadBackendHopLimit() {
  try {
    const response = await fetch(apiUrl('/api/hop-limit'), { headers: { Accept: 'application/json' } });
    const payload = await response.json();
    if (response.ok && payload.success) {
      syncHopLimitFromServer(payload.data);
    }
  } catch (error) {
    refs.hopLimitMessage.textContent = 'Daily hop limit status could not be loaded from the configured API.';
  }
}

function getDailyHopCount() {
  ensureDailyHopWindow();
  return Math.max(0, Math.min(DAILY_HOP_LIMIT, Number(state.dailyHop.count || 0)));
}

function isDailyLimitReached() {
  return getDailyHopCount() >= DAILY_HOP_LIMIT;
}

function recordSuccessfulHop(hopLimit) {
  ensureDailyHopWindow();
  if (hopLimit && hopLimit.utcDate === state.dailyHop.utcDate) {
    syncHopLimitFromServer(hopLimit);
    return;
  }

  state.dailyHop.count = Math.min(DAILY_HOP_LIMIT, getDailyHopCount() + 1);
  saveDailyHopCounter();
  updateDailyHopDisplay();
}

function formatUtcResetCountdown(date = new Date()) {
  const remainingMs = Math.max(0, getNextUtcReset(date).getTime() - date.getTime());
  const totalMinutes = Math.ceil(remainingMs / 60000);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  return `${hours}h ${minutes}m remaining`;
}

function updateDailyHopDisplay() {
  ensureDailyHopWindow();
  const count = getDailyHopCount();
  refs.statDailyHops.textContent = `${count}/${DAILY_HOP_LIMIT}`;
  refs.dailyHopResetDisplay.textContent = `Resets at 00:00 UTC - ${formatUtcResetCountdown()}`;
  refs.hopLimitMessage.textContent = isDailyLimitReached()
    ? 'Daily UTC hop limit reached. Refresh stays available; launches resume after 00:00 UTC.'
    : `Daily Hops: ${count}/${DAILY_HOP_LIMIT}`;

  const blockLaunches = isDailyLimitReached() || Boolean(pendingJoinPromise);
  refs.startAutoHopButton.disabled = isDailyLimitReached();
  refs.serverList?.querySelectorAll('[data-join-id]').forEach((button) => {
    button.disabled = blockLaunches;
  });
}

function scheduleDailyResetCheck() {
  if (dailyResetTimerId !== null) {
    clearTimeout(dailyResetTimerId);
  }

  const delayMs = Math.max(1000, getNextUtcReset().getTime() - Date.now());
  dailyResetTimerId = setTimeout(() => {
    dailyResetTimerId = null;
    ensureDailyHopWindow();
    updateDailyHopDisplay();
    loadBackendHopLimit().catch(() => {});
    scheduleDailyResetCheck();
  }, Math.min(delayMs, 2147483647));
}

function ensureHistoryWindow() {
  serverSkipHistory?.ensureCurrent();
}

function refreshServerList({ manual = false, signal } = {}) {
  if (!manual && !state.autoHop.enabled) {
    return Promise.resolve({ cancelled: true });
  }
  if (refreshPromise) {
    return refreshPromise;
  }

  const requestSeq = refreshRequestSeq + 1;
  refreshRequestSeq = requestSeq;

  const request = (async () => {
    if (signal?.aborted || (!manual && !state.autoHop.enabled)) {
      return { cancelled: true };
    }

    refreshInProgress = true;
    if (state.refreshMeta.status === 'idle') {
      state.refreshMeta.status = 'loading';
    }
    refs.serverList.setAttribute('aria-busy', 'true');
    refs.refreshStatusText.textContent = 'Updating servers...';
    refs.refreshButton.disabled = true;
    try {
      const path = manual ? '/api/servers?force=true&limit=100&maxPages=5' : '/api/servers?limit=100&maxPages=5';
      const url = apiUrl(path);
      const response = await fetch(url, { headers: { Accept: 'application/json' }, signal });
      const payload = await response.json();
      if (requestSeq !== refreshRequestSeq) {
        return { cancelled: true, stale: true };
      }
      if (!response.ok || !payload.success) {
        const error = new Error(payload.data?.error || payload.message || 'Could not update the server list.');
        error.retryAfterMs = Number(payload.data?.retryAfterMs || 0);
        throw error;
      }

      const data = payload.data || {};
      const servers = Array.isArray(data.servers) ? data.servers : [];
      const parsedServers = servers
        .map((entry) => normaliseServerPayload({ ...entry, placeId: GAME_PLACE_ID }))
        .filter(isDisplayableServer);
      const uniqueServers = deduplicateServers(parsedServers);

      if (uniqueServers.length > 0 || state.servers.length === 0 || !data.partial) {
        state.servers = uniqueServers;
      }

      state.refreshMeta.status = data.partial ? 'partial' : 'fresh';
      state.refreshMeta.source = data.source || 'fresh';
      state.refreshMeta.retryAfterMs = Number(data.retryAfterMs || 0);
      state.refreshMeta.lastUpdated = data.refreshedAt ? new Date(data.refreshedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : null;
      const retrySeconds = Math.ceil(state.refreshMeta.retryAfterMs / 1000);
      refs.refreshStatusText.textContent = data.partial
        ? (retrySeconds > 0 ? `Roblox cooldown: wait at least ${retrySeconds} seconds.` : 'Some servers may be missing while Roblox cools down.')
        : `Updated ${state.refreshMeta.lastUpdated || 'just now'}`;

      renderStats();
      renderServerList();
      return { retryAfterMs: state.refreshMeta.retryAfterMs, partial: Boolean(data.partial) };
    } catch (error) {
      if (signal?.aborted) {
        throw error;
      }
      state.refreshMeta.status = state.servers.length ? 'stale' : 'error';
      refs.refreshStatusText.textContent = state.servers.length
        ? 'Could not update servers. Keeping the last good list.'
        : 'Could not update servers. Try again later.';
      if (state.servers.length === 0) {
        renderServerList();
      }
      throw error;
    } finally {
      refreshInProgress = false;
      refs.serverList.removeAttribute('aria-busy');
      refs.refreshButton.disabled = false;
      const maxPages = Math.max(1, Math.ceil(getVisibleServers().length / state.pageSize));
      refs.previousPageButton.disabled = state.page <= 1;
      refs.nextPageButton.disabled = state.page >= maxPages;
    }
  })();

  refreshPromise = request;
  request.finally(() => {
    if (refreshPromise === request) {
      refreshPromise = null;
    }
  }).catch(() => {});
  return request;
}

function schedulePostLaunchRefresh(signal, launchStartedAt) {
  if (postLaunchRefreshTimerId !== null) {
    clearTimeout(postLaunchRefreshTimerId);
  }

  const delayMs = Math.max(0, launchStartedAt + POST_LAUNCH_REFRESH_DELAY_MS - Date.now());
  refs.countdownDisplay.textContent = `Refreshing list in ${Math.ceil(delayMs / 1000)} seconds`;
  postLaunchRefreshTimerId = setTimeout(() => {
    postLaunchRefreshTimerId = null;
    if (signal?.aborted) {
      return;
    }
    refreshServerList({ manual: true, signal })
      .catch(() => {});
  }, delayMs);
}

function getEligibleServerPool() {
  let visible = state.servers.filter(isDisplayableServer);
  const attemptedSet = state.history.attemptedSet || buildAttemptedSet(state.history.attemptedIds || []);
  if (refs.skipAttemptedToggle && refs.skipAttemptedToggle.checked) {
    visible = visible.filter((server) => !attemptedSet.has(normalizeServerId(server.jobId)));
  }
  return visible;
}

function isEligibleServer(server) {
  return isDisplayableServer(server)
    && hasValidPlayerCount(server)
    && server.players < server.maxPlayers;
}

function getVisibleServers() {
  const visible = [...getEligibleServerPool()];
  if (!state.searchTerm) {
    return sortServers(visible);
  }
  return sortServers(visible.filter((server) => String(server.jobId).toLowerCase().includes(state.searchTerm)));
}

function sortServers(servers) {
  const list = [...servers];
  switch (state.sortMode) {
    case 'highestPlayers':
      return sortByPlayerCount(list, true);
    case 'lowestOccupancy':
      return list.sort((a, b) => compareNullableMetric(a.occupancy, b.occupancy));
    case 'bestFps':
      return list.sort((a, b) => compareNullableMetric(b.fps, a.fps));
    case 'lowestPing':
      return list.sort((a, b) => compareNullableMetric(a.ping, b.ping));
    case 'lowestPlayers':
    default:
      return sortByPlayerCount(list);
  }
}

function compareNullableMetric(a, b) {
  const aKnown = typeof a === 'number' && Number.isFinite(a);
  const bKnown = typeof b === 'number' && Number.isFinite(b);
  if (aKnown !== bKnown) {
    return aKnown ? -1 : 1;
  }
  return aKnown ? a - b : 0;
}

function renderStats() {
  const summary = getServerSummary(getVisibleServers());
  refs.statServersLoaded.textContent = String(summary.availableServers);
  refs.statLowestPlayers.textContent = summary.lowestPlayers === null ? '—' : String(summary.lowestPlayers);
  refs.statAutoHopState.textContent = state.autoHop.enabled ? 'Running' : 'Stopped';
  updateDailyHopDisplay();
}

function renderServerList() {
  const visible = getVisibleServers();
  const totalPages = Math.max(1, Math.ceil(visible.length / state.pageSize));
  state.page = Math.min(state.page, totalPages);
  const start = (state.page - 1) * state.pageSize;
  const pageServers = visible.slice(start, start + state.pageSize);

  refs.pageIndicator.textContent = `Page ${state.page}/${totalPages}`;
  refs.previousPageButton.disabled = refreshInProgress || state.page <= 1;
  refs.nextPageButton.disabled = refreshInProgress || state.page >= totalPages;

  if (pageServers.length === 0) {
    const allLoadedAttempted = refs.skipAttemptedToggle && refs.skipAttemptedToggle.checked && state.servers.length > 0 && getEligibleServerPool().length === 0;
    const emptyMessage = allLoadedAttempted
      ? 'All loaded servers attempted - refresh the server list or wait for the next reset.'
      : state.refreshMeta.status === 'error'
        ? 'The server list could not be loaded. Try Refresh again.'
        : 'No eligible servers fit the current filters.';
    refs.serverList.innerHTML = `<div class="empty-state">${emptyMessage}</div>`;
    return;
  }

  const serverSummary = getServerSummary(visible);
  const attemptedSet = state.history.attemptedSet || buildAttemptedSet(state.history.attemptedIds || []);

  refs.serverList.innerHTML = pageServers.map((server) => {
    const isLowest = isLowestPlayerServer(server, serverSummary);
    const occupancy = server.occupancy;
    const attempted = attemptedSet.has(normalizeServerId(server.jobId));
    const cardClass = isLowest ? 'server-card lowest' : 'server-card';
    const serverLabel = !hasValidPlayerCount(server)
      ? 'Count unknown'
      : (server.players >= server.maxPlayers ? 'Full' : (attempted ? 'Tried' : (isLowest ? 'Lowest players' : 'Open')));
    const ping = server.ping;
    const fps = server.fps;
    const jobId = escapeHtml(server.jobId);
    const playerCount = escapeHtml(formatPlayerCapacity(server));
    const occupancyMarkup = occupancy === null
      ? '<span>Occupancy unknown</span>'
      : `<div class="occupancy-track" role="img" aria-label="${Math.round(occupancy)} percent full"><span style="width:${Math.max(0, Math.min(100, occupancy))}%"></span></div><span>${Math.round(occupancy)}% full</span>`;

    return `
      <article class="${cardClass}" data-job-id="${jobId}">
        <div class="server-card-header">
          <div class="server-info">
            <h3>${playerCount}</h3>
            <span class="server-pill ${hasValidPlayerCount(server) && server.players >= server.maxPlayers ? 'full' : attempted ? 'attempted' : isLowest ? 'lowest' : ''}">${serverLabel}</span>
          </div>
          <button class="copy-button" type="button" data-copy-id="${jobId}">Copy ID</button>
        </div>
        <div class="server-card-meta">
          ${occupancyMarkup}
          <span>${ping === null ? 'Ping unavailable' : `Ping ${Math.round(ping)} ms`}</span>
          <span>${fps === null ? 'FPS unavailable' : `Server FPS ${Math.round(fps)}`}</span>
        </div>
        <div class="server-card-footer">
          <button class="join-button" type="button" data-join-id="${jobId}" ${pendingJoinPromise || isDailyLimitReached() ? 'disabled' : ''}>Join</button>
        </div>
      </article>
    `;
  }).join('');

  refs.serverList.querySelectorAll('[data-copy-id]').forEach((button) => {
    button.addEventListener('click', async () => {
      await navigator.clipboard.writeText(button.dataset.copyId);
    });
  });

  refs.serverList.querySelectorAll('[data-join-id]').forEach((button) => {
    button.addEventListener('click', async () => {
      const target = state.servers.find((server) => String(server.jobId) === button.dataset.joinId);
      if (target) {
        if (refreshPromise) {
          await refreshPromise.catch(() => {});
        }
        await sendJoinRequest(target).catch(() => {});
      }
    });
  });
  updateDailyHopDisplay();
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, (character) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  })[character]);
}

function sendJoinRequest(server, { signal, scheduleRefreshAfterLaunch = true } = {}) {
  if (pendingJoinPromise) {
    return Promise.reject(new Error('A join request is already in progress.'));
  }
  if (signal?.aborted) {
    return Promise.reject(new Error('Auto-Hop was stopped.'));
  }
  ensureDailyHopWindow();
  if (isDailyLimitReached()) {
    const error = new Error('Daily hop limit reached. Try again after 00:00 UTC.');
    error.dailyLimitReached = true;
    refs.hopLimitMessage.textContent = 'Daily UTC hop limit reached. No launch request was initiated.';
    renderStats();
    renderServerList();
    return Promise.reject(error);
  }

  const requestBody = {
    placeId: GAME_PLACE_ID,
    serverJobId: server.jobId
  };

  ensureHistoryWindow();
  renderStats();
  renderServerList();
  const launchStartedAt = Date.now();

  const request = (async () => {
    let reservationToken = null;
    let launchRequested = false;
    try {
      const response = await fetch(apiUrl('/api/join'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(requestBody),
        signal
      });

      const payload = await response.json();
      if (signal?.aborted) {
        return;
      }
      const data = payload.data || {};
      syncHopLimitFromServer(data.hopLimit);
      if (!payload.success) {
        const launchError = new Error(data.error || 'The launch request could not be completed.');
        if (data.status === 'daily_limit_reached') {
          launchError.dailyLimitReached = true;
        }
        throw launchError;
      }
      if (data.status !== 'awaiting_local_agent' || typeof data.reservationToken !== 'string') {
        throw new Error('The API returned an unexpected launch authorization response.');
      }
      reservationToken = data.reservationToken;

      if (signal?.aborted) {
        await completeLaunchReservation(reservationToken, false);
        reservationToken = null;
        return;
      }

      let localStatus;
      try {
        const statusResponse = await fetch(`${LOCAL_AGENT_URL}/api/status`, {
          headers: { Accept: 'application/json' }
        });
        const statusPayload = await statusResponse.json();
        if (!statusResponse.ok || !statusPayload.success) {
          throw new Error(statusPayload.message || 'The local launch agent status check failed.');
        }
        localStatus = statusPayload.data;
      } catch (error) {
        throw new Error(`The local launch agent could not be reached. Ensure it is running with "npm run agent", allow this website origin in MONSTER_HOPPER_ALLOWED_ORIGINS, and permit local-network access in your browser. (${error.message})`);
      }

      if (!localStatus.launchAvailable) {
        throw new Error(localStatus.error || 'The local launch agent cannot launch Roblox on this PC.');
      }

      if (signal?.aborted) {
        await completeLaunchReservation(reservationToken, false);
        reservationToken = null;
        return;
      }

      const localResponse = await fetch(`${LOCAL_AGENT_URL}/api/join`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(requestBody)
      });
      const localPayload = await localResponse.json();
      if (!localResponse.ok || !localPayload.success || localPayload.data?.ok !== true) {
        throw new Error(localPayload.data?.error || localPayload.message || 'The local agent could not start Roblox.');
      }
      launchRequested = true;

      let completionResponse;
      let completionPayload;
      try {
        completionResponse = await fetch(apiUrl('/api/join/complete'), {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ reservationToken, launchRequested: true })
        });
        completionPayload = await completionResponse.json();
      } catch (error) {
        const completionError = new Error(`Roblox launch was requested on this PC, but the API could not confirm the daily hop count. Do not retry immediately; verify Roblox first. (${error.message})`);
        completionError.stopAutoHop = true;
        throw completionError;
      }
      if (!completionResponse.ok || !completionPayload.success) {
        const completionError = new Error(`Roblox launch was requested on this PC, but the API could not confirm the daily hop count. Do not retry immediately; verify Roblox first. (${completionPayload.message || 'confirmation failed'})`);
        completionError.stopAutoHop = true;
        throw completionError;
      }
      reservationToken = null;
      syncHopLimitFromServer(completionPayload.data?.hopLimit);

      recordSuccessfulHop(completionPayload.data?.hopLimit);
      recordAttemptedServer(server.jobId, launchStartedAt);
      if (scheduleRefreshAfterLaunch) {
        schedulePostLaunchRefresh(signal, launchStartedAt);
      }
      renderStats();
      renderServerList();
    } catch (error) {
      if (reservationToken && !launchRequested) {
        try {
          await completeLaunchReservation(reservationToken, false);
          reservationToken = null;
        } catch (releaseError) {
          console.error('Could not release the unused launch reservation.', releaseError);
        }
      }
      if (signal?.aborted) {
        return;
      }
      renderStats();
      renderServerList();
      throw error;
    } finally {
      if (pendingJoinPromise === request) {
        pendingJoinPromise = null;
      }
      renderServerList();
    }
  })();
  pendingJoinPromise = request;
  return request;
}

async function completeLaunchReservation(reservationToken, launchRequested) {
  const response = await fetch(apiUrl('/api/join/complete'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reservationToken, launchRequested })
  });
  const payload = await response.json();
  if (!response.ok || !payload.success) {
    throw new Error(payload.message || 'The launch reservation could not be completed.');
  }
  syncHopLimitFromServer(payload.data?.hopLimit);
  return payload.data;
}

function recordAttemptedServer(jobId, initiatedAt) {
  const date = new Date();
  const windowKey = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}T${String(date.getHours()).padStart(2, '0')}:${String(Math.floor(date.getMinutes() / 15) * 15).padStart(2, '0')}`;
  serverSkipHistory.recordSuccessfulHop(jobId, windowKey, initiatedAt);
}

async function refreshProfileAvatar() {
  if (profileAvatarTimerId !== null) {
    clearTimeout(profileAvatarTimerId);
    profileAvatarTimerId = null;
  }
  let refreshDelayMs = 5 * 60 * 1000;
  try {
    const response = await fetch(apiUrl('/api/profile-avatar'), { headers: { Accept: 'application/json' } });
    if (!response.ok) {
      throw new Error(`Avatar endpoint returned status ${response.status}.`);
    }
    const payload = await response.json();
    const avatarState = payload?.data?.state;
    const imageUrl = payload?.success ? payload.data?.imageUrl : null;
    refreshDelayMs = avatarState === 'Pending' ? 60 * 1000 : 30 * 60 * 1000;
    if (typeof imageUrl !== 'string' || !imageUrl.startsWith('https://')) {
      refs.profileAvatar.hidden = true;
      refs.profileAvatarFallback.hidden = false;
      return;
    }

    refs.profileAvatar.onload = () => {
      refs.profileAvatar.hidden = false;
      refs.profileAvatarFallback.hidden = true;
    };
    refs.profileAvatar.onerror = () => {
      refs.profileAvatar.hidden = true;
      refs.profileAvatarFallback.hidden = false;
    };
    if (refs.profileAvatar.src !== imageUrl) {
      refs.profileAvatar.src = imageUrl;
    }
  } catch (error) {
    refs.profileAvatar.hidden = true;
    refs.profileAvatarFallback.hidden = false;
  } finally {
    profileAvatarTimerId = setTimeout(refreshProfileAvatar, refreshDelayMs);
  }
}

function startAutoHop() {
  ensureDailyHopWindow();
  if (isDailyLimitReached()) {
    refs.autoHopMessage.textContent = 'Daily UTC hop limit reached. Auto-Hop can start after 00:00 UTC.';
    updateDailyHopDisplay();
    return;
  }

  if (autoHopController.start()) {
    refs.autoHopMessage.textContent = 'Auto-Hop is running.';
  }
}

function stopAutoHop() {
  const stopped = autoHopController.stop();
  if (postLaunchRefreshTimerId !== null) {
    clearTimeout(postLaunchRefreshTimerId);
    postLaunchRefreshTimerId = null;
  }
  if (!stopped) {
    updateAutoHopStatus('stopped');
  }
  refs.countdownDisplay.textContent = 'No countdown';
  refs.autoHopMessage.textContent = 'Auto-Hop is stopped.';
}

window.addEventListener('beforeunload', () => {
  autoHopController?.stop();
  serverSkipHistory?.dispose();
  if (dailyResetTimerId !== null) {
    clearTimeout(dailyResetTimerId);
  }
  if (profileAvatarTimerId !== null) {
    clearTimeout(profileAvatarTimerId);
  }
});

setInterval(() => {
  ensureHistoryWindow();
  ensureDailyHopWindow();
  updateDailyHopDisplay();
  renderStats();
}, 30000);
