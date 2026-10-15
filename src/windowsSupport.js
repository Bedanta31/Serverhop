const os = require('os');

function detectOperatingSystem({ platform = process.platform, release = os.release() } = {}) {
  const version = /^(\d+)\.(\d+)\.(\d+)/.exec(String(release));
  const major = version ? Number(version[1]) : null;
  const minor = version ? Number(version[2]) : null;
  const build = version ? Number(version[3]) : null;
  const isWindows11 = platform === 'win32'
    && major === 10
    && minor === 0
    && build >= 22000;

  return {
    platform,
    release: String(release),
    isWindows11
  };
}

function getLaunchCapability({ operatingSystem, robloxInstalled }) {
  if (!operatingSystem.isWindows11) {
    return {
      available: false,
      reason: 'Monster Hopper launching requires the local agent to run on Windows 11.'
    };
  }

  if (!robloxInstalled) {
    return {
      available: false,
      reason: 'Roblox Player was not found on this PC. Install Roblox Player, then restart the local agent.'
    };
  }

  return { available: true, reason: null };
}

module.exports = {
  detectOperatingSystem,
  getLaunchCapability
};
