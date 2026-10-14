const PROFILE_USER_ID = '8346955610';
const THUMBNAIL_URL = 'https://thumbnails.roblox.com/v1/users/avatar-headshot';
const COMPLETE_CACHE_TTL_MS = 30 * 60 * 1000;
const PENDING_CACHE_TTL_MS = 30 * 1000;

class RobloxThumbnailService {
  constructor({ fetchFn = (...args) => fetch(...args), nowFn = () => Date.now() } = {}) {
    this.fetchFn = fetchFn;
    this.nowFn = nowFn;
    this.cachedResult = null;
    this.cacheExpiresAt = 0;
    this.inFlight = null;
  }

  async getAvatarHeadshot() {
    if (this.cachedResult && this.cacheExpiresAt > this.nowFn()) {
      return this.cachedResult;
    }
    if (this.inFlight) {
      return this.inFlight;
    }

    const request = this._fetchAvatarHeadshot();
    this.inFlight = request;
    request.finally(() => {
      if (this.inFlight === request) {
        this.inFlight = null;
      }
    }).catch(() => {});
    return request;
  }

  async _fetchAvatarHeadshot() {
    const url = new URL(THUMBNAIL_URL);
    url.searchParams.set('userIds', PROFILE_USER_ID);
    url.searchParams.set('size', '150x150');
    url.searchParams.set('format', 'Png');
    url.searchParams.set('isCircular', 'false');

    const response = await this.fetchFn(url, {
      headers: { Accept: 'application/json', 'User-Agent': 'MonsterHopper/1.0' },
      signal: AbortSignal.timeout(10000)
    });
    if (!response.ok) {
      throw new Error(`Roblox avatar thumbnail request failed with status ${response.status}.`);
    }

    const payload = await response.json();
    const thumbnail = Array.isArray(payload?.data)
      ? payload.data.find((entry) => String(entry?.targetId) === PROFILE_USER_ID)
      : null;
    const imageUrl = thumbnail?.state === 'Completed'
      && typeof thumbnail.imageUrl === 'string'
      && thumbnail.imageUrl.startsWith('https://')
      ? thumbnail.imageUrl
      : null;
    const result = {
      userId: PROFILE_USER_ID,
      state: typeof thumbnail?.state === 'string' ? thumbnail.state : 'Unavailable',
      imageUrl
    };

    this.cachedResult = result;
    this.cacheExpiresAt = this.nowFn() + (imageUrl ? COMPLETE_CACHE_TTL_MS : PENDING_CACHE_TTL_MS);
    return result;
  }
}

module.exports = { RobloxThumbnailService, PROFILE_USER_ID };
