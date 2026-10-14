const { exec } = require('child_process');
const { ROBLOX_LAUNCH_SCHEME, GAME_PLACE_ID_STRING } = require('./config.js');
const { validatePlaceId, validateServerJobId } = require('./validation.js');

function buildLaunchUri(placeId, serverJobId) {
  return `${ROBLOX_LAUNCH_SCHEME}?placeId=${placeId}&gameInstanceId=${serverJobId}`;
}

function requestJoin({ placeId, serverJobId }) {
  const sanitizedPlaceId = String(placeId ?? '').trim();
  const sanitizedServerJobId = String(serverJobId ?? '').trim();

  if (!validatePlaceId(sanitizedPlaceId)) {
    throw new Error('Invalid Place ID. The launch request must use the Catch a Monster place ID 98664161516921.');
  }

  if (!validateServerJobId(sanitizedServerJobId)) {
    throw new Error('Invalid server Job ID. The launch request must include a valid Roblox server job ID.');
  }

  const launchUri = buildLaunchUri(sanitizedPlaceId, sanitizedServerJobId);

  if (process.platform !== 'win32') {
    return Promise.resolve({
      ok: false,
      status: 'unsupported_os',
      placeId: sanitizedPlaceId,
      serverJobId: sanitizedServerJobId,
      launchUri,
      error: 'This launch flow is available only on Windows 11.',
      joinConfirmed: false,
      joinVerified: false,
      launchedAt: new Date().toISOString()
    });
  }

  return new Promise((resolve) => {
    exec(`cmd /c start "" "${launchUri}"`, { windowsHide: true }, (error) => {
      if (error) {
        resolve({
          ok: false,
          status: 'launch_failed',
          placeId: sanitizedPlaceId,
          serverJobId: sanitizedServerJobId,
          launchUri,
          error: `Launch request could not start: ${error.message}`,
          joinConfirmed: false,
          joinVerified: false,
          launchedAt: new Date().toISOString()
        });
        return;
      }

      resolve({
        ok: true,
        status: 'launch_requested',
        placeId: sanitizedPlaceId,
        serverJobId: sanitizedServerJobId,
        launchUri,
        error: null,
        joinConfirmed: false,
        joinVerified: false,
        launchedAt: new Date().toISOString(),
        note: 'Roblox was asked to launch. A successful launch request is not proof that the server was joined.'
      });
    });
  });
}

module.exports = {
  buildLaunchUri,
  requestJoin
};
