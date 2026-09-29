// Structural runtime smoke checks for every registered mini-program page.
// wx is mocked; this does not replace WeChat simulator/device rendering.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../admin-web/node_modules/typescript');
const root = path.resolve(__dirname, '../miniprogram');
const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
let bindings = 0;
for (const pagePath of app.pages) {
  const stem = path.join(root, pagePath);
  for (const ext of ['.js', '.ts', '.json', '.wxml', '.wxss']) assert.ok(fs.existsSync(stem + ext), pagePath + ext);
  JSON.parse(fs.readFileSync(stem + '.json', 'utf8'));
  let page;
  vm.runInNewContext(fs.readFileSync(stem + '.js', 'utf8'), {
    exports: {}, Page: value => { page = value; },
    require: name => name.includes('display') ? require('../miniprogram/utils/display.js') : {},
    getApp: () => ({ globalData: {} }), wx: {},
  }, { filename: pagePath });
  assert.ok(page && page.data, pagePath + ' registers Page');
  const template = fs.readFileSync(stem + '.wxml', 'utf8');
  for (const match of template.matchAll(/\b(?:bind|catch)(?::?[\w-]+)\s*=\s*["']([\w$]+)["']/g)) {
    assert.equal(typeof page[match[1]], 'function', pagePath + ' missing handler ' + match[1]);
    bindings++;
  }
  const expected = ts.transpileModule(fs.readFileSync(stem + '.ts', 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS, esModuleInterop: true }
  }).outputText;
  const normalize = s => s.replace(/\r\n/g, '\n').trim();
  assert.equal(normalize(fs.readFileSync(stem + '.js', 'utf8')), normalize(expected), pagePath + ' stale generated JS');
  console.log('PASS page assets, event handlers and TS/JS consistency: ' + pagePath);
}
console.log(`PASS ${app.pages.length} registered pages, ${bindings} event bindings`);
