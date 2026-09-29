// Runs the actual page JS and request adapter against the isolated Spring HTTP server.
// Only wx transport/storage/UI primitives and WeChat subscriptions are substituted.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const base=fs.readFileSync(path.join(__dirname,'../server/target/acceptance-api-url.txt'),'utf8');
if(new URL(base).hostname!=='127.0.0.1')throw Error('Isolated loopback backend required');
const app={globalData:{apiBaseUrl:base,accessToken:''}},storage=new Map();
const failures=[]; let requests=0, scenarios=0;
const wx={getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,v),removeStorageSync:k=>storage.delete(k),
  removeTabBarBadge(){},setTabBarBadge(){},showToast(){},showModal(){},reLaunch(){},switchTab(){},navigateTo(){},
  request:async opts=>{try{requests++;const r=await fetch(opts.url,{method:opts.method,headers:{'Content-Type':'application/json',...opts.header},body:opts.method==='GET'?undefined:JSON.stringify(opts.data)});opts.success({statusCode:r.status,data:await r.json()});}catch(e){opts.fail(e);}}
};
const reqContext={exports:{},getApp:()=>app,wx};
vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../miniprogram/utils/request.js'),'utf8'),reqContext);
const subscriptions={refreshSubscriptionHarvesting(){},collectSubscriptionCredit:async()=>{}};
function load(name){let page;vm.runInNewContext(fs.readFileSync(path.join(__dirname,`../miniprogram/pages/${name}/index.js`),'utf8'),{
  exports:{},Error,Page:p=>{page=p;p.setData=d=>Object.assign(p.data,d)},wx,getApp:()=>app,
  require:n=>n.includes('display')?require('../miniprogram/utils/display.js'):n.includes('subscription')?subscriptions:reqContext.exports
});return page;}
(async()=>{
for(const role of ['TECHNICIAN','ASSISTANT_ENGINEER','SUPERVISOR','DEPARTMENT_MANAGER']) {
  app.globalData.accessToken='';
  const profile=load('profile');profile.data.username=role;profile.data.password='Acceptance2026!';
  await profile.accountLogin();assert.equal(profile.data.error,'',role+' login');assert.ok(app.globalData.accessToken);
  scenarios++;console.log('PASS actual mini password login: '+role);
  for(const [name,method] of [['home','load'],['score','initialize'],['schedule','load'],['incidents','initialize'],['profile','loadUser'],['messages','load']]) {
    const page=load(name);await page[method]();scenarios++;
    if(page.data.error)failures.push(`${role} ${name}: ${page.data.error}`);
    else console.log(`PASS real API page load: ${role} ${name}`);
    assert.notEqual(page.data.loading,true,`${role} ${name} remains loading`);
  }
  if(['ASSISTANT_ENGINEER','SUPERVISOR'].includes(role)){
    const page=load('performance-form');page.data.type='DEDUCTION';await page.loadPeople();await page.loadRules();scenarios++;
    if(page.data.error)failures.push(`${role} form: ${page.data.error}`);else console.log('PASS real API deduction form options: '+role);
  }
  const detail=load('incident-detail');detail.data.id='00000000-0000-0000-0000-000000000099';await detail.load();scenarios++;
  assert.ok(detail.data.error,'Missing incident must show an error');assert.equal(detail.data.loading,false);
}
console.log(JSON.stringify({scenarios,requests,failures},null,2));assert.deepEqual(failures,[]);
})().catch(e=>{console.error(e);process.exitCode=1;});
