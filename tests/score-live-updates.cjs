const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
function setup(refresh){
 const timers=[],requests=[];let cancelled=0;const exports={};
 vm.runInNewContext(fs.readFileSync('miniprogram/utils/scoreWatch.js','utf8'),{exports,Date,setTimeout:fn=>{timers.push(fn);return timers.length},clearTimeout:()=>{}});
 const stop=exports.startScoreWatch(since=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});requests.push({since,resolve,reject});return {promise,cancel:()=>cancelled++}},refresh);
 return {requests,timers,stop,cancelled:()=>cancelled};
}
const flush=()=>new Promise(resolve=>setImmediate(resolve));
test('same revision does not reload, changed revision reloads and stop cancels',async()=>{
 let loads=0;const s=setup(async()=>loads++);
 s.requests[0].resolve({revision:'a'});await flush();assert.equal(loads,1);
 s.timers.shift()();assert.equal(s.requests[1].since,'a');s.requests[1].resolve({revision:'a'});await flush();assert.equal(loads,1);
 s.timers.shift()();s.requests[2].resolve({revision:'b'});await flush();assert.equal(loads,2);
 s.stop();assert.equal(s.cancelled(),1);s.timers.shift()();assert.equal(s.requests.length,3);
});
test('failed data refresh does not acknowledge or lose a change',async()=>{
 let fail=true;const s=setup(async()=>{if(fail)throw Error('offline')});
 s.requests[0].resolve({revision:'a'});await flush();s.timers.shift()();assert.equal(s.requests[1].since,'');
 fail=false;s.requests[1].resolve({revision:'a'});await flush();s.timers.shift()();assert.equal(s.requests[2].since,'a');s.stop();
});
test('late response after page hide does not refresh',async()=>{
 let loads=0;const s=setup(async()=>loads++);s.stop();s.requests[0].resolve({revision:'a'});await flush();assert.equal(loads,0);
});
test('Web and mini program share the same watcher behavior',()=>{
 assert.equal(fs.readFileSync('admin-web/src/scoreWatch.ts','utf8'),fs.readFileSync('miniprogram/utils/scoreWatch.ts','utf8'));
});
