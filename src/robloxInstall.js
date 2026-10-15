const fs = require('fs');
const path = require('path');

function findRobloxPlayer(localAppData = process.env.LOCALAPPDATA) {
  if (!localAppData) {
    return null;
  }

  const versionsPath = path.join(localAppData, 'Roblox', 'Versions');
  let entries;
  try {
    entries = fs.readdirSync(versionsPath, { withFileTypes: true });
  } catch (error) {
    if (error.code === 'ENOENT') {
      return null;
    }
    throw error;
  }

  for (const entry of entries) {
    if (!entry.isDirectory() || !entry.name.startsWith('version-')) {
      continue;
    }
    const executable = path.join(versionsPath, entry.name, 'RobloxPlayerBeta.exe');
    try {
      fs.accessSync(executable, fs.constants.F_OK);
      return executable;
    } catch (error) {
      if (error.code !== 'ENOENT') {
        throw error;
      }
    }
  }

  return null;
}

module.exports = { findRobloxPlayer };
