const test = require('node:test');
const assert = require('node:assert/strict');
const { once } = require('node:events');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

process.env.MONSTER_HOPPER_ALLOWED_ORIGINS = 'https://username.github.io';
const { startServer, getServerConfig } = require('../server.js');

test('uses safe local defaults and Railway-provided network settings', () => {
  assert.deepEqual(getServerConfig({}), { host: '127.0.0.1', port: 3000 });
  assert.deepEqual(getServerConfig({ RAILWAY_ENVIRONMENT: 'production', PORT: '8080' }), {
    host: '0.0.0.0',
    port: 8080
  });
  assert.deepEqual(getServerConfig({ HOST: '127.0.0.1', PORT: '5000', RAILWAY_ENVIRONMENT: 'production' }), {
    host: '127.0.0.1',
    port: 5000
  });
  assert.throws(() => getServerConfig({ PORT: 'invalid' }), /PORT must be an integer/);
});

test('documents and configures the frontend to use the implemented loopback agent endpoint', () => {
  const packageJson = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'package.json'), 'utf8'));
  const app = fs.readFileSync(path.join(__dirname, '..', 'public', 'app.js'), 'utf8');
  const runtimeConfig = fs.readFileSync(path.join(__dirname, '..', 'public', 'runtime-config.js'), 'utf8');
  const readme = fs.readFileSync(path.join(__dirname, '..', 'README.md'), 'utf8');
  const runtimeContext = { window: {} };
  vm.runInNewContext(runtimeConfig, runtimeContext);

  assert.equal(packageJson.scripts.agent, 'node local-agent.js');
  assert.match(app, /http:\/\/127\.0\.0\.1:3210/);
  assert.match(app, /`\$\{LOCAL_AGENT_URL\}\/api\/status`/);
  assert.match(app, /`\$\{LOCAL_AGENT_URL\}\/api\/join`/);
  assert.match(app, /local-network access permission and CORS\/preflight errors/);
  assert.match(app, /window\.location\.origin/);
  assert.equal(runtimeContext.window.MONSTER_HOPPER_CONFIG.apiBaseUrl, 'https://serverhop-production.up.railway.app');
  assert.doesNotMatch(runtimeContext.window.MONSTER_HOPPER_CONFIG.apiBaseUrl, /localhost|127\.0\.0\.1/i);
  assert.match(readme, /MONSTER_HOPPER_ALLOWED_ORIGINS/);
  assert.match(readme, /http:\/\/127\.0\.0\.1:3210\/api\/status/);
});

test('serves the website and health route on a dynamically assigned port', async () => {
  const server = startServer({ host: '127.0.0.1', port: 0 });
  await once(server, 'listening');
  const address = server.address();
  const baseUrl = `http://127.0.0.1:${address.port}`;

  try {
    const pageResponse = await fetch(baseUrl);
    const page = await pageResponse.text();
    assert.equal(pageResponse.status, 200);
    assert.match(page, /Monster Hopper \| Catch a Monster/);
    assert.match(page, /cosmic-anubis-hopper-logo\.png/);
    assert.match(page, /<div class="brand-wrap">/);
    assert.doesNotMatch(page, /<a class="brand-wrap"/);
    assert.match(page, /<script src="\.\/server-data\.js"><\/script>/);
    assert.match(page, /<script src="\.\/runtime-config\.js"><\/script>/);
    assert.match(page, /id="intervalInput" type="number" min="15"/);
    assert.match(page, /Loading servers\.\.\./);
    assert.doesNotMatch(page, /Advanced details/i);
    assert.doesNotMatch(page, /Backend online|Backend checking|backendStatusBadge/i);
    assert.match(page, /href="https:\/\/www\.roblox\.com\/users\/8346955610\/profile"/);
    assert.match(page, /target="_blank"[\s\S]*?rel="noopener noreferrer"/);
    assert.match(page, /id="profileAvatar"[^>]*draggable="false"/);
    assert.match(page, /class="profile-watermark"/);

    const appResponse = await fetch(`${baseUrl}/app.js`);
    const app = await appResponse.text();
    assert.equal(appResponse.status, 200);
    assert.doesNotMatch(app, /server-details|<summary>Details<\/summary>/);

    const runtimeConfigResponse = await fetch(`${baseUrl}/runtime-config.js`);
    const runtimeConfig = await runtimeConfigResponse.text();
    assert.equal(runtimeConfigResponse.status, 200);
    assert.match(runtimeConfig, /apiBaseUrl/);

    const stylesheetResponse = await fetch(`${baseUrl}/styles.css`);
    const stylesheet = await stylesheetResponse.text();
    assert.equal(stylesheetResponse.status, 200);
    assert.match(stylesheet, /\.ambient-particles[\s\S]*?pointer-events: none/);
    assert.match(stylesheet, /@media \(prefers-reduced-motion: reduce\)/);
    assert.match(stylesheet, /user-select: none/);
    assert.match(stylesheet, /user-select: text/);
    assert.match(stylesheet, /\.profile-watermark[\s\S]*?position: fixed/);
    assert.match(stylesheet, /@media \(max-width: 760px\)[\s\S]*?\.profile-watermark/);

    const skipHistoryResponse = await fetch(`${baseUrl}/server-skip-history.js`);
    const skipHistory = await skipHistoryResponse.text();
    assert.equal(skipHistoryResponse.status, 200);
    assert.match(skipHistory, /15 \* 60 \* 1000/);

    const originalFetch = global.fetch;
    global.fetch = async (input, options) => {
      const url = new URL(input);
      if (url.hostname === 'thumbnails.roblox.com') {
        return {
          ok: true,
          json: async () => ({ data: [{ targetId: 8346955610, state: 'Pending' }] })
        };
      }
      return originalFetch(input, options);
    };
    try {
      const avatarResponse = await fetch(`${baseUrl}/api/profile-avatar`);
      const avatar = await avatarResponse.json();
      assert.equal(avatar.data.userId, '8346955610');
      assert.equal(avatar.data.imageUrl, null);
    } finally {
      global.fetch = originalFetch;
    }

    const healthResponse = await fetch(`${baseUrl}/api/health`);
    const health = await healthResponse.json();
    assert.equal(healthResponse.status, 200);
    assert.equal(health.success, true);
    assert.equal(health.data.placeId, '98664161516921');

    const githubPagesResponse = await fetch(`${baseUrl}/api/health`, {
      headers: { Origin: 'https://username.github.io' }
    });
    assert.equal(githubPagesResponse.status, 200);
    assert.equal(githubPagesResponse.headers.get('access-control-allow-origin'), 'https://username.github.io');

    const githubPagesPreflight = await fetch(`${baseUrl}/api/join`, {
      method: 'OPTIONS',
      headers: {
        Origin: 'https://username.github.io',
        'Access-Control-Request-Method': 'POST',
        'Access-Control-Request-Headers': 'content-type'
      }
    });
    assert.equal(githubPagesPreflight.status, 204);
    assert.equal(githubPagesPreflight.headers.get('access-control-allow-origin'), 'https://username.github.io');

    const hopLimitResponse = await fetch(`${baseUrl}/api/hop-limit`);
    const hopLimit = await hopLimitResponse.json();
    assert.equal(hopLimitResponse.status, 200);
    assert.equal(hopLimit.success, true);
    assert.equal(hopLimit.data.limit, 50);
    assert.equal(hopLimit.data.timezone, 'UTC');

    const reservationResponse = await fetch(`${baseUrl}/api/join`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ placeId: '98664161516921', serverJobId: 'valid-server-id-1' })
    });
    const reservation = await reservationResponse.json();
    assert.equal(reservationResponse.status, 202);
    assert.equal(reservation.data.status, 'awaiting_local_agent');
    assert.equal(reservation.data.joinConfirmed, false);
    assert.equal(typeof reservation.data.reservationToken, 'string');
    assert.equal(reservation.data.hopLimit.count, 0);
    assert.equal(reservation.data.hopLimit.pending, 1);

    const failedCompletionResponse = await fetch(`${baseUrl}/api/join/complete`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        reservationToken: reservation.data.reservationToken,
        launchRequested: false
      })
    });
    const failedCompletion = await failedCompletionResponse.json();
    assert.equal(failedCompletionResponse.status, 200);
    assert.equal(failedCompletion.data.status, 'launch_failed');
    assert.equal(failedCompletion.data.hopLimit.count, 0);
    assert.equal(failedCompletion.data.hopLimit.pending, 0);

    const acceptedReservationResponse = await fetch(`${baseUrl}/api/join`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ placeId: '98664161516921', serverJobId: 'valid-server-id-2' })
    });
    const acceptedReservation = await acceptedReservationResponse.json();
    const acceptedCompletionResponse = await fetch(`${baseUrl}/api/join/complete`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        reservationToken: acceptedReservation.data.reservationToken,
        launchRequested: true
      })
    });
    const acceptedCompletion = await acceptedCompletionResponse.json();
    assert.equal(acceptedCompletionResponse.status, 200);
    assert.equal(acceptedCompletion.data.status, 'launch_requested');
    assert.equal(acceptedCompletion.data.joinConfirmed, false);
    assert.equal(acceptedCompletion.data.hopLimit.count, 1);
  } finally {
    await new Promise((resolve, reject) => {
      server.close((error) => error ? reject(error) : resolve());
    });
  }
});
