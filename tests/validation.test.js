const test = require('node:test');
const assert = require('node:assert/strict');

const { validatePlaceId, validateServerJobId, ensureServerJobId, ensureServerSelection } = require('../src/validation.js');

test('accepts the correct place id', () => {
  assert.equal(validatePlaceId('98664161516921'), true);
  assert.equal(validatePlaceId(98664161516921), true);
});

test('rejects invalid place ids', () => {
  assert.equal(validatePlaceId('1234'), false);
  assert.equal(validatePlaceId('98664161516922'), false);
  assert.equal(validatePlaceId(''), false);
});

test('accepts a valid server job id and rejects a swapped place id', () => {
  assert.equal(validateServerJobId('d5c3a8f7-f2d1-47b5-8a0c-a1d38f4ec512'), true);
  assert.equal(validateServerJobId('98664161516921'), false);
  assert.equal(validateServerJobId(''), false);
});

test('keeps place id and server job id separate during selection checks', () => {
  const selection = ensureServerSelection({
    placeId: '98664161516921',
    serverJobId: 'a4cf3d23-6e11-4d6e-b2b8-94a1e725e40c'
  });

  assert.equal(selection.placeId, '98664161516921');
  assert.equal(selection.serverJobId, 'a4cf3d23-6e11-4d6e-b2b8-94a1e725e40c');
  assert.notEqual(selection.placeId, selection.serverJobId);
});
