const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm'),fs=require('node:fs');
const ts=require('../admin-web/node_modules/typescript');
function mini(){
 const app={globalData:{accessToken:'user-A',apiBaseUrl:'unused'}},wx={},api={};
 vm.runInNewContext(fs.readFileSync('miniprogram/utils/request.js','utf8'),{exports:api,wx,getApp:()=>app});
 return {api,wx,app};
}
test('mini retry keeps the operation key after network loss, rotates after success and isolates users',async()=>{
 const c=mini(),keys=[];
 c.wx.request=o=>{keys.push(o.header['Idempotency-Key']);o.fail()};
 await assert.rejects(c.api.request('/cases','POST',{score:3}));
 c.wx.request=o=>{keys.push(o.header['Idempotency-Key']);o.success({statusCode:200,data:{code:'OK',data:{id:'one'}}})};
 await c.api.request('/cases','POST',{score:3});
 await c.api.request('/cases','POST',{score:3});
 assert.equal(keys[0],keys[1]);assert.notEqual(keys[1],keys[2]);
 c.wx.request=o=>{keys.push(o.header['Idempotency-Key']);o.fail()};
 await assert.rejects(c.api.request('/cases','POST',{score:4}));
 c.app.globalData.accessToken='user-B';
 await assert.rejects(c.api.request('/cases','POST',{score:4}));
 assert.notEqual(keys[3],keys[4]);
});
test('mini upload HTML 401 clears token; lost upload response reuses operation key',async()=>{
 const c=mini(),keys=[];c.wx.removeStorageSync=()=>{};
 c.wx.uploadFile=o=>{keys.push(o.header['Idempotency-Key']);o.fail()};
 await assert.rejects(c.api.uploadImage('img'));
 c.wx.uploadFile=o=>{keys.push(o.header['Idempotency-Key']);o.success({statusCode:401,data:'<html>expired</html>'})};
 await assert.rejects(c.api.uploadImage('img'),/登录已失效/);
 assert.equal(keys[0],keys[1]);assert.equal(c.app.globalData.accessToken,'');
});
test('Web retry retains keys across network/502/in-progress and rotates on changed payload',async()=>{
 let response,sequence=0;const headers=[];
 const exports={};
 const source=ts.transpileModule(fs.readFileSync('admin-web/src/api/client.ts','utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
 vm.runInNewContext(source,{exports,crypto:{randomUUID:()=>`test-${++sequence}`},localStorage:{getItem:()=> 'actor'},
   fetch:async(_,init)=>{headers.push(init.headers['Idempotency-Key']);if(!response)throw Error('lost');return response}});
 await assert.rejects(exports.apiPost('/cases',{score:1}),/网络/);
 response={status:502,ok:false,json:async()=>{throw Error('html')}};
 await assert.rejects(exports.apiPost('/cases',{score:1}),/502/);
 response={status:409,ok:false,json:async()=>({code:'IDEMPOTENCY_IN_PROGRESS'})};
 await assert.rejects(exports.apiPost('/cases',{score:1}));
 response={status:200,ok:true,json:async()=>({code:'OK',data:{id:1}})};
 await exports.apiPost('/cases',{score:1});
 assert.equal(new Set(headers).size,1);
 await exports.apiPost('/cases',{score:2});assert.notEqual(headers[4],headers[0]);
});
test('performance form freezes inputs during upload and cannot submit twice after success',async()=>{
 let page,release;const payloads=[];
 const gate=new Promise(resolve=>release=resolve);
 vm.runInNewContext(fs.readFileSync('miniprogram/pages/performance-form/index.js','utf8'),{
 exports:{},Page:p=>{page=p;p.setData=d=>Object.assign(p.data,d)},setTimeout:()=>{},wx:{showToast:()=>{},showModal:o=>o.success({confirm:true})},
 require:name=>name.includes('draft')?{clearDraft:()=>{}}:name.includes('subscription')?{collectSubscriptionCredit:()=>gate}:{request:async(path,method,body)=>payloads.push(body)}
 });
 Object.assign(page.data,{targetIndex:0,type:'DEDUCTION',supervisor:true,ruleCode:'rule',score:'3',description:'original',date:'2026-09-01',time:'10:00',people:[{id:'one'}]});
 const submit=page.submit();
 page.dateChange({detail:{value:'2026-10-01'}});page.inputScore({detail:{value:'9'}});page.removeImage({currentTarget:{dataset:{index:0}}});
 assert.equal(page.data.date,'2026-09-01');assert.equal(page.data.score,'3');
 release();await submit;await page.submit();
 assert.equal(payloads.length,1);assert.equal(payloads[0].occurredAt,'2026-09-01T10:00:00+08:00');assert.equal(payloads[0].score,3);
});
