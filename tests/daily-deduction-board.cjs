const {test}=require('node:test');
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
function page(request){let page;const modal=[];vm.runInNewContext(fs.readFileSync('miniprogram/pages/home/index.js','utf8'),{
 exports:{},Page:p=>{page=p;p.setData=d=>Object.assign(p.data,d)},require:name=>name.includes('request')?{request}:{},wx:{showModal:o=>modal.push(o)}
});return {page,modal}}
function board(date,items=[],page=0,hasMore=false){return {date,today:'2026-09-07',scopeName:'测试工程部',total:5,people:2,totalScore:12,page,hasMore,items}}
test('latest date wins and old responses cannot overwrite publicity',async()=>{
 const pending=[];const {page:p}=page(url=>new Promise(resolve=>pending.push({url,resolve})));
 p.data.publicityDate='2026-09-06';const first=p.loadPublicity();p.data.publicityDate='2026-09-07';const second=p.loadPublicity();
 pending[1].resolve(board('2026-09-07'));await second;pending[0].resolve(board('2026-09-06'));await first;
 assert.equal(p.data.publicity.date,'2026-09-07');assert.equal(p.data.publicityLoading,false);
});
test('pagination appends items, keeps server totals and rejects duplicate load-more clicks',async()=>{
 let resolve,calls=0;const {page:p}=page(()=>{calls++;return new Promise(r=>resolve=r)});
 p.data.publicity=board('2026-09-07',[{id:'a'},{id:'b'},{id:'c'}],0,true);
 const loading=p.loadPublicity(true);await p.loadPublicity(true);assert.equal(calls,1);
 resolve(board('2026-09-07',[{id:'d'},{id:'e'}],1,false));await loading;
 assert.equal(p.data.publicity.items.length,5);assert.equal(p.data.publicity.totalScore,12);assert.equal(p.data.publicity.hasMore,false);
});
test('failure has an explicit error and retry can recover to an honest empty state',async()=>{
 let fail=true;const {page:p}=page(async()=>{if(fail)throw Error('offline');return {...board('2026-09-07'),total:0,people:0,totalScore:0}});
 await p.loadPublicity();assert.ok(p.data.publicityError);assert.equal(p.data.publicity,null);assert.equal(p.data.publicityLoading,false);
 fail=false;await p.loadPublicity();assert.equal(p.data.publicityError,'');assert.equal(p.data.publicity.total,0);
});
test('details show the complete reason, role, occurrence time and appeal status',()=>{
 const {page:p,modal}=page(()=>{});const long='完整事项说明'.repeat(80);
 p.data.publicity=board('2026-09-07',[{id:'a',displayName:'测试人员',teamName:'A组',roleName:'助理工程师',description:long,score:3,category:'特殊扣分',effectiveTime:'09:00',occurredTime:'09-06 18:00',underAppeal:true}]);
 p.showPublicDeduction({currentTarget:{dataset:{id:'a'}}});assert.ok(modal[0].content.includes(long));assert.ok(modal[0].content.includes('申诉处理中'));assert.ok(modal[0].content.includes('09-06 18:00'));
 const template=fs.readFileSync('miniprogram/pages/home/index.wxml','utf8');assert.ok(template.indexOf('score-hero')<template.indexOf('publicity-card'));assert.ok(template.indexOf('management-hero')<template.indexOf('publicity-card'));
});

