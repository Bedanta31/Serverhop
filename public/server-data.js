(function exposeServerData(root, factory) {
  const exports = factory();
  if (typeof module === 'object' && module.exports) {
    module.exports = exports;
  } else {
    root.MonsterHopperServerData = exports;
  }
}(globalThis, function createServerDataModule() {
  function parsePlayerCount(values) {
    for (const value of values) {
      if (typeof value !== 'number' && typeof value !== 'string') {
        continue;
      }
      if (typeof value === 'string' && value.trim() === '') {
        continue;
      }

      const count = Number(value);
      if (Number.isSafeInteger(count) && count >= 0) {
        return count;
      }
    }

    return null;
  }

  function parseCapacity(values) {
    const capacity = parsePlayerCount(values);
    return capacity !== null && capacity > 0 ? capacity : null;
  }

  function parseMetric(values) {
    for (const value of values) {
      if ((typeof value === 'number' || typeof value === 'string') && String(value).trim() !== '') {
        const metric = Number(value);
        if (Number.isFinite(metric) && metric >= 0) {
          return metric;
        }
      }
    }

    return null;
  }

  function normaliseServerPayload(payload) {
    const raw = payload && typeof payload === 'object' ? payload : {};
    const jobId = raw.id ?? raw.jobId ?? raw.serverId ?? raw.serverJobId ?? raw.job_id ?? null;
    const placeId = raw.placeId ?? raw.place_id ?? '98664161516921';
    const players = parsePlayerCount([
      raw.playing,
      raw.players,
      raw.currentPlayers,
      raw.current_players
    ]);
    const maxPlayers = parseCapacity([
      raw.maxPlayers,
      raw.max_players,
      raw.maxPlayersAllowed
    ]);
    const fps = parseMetric([raw.fps, raw.fpsValue]);
    const ping = parseMetric([raw.ping, raw.pingMs, raw.latency]);
    const occupancy = players !== null && maxPlayers !== null
      ? (players / maxPlayers) * 100
      : null;

    return {
      jobId: jobId ? String(jobId) : null,
      placeId: String(placeId),
      players,
      maxPlayers,
      fps,
      ping,
      occupancy,
      isFull: players !== null && maxPlayers !== null && players >= maxPlayers
    };
  }

  function hasValidPlayerCount(server) {
    return Number.isSafeInteger(server?.players) && server.players >= 0;
  }

  function isDisplayableServer(server) {
    return typeof server?.jobId === 'string'
      && /^[A-Za-z0-9_.:-]{8,256}$/.test(server.jobId)
      && server.jobId !== '98664161516921'
      && Number.isSafeInteger(server.maxPlayers)
      && server.maxPlayers > 0
      && (server.players === null
        || (hasValidPlayerCount(server) && server.players < server.maxPlayers));
  }

  function comparePlayerCount(a, b, descending = false) {
    const aKnown = hasValidPlayerCount(a);
    const bKnown = hasValidPlayerCount(b);
    if (aKnown !== bKnown) {
      return aKnown ? -1 : 1;
    }
    if (!aKnown) {
      return 0;
    }

    const countDelta = a.players - b.players;
    if (countDelta !== 0) {
      return descending ? -countDelta : countDelta;
    }

    const aCapacity = Number.isSafeInteger(a.maxPlayers) ? a.maxPlayers : Number.POSITIVE_INFINITY;
    const bCapacity = Number.isSafeInteger(b.maxPlayers) ? b.maxPlayers : Number.POSITIVE_INFINITY;
    return aCapacity - bCapacity;
  }

  function sortByPlayerCount(servers, descending = false) {
    return [...servers].sort((a, b) => comparePlayerCount(a, b, descending));
  }

  function deduplicateServers(servers) {
    const seen = new Set();
    return servers.filter((server) => {
      const jobId = String(server?.jobId ?? '').trim().replace(/\s+/g, '').toLowerCase();
      if (!jobId || seen.has(jobId)) {
        return false;
      }
      seen.add(jobId);
      return true;
    });
  }

  function getServerSummary(servers) {
    const displayableServers = deduplicateServers(servers).filter(isDisplayableServer);
    const playerCounts = displayableServers
      .filter(hasValidPlayerCount)
      .map((server) => server.players);

    return {
      availableServers: displayableServers.length,
      lowestPlayers: playerCounts.length ? Math.min(...playerCounts) : null
    };
  }

  function isLowestPlayerServer(server, summary) {
    return hasValidPlayerCount(server)
      && summary.lowestPlayers !== null
      && server.players === summary.lowestPlayers;
  }

  function formatPlayerCount(server) {
    return hasValidPlayerCount(server) ? String(server.players) : 'Unknown';
  }

  function formatPlayerCapacity(server) {
    return `${formatPlayerCount(server)} / ${server.maxPlayers} players`;
  }

  return {
    normaliseServerPayload,
    hasValidPlayerCount,
    isDisplayableServer,
    comparePlayerCount,
    sortByPlayerCount,
    deduplicateServers,
    getServerSummary,
    isLowestPlayerServer,
    formatPlayerCount,
    formatPlayerCapacity
  };
}));
