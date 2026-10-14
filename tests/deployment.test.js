const test = require('node:test');
const assert = require('node:assert/strict');
const { once } = require('node:events');

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
    assert.match(page, /<script src="\/server-data\.js"><\/script>/);
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

    const hopLimitResponse = await fetch(`${baseUrl}/api/hop-limit`);
    const hopLimit = await hopLimitResponse.json();
    assert.equal(hopLimitResponse.status, 200);
    assert.equal(hopLimit.success, true);
    assert.equal(hopLimit.data.limit, 50);
    assert.equal(hopLimit.data.timezone, 'UTC');
  } finally {
    await new Promise((resolve, reject) => {
      server.close((error) => error ? reject(error) : resolve());
    });
  }
});
