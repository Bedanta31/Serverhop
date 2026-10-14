const { normaliseServerPayload, hasValidPlayerCount, sortByPlayerCount } = require('../public/server-data.js');

function normalizeServerId(value) {
  return String(value ?? '').trim().replace(/\s+/g, '').toLowerCase();
}

function getQuarterHourKey(date = new Date()) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  const hours = String(date.getHours()).padStart(2, '0');
  const minutes = String(Math.floor(date.getMinutes() / 15) * 15).padStart(2, '0');
  return `${year}-${month}-${day}T${hours}:${minutes}`;
}

function excludeFullServers(servers) {
  return (servers || []).filter((server) => {
    const normalized = normaliseServerPayload(server);
    const id = normalizeServerId(server.id ?? server.jobId ?? server.serverJobId ?? server.serverId ?? '');
    return id
      && hasValidPlayerCount(normalized)
      && normalized.maxPlayers !== null
      && normalized.players < normalized.maxPlayers;
  });
}

function pickLowestPlayerServer(servers) {
  const eligible = excludeFullServers(servers || []);
  if (eligible.length === 0) {
    return null;
  }

  return sortByPlayerCount(eligible.map((server) => ({
    server,
    ...normaliseServerPayload(server)
  })))[0].server;
}

function filterAttemptedServers(candidates, attemptedIds = [], skipAttempted = true) {
  if (!skipAttempted) {
    return (candidates || []).map((candidate) => candidate);
  }

  const attempted = new Set();
  const values = attemptedIds instanceof Set ? attemptedIds : attemptedIds || [];

  for (const value of values) {
    const normalized = normalizeServerId(value);
    if (normalized) {
      attempted.add(normalized);
    }
  }

  return (candidates || []).filter((candidate) => {
    const candidateId = normalizeServerId(candidate.id ?? candidate.jobId ?? candidate.serverJobId ?? candidate.serverId ?? '');
    return !attempted.has(candidateId);
  });
}

function avoidDuplicateAttempts(candidates, attemptedIds = []) {
  return filterAttemptedServers(candidates, attemptedIds, true);
}

module.exports = {
  normalizeServerId,
  getQuarterHourKey,
  excludeFullServers,
  pickLowestPlayerServer,
  filterAttemptedServers,
  avoidDuplicateAttempts
};
