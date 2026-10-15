const { exec } = require('child_process');
const os = require('os');
const { ROBLOX_LAUNCH_SCHEME, GAME_PLACE_ID_STRING } = require('./config.js');
const { validatePlaceId, validateServerJobId } = require('./validation.js');
const { detectOperatingSystem, getLaunchCapability } = require('./windowsSupport.js');
const { findRobloxPlayer } = require('./robloxInstall.js');

function buildLaunchUri(placeId, serverJobId) {
  return `${ROBLOX_LAUNCH_SCHEME}?placeId=${placeId}&gameInstanceId=${serverJobId}`;
}

function requestJoin({ placeId, serverJobId }, {
  platform = process.platform,
  release = os.release(),
  robloxExecutable = findRobloxPlayer(),
  execute = exec
} = {}) {
  const sanitizedPlaceId = String(placeId ?? '').trim();
  const sanitizedServerJobId = String(serverJobId ?? '').trim();

  if (!validatePlaceId(sanitizedPlaceId)) {
    throw new Error('Invalid Place ID. The launch request must use the Catch a Monster place ID 98664161516921.');
  }

  if (!validateServerJobId(sanitizedServerJobId)) {
    throw new Error('Invalid server Job ID. The launch request must include a valid Roblox server job ID.');
  }

  const launchUri = buildLaunchUri(sanitizedPlaceId, sanitizedServerJobId);

  const capability = getLaunchCapability({
    operatingSystem: detectOperatingSystem({ platform, release }),
    robloxInstalled: Boolean(robloxExecutable)
  });

  if (!capability.available) {
    return Promise.resolve({
      ok: false,
      status: platform === 'win32' ? 'local_launch_unavailable' : 'unsupported_os',
      placeId: sanitizedPlaceId,
      serverJobId: sanitizedServerJobId,
      launchUri,
      error: capability.reason,
      joinConfirmed: false,
      joinVerified: false,
      launchedAt: new Date().toISOString()
    });
  }

  return new Promise((resolve) => {
    execute(`cmd /c start "" "${launchUri}"`, { windowsHide: true }, (error) => {
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
