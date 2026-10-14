const { GAME_PLACE_ID, GAME_PLACE_ID_STRING } = require('./config.js');
const { normaliseServerPayload } = require('../public/server-data.js');

function buildApiResponse(success, message, data = null, status = 200) {
  return {
    success,
    message,
    status,
    data,
    timestamp: new Date().toISOString()
  };
}

function validatePlaceId(value) {
  if (value === null || value === undefined) {
    return false;
  }

  const stringValue = String(value).trim();
  if (!/^\d+$/.test(stringValue)) {
    return false;
  }

  const numericValue = Number(stringValue);
  if (!Number.isSafeInteger(numericValue)) {
    return false;
  }

  return numericValue === GAME_PLACE_ID;
}

function validateServerJobId(value) {
  if (value === null || value === undefined) {
    return false;
  }

  const stringValue = String(value).trim();
  if (!stringValue || stringValue === 'undefined' || stringValue === 'null') {
    return false;
  }

  if (stringValue === GAME_PLACE_ID_STRING) {
    return false;
  }

  if (!/^[A-Za-z0-9_.:-]+$/.test(stringValue)) {
    return false;
  }

  return stringValue.length >= 8 && stringValue.length <= 256;
}

function ensurePlaceId(value) {
  if (!validatePlaceId(value)) {
    throw new Error('Invalid Place ID. The required Roblox place ID is 98664161516921.');
  }
  return String(GAME_PLACE_ID);
}

function ensureServerJobId(value) {
  if (!validateServerJobId(value)) {
    throw new Error('Invalid server Job ID. It must be a separate Roblox server identifier and not the Place ID.');
  }
  return String(value).trim();
}

function ensureServerSelection(selection) {
  if (!selection || typeof selection !== 'object') {
    throw new Error('A server selection object is required.');
  }

  const placeId = ensurePlaceId(selection.placeId ?? selection.place_id ?? GAME_PLACE_ID_STRING);
  const serverJobId = ensureServerJobId(selection.serverJobId ?? selection.server_job_id ?? selection.jobId ?? selection.id);

  return {
    placeId,
    serverJobId
  };
}

module.exports = {
  buildApiResponse,
  validatePlaceId,
  validateServerJobId,
  ensurePlaceId,
  ensureServerJobId,
  ensureServerSelection,
  normaliseServerPayload
};
