const { PUBLIC_SERVERS_URL, DEFAULT_SERVER_LIMIT, DEFAULT_MAX_PAGES, CACHE_TTL_MS } = require('./config.js');
const { validateServerJobId } = require('./validation.js');
const { normaliseServerPayload, comparePlayerCount } = require('../public/server-data.js');

class RateLimitError extends Error {
  constructor(message, retryAfterMs) {
    super(message);
    this.name = 'RateLimitError';
    this.retryAfterMs = retryAfterMs;
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

class RobloxServerService {
  constructor() {
    this.cache = {
      servers: [],
      refreshedAt: null,
      source: 'empty',
      partial: false,
      lastError: null,
      rateLimitUntil: 0
    };
    this.inFlight = null;
  }

  getStatus() {
    return {
      source: this.cache.source,
      refreshedAt: this.cache.refreshedAt,
      partial: this.cache.partial,
      lastError: this.cache.lastError,
      totalServers: this.cache.servers.length,
      rateLimitUntil: this.cache.rateLimitUntil || null
    };
  }

  async _requestPage(limit, cursor) {
    const url = new URL(PUBLIC_SERVERS_URL);
    url.searchParams.set('sortOrder', 'Asc');
    url.searchParams.set('limit', String(limit));
    if (cursor) {
      url.searchParams.set('cursor', cursor);
    }

    const fetchResponse = await fetch(url, {
      headers: {
        Accept: 'application/json',
        'User-Agent': 'MonsterHopper/1.0'
      },
      signal: AbortSignal.timeout(12000)
    });

    if (fetchResponse.status === 429) {
      const retryAfterHeader = fetchResponse.headers.get('Retry-After');
      const retrySeconds = Number(retryAfterHeader || 0);
      const retryAfterMs = Number.isFinite(retrySeconds) && retrySeconds > 0
        ? retrySeconds * 1000
        : 5000;
      throw new RateLimitError(`HTTP 429: Roblox public server listing is rate-limited. Retry after ${retryAfterMs} ms.`, retryAfterMs);
    }

    if (!fetchResponse.ok) {
      throw new Error(`Roblox public server request failed with status ${fetchResponse.status}.`);
    }

    const json = await fetchResponse.json();
    if (!json || typeof json !== 'object') {
      throw new Error('Malformed Roblox server JSON response.');
    }

    const data = Array.isArray(json.data) ? json.data : [];
    const nextCursor = json.nextPageCursor || json.nextCursor || null;

    return { data, nextCursor };
  }

  async fetchPublicServers({ force = false, limit = DEFAULT_SERVER_LIMIT, maxPages = DEFAULT_MAX_PAGES } = {}) {
    if (this.inFlight) {
      return this.inFlight;
    }

    const request = this._fetchPublicServers({ force, limit, maxPages });
    this.inFlight = request;
    request.finally(() => {
      if (this.inFlight === request) {
        this.inFlight = null;
      }
    }).catch(() => {});
    return request;
  }

  async _fetchPublicServers({ force = false, limit = DEFAULT_SERVER_LIMIT, maxPages = DEFAULT_MAX_PAGES } = {}) {
    const now = Date.now();
    if (this.cache.rateLimitUntil > now) {
      return {
        servers: this.cache.servers,
        source: 'partial',
        refreshedAt: this.cache.refreshedAt,
        partial: true,
        lastError: this.cache.lastError,
        retryAfterMs: this.cache.rateLimitUntil - now,
        fromCache: true
      };
    }

    const hasFreshCache = this.cache.refreshedAt && (now - this.cache.refreshedAt) < CACHE_TTL_MS && this.cache.servers.length > 0;
    if (!force && hasFreshCache) {
      return {
        servers: this.cache.servers,
        source: 'cache',
        refreshedAt: this.cache.refreshedAt,
        partial: this.cache.partial,
        lastError: this.cache.lastError,
        fromCache: true
      };
    }

    const pageLimit = Math.max(1, Number(maxPages) || DEFAULT_MAX_PAGES);
    const requestedLimit = Math.max(10, Math.min(100, Number(limit) || DEFAULT_SERVER_LIMIT));
    const collected = [];
    const seen = new Set();
    let nextCursor = null;
    let partial = false;
    let lastError = null;

    for (let pageIndex = 0; pageIndex < pageLimit; pageIndex += 1) {
      try {
        const page = await this._requestPage(requestedLimit, nextCursor);
        const entries = Array.isArray(page.data) ? page.data : [];

        if (entries.length === 0 && !nextCursor) {
          break;
        }

        for (const entry of entries) {
          const normalized = normaliseServerPayload(entry);
          if (!this._isEligibleServer(normalized)) {
            continue;
          }

          const normalizedId = normalized.jobId.toLowerCase();
          if (!seen.has(normalizedId)) {
            seen.add(normalizedId);
            collected.push(normalized);
          }
        }

        if (!page.nextCursor) {
          break;
        }

        nextCursor = page.nextCursor;
      } catch (error) {
        const retryWait = error instanceof RateLimitError ? error.retryAfterMs : 0;
        lastError = error instanceof Error ? error.message : 'Unknown server fetch error.';

        if (error instanceof RateLimitError) {
          this.cache.rateLimitUntil = Date.now() + retryWait;
          this.cache.lastError = lastError;
          return {
            servers: collected.length ? this._sortServers(collected) : this.cache.servers,
            source: 'partial',
            refreshedAt: this.cache.refreshedAt || now,
            partial: true,
            lastError,
            retryAfterMs: retryWait,
            fromCache: false
          };
        }

        partial = true;
        break;
      }
    }

    const sortedServers = this._sortServers(collected);
    if (sortedServers.length === 0 && this.cache.servers.length > 0) {
      const emptyError = lastError || 'Roblox returned no eligible public servers; keeping the previous valid cache.';
      this.cache = {
        ...this.cache,
        source: 'partial',
        partial: true,
        lastError: emptyError,
        rateLimitUntil: 0
      };

      return {
        servers: this.cache.servers,
        source: this.cache.source,
        refreshedAt: this.cache.refreshedAt,
        partial: true,
        lastError: emptyError,
        fromCache: true
      };
    }

    this.cache = {
      servers: sortedServers,
      refreshedAt: Date.now(),
      source: partial ? 'partial' : 'fresh',
      partial,
      lastError,
      rateLimitUntil: 0
    };

    return {
      servers: sortedServers,
      source: this.cache.source,
      refreshedAt: this.cache.refreshedAt,
      partial,
      lastError,
      fromCache: false
    };
  }

  _isEligibleServer(server) {
    return validateServerJobId(server.jobId)
      && server.maxPlayers !== null
      && (server.players === null || server.players < server.maxPlayers);
  }

  _sortServers(servers) {
    return [...servers].sort(comparePlayerCount);
  }
}

module.exports = new RobloxServerService();
