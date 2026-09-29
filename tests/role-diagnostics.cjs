// Regression tests for role visibility and dependent selectors.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const src=fs.readFileSync(path.join(__dirname,'../miniprogram/pages/incidents/index.js'),'utf8');
(async()=>{
 for(const role of ['TECHNICIAN','ASSISTANT_ENGINEER','SUPERVISOR','DEPARTMENT_MANAGER','ADMINISTRATOR']) {
  let page;
  const request=async url=>url==='/me'?{roles:role==='ADMINISTRATOR'?[]:[role],administrator:role==='ADMINISTRATOR'}:url==='/catalog'?{lines:[{id:'A'},{id:'B'}],stations:[{id:'A1',lineId:'A'},{id:'B1',lineId:'B'}]}:[];
  vm.runInNewContext(src,{exports:{},Page:p=>{page=p;p.setData=d=>Object.assign(p.data,d)},require:name=>name.includes('display')?require('../miniprogram/utils/display.js'):name.includes('subscription')?{}:{request},getApp:()=>({globalData:{accessToken:'mock'}})});
  await page.initialize();assert.equal(page.data.error,'');
  assert.equal(page.data.canCreate,['ASSISTANT_ENGINEER','SUPERVISOR'].includes(role));
  assert.equal(page.data.canReview,role==='SUPERVISOR');
  if(page.data.canCreate){page.lineChange({detail:{value:1}});await page.initialize();assert.equal(page.data.lines[page.data.lineIndex].id,'B');assert.equal(page.data.filteredStations[0].lineId,'B')}
  console.log('PASS role visibility (mock): '+role);
 }
 console.log('PASS: incident editor keeps matching line and station after reload');
})().catch(e=>{console.error(e);process.exitCode=1});
