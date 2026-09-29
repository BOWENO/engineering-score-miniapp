const assert=require('node:assert/strict');
const path=require('node:path');
let page,calls=[],destination='';
global.getApp=()=>({globalData:{accessToken:'test-user'}});
global.Page=p=>{page=p;p.setData=d=>Object.assign(p.data,d)};
global.wx={switchTab:o=>destination=o.url,showToast:()=>{}};
const req=path.resolve(__dirname,'../miniprogram/utils/request.js');
require.cache[req]={id:req,filename:req,loaded:true,exports:{request:async(url,method,data)=>{calls.push({url,method,data});if(url.endsWith('/source-date'))return '2026-09-01';if(!method)return {items:[],unread:0,awaitingAcknowledgement:0}}}};
const sub=path.resolve(__dirname,'../miniprogram/utils/subscription.js');
require.cache[sub]={id:sub,filename:sub,loaded:true,exports:{collectSubscriptionCredit:async()=>{}}};
require('../miniprogram/pages/messages/index.js');
(async()=>{
 page.data.items=[{id:'notice',sourceId:'assignment',sourceType:'SCHEDULE_ASSIGNMENT',requiresAcknowledgement:true}];
 await page.open({currentTarget:{dataset:{index:0}}});
 assert.equal(calls[0].url,'/notifications/notice/read');assert.equal(calls[0].data.acknowledge,false);
 assert.equal(calls.some(c=>c.url.includes('/schedules/')),false);assert.equal(destination,'/pages/schedule/index');
 console.log('PASS: opening schedule notification only marks it read; no assignment confirmation');
})().catch(e=>{console.error(e);process.exitCode=1});
