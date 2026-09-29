import { takeTarget } from '../../utils/navigation'
import { request } from '../../utils/request'
import { collectSubscriptionCredit, refreshSubscriptionHarvesting } from '../../utils/subscription'

interface Me { userId: string; roles: string[] }
interface Person { id: string; employeeNo: string; name: string; teamName: string; roles: string[] }
interface Line { id: string; name: string; teamId: string }
interface Station { id: string; name: string; lineId: string }
interface Catalog { lines: Line[]; stations: Station[]; shifts: Array<{ code: string; name: string }> }
interface Assignment { id: string; userId: string; displayName: string; businessDate: string; shiftCode: string; lineName: string; stationName: string; status: string; acknowledgedAt?: string; version: number }

Page({
  loadVersion: 0,
  targetId:'',
  data: {
    targetNotice:'',highlightId:'',from: dateOffset(-7), to: dateOffset(31), items: [] as Assignment[], me: null as Me | null,
    canEdit: false, showEditor: false, people: [] as Person[], lines: [] as Line[], stations: [] as Station[], filteredStations: [] as Station[],
    personIndex: 0, lineIndex: 0, stationIndex: 0, shiftIndex: 0, shifts: [{ code: 'DAY', name: '白班' }, { code: 'NIGHT', name: '夜班' }],
    editFrom: dateOffset(1), editTo: dateOffset(1), publish: true, loading: false, saving: false, catalogReady: false, error: ''
  },
  onShow() { if (getApp<IAppOption>().globalData.accessToken) { refreshSubscriptionHarvesting();const target=takeTarget('schedule');if(target){this.targetId=target.id;if(target.date)this.setData({from:target.date,to:target.date})}this.load() } },
  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()) },
  async load() {
    const version = ++this.loadVersion
    this.setData({ loading: true, error: '' })
    try {
      const [me, items, catalog, people] = await Promise.all([
        request<Me>('/me'), request<Assignment[]>(`/schedules?from=${this.data.from}&to=${this.data.to}`),
        request<Catalog>('/catalog'), request<Person[]>('/directory/people')
      ])
      if (version !== this.loadVersion) return
      const canEdit = me.roles.some(role => role === 'ASSISTANT_ENGINEER' || role === 'SUPERVISOR')
      const eligible = people.filter(person => person.roles.some(role => role === 'TECHNICIAN' || role === 'ASSISTANT_ENGINEER'))
      const lines = catalog.lines
      const stations = catalog.stations
      // Preserve identities, not positions: the catalog may have been reordered or reduced.
      const selectedLine = this.data.lines[this.data.lineIndex]
      const selectedStation = this.data.filteredStations[this.data.stationIndex]
      const selectedPerson = this.data.people[this.data.personIndex]
      const selectedShift = this.data.shifts[this.data.shiftIndex]
      const lineIndex = Math.max(0, lines.findIndex(item => item.id === selectedLine?.id))
      const filteredStations = stations.filter(item => item.lineId === lines[lineIndex]?.id)
      const stationIndex = Math.max(0, filteredStations.findIndex(item => item.id === selectedStation?.id))
      const personIndex = Math.max(0, eligible.findIndex(item => item.id === selectedPerson?.id))
      const shifts = catalog.shifts
      const shiftIndex = Math.max(0, shifts.findIndex(item => item.code === selectedShift?.code))
      this.setData({ me, canEdit, items, people: eligible, lines, stations, shifts, lineIndex, filteredStations, stationIndex, personIndex, shiftIndex, catalogReady: true });if(this.targetId){const id=this.targetId;this.targetId='';const found=items.some(i=>i.id===id);this.setData({highlightId:id,targetNotice:found?'已定位消息对应排班':'该排班已取消或不在当前可见范围'});if(found)wx.pageScrollTo?.({selector:'#schedule-'+id,duration:200})}
    } catch (reason) { if (version === this.loadVersion) this.setData({ catalogReady: false, error: reason instanceof Error ? reason.message : '排班加载失败' }) }
    finally { if (version === this.loadVersion) this.setData({ loading: false }) }
  },
  fromChange(e: any) { this.setData({ from: e.detail.value }); this.load() },
  toChange(e: any) { this.setData({ to: e.detail.value }); this.load() },
  editFromChange(e: any) { this.setData({ editFrom: e.detail.value, editTo: e.detail.value }) },
  editToChange(e: any) { this.setData({ editTo: e.detail.value }) },
  personChange(e: any) { this.setData({ personIndex: Number(e.detail.value) }) },
  lineChange(e: any) { this.refreshStations(Number(e.detail.value)) },
  stationChange(e: any) { this.setData({ stationIndex: Number(e.detail.value) }) },
  shiftChange(e: any) { this.setData({ shiftIndex: Number(e.detail.value) }) },
  publishChange(e: any) { this.setData({ publish: e.detail.value }) },
  refreshStations(lineIndex: number) {
    if (!Number.isInteger(lineIndex) || !this.data.lines[lineIndex]) lineIndex = 0
    const line = this.data.lines[lineIndex]
    this.setData({ lineIndex, filteredStations: line ? this.data.stations.filter(item => item.lineId === line.id) : [], stationIndex: 0 })
  },
  toggleEditor() { this.setData({ showEditor: !this.data.showEditor, error: '' }) },
  async save() {
    if (this.data.saving || this.data.loading) return
    if (!this.data.canEdit || !this.data.catalogReady) { this.setData({ error: '请刷新页面并确认排班权限后重试' }); return }
    const person = this.data.people[this.data.personIndex], line = this.data.lines[this.data.lineIndex], station = this.data.filteredStations[this.data.stationIndex], shift = this.data.shifts[this.data.shiftIndex]
    if (!person || !line || !station || !shift) { this.setData({ error: '请选择人员、线体、站位和班次' }); return }
    if (station.lineId !== line.id) { this.refreshStations(this.data.lineIndex); this.setData({ error: '站位与线体不匹配，已刷新站位，请重新选择后保存' }); return }
    if (this.data.editFrom > this.data.editTo) { this.setData({ error: '结束日期不能早于开始日期' }); return }
    const payload = { from: this.data.editFrom, to: this.data.editTo, shiftCode: shift.code, userIds: [person.id], lineId: line.id, stationIds: [station.id], publish: this.data.publish, reason: null }
    this.setData({ saving: true, error: '' })
    try {
      await collectSubscriptionCredit()
      await request('/schedules/batch', 'POST', payload)
      this.setData({ showEditor: false }); await this.load(); wx.showToast({ title: payload.publish ? '排班已发布' : '草稿已保存', icon: 'success' })
    } catch (reason) { this.setData({ error: reason instanceof Error ? reason.message : '排班保存失败' }) }
    finally { this.setData({ saving: false }) }
  },
  async cancel(e: WechatMiniprogram.BaseEvent) {
    const { id, version } = e.currentTarget.dataset as { id: string; version: number }
    wx.showModal({ title: '取消排班', editable: true, placeholderText: '班次已开始时必须填写纠正原因', success: async result => {
      if (!result.confirm) return
      try { await collectSubscriptionCredit(); await request(`/schedules/${id}/cancel`, 'POST', { version, reason: result.content || null }); await this.load(); wx.showToast({ title: '已取消', icon: 'success' }) }
      catch (reason) { wx.showToast({ title: reason instanceof Error ? reason.message : '取消失败', icon: 'none' }) }
    } })
  }
})

function dateOffset(days: number) { const d = new Date(); d.setDate(d.getDate() + days); return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}` }
