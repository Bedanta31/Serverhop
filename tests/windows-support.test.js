const test = require('node:test');
const assert = require('node:assert/strict');
const { detectOperatingSystem, getLaunchCapability } = require('../src/windowsSupport.js');
const { requestJoin } = require('../src/joinService.js');

test('detects Windows 11 from the local Windows build number', () => {
  assert.deepEqual(detectOperatingSystem({
    platform: 'win32',
    release: '10.0.26100'
  }), {
    platform: 'win32',
    release: '10.0.26100',
    isWindows11: true
  });
});

test('does not classify Windows 10 or non-Windows hosts as Windows 11', () => {
  assert.equal(detectOperatingSystem({
    platform: 'win32',
    release: '10.0.19045'
  }).isWindows11, false);
  assert.equal(detectOperatingSystem({
    platform: 'linux',
    release: '10.0.26100'
  }).isWindows11, false);
  assert.equal(detectOperatingSystem({
    platform: 'darwin',
    release: '23.0.0'
  }).isWindows11, false);
});

test('checks local launch capability independently from OS identification', () => {
  const windows11 = detectOperatingSystem({ platform: 'win32', release: '10.0.26100' });
  assert.deepEqual(getLaunchCapability({
    operatingSystem: windows11,
    robloxInstalled: true
  }), { available: true, reason: null });
  assert.equal(getLaunchCapability({
    operatingSystem: windows11,
    robloxInstalled: false
  }).available, false);
  assert.equal(getLaunchCapability({
    operatingSystem: detectOperatingSystem({ platform: 'linux', release: '6.8.0' }),
    robloxInstalled: true
  }).available, false);
});

test('requests a Roblox launch only with a Windows 11 local Roblox installation', async () => {
  let command = null;
  const result = await requestJoin({
    placeId: '98664161516921',
    serverJobId: 'valid-server-id-123'
  }, {
    platform: 'win32',
    release: '10.0.26100',
    robloxExecutable: 'C:\\Roblox\\RobloxPlayerBeta.exe',
    execute: (commandText, options, callback) => {
      command = commandText;
      assert.equal(options.windowsHide, true);
      callback(null);
    }
  });

  assert.equal(result.ok, true);
  assert.equal(result.status, 'launch_requested');
  assert.match(command, /^cmd \/c start/);
  assert.equal(result.joinConfirmed, false);
});

test('refuses launch requests on non-Windows and without Roblox installed', async () => {
  const nonWindows = await requestJoin({
    placeId: '98664161516921',
    serverJobId: 'valid-server-id-123'
  }, {
    platform: 'linux',
    release: '6.8.0',
    robloxExecutable: 'ignored.exe'
  });
  assert.equal(nonWindows.ok, false);
  assert.equal(nonWindows.status, 'unsupported_os');

  const noRoblox = await requestJoin({
    placeId: '98664161516921',
    serverJobId: 'valid-server-id-123'
  }, {
    platform: 'win32',
    release: '10.0.26100',
    robloxExecutable: null
  });
  assert.equal(noRoblox.ok, false);
  assert.equal(noRoblox.status, 'local_launch_unavailable');
  assert.match(noRoblox.error, /Roblox Player was not found/);
});
