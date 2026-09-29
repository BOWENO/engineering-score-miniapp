import { setTarget } from '../../utils/navigation'
import { startScoreWatch } from '../../utils/scoreWatch'
import { pollScoreChanges } from '../../utils/scoreChanges'
import { request } from '../../utils/request'
import { refreshSubscriptionHarvesting } from '../../utils/subscription'

interface CurrentUser { displayName: string; employeeNo: string; roles: string[]; passwordChangeRequired: boolean }
interface Score { total: number; averageScore: number; base: number; bonus: number; penalty: number; shiftCount: number; rank: number; finalGrade?: string }
interface Pending { unreadMessages: number; unacknowledgedSchedules: number; ownIncidentStatements: number; returnedCases: number; performanceReviews: number; appeals: number; incidentReviews: number; overdueIncidents: number; ownCorrectiveActions:number; investigations:number; pendingAcceptances:number; overdueCorrectiveActions:number }
interface Schedule { id: string; businessDate: string; displayDate?: string; shiftCode: string; lineName: string; stationName: string; acknowledgedAt?: string }
interface Workbench { primaryRole: string; administrator: boolean; score?: Score; pending: Pending; currentSchedules: Schedule[]; hotEquipment: Array<{ id: string; code: string; name: string; incidents: number }> }
interface SubscriptionConfig { enabled:boolean;templateIds:string[];eligible:boolean;permissionStatus:string;credits:number }
interface PublicDeduction { id:string;displayName:string;teamName:string;roleName:string;description:string;score:number;category:string;effectiveTime:string;occurredTime:string;underAppeal:boolean }
interface DeductionBoard { date:string;today:string;scopeName:string;total:number;people:number;totalScore:number;page:number;hasMore:boolean;items:PublicDeduction[] }

const roleNames: Record<string, string> = {
  TECHNICIAN: '技术员', ASSISTANT_ENGINEER: '助理工程师', SUPERVISOR: '主管', DEPARTMENT_MANAGER: '部长'
}

Page({
  stopScoreWatch:null as (()=>void)|null,
  visible:false,
  onHide(){this.visible=false;this.stopScoreWatch?.();this.stopScoreWatch=null},
  onUnload(){this.onHide()},
  data: {liveConnected:false,
    name: '', employeeNo: '', roleName: '', greeting: '', scored: false, management: false,
    score: null as Score | null, pending: {} as Pending, schedules: [] as Schedule[],
    hotEquipment: [] as Workbench['hotEquipment'], subscriptionPrompt:false,templateIds:[] as string[],loading: false, error: ''
    ,publicity:null as DeductionBoard|null,publicityLoading:false,publicityError:'',publicityDate:'',publicityToday:''
  },
  publicitySequence:0,
  onShow() {
    if (!getApp<IAppOption>().globalData.accessToken) {
      wx.reLaunch({ url: '/pages/profile/index' }); return
    }
    this.visible=true;this.stopScoreWatch?.()
    this.stopScoreWatch=startScoreWatch(pollScoreChanges,async()=>{await Promise.all([this.load(),this.loadPublicity()]);if(this.data.error||this.data.publicityError)throw new Error('数据更新失败')},connected=>this.setData({liveConnected:connected}))
  },
  async onPullDownRefresh(){try{await Promise.all([this.load(),this.loadPublicity()])}finally{wx.stopPullDownRefresh()}},
  async loadPublicity(more=false){
    if(more&&(this.data.publicityLoading||!this.data.publicity?.hasMore))return;
    const sequence=++this.publicitySequence;
    const page=more?(this.data.publicity?.page||0)+1:0;
    const date=more?this.data.publicity?.date:this.data.publicityDate;
    this.setData({publicityLoading:true,publicityError:''});
    try{
      const result=await request<DeductionBoard>(`/publicity/daily-deductions?size=3&page=${page}${date?'&date='+date:''}`);
      if(sequence!==this.publicitySequence)return;
      this.setData({publicity:{...result,items:more?[...(this.data.publicity?.items||[]),...result.items]:result.items},publicityToday:result.today});
    }catch(reason){if(sequence===this.publicitySequence)this.setData({publicityError:reason instanceof Error?reason.message:'公示加载失败，请重试'})}
    finally{if(sequence===this.publicitySequence)this.setData({publicityLoading:false})}
  },
  refreshPublicity(){this.loadPublicity()},
  morePublicity(){this.loadPublicity(true)},
  publicityDateChange(e:WechatMiniprogram.PickerChange){this.setData({publicityDate:e.detail.value,publicity:null});this.loadPublicity()},
  showPublicDeduction(e:WechatMiniprogram.BaseEvent){
    const item=this.data.publicity?.items.find(row=>row.id===e.currentTarget.dataset.id);if(!item)return;
    wx.showModal({title:`${item.displayName} · 扣 ${item.score} 分`,content:`${item.roleName} · ${item.teamName}\n${item.category}\n${item.description}\n\n事项发生：${item.occurredTime}\n生效时间：${this.data.publicity?.date} ${item.effectiveTime}${item.underAppeal?'\n当前正在申诉处理中':''}`,showCancel:false,confirmText:'我知道了'});
  },
  async load() {
    this.setData({ loading: true, error: '' })
    try {
      const [me, workbench, subscription] = await Promise.all([request<CurrentUser>('/me'), request<Workbench>('/workbench'),request<SubscriptionConfig>('/notifications/subscription-config').catch(()=>({enabled:false,templateIds:[],eligible:false,permissionStatus:'NOT_CONFIGURED',credits:0}))])
      if (me.passwordChangeRequired) {
        wx.showModal({ title: '请先修改密码', content: '首次登录必须修改默认密码后才能使用业务功能。', showCancel: false,
          success: () => wx.switchTab({ url: '/pages/profile/index' }) })
        return
      }
      const hour = new Date().getHours()
      this.setData({
        name: me.displayName, employeeNo: me.employeeNo, roleName: roleNames[workbench.primaryRole] || workbench.primaryRole,
        greeting: hour < 11 ? '早上好' : hour < 14 ? '中午好' : hour < 18 ? '下午好' : '晚上好',
        scored: workbench.primaryRole === 'TECHNICIAN' || workbench.primaryRole === 'ASSISTANT_ENGINEER',
        management: workbench.primaryRole === 'SUPERVISOR' || workbench.primaryRole === 'DEPARTMENT_MANAGER',
        score: workbench.score || null, pending: workbench.pending, schedules: workbench.currentSchedules.map(item => ({ ...item, displayDate: item.businessDate.slice(5) })),
        hotEquipment: workbench.hotEquipment,
        subscriptionPrompt:wx.getStorageSync('login_method')!=='password'&&subscription.enabled&&subscription.eligible&&subscription.permissionStatus!=='ACCEPTED',templateIds:subscription.templateIds
      })
      refreshSubscriptionHarvesting(subscription)
      this.updateBadges(workbench.pending)
    } catch (reason) { this.setData({ error: reason instanceof Error ? reason.message : '工作台加载失败' }) }
    finally { this.setData({ loading: false }) }
  },
  updateBadges(pending: Pending) {
    const incidentCount = Number(pending.ownIncidentStatements || 0) + Number(pending.incidentReviews || 0) + Number(pending.overdueIncidents || 0) + Number(pending.ownCorrectiveActions || 0) + Number(pending.investigations || 0) + Number(pending.pendingAcceptances || 0)
    wx.removeTabBarBadge({ index: 2 })
    if (incidentCount) wx.setTabBarBadge({ index: 3, text: String(Math.min(99, incidentCount)) }); else wx.removeTabBarBadge({ index: 3 })
  },
  goScore() { wx.switchTab({ url: '/pages/score/index' }) },
  goSchedule() { wx.switchTab({ url: '/pages/schedule/index' }) },
  goIncidents() { wx.switchTab({ url: '/pages/incidents/index' }) },
  goIncidentReviews() { setTarget('incidents', 'review'); this.goIncidents() },
  goOverdueIncidents() { setTarget('incidents', 'overdue'); this.goIncidents() },
  goMessages() { wx.navigateTo({ url: '/pages/messages/index' }) },
  async enableWechatNotification(){if(wx.getStorageSync('login_method')==='password'||!this.data.templateIds.length)return;try{const result=await new Promise<Record<string,string>>((resolve,reject)=>(wx as any).requestSubscribeMessage({tmplIds:this.data.templateIds,success:resolve,fail:reject}));await request('/notifications/subscriptions','POST',{decisions:result,acceptedTemplateIds:this.data.templateIds.filter(id=>result[id]==='accept')});const accepted=this.data.templateIds.some(id=>result[id]==='accept');this.setData({subscriptionPrompt:!accepted});if(accepted)refreshSubscriptionHarvesting();wx.showToast({title:accepted?'微信提醒已开启':'未开启微信提醒',icon:accepted?'success':'none'})}catch(reason){wx.showToast({title:reason instanceof Error?reason.message:'开启失败',icon:'none'})}},
  goBonus() { wx.navigateTo({ url: '/pages/performance-form/index?type=BONUS' }) },
  goDeduction() { wx.navigateTo({ url: '/pages/performance-form/index?type=DEDUCTION' }) },
  goReviews() { setTarget('score');wx.switchTab({ url: '/pages/score/index' }) },
})
