// Local-only fixture server for browser checks. No production API forwarding.
const http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../admin-web/dist');
const user={userId:'fixture',employeeNo:'fixture',displayName:'隔离测试主管',orgUnitId:'team',roles:['SUPERVISOR'],administrator:false,passwordChangeRequired:false};
http.createServer((req,res)=>{
 const url=new URL(req.url,'http://127.0.0.1');
 if(url.pathname.startsWith('/api/')){
  let data=[];
  if(url.pathname==='/api/auth/account/login')data={accessToken:'local-fixture-only'};
  if(url.pathname==='/api/me')data=user;
  if(url.pathname==='/api/workbench')data={pending:{unreadMessages:0},currentSchedules:[],hotEquipment:[]};
  if(url.pathname==='/api/publicity/daily-deductions')data={date:url.searchParams.get('date')||'2026-09-07',today:'2026-09-07',scopeName:'测试工程部（样例）',total:4,people:3,totalScore:12,page:0,hasMore:false,items:[
    {id:'sample-1',displayName:'陈一（样例）',teamName:'设备一组',roleName:'技术员',description:'交接班巡检记录未按要求填写完整，缺少设备运行状态及异常处置说明。',score:3,category:'基础扣分',effectiveTime:'10:35',occurredTime:'09-07 08:00',underAppeal:false},
    {id:'sample-2',displayName:'林二（样例）',teamName:'设备二组',roleName:'助理工程师',description:'整改跟踪未在规定时限内完成，相关验收记录需补充。',score:5,category:'特殊扣分',effectiveTime:'09:48',occurredTime:'09-06 16:00',underAppeal:true},
    {id:'sample-3',displayName:'周三（样例）',teamName:'设备一组',roleName:'技术员',description:'当班设备保养点检遗漏，未及时完成记录确认。',score:2,category:'基础扣分',effectiveTime:'09:12',occurredTime:'09-07 08:00',underAppeal:false},
    {id:'sample-4',displayName:'陈一（样例）',teamName:'设备一组',roleName:'技术员',description:'工器具归位不符合要求，已完成现场整改。',score:2,category:'基础扣分',effectiveTime:'08:56',occurredTime:'09-07 08:00',underAppeal:false}
  ]};
  if(url.pathname==='/api/catalog')data={lines:[],stations:[],shifts:[]};
  if(url.pathname==='/api/performance/overview')data={people:[{userId:'negative',employeeNo:'TESTNEG',name:'负分测试员',teamName:'测试班组',base:0,bonus:0,penalty:5,total:-5,shiftCount:0,averageScore:0,rank:0,previewGrade:null}],gradeCounts:{}};
  if(url.pathname==='/api/performance-cases/records')data=[{id:'record',employeeNo:'TESTNEG',targetName:'负分测试员',targetTeamName:'测试班组',caseType:'SPECIAL_DEDUCTION',description:'隔离回归测试记录',ruleCode:'TEST',suggestedScore:5,approvedScore:5,effectiveScore:5,status:'EFFECTIVE',occurredAt:'2026-09-03T01:00:00Z',initiatorName:'隔离测试主管'}];
  if(url.pathname==='/api/schedules')data=Array.from({length:25},(_,i)=>({id:String(i),userId:'u'+i,employeeNo:'TEST'+i,displayName:'测试人员'+i,teamId:'team',teamName:'测试班组',businessDate:'2026-09-03',shiftCode:'DAY',lineId:i%2?'A':'B',lineName:i%2?'A线':'B线',stationId:i%2?'A1':'B1',stationName:i%2?'A站':'B站',status:'PUBLISHED',version:0}));
  if(url.pathname.includes('report'))data={total:0,downtimeMinutes:0,averageCloseHours:0,overdueActions:0};
  res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({code:'OK',data,requestId:'local-fixture'}));return;
 }
 const file=path.resolve(root,'.'+decodeURIComponent(url.pathname==='/'?'/index.html':url.pathname));
 if(!file.startsWith(root+path.sep)||!fs.existsSync(file)){res.writeHead(404);res.end();return}
 res.setHeader('Content-Type',file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html');fs.createReadStream(file).pipe(res);
}).listen(4179,'127.0.0.1',()=>console.log('Local fixture: http://127.0.0.1:4179'));
