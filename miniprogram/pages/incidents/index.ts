import { decorate } from '../../utils/display'
import { takeTarget } from '../../utils/navigation'
import { request } from '../../utils/request'
import { collectSubscriptionCredit, refreshSubscriptionHarvesting } from '../../utils/subscription'

interface Me { roles: string[] }
interface Line { id: string; name: string }
interface Station { id: string; lineId: string; name: string }
interface Equipment { id: string; code: string; name: string; lineId: string; stationId: string }
interface Incident { id: string; incidentNo: string; occurredAt: string; businessDate: string; lineName: string; stationName: string; status: string; devices: Array<{name:string;code:string}>; statements: Array<{responsibleName:string;status:string}>; devicesText?:string; responsibleText?:string }

Page({
  loadSequence: 0,
  data: {
    overdueOnly: false, overdueCount: 0,
    tab: 'mine', tabs: [{key:'mine',name:'待我处理'},{key:'review',name:'待审核'},{key:'archive',name:'异常档案'}],
    me: null as Me | null, canCreate: false, canReview: false, items: [] as Incident[], loading: false, error: '', showCreate: false,
    lines: [] as Line[], stations: [] as Station[], equipment: [] as Equipment[], filteredStations: [] as Station[], filteredEquipment: [] as Equipment[],
    lineIndex: 0, stationIndex: 0, selectedEquipmentIds: [] as string[], occurredDate: today(), occurredTime: nowTime(), saving: false
  },
  onShow() { if (getApp<IAppOption>().globalData.accessToken) { const target=takeTarget('incidents'); if(target)this.setData({tab:'review',overdueOnly:target.id==='overdue',showCreate:false}); refreshSubscriptionHarvesting(); this.initialize() } },
  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()) },
  async initialize() {
    try {
      await this.load()
      const previousLineId=this.data.lines[this.data.lineIndex]?.id
      const [me, catalog, equipment] = await Promise.all([request<Me>('/me'), request<{lines:Line[];stations:Station[]}>('/catalog'), request<Equipment[]>('/equipment')])
      const canCreate = me.roles.some(role => role === 'ASSISTANT_ENGINEER' || role === 'SUPERVISOR')
      this.setData({ me, canCreate, canReview: me.roles.includes('SUPERVISOR'), lines: catalog.lines, stations: catalog.stations, equipment })
      const lineIndex=Math.max(0,catalog.lines.findIndex(line=>line.id===previousLineId))
      this.setData({lineIndex}); this.refreshStations(lineIndex)
    } catch (reason) { this.setData({ error: reason instanceof Error ? reason.message : '异常信息加载失败' }) }
  },
  async load() {
    const sequence=++this.loadSequence, overdueOnly=this.data.overdueOnly
    this.setData({ loading: true, error: '', items: [] })
    try {
      const path = this.data.tab === 'mine' ? '/incidents/mine/pending' : this.data.tab === 'review' ? '/incidents/review-queue' : '/incidents/archive?limit=500'
      const result = await request<Incident[]>(path)
      if(sequence!==this.loadSequence)return
      const items=overdueOnly?result.filter(item=>item.statements.some(s=>s.status==='OVERDUE')):result
      this.setData({overdueCount:items.reduce((count,item)=>count+item.statements.filter(s=>s.status==='OVERDUE').length,0)})
      this.setData({ items: items.map(item => ({...decorate(item), devicesText:item.devices.map(d=>d.name).join('、'), responsibleText:item.statements.map(s=>s.responsibleName).join('、')})) })
    } catch (reason) { if(sequence===this.loadSequence)this.setData({ error: reason instanceof Error ? reason.message : '异常信息加载失败' }) }
    finally { if(sequence===this.loadSequence)this.setData({ loading: false }) }
  },
  switchTab(e: WechatMiniprogram.BaseEvent) { this.setData({ tab: e.currentTarget.dataset.key, overdueOnly:false, showCreate: false }); this.load() },
  toggleOverdue() { this.setData({overdueOnly:!this.data.overdueOnly});this.load() },
  toggleCreate() { this.setData({ showCreate: !this.data.showCreate, error: '' }) },
  lineChange(e:any){const index=Number(e.detail.value);this.setData({lineIndex:index});this.refreshStations(index)},
  stationChange(e:any){const index=Number(e.detail.value);this.setData({stationIndex:index,selectedEquipmentIds:[]});this.refreshEquipment(index)},
  dateChange(e:any){this.setData({occurredDate:e.detail.value})}, timeChange(e:any){this.setData({occurredTime:e.detail.value})},
  equipmentChange(e:any){this.setData({selectedEquipmentIds:e.detail.value})},
  refreshStations(lineIndex:number){const line=this.data.lines[lineIndex];const filtered=line?this.data.stations.filter(s=>s.lineId===line.id):[];this.setData({filteredStations:filtered,stationIndex:0,selectedEquipmentIds:[]});this.refreshEquipment(0,filtered)},
  refreshEquipment(stationIndex:number,stations?:Station[]){const list=stations||this.data.filteredStations;const station=list[stationIndex];this.setData({filteredEquipment:station?this.data.equipment.filter(e=>e.stationId===station.id):[]})},
  async create() {
    if(this.data.saving)return
    const line=this.data.lines[this.data.lineIndex],station=this.data.filteredStations[this.data.stationIndex]
    if(!line||!station||station.lineId!==line.id||!this.data.selectedEquipmentIds.length||this.data.selectedEquipmentIds.some(id=>!this.data.filteredEquipment.some(e=>e.id===id))){this.setData({error:'请选择线体、站位和至少一台设备'});return}
    this.setData({saving:true,error:''})
    try{await collectSubscriptionCredit();const item=await request<Incident>('/incidents','POST',{occurredAt:`${this.data.occurredDate}T${this.data.occurredTime}:00+08:00`,lineId:line.id,stationId:station.id,equipmentIds:this.data.selectedEquipmentIds});this.setData({showCreate:false,tab:'mine'});wx.navigateTo({url:`/pages/incident-detail/index?id=${item.id}`})}
    catch(reason){this.setData({error:reason instanceof Error?reason.message:'异常单创建失败'})}finally{this.setData({saving:false})}
  },
  open(e:WechatMiniprogram.BaseEvent){wx.navigateTo({url:`/pages/incident-detail/index?id=${e.currentTarget.dataset.id}`})}
})
function today(){const d=new Date();return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`}
function nowTime(){const d=new Date();return `${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`}
