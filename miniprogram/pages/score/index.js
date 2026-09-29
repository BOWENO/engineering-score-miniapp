"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const navigation_1 = require("../../utils/navigation");
const scoreWatch_1 = require("../../utils/scoreWatch");
const scoreChanges_1 = require("../../utils/scoreChanges");
const display_1 = require("../../utils/display");
const request_1 = require("../../utils/request");
const typeText = { BONUS: '加分申请', BASE_DEDUCTION: '基础扣分', SPECIAL_DEDUCTION: '特殊扣分', ADMONITION: '劝诫记录' };
const statusText = { PENDING_ASSISTANT: '待助理工程师初审', PENDING_SUPERVISOR: '待主管审核', RETURNED: '已退回', REJECTED: '已否决', EFFECTIVE: '已生效', REVERSED: '申诉撤销' };
Page({
    stopScoreWatch: null,
    visible: false, showSequence: 0, loadSequence: 0,
    onHide() { this.visible = false; this.showSequence++; this.stopScoreWatch?.(); this.stopScoreWatch = null; },
    onUnload() { this.onHide(); },
    data: { targetRecord: null, targetNotice: '', highlightId: '', liveConnected: false, period: currentPeriod(), me: null, scored: false, supervisor: false, manager: false, assistant: false, active: 'mine', gradeKeys: ['S', 'A', 'B1', 'B2', 'B3', 'C', 'D'], personal: null, people: [], gradeCounts: {}, cases: [], pending: [], appeals: [], dMine: [], dNominations: [], dAppeals: [], settlements: [], corrections: [], selected: null, reviewScore: '', reviewRule: '', reviewRules: [], reviewRuleIndex: -1, reviewComment: '', loading: false, error: '' },
    async onShow() { const shown = ++this.showSequence; this.visible = true; this.stopScoreWatch?.(); if (!getApp().globalData.accessToken)
        return; const target = (0, navigation_1.takeTarget)('score'); if (target?.date)
        this.setData({ period: target.date.slice(0, 7) }); await this.initialize(); if (target) {
        if (!target.id && (this.data.assistant || this.data.supervisor || this.data.manager))
            this.setData({ active: 'review' });
        else {
            const pending = this.data.pending.find(i => i.id === target.id), mine = this.data.cases.find(i => i.id === target.id);
            this.setData({ highlightId: target.id, active: pending ? 'review' : mine ? 'mine' : this.data.active, targetNotice: pending || mine ? '已定位消息对应事项' : '该事项已处理或不在当前可见列表，请查看相应月份明细' });
            if (pending)
                await this.selectReview({ currentTarget: { dataset: { id: target.id } }, target: { dataset: {} } });
            if (!pending && !mine && (this.data.assistant || this.data.supervisor || this.data.manager)) {
                try {
                    const records = await (0, request_1.request)('/performance-cases/records?period=' + this.data.period);
                    const record = records.find(r => r.id === target.id);
                    if (record)
                        this.setData({ targetRecord: this.decorate([record])[0], targetNotice: '已定位消息对应事项' });
                }
                catch { }
            }
            if (pending || mine)
                wx.pageScrollTo?.({ selector: '#case-' + target.id, duration: 200 });
        }
    } if (!this.visible || shown !== this.showSequence)
        return; this.stopScoreWatch = (0, scoreWatch_1.startScoreWatch)(scoreChanges_1.pollScoreChanges, async () => { await this.load(); if (this.data.error)
        throw new Error(this.data.error); }, connected => this.setData({ liveConnected: connected })); },
    async initialize() { this.setData({ loading: true, error: '' }); try {
        const me = await (0, request_1.request)('/me');
        const scored = me.roles.some(r => r === 'TECHNICIAN' || r === 'ASSISTANT_ENGINEER'), supervisor = me.roles.includes('SUPERVISOR'), manager = me.roles.includes('DEPARTMENT_MANAGER'), assistant = me.roles.includes('ASSISTANT_ENGINEER');
        this.setData({ me, scored, supervisor, manager, assistant, active: scored ? 'mine' : 'overview' });
        await this.load();
    }
    catch (reason) {
        this.setData({ error: reason instanceof Error ? reason.message : '绩效数据加载失败' });
    }
    finally {
        this.setData({ loading: false });
    } },
    async load() { const sequence = ++this.loadSequence, month = this.data.period; this.setData({ loading: true, error: '' }); try {
        const tasks = [];
        if (this.data.scored)
            tasks.push((0, request_1.request)(`/performance/me?period=${this.data.period}`), (0, request_1.request)('/performance-cases/mine'), (0, request_1.request)('/d-grades/mine'));
        if (this.data.supervisor || this.data.manager)
            tasks.push((0, request_1.request)(`/performance/overview?period=${this.data.period}`));
        if (this.data.assistant || this.data.supervisor || this.data.manager)
            tasks.push((0, request_1.request)('/performance-cases/pending'));
        if (this.data.supervisor || this.data.manager)
            tasks.push((0, request_1.request)('/performance-cases/appeals/pending'), (0, request_1.request)(`/settlements?period=${this.data.period}`), (0, request_1.request)(`/d-grades?period=${this.data.period}`), (0, request_1.request)('/d-grades/appeals/pending'), (0, request_1.request)(`/settlement-corrections?period=${this.data.period}`));
        const values = await Promise.all(tasks);
        if (sequence !== this.loadSequence || month !== this.data.period)
            return;
        let n = 0;
        if (this.data.scored) {
            this.setData({ personal: values[n++], cases: this.decorate(values[n++]), dMine: values[n++] });
        }
        if (this.data.supervisor || this.data.manager) {
            const overview = values[n++];
            this.setData({ people: overview.people, gradeCounts: overview.gradeCounts });
        }
        if (this.data.assistant || this.data.supervisor || this.data.manager)
            this.setData({ pending: this.decorate(values[n++]) });
        if (this.data.supervisor || this.data.manager)
            this.setData({ appeals: values[n++], settlements: values[n++], dNominations: values[n++], dAppeals: values[n++], corrections: values[n++].map((item) => ({ ...item, statusText: { "PENDING": "待表决", "ACCEPTED": "更正已通过", "REJECTED": "更正已否决", "SUPERSEDED": "批次已替代，请重新发起" }[item.status] || item.status })) });
    }
    catch (reason) {
        this.setData({ error: reason instanceof Error ? reason.message : '绩效数据加载失败' });
    }
    finally {
        this.setData({ loading: false });
    } },
    decorate(items) { return items.map(item => ({ ...item, typeText: typeText[item.caseType] || item.caseType, statusText: statusText[item.status] || item.status })); },
    switchView(e) { this.setData({ active: e.currentTarget.dataset.key, selected: null }); }, previousMonth() { this.changeMonth(-1); }, nextMonth() { this.changeMonth(1); }, changeMonth(offset) { const [y, m] = this.data.period.split('-').map(Number), d = new Date(y, m - 1 + offset, 1); this.setData({ period: formatPeriod(d) }); this.load(); },
    goBonus() { wx.navigateTo({ url: '/pages/performance-form/index?type=BONUS' }); }, goDeduction() { wx.navigateTo({ url: '/pages/performance-form/index?type=DEDUCTION' }); }, goAdmonition() { wx.navigateTo({ url: '/pages/performance-form/index?type=ADMONITION' }); },
    async selectReview(e) { const item = this.data.pending.find(x => x.id === e.currentTarget.dataset.id) || null; this.setData({ selected: item, reviewScore: item?.suggestedScore ? String(item.suggestedScore) : '', reviewRule: item?.ruleCode || '', reviewComment: '', reviewRules: [], reviewRuleIndex: -1 }); if (item && this.data.supervisor) {
        try {
            const rules = await (0, request_1.request)('/score-rules?type=' + (item.caseType === 'BONUS' ? 'BONUS' : 'PENALTY') + '&date=' + (0, display_1.chinaTime)(item.occurredAt).slice(0, 10));
            if (this.data.selected?.id !== item.id)
                return;
            const index = rules.findIndex(r => r.code === item.ruleCode);
            this.setData({ reviewRules: rules, reviewRuleIndex: index, reviewRule: index >= 0 ? rules[index].code : '' });
        }
        catch (reason) {
            this.setData({ error: reason instanceof Error ? reason.message : '规则加载失败' });
        }
    } }, selectRule(e) { const index = Number(e.detail.value); this.setData({ reviewRuleIndex: index, reviewRule: this.data.reviewRules[index]?.code || '' }); }, inputScore(e) { this.setData({ reviewScore: e.detail.value.replace(/\D/g, '') }); }, inputRule(e) { this.setData({ reviewRule: e.detail.value }); }, inputComment(e) { this.setData({ reviewComment: e.detail.value }); },
    async decide(e) { if (!this.data.selected)
        return; const decision = e.currentTarget.dataset.decision; if ((decision === 'RETURN' || decision === 'REJECT') && !this.data.reviewComment.trim()) {
        this.setData({ error: '退回或否决时必须填写审核意见' });
        return;
    } if (decision === 'APPROVE' && this.data.supervisor && (!this.data.reviewScore || !this.data.reviewRule.trim())) {
        this.setData({ error: '主管审核通过时必须填写确认分值和规则编号' });
        return;
    } try {
        await (0, request_1.request)(`/performance-cases/${this.data.selected.id}/actions`, 'POST', { version: this.data.selected.version, decision, score: this.data.supervisor ? Number(this.data.reviewScore) || null : null, ruleCode: this.data.supervisor ? this.data.reviewRule.trim() || null : null, comment: this.data.reviewComment.trim() || null });
        this.setData({ selected: null });
        await this.load();
        wx.showToast({ title: '处理完成', icon: 'success' });
    }
    catch (reason) {
        this.setData({ error: reason instanceof Error ? reason.message : '审核失败' });
    } },
    appeal(e) { const id = e.currentTarget.dataset.id; wx.showModal({ title: '提交扣分申诉', editable: true, placeholderText: '请填写申诉理由，每条扣分仅可申诉一次', success: async (result) => { if (!result.confirm || !result.content?.trim())
            return; try {
            await (0, request_1.request)(`/performance-cases/${id}/appeals`, 'POST', { description: result.content });
            await this.load();
            wx.showToast({ title: '申诉已提交', icon: 'success' });
        }
        catch (reason) {
            wx.showToast({ title: reason instanceof Error ? reason.message : '申诉失败', icon: 'none' });
        } } }); },
    resubmit(e) { const item = this.data.cases.find(x => x.id === e.currentTarget.dataset.id); if (!item)
        return; wx.showModal({ title: '修改并重新提交', editable: true, content: item.description, placeholderText: '修改申请说明', success: async (result) => { if (!result.confirm || !result.content?.trim())
            return; try {
            await (0, request_1.request)(`/performance-cases/${item.id}/resubmit`, 'POST', { version: item.version, description: result.content, attachmentIds: item.attachmentIds });
            await this.load();
            wx.showToast({ title: '已重新提交', icon: 'success' });
        }
        catch (reason) {
            wx.showToast({ title: reason instanceof Error ? reason.message : '重新提交失败', icon: 'none' });
        } } }); },
    async vote(e) { const { id, decision } = e.currentTarget.dataset; try {
        await (0, request_1.request)(`/performance-cases/appeals/${id}/votes`, 'POST', { decision, comment: null });
        await this.load();
        wx.showToast({ title: '表决完成', icon: 'success' });
    }
    catch (reason) {
        wx.showToast({ title: reason instanceof Error ? reason.message : '表决失败', icon: 'none' });
    } },
    async createSettlement() { try {
        await (0, request_1.request)('/settlements/preview', 'POST', { period: this.data.period, orgUnitId: null, version: `MANUAL-${this.data.period}` });
        await this.load();
        wx.showToast({ title: '结算已生成', icon: 'success' });
    }
    catch (reason) {
        this.setData({ error: reason instanceof Error ? reason.message : '结算生成失败' });
    } },
    async confirmSettlement(e) { try {
        await (0, request_1.request)(`/settlements/${e.currentTarget.dataset.id}/confirmations`, 'POST', { comment: null });
        await this.load();
        wx.showToast({ title: '确认完成', icon: 'success' });
    }
    catch (reason) {
        this.setData({ error: reason instanceof Error ? reason.message : '结算确认失败' });
    } },
    requestCorrection(e) { const { run, user, name } = e.currentTarget.dataset; wx.showModal({ title: `更正 ${name} 的封存积分`, editable: true, placeholderText: '输入调整分值，例如 2 或 -3', success: first => { if (!first.confirm || !first.content?.trim())
            return; const adjustmentScore = Number(first.content); if (!Number.isInteger(adjustmentScore) || adjustmentScore === 0) {
            wx.showToast({ title: '请输入非零整数分值', icon: 'none' });
            return;
        } wx.showModal({ title: '填写更正原因', editable: true, placeholderText: '说明封存后更正的事实依据', success: async (second) => { if (!second.confirm || !second.content?.trim())
                return; try {
                await (0, request_1.request)('/settlement-corrections', 'POST', { runId: run, userId: user, adjustmentScore, reason: second.content.trim() });
                await this.load();
                wx.showToast({ title: '更正已发起', icon: 'success' });
            }
            catch (reason) {
                wx.showToast({ title: reason instanceof Error ? reason.message : '更正发起失败', icon: 'none' });
            } } }); } }); },
    async voteCorrection(e) { const { id, decision } = e.currentTarget.dataset; try {
        await (0, request_1.request)(`/settlement-corrections/${id}/votes`, 'POST', { decision, comment: null });
        await this.load();
        wx.showToast({ title: '更正表决完成', icon: 'success' });
    }
    catch (reason) {
        wx.showToast({ title: reason instanceof Error ? reason.message : '更正表决失败', icon: 'none' });
    } },
    voteBoundary(e) { const { run, user, grade } = e.currentTarget.dataset; wx.showModal({ title: '同分边界表决', editable: true, placeholderText: `输入最终等级（当前建议 ${grade}）`, success: first => { if (!first.confirm || !first.content?.trim())
            return; const selectedGrade = first.content.trim().toUpperCase(); wx.showModal({ title: `确认等级 ${selectedGrade}`, editable: true, placeholderText: '填写表决理由', success: async (second) => { if (!second.confirm || !second.content?.trim())
                return; try {
                await (0, request_1.request)(`/settlements/${run}/boundary-decisions`, 'POST', { userId: user, grade: selectedGrade, reason: second.content });
                await this.load();
                wx.showToast({ title: '表决已提交', icon: 'success' });
            }
            catch (reason) {
                wx.showToast({ title: reason instanceof Error ? reason.message : '表决失败', icon: 'none' });
            } } }); } }); },
    nominateD(e) { const userId = e.currentTarget.dataset.id; wx.showModal({ title: 'D级提名', editable: true, placeholderText: '填写D级原因编号', success: first => { if (!first.confirm || !first.content?.trim())
            return; wx.showModal({ title: 'D级事实说明', editable: true, placeholderText: '填写可核实的详细原因', success: async (second) => { if (!second.confirm || !second.content?.trim())
                return; try {
                await (0, request_1.request)('/d-grades', 'POST', { period: this.data.period, userId, reasonCode: first.content, description: second.content, evidenceAttachmentId: null });
                await this.load();
                wx.showToast({ title: '提名已发起', icon: 'success' });
            }
            catch (reason) {
                wx.showToast({ title: reason instanceof Error ? reason.message : '提名失败', icon: 'none' });
            } } }); } }); },
    async confirmD(e) { const { id, decision } = e.currentTarget.dataset; try {
        await (0, request_1.request)(`/d-grades/${id}/confirmations`, 'POST', { decision, comment: null });
        await this.load();
        wx.showToast({ title: 'D级表决完成', icon: 'success' });
    }
    catch (reason) {
        wx.showToast({ title: reason instanceof Error ? reason.message : 'D级表决失败', icon: 'none' });
    } },
    appealD(e) { const id = e.currentTarget.dataset.id; wx.showModal({ title: 'D级申诉', editable: true, placeholderText: '填写申诉理由，仅可申诉一次', success: async (result) => { if (!result.confirm || !result.content?.trim())
            return; try {
            await (0, request_1.request)(`/d-grades/${id}/appeals`, 'POST', { description: result.content });
            await this.load();
            wx.showToast({ title: '申诉已提交', icon: 'success' });
        }
        catch (reason) {
            wx.showToast({ title: reason instanceof Error ? reason.message : '申诉失败', icon: 'none' });
        } } }); },
    async voteD(e) { const { id, decision } = e.currentTarget.dataset; try {
        await (0, request_1.request)(`/d-grades/appeals/${id}/votes`, 'POST', { decision, comment: null });
        await this.load();
        wx.showToast({ title: 'D级申诉表决完成', icon: 'success' });
    }
    catch (reason) {
        wx.showToast({ title: reason instanceof Error ? reason.message : '表决失败', icon: 'none' });
    } }
});
function currentPeriod() { return formatPeriod(new Date()); }
function formatPeriod(d) { return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`; }
