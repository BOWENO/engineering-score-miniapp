"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const draft_1 = require("../../utils/draft");
const request_1 = require("../../utils/request");
const subscription_1 = require("../../utils/subscription");
Page({
    data: { personSearch: '', draftNotice: '', type: 'BONUS', title: '申请加分', people: [], targetIndex: -1, date: today(), time: nowTime(), description: '', score: '', ruleCode: '', rules: [], ruleIndex: -1, supervisor: false, deductionKinds: [{ code: 'BASE_DEDUCTION', name: '基础扣分' }, { code: 'SPECIAL_DEDUCTION', name: '特殊扣分' }], deductionKindIndex: 0, images: [], saving: false, submitted: false, rulesLoading: false, error: '' },
    rulesRequest: 0, draftKey: '', allPeople: [], restoredTarget: '',
    onHide() { this.persistDraft(); }, onUnload() { this.persistDraft(); },
    async setupDraft() { try {
        const me = await (0, request_1.request)('/me');
        this.draftKey = 'ux:performance:' + me.userId + ':' + this.data.type;
        const draft = (0, draft_1.readDraft)(this.draftKey);
        if (draft && !this.data.description) {
            this.restoredTarget = draft.targetId || '';
            this.setData({ date: draft.date, time: draft.time, description: draft.description, score: draft.score, deductionKindIndex: draft.deductionKindIndex || 0, draftNotice: '已恢复文字草稿，证明图片请重新选择' });
            this.filterPeople();
            if (this.data.type === 'DEDUCTION')
                this.loadRules();
            (0, draft_1.protectDraft)();
        }
    }
    catch {
        this.setData({ draftNotice: '草稿暂不可用，请保留页面直至提交成功' });
    } },
    persistDraft() { if (this.data.submitted || !this.data.description)
        return; (0, draft_1.saveDraft)(this.draftKey, { date: this.data.date, time: this.data.time, description: this.data.description, score: this.data.score, deductionKindIndex: this.data.deductionKindIndex, targetId: this.data.people[this.data.targetIndex]?.id || '' }); (0, draft_1.protectDraft)(); },
    discardDraft() { wx.showModal({ title: '放弃当前草稿？', success: (r) => { if (r.confirm) {
            (0, draft_1.clearDraft)(this.draftKey);
            this.restoredTarget = '';
            this.setData({ description: '', score: '', images: [], targetIndex: -1, draftNotice: '' });
        } } }); },
    searchPeople(e) { if (this.data.saving)
        return; this.setData({ personSearch: e.detail.value }); this.restoredTarget = ''; this.filterPeople(); },
    filterPeople() { const selected = this.data.people[this.data.targetIndex]?.id || this.restoredTarget; const q = this.data.personSearch.trim().toLowerCase(); const people = this.allPeople.filter(p => [p.name, p.employeeNo, p.teamName].some(v => (v || '').toLowerCase().includes(q))).map(p => ({ ...p, label: p.name + ' · ' + p.employeeNo + ' · ' + p.teamName })); this.setData({ people, targetIndex: people.findIndex(p => p.id === selected) }); this.restoredTarget = ''; },
    onLoad(options) { (0, subscription_1.refreshSubscriptionHarvesting)(); const type = options.type || 'BONUS'; this.setData({ type, title: type === 'BONUS' ? '申请加分' : type === 'ADMONITION' ? '记录劝诫' : '发起扣分' }); this.setupDraft(); if (type !== 'BONUS')
        this.loadPeople(); if (type === 'DEDUCTION')
        this.loadRules(); },
    async loadPeople() { try {
        const people = (await (0, request_1.request)('/directory/people')).filter(p => p.roles.some(role => role === 'TECHNICIAN' || role === 'ASSISTANT_ENGINEER'));
        this.allPeople = people;
        this.filterPeople();
    }
    catch (reason) {
        this.setData({ error: reason instanceof Error ? reason.message : '人员加载失败' });
    } },
    async loadRules() {
        const sequence = ++this.rulesRequest, date = this.data.date;
        this.setData({ rulesLoading: true, rules: [], ruleCode: '', ruleIndex: -1 });
        try {
            const [me, rules] = await Promise.all([(0, request_1.request)('/me'), (0, request_1.request)('/score-rules?type=PENALTY&date=' + date)]);
            if (sequence !== this.rulesRequest || date !== this.data.date)
                return;
            this.setData({ rules, supervisor: me.roles.includes('SUPERVISOR') });
        }
        catch (reason) {
            if (sequence === this.rulesRequest)
                this.setData({ error: reason instanceof Error ? reason.message : '规则加载失败' });
        }
        finally {
            if (sequence === this.rulesRequest)
                this.setData({ rulesLoading: false });
        }
    },
    selectRule(e) { if (this.data.saving || this.data.submitted)
        return; const ruleIndex = Number(e.detail.value); this.setData({ ruleIndex, ruleCode: this.data.rules[ruleIndex]?.code || '' }); },
    targetChange(e) { if (this.data.saving || this.data.submitted)
        return; this.setData({ targetIndex: Number(e.detail.value) }); }, deductionKindChange(e) { if (this.data.saving || this.data.submitted)
        return; this.setData({ deductionKindIndex: Number(e.detail.value) }); }, dateChange(e) { if (this.data.saving || this.data.submitted)
        return; this.setData({ date: e.detail.value }); if (this.data.type === 'DEDUCTION')
        this.loadRules(); }, timeChange(e) { if (this.data.saving || this.data.submitted)
        return; this.setData({ time: e.detail.value }); }, inputDescription(e) { if (this.data.saving || this.data.submitted)
        return; this.setData({ description: e.detail.value }); this.persistDraft(); }, inputScore(e) { if (this.data.saving || this.data.submitted)
        return; this.setData({ score: e.detail.value.replace(/\D/g, '') }); }, inputRule(e) { if (this.data.saving || this.data.submitted)
        return; this.setData({ ruleCode: e.detail.value }); },
    chooseImages() { if (this.data.saving || this.data.submitted)
        return; const remaining = 9 - this.data.images.length; if (remaining <= 0)
        return; wx.chooseMedia({ count: remaining, mediaType: ['image'], sourceType: ['album', 'camera'], success: result => { if (this.data.saving || this.data.submitted)
            return; const added = result.tempFiles.map(file => ({ path: file.tempFilePath })); this.setData({ images: [...this.data.images, ...added] }); } }); },
    removeImage(e) { if (this.data.saving || this.data.submitted)
        return; const index = Number(e.currentTarget.dataset.index); this.setData({ images: this.data.images.filter((_, i) => i !== index) }); },
    async uploadAll() { const uploaded = []; for (let i = 0; i < this.data.images.length; i++) {
        const item = this.data.images[i];
        if (item.id) {
            uploaded.push(item.id);
            continue;
        }
        this.setData({ [`images[${i}].uploading`]: true });
        try {
            const id = await (0, request_1.uploadImage)(item.path);
            this.setData({ [`images[${i}].id`]: id });
            uploaded.push(id);
        }
        finally {
            this.setData({ [`images[${i}].uploading`]: false });
        }
    } return uploaded; },
    async submit() { if (this.data.saving || this.data.submitted)
        return; if (this.data.rulesLoading) {
        this.setData({ error: "规则正在加载，请稍后提交" });
        return;
    } if (this.data.type === 'DEDUCTION' && this.data.supervisor && !this.data.ruleCode) {
        this.setData({ error: '请选择有效的扣分规则' });
        return;
    } const description = this.data.description.trim(); if (!description) {
        this.setData({ error: '请填写事项说明' });
        return;
    } const target = this.data.people[this.data.targetIndex]; if (this.data.type !== 'BONUS' && !target) {
        this.setData({ error: '请选择责任人' });
        return;
    } if (this.data.type === 'DEDUCTION' && (!this.data.score || Number(this.data.score) <= 0)) {
        this.setData({ error: '请填写大于0的整数建议扣分值' });
        return;
    } const snapshot = { type: this.data.type, date: this.data.date, time: this.data.time, ruleCode: this.data.ruleCode, score: this.data.score, caseType: this.data.deductionKinds[this.data.deductionKindIndex].code }; this.setData({ saving: true, error: '' }); try {
        const confirmed = await new Promise(resolve => wx.showModal({ title: '核对并提交', content: (target ? ('责任人：' + target.name + ' · ' + target.employeeNo + ' · ' + target.teamName + '\n') : '') + this.data.title + ' · ' + snapshot.date + ' ' + snapshot.time + (snapshot.type === 'DEDUCTION' ? '\n建议扣分：' + snapshot.score + ' 分' : '') + '\n' + description.slice(0, 160), success: (r) => resolve(r.confirm), fail: () => resolve(false) }));
        if (!confirmed)
            return;
        await (0, subscription_1.collectSubscriptionCredit)();
        const attachmentIds = await this.uploadAll();
        const occurredAt = `${snapshot.date}T${snapshot.time}:00+08:00`;
        if (snapshot.type === 'BONUS')
            await (0, request_1.request)('/performance-cases/bonus', 'POST', { occurredAt, description, attachmentIds });
        else if (snapshot.type === 'ADMONITION')
            await (0, request_1.request)('/performance-cases/admonitions', 'POST', { targetUserId: target.id, occurredAt, description });
        else
            await (0, request_1.request)('/performance-cases/deductions', 'POST', { caseType: snapshot.caseType, targetUserId: target.id, occurredAt, description, ruleCode: snapshot.ruleCode.trim() || null, score: Number(snapshot.score), attachmentIds });
        this.setData({ submitted: true });
        (0, draft_1.clearDraft)(this.draftKey);
        wx.showToast({ title: '提交成功', icon: 'success' });
        setTimeout(() => wx.navigateBack(), 700);
    }
    catch (reason) {
        this.setData({ error: reason instanceof Error ? reason.message : '提交失败' });
    }
    finally {
        this.setData({ saving: false });
    } }
});
function today() { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; }
function nowTime() { const d = new Date(); return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`; }
