const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm');
const display=require('../miniprogram/utils/display.js');
const raw={status:'WAITING_STATEMENTS',occurredAt:'2026-08-27T05:50:00Z',events:[{eventType:'INCIDENT_CREATED',createdAt:'2026-09-02T05:55:59.470294Z'}]};
const result=display.decorate(raw);
assert.equal(result.status,'WAITING_STATEMENTS');assert.equal(result.statusText,'待收集说明');
assert.equal(result.occurredAtText,'2026-08-27 13:50');assert.equal(result.events[0].eventTypeText,'创建异常档案');assert.equal(result.events[0].createdAtText,'2026-09-02 13:55');
assert.equal(raw.statusText,undefined);
assert.equal(display.chinaTime('2026-09-02T17:00:00Z').slice(0,10),'2026-09-03');
let page,calls=[];
vm.runInNewContext(fs.readFileSync('miniprogram/pages/performance-form/index.js','utf8'),{exports:{},Page:p=>{page=p;p.setData=d=>Object.assign(p.data,d)},require:name=>name.includes('subscription')?{}:{request:async url=>{calls.push(url);return url==='/me'?{roles:['SUPERVISOR']}:[{code:'RULE1',title:'测试扣分规则'}]}}});
(async()=>{
 page.data.type='DEDUCTION';await page.loadRules();assert.equal(page.data.supervisor,true);assert.equal(page.data.ruleIndex,-1);
 const before=calls.length;await page.submit();assert.equal(calls.length,before);assert.match(page.data.error,/请选择有效/);
 page.selectRule({detail:{value:0}});assert.equal(page.data.ruleCode,'RULE1');
 page.data.saving=true;await page.submit();assert.equal(calls.length,before);
 console.log('PASS: Chinese incident labels, Shanghai timestamps, raw statuses preserved, rule picker and duplicate-submit guard');
})().catch(e=>{console.error(e);process.exitCode=1});
