const path = require('path');
const express = require('express');
const { buildApiResponse, ensureServerSelection } = require('./src/validation.js');
const { requestJoin } = require('./src/joinService.js');
const serverService = require('./src/serverService.js');
const { hopLimitService } = require('./src/hopLimitService.js');
const { RobloxThumbnailService } = require('./src/robloxThumbnailService.js');
const { getNextQuarterHourBoundary, shouldResetHistoryAt } = require('./src/quarterHour.js');
const { GAME_PLACE_ID_STRING } = require('./src/config.js');

const app = express();
const robloxThumbnailService = new RobloxThumbnailService();

function parsePort(value) {
  if (value === undefined || value === '') {
    return 3000;
  }

  const port = Number(value);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error('PORT must be an integer between 1 and 65535.');
  }
  return port;
}

function getServerConfig(env = process.env) {
  return {
    host: env.HOST || (env.RAILWAY_ENVIRONMENT ? '0.0.0.0' : '127.0.0.1'),
    port: parsePort(env.PORT)
  };
}

const { host: HOST, port: PORT } = getServerConfig();

app.use(express.json({ limit: '1mb' }));
app.use(express.static(path.join(__dirname, 'public')));

app.get('/api/health', (req, res) => {
  res.status(200).json(buildApiResponse(true, 'Healthy', {
    status: 'ok',
    platform: process.platform,
    placeId: GAME_PLACE_ID_STRING,
    app: 'Monster Hopper'
  }, 200));
});

app.get('/api/status', (req, res) => {
  const nextReset = getNextQuarterHourBoundary(new Date());

  res.status(200).json(buildApiResponse(true, 'Status loaded', {
    app: 'Monster Hopper',
    host: HOST,
    placeId: GAME_PLACE_ID_STRING,
    currentTime: new Date().toISOString(),
    nextQuarterHourReset: nextReset.toISOString(),
    quarterHourBoundaryReached: shouldResetHistoryAt(new Date()),
    serverCache: serverService.getStatus(),
    hopLimit: hopLimitService.getStatus()
  }, 200));
});

app.get('/api/hop-limit', (req, res) => {
  res.status(200).json(buildApiResponse(true, 'Daily hop limit status loaded.', hopLimitService.getStatus(), 200));
});

app.get('/api/profile-avatar', async (req, res) => {
  try {
    const avatar = await robloxThumbnailService.getAvatarHeadshot();
    res.status(200).json(buildApiResponse(true, 'Roblox profile avatar status loaded.', avatar, 200));
  } catch (error) {
    res.status(502).json(buildApiResponse(false, 'Roblox profile avatar is temporarily unavailable.', {
      error: error instanceof Error ? error.message : 'Unknown avatar thumbnail error.'
    }, 502));
  }
});

app.get('/api/servers', async (req, res) => {
  try {
    const force = req.query.force === 'true';
    const limit = Number(req.query.limit || 100);
    const maxPages = Number(req.query.maxPages || 5);

    const result = await serverService.fetchPublicServers({ force, limit, maxPages });
    const statusCode = result.partial || result.source === 'partial' ? 206 : 200;

    res.status(statusCode).json(buildApiResponse(
      true,
      result.partial ? 'Server list contains partial data.' : 'Server list loaded.',
      result,
      statusCode
    ));
  } catch (error) {
    res.status(500).json(buildApiResponse(false, 'Unable to load public servers.', {
      error: error instanceof Error ? error.message : 'Unknown error'
    }, 500));
  }
});

app.post('/api/servers/refresh', async (req, res) => {
  try {
    const limit = Number(req.body?.limit || 100);
    const maxPages = Number(req.body?.maxPages || 5);
    const data = await serverService.fetchPublicServers({ force: true, limit, maxPages });

    res.status(200).json(buildApiResponse(true, 'Public server cache refreshed.', data, 200));
  } catch (error) {
    res.status(500).json(buildApiResponse(false, 'Refresh failed.', {
      error: error instanceof Error ? error.message : 'Unknown server refresh error.'
    }, 500));
  }
});

app.post('/api/join', async (req, res) => {
  let reservationId = null;
  try {
    const payload = req.body || {};
    const selection = ensureServerSelection(payload);
    const reservation = hopLimitService.reserve();
    if (!reservation.ok) {
      res.status(429).json(buildApiResponse(false, 'Daily hop limit reached. Try again after the next UTC reset.', {
        placeId: selection.placeId,
        serverJobId: selection.serverJobId,
        status: 'daily_limit_reached',
        launchUri: null,
        error: 'Daily hop limit reached. No launch request was initiated.',
        joinConfirmed: false,
        joinVerified: false,
        hopLimit: reservation.status
      }, 429));
      return;
    }

    reservationId = reservation.reservationId;
    const result = await requestJoin(selection);
    const hopLimit = result.ok
      ? hopLimitService.commit(reservationId)
      : hopLimitService.release(reservationId);
    reservationId = null;

    const responseCode = result.ok ? 200 : 400;
    res.status(responseCode).json(buildApiResponse(
      result.ok,
      result.ok ? 'Roblox launch request sent.' : 'Roblox launch request failed.',
      {
        placeId: selection.placeId,
        serverJobId: selection.serverJobId,
        status: result.status,
        launchUri: result.launchUri,
        error: result.error || null,
        joinConfirmed: false,
        joinVerified: false,
        requestedAt: result.launchedAt || new Date().toISOString(),
        hopLimit
      },
      responseCode
    ));
  } catch (error) {
    if (reservationId) {
      hopLimitService.release(reservationId);
    }
    res.status(400).json(buildApiResponse(false, 'Join request rejected.', {
      error: error instanceof Error ? error.message : 'Unknown validation error.',
      hopLimit: hopLimitService.getStatus()
    }, 400));
  }
});

app.get('*', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

function startServer({ port = PORT, host = HOST } = {}) {
  const server = app.listen(port, host, () => {
    const address = server.address();
    console.log(`Monster Hopper is running at http://${host}:${address.port}`);
  });

  server.on('error', (error) => {
    console.error(`Monster Hopper could not listen on ${host}:${port}: ${error.message}`);
    process.exitCode = 1;
  });

  return server;
}

if (require.main === module) {
  startServer();
}

module.exports = { app, startServer, getServerConfig };
