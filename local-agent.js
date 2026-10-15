const os = require('os');
const express = require('express');
const { requestJoin } = require('./src/joinService.js');
const { ensureServerSelection, buildApiResponse } = require('./src/validation.js');
const { detectOperatingSystem, getLaunchCapability } = require('./src/windowsSupport.js');
const { parseAllowedOrigins, createCorsMiddleware } = require('./src/cors.js');
const { findRobloxPlayer } = require('./src/robloxInstall.js');

const DEFAULT_PORT = 3210;
const LOCAL_WEB_ORIGINS = ['http://127.0.0.1:3000', 'http://localhost:3000'];

function getAllowedOrigins() {
  return new Set([
    ...LOCAL_WEB_ORIGINS,
    ...parseAllowedOrigins(process.env.MONSTER_HOPPER_ALLOWED_ORIGINS)
  ]);
}

function getAgentStatus({ platform = process.platform, release = os.release(), robloxExecutable = findRobloxPlayer() } = {}) {
  const operatingSystem = detectOperatingSystem({ platform, release });
  const capability = getLaunchCapability({
    operatingSystem,
    robloxInstalled: Boolean(robloxExecutable)
  });

  return {
    agentAvailable: true,
    operatingSystem,
    robloxInstalled: Boolean(robloxExecutable),
    launchAvailable: capability.available,
    error: capability.reason
  };
}

function createLocalAgent({ allowedOrigins = getAllowedOrigins(), getStatus = getAgentStatus, join = requestJoin } = {}) {
  const app = express();
  app.use(createCorsMiddleware({ allowedOrigins, allowPrivateNetwork: true }));
  app.use(express.json({ limit: '32kb' }));

  const sendStatus = (req, res) => {
    try {
      res.status(200).json(buildApiResponse(true, 'Local launch agent status loaded.', getStatus(), 200));
    } catch (error) {
      res.status(500).json(buildApiResponse(false, 'Unable to inspect the local launch capability.', {
        error: error instanceof Error ? error.message : 'Unknown local agent status error.'
      }, 500));
    }
  };

  app.get(['/', '/api/status'], sendStatus);

  app.post('/api/join', async (req, res) => {
    try {
      const status = getStatus();
      if (!status.launchAvailable) {
        res.status(409).json(buildApiResponse(false, status.error, {
          status: 'local_launch_unavailable',
          agentStatus: status
        }, 409));
        return;
      }

      const selection = ensureServerSelection(req.body);
      const result = await join(selection);
      const responseCode = result.ok ? 200 : 502;
      res.status(responseCode).json(buildApiResponse(
        result.ok,
        result.ok ? 'Roblox launch request sent from this PC.' : result.error,
        result,
        responseCode
      ));
    } catch (error) {
      res.status(400).json(buildApiResponse(false, 'Local launch request rejected.', {
        error: error instanceof Error ? error.message : 'Unknown local launch error.'
      }, 400));
    }
  });

  return app;
}

function startLocalAgent({ port = DEFAULT_PORT, host = '127.0.0.1', ...options } = {}) {
  const app = createLocalAgent(options);
  const server = app.listen(port, host, () => {
    const address = server.address();
    console.log(`Monster Hopper local launch agent is listening at http://${host}:${address.port}`);
  });

  server.on('error', (error) => {
    console.error(`Monster Hopper local launch agent could not listen on ${host}:${port}: ${error.message}`);
    process.exitCode = 1;
  });

  return server;
}

if (require.main === module) {
  startLocalAgent();
}

module.exports = {
  DEFAULT_PORT,
  getAllowedOrigins,
  findRobloxPlayer,
  getAgentStatus,
  createLocalAgent,
  startLocalAgent
};
