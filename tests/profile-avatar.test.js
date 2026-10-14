const test = require('node:test');
const assert = require('node:assert/strict');

const { RobloxThumbnailService, PROFILE_USER_ID } = require('../src/robloxThumbnailService.js');

test('uses Roblox public headshot API response for the configured profile user', async () => {
  let requestedUrl;
  const service = new RobloxThumbnailService({
    nowFn: () => 100,
    fetchFn: async (url) => {
      requestedUrl = new URL(url);
      return {
        ok: true,
        json: async () => ({
          data: [{
            targetId: Number(PROFILE_USER_ID),
            state: 'Completed',
            imageUrl: 'https://tr.rbxcdn.com/avatar-headshot.png'
          }]
        })
      };
    }
  });

  const result = await service.getAvatarHeadshot();
  assert.equal(requestedUrl.origin, 'https://thumbnails.roblox.com');
  assert.equal(requestedUrl.searchParams.get('userIds'), '8346955610');
  assert.equal(result.userId, '8346955610');
  assert.equal(result.state, 'Completed');
  assert.equal(result.imageUrl, 'https://tr.rbxcdn.com/avatar-headshot.png');
});

test('caches completed thumbnails and retries pending thumbnails without guessing an image URL', async () => {
  let now = 1000;
  let requests = 0;
  const service = new RobloxThumbnailService({
    nowFn: () => now,
    fetchFn: async () => {
      requests += 1;
      return {
        ok: true,
        json: async () => ({
          data: [{ targetId: 8346955610, state: requests === 1 ? 'Pending' : 'Completed', imageUrl: 'https://cdn.roblox.com/headshot.png' }]
        })
      };
    }
  });

  const pending = await service.getAvatarHeadshot();
  assert.equal(pending.state, 'Pending');
  assert.equal(pending.imageUrl, null);
  assert.equal((await service.getAvatarHeadshot()).state, 'Pending');
  assert.equal(requests, 1);

  now += 30000;
  const completed = await service.getAvatarHeadshot();
  assert.equal(completed.imageUrl, 'https://cdn.roblox.com/headshot.png');
  assert.equal(requests, 2);
  assert.equal((await service.getAvatarHeadshot()).imageUrl, completed.imageUrl);
  assert.equal(requests, 2);
});

test('rejects non-HTTPS thumbnail URLs and reports request errors', async () => {
  const service = new RobloxThumbnailService({
    fetchFn: async () => ({
      ok: true,
      json: async () => ({
        data: [{ targetId: 8346955610, state: 'Completed', imageUrl: 'http://example.invalid/avatar.png' }]
      })
    })
  });
  assert.equal((await service.getAvatarHeadshot()).imageUrl, null);

  const failed = new RobloxThumbnailService({
    fetchFn: async () => ({ ok: false, status: 503 })
  });
  await assert.rejects(failed.getAvatarHeadshot(), /status 503/);
});
