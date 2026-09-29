const assert = require('node:assert/strict');
const path = require('node:path');
let page, posts = [], pending = null, fail = false, harvest = null;
let catalog = {lines:[{id:'A'},{id:'B'}],stations:[{id:'A1',lineId:'A'},{id:'B1',lineId:'B'},{id:'B2',lineId:'B'}],shifts:[{code:'DAY'},{code:'NIGHT'}]};
let people = [{id:'P1',roles:['TECHNICIAN']},{id:'P2',roles:['TECHNICIAN']}];
global.Page = p => {page=p;p.setData=patch=>Object.assign(p.data,patch)};
global.wx = {showToast:()=>{}};
const req = path.resolve(__dirname,'../miniprogram/utils/request.js');
require.cache[req]={id:req,filename:req,loaded:true,exports:{request:async(url,method,data)=>{
 if(method==='POST'){posts.push(data);return {}}
 if(fail)throw Error('offline');
 if(url==='/me')return {roles:['ASSISTANT_ENGINEER']};
 if(url==='/catalog'){if(pending){const p=pending;pending=null;return p}return catalog}
 if(url==='/directory/people')return people;
 return [];
}}};
const sub = path.resolve(__dirname,'../miniprogram/utils/subscription.js');
require.cache[sub]={id:sub,filename:sub,loaded:true,exports:{collectSubscriptionCredit:async()=>{if(harvest)await harvest}}};
require('../miniprogram/pages/schedule/index.js');
const selected = () => [page.data.lines[page.data.lineIndex]?.id,page.data.filteredStations[page.data.stationIndex]?.id];
(async()=>{
 await page.load();page.lineChange({detail:{value:1}});page.stationChange({detail:{value:1}});
 page.personChange({detail:{value:1}});page.shiftChange({detail:{value:1}});
 await page.load();assert.deepEqual(selected(),['B','B2']);
 catalog={...catalog,lines:[...catalog.lines].reverse(),stations:[...catalog.stations].reverse(),shifts:[...catalog.shifts].reverse()};people.reverse();
 await page.load();assert.deepEqual(selected(),['B','B2']);assert.equal(page.data.people[page.data.personIndex].id,'P2');assert.equal(page.data.shifts[page.data.shiftIndex].code,'NIGHT');
 let release;pending=new Promise(r=>release=r);const old=page.load();await page.load();release({lines:[],stations:[],shifts:[]});await old;assert.deepEqual(selected(),['B','B2']);
 catalog={...catalog,stations:catalog.stations.filter(s=>s.id!=='B2')};await page.load();assert.deepEqual(selected(),['B','B1']);
 page.data.filteredStations=[{id:'A1',lineId:'A'}];await page.save();assert.equal(posts.length,0);assert.match(page.data.error,/不匹配/);assert.deepEqual(selected(),['B','B1']);
 page.setData({editFrom:'2026-09-04',editTo:'2026-09-03'});await page.save();assert.equal(posts.length,0);
 page.setData({editTo:'2026-09-04'});
 let finish;harvest=new Promise(r=>finish=r);const save=page.save();await page.save();page.setData({publish:false});finish();await save;harvest=null;
 assert.equal(posts.length,1);assert.equal(posts[0].lineId,'B');assert.deepEqual(posts[0].stationIds,['B1']);assert.equal(posts[0].publish,true);
 fail=true;await page.load();await page.save();assert.equal(posts.length,1);assert.equal(page.data.catalogReady,false);fail=false;
 catalog={lines:[{id:'A'}],stations:[],shifts:[]};await page.load();assert.deepEqual(selected(),['A',undefined]);await page.save();assert.equal(posts.length,1);
 catalog={lines:[],stations:[],shifts:[]};await page.load();page.lineChange({detail:{value:99}});assert.deepEqual(selected(),[undefined,undefined]);
 console.log('PASS: reload, reorder, latest-response wins, removed selections, mismatch guard, date validation, duplicate submit, payload snapshot, failed refresh, empty catalog');
})().catch(e=>{console.error(e);process.exitCode=1});
