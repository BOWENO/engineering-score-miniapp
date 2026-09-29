import { request } from './request'

interface SubscriptionConfig { enabled:boolean;templateIds:string[];eligible:boolean;permissionStatus:string }
interface DailyCount { date:string;count:number }

const STORAGE_KEY='subscription_credit_daily'
const DAILY_LIMIT=3

export async function refreshSubscriptionHarvesting(config?:SubscriptionConfig):Promise<void>{
  const app=getApp<IAppOption>()
  if(wx.getStorageSync('login_method')==='password'){disable(app);return}
  try{
    const current=config||await request<SubscriptionConfig>('/notifications/subscription-config')
    if(!current.enabled||!current.eligible||!['ACCEPTED','EXHAUSTED'].includes(current.permissionStatus)||!current.templateIds.length){disable(app);return}
    const setting=await new Promise<any>((resolve,reject)=>(wx as any).getSetting({withSubscriptions:true,success:resolve,fail:reject}))
    const items=setting?.subscriptionsSetting?.itemSettings||{}
    const remembered=current.templateIds.filter(id=>items[id]==='accept')
    app.globalData.subscriptionTemplateIds=remembered
    app.globalData.subscriptionRemembered=remembered.length>0
  }catch(_){disable(app)}
}

export async function collectSubscriptionCredit():Promise<void>{
  const app=getApp<IAppOption>(),ids=app.globalData.subscriptionTemplateIds||[]
  if(wx.getStorageSync('login_method')==='password'||!app.globalData.subscriptionRemembered||!ids.length||daily().count>=DAILY_LIMIT)return
  try{
    const result=await new Promise<Record<string,string>>((resolve,reject)=>(wx as any).requestSubscribeMessage({tmplIds:ids,success:resolve,fail:reject}))
    const accepted=ids.filter(id=>result[id]==='accept')
    if(accepted.length){increaseDaily();await request('/notifications/subscriptions','POST',{decisions:result,acceptedTemplateIds:accepted})}
    else if(ids.some(id=>result[id]==='reject'||result[id]==='ban')){disable(app);await request('/notifications/subscriptions','POST',{decisions:result,acceptedTemplateIds:[]})}
  }catch(_){/* 通知额度采集不得阻断业务操作 */}
}

function disable(app:IAppOption){app.globalData.subscriptionTemplateIds=[];app.globalData.subscriptionRemembered=false}
function day(){const d=new Date();return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`}
function daily():DailyCount{const value=wx.getStorageSync(STORAGE_KEY) as DailyCount|undefined;return value?.date===day()?value:{date:day(),count:0}}
function increaseDaily(){const value=daily();wx.setStorageSync(STORAGE_KEY,{date:value.date,count:value.count+1})}
