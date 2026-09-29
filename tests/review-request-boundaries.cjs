// Regression assertions for the request/date boundaries reported on 2026-09-07.
// No network, real accounts, or production data are used.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
function client() {
  const app = { globalData: { accessToken: 'expired-test-token', apiBaseUrl: 'http://unused' } };
  const removed = [];
  const wx = { removeStorageSync: key => removed.push(key) };
  const exports = {};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/utils/request.js'), 'utf8'),
    { exports, wx, getApp: () => app });
  return { api: exports, wx, app, removed };
}
test('ordinary API 401 clears the expired session', async () => {
  const c = client();
  c.wx.request = o => o.success({ statusCode: 401, data: { code: 'UNAUTHORIZED', message: '登录已失效' } });
  await assert.rejects(c.api.request('/me'), e => e.statusCode === 401);
  assert.equal(c.app.globalData.accessToken, '');
  assert.ok(c.removed.includes('access_token'));
});
test('image upload 401 must also clear the expired session', async () => {
  const c = client();
  c.wx.uploadFile = o => o.success({ statusCode: 401, data: JSON.stringify({ code: 'UNAUTHORIZED', message: '登录已失效' }) });
  await assert.rejects(c.api.uploadImage('mock-image'), e => e.statusCode === 401);
  assert.equal(c.app.globalData.accessToken, '');
  assert.ok(c.removed.includes('access_token'));
});
test('API errors retain the server request ID for support', async () => {
  const c = client();
  c.wx.request = o => o.success({ statusCode: 500, data: { code: 'INTERNAL_ERROR', message: '系统暂时不可用', requestId: 'review-trace-123' } });
  await assert.rejects(c.api.request('/me'), e => e.requestId === 'review-trace-123' || e.message.includes('review-trace-123'));
});
test('deduction rules must match the latest selected date when responses arrive out of order', async () => {
  let page;
  const pending = new Map();
  const request = url => url === '/me' ? Promise.resolve({ roles: ['SUPERVISOR'] }) : new Promise(resolve => pending.set(url, resolve));
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/pages/performance-form/index.js'), 'utf8'), {
    exports: {}, Page: p => { page = p; p.setData = d => Object.assign(p.data, d); },
    require: name => name.includes('subscription') ? {} : { request },
  });
  page.data.date = '2026-09-01';
  const first = page.loadRules();
  page.data.date = '2026-09-02';
  const second = page.loadRules();
  pending.get('/score-rules?type=PENALTY&date=2026-09-02')([{ code: 'NEW', title: 'new' }]);
  await second;
  pending.get('/score-rules?type=PENALTY&date=2026-09-01')([{ code: 'OLD', title: 'old' }]);
  await first;
  assert.equal(page.data.rules[0].code, 'NEW');
});
