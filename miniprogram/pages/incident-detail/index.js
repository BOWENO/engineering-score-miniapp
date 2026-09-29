"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const draft_1 = require("../../utils/draft");
const display_1 = require("../../utils/display");
const request_1 = require("../../utils/request");
const subscription_1 = require("../../utils/subscription");
Page({
    draftKey: '', dirty: false, draftVersion: null,
    onHide() { this.persistDraft(); }, onUnload() { this.persistDraft(); },
    persistDraft() { if (this.dirty && this.draftKey)
        (0, draft_1.saveDraft)(this.draftKey, { phenomenon: this.data.phenomenon, handlingMethod: this.data.handlingMethod, rootCause: this.data.rootCause, longTermAction: this.data.longTermAction, version: this.draftVersion }); },
    markDirty() { this.dirty = true; this.persistDraft(); (0, draft_1.protectDraft)(); },
    discardDraft() { wx.showModal({ title: '放弃未提交的草稿？', content: '将恢复服务端最近保存的说明。', success: (r) => { if (r.confirm) {
            (0, draft_1.clearDraft)(this.draftKey);
            this.dirty = false;
            this.setData({ draftNotice: '' });
            this.load();
        } } }); },
    data: { reviewing: false, draftNotice: '', id: '', meId: '', supervisor: false, incident: null, dossier: null, own: null, canSubmit: false, phenomenon: '', handlingMethod: '', rootCause: '', longTermAction: '', loading: false, saving: false, error: '' },
    onLoad(options) { this.setData({ id: options.id || '' }); }, onShow() { (0, subscription_1.refreshSubscriptionHarvesting)(); if (this.data.id)
        this.load(); },
    async load() { this.setData({ loading: true, error: '' }); try {
        const [me, rawIncident, rawDossier] = await Promise.all([(0, request_1.request)('/me'), (0, request_1.request)(`/incidents/${this.data.id}`), (0, request_1.request)(`/incidents/${this.data.id}/dossier`)]);
        const incident = (0, display_1.decorate)(rawIncident), dossier = (0, display_1.decorate)(rawDossier);
        const own = incident.statements.find(s => s.responsibleUserId === me.userId) || null;
        const key = 'ux:incident:' + me.userId + ':' + this.data.id;
        if (this.draftKey !== key) {
            this.draftKey = key;
            this.dirty = false;
        }
        const draft = this.dirty ? { phenomenon: this.data.phenomenon, handlingMethod: this.data.handlingMethod, rootCause: this.data.rootCause, longTermAction: this.data.longTermAction, version: this.draftVersion } : (0, draft_1.readDraft)(key);
        this.setData({ meId: me.userId, supervisor: me.roles.includes('SUPERVISOR'), incident, dossier, own, canSubmit: Boolean(own && ['PENDING', 'RETURNED', 'OVERDUE'].includes(own.status)), phenomenon: own?.phenomenon || '', handlingMethod: own?.handlingMethod || '', rootCause: own?.rootCause || '', longTermAction: own?.longTermAction || '' });
        if (draft) {
            this.setData({ phenomenon: draft.phenomenon, handlingMethod: draft.handlingMethod, rootCause: draft.rootCause, longTermAction: draft.longTermAction, draftNotice: draft.version !== own?.version ? '服务端说明已更新，已保留草稿，请核对后再提交。' : '已恢复未提交的文字草稿' });
            this.dirty = true;
            (0, draft_1.protectDraft)();
        }
        this.draftVersion = draft?.version ?? own?.version ?? null;
    }
    catch (reason) {
        this.setData({ error: reason instanceof Error ? reason.message : '异常详情加载失败' });
    }
    finally {
        this.setData({ loading: false });
    } },
    inputPhenomenon(e) { if (this.data.saving)
        return; this.setData({ phenomenon: e.detail.value }); this.markDirty(); }, inputHandling(e) { if (this.data.saving)
        return; this.setData({ handlingMethod: e.detail.value }); this.markDirty(); }, inputRoot(e) { if (this.data.saving)
        return; this.setData({ rootCause: e.detail.value }); this.markDirty(); }, inputAction(e) { if (this.data.saving)
        return; this.setData({ longTermAction: e.detail.value }); this.markDirty(); },
    async submit() { if (this.data.saving || !this.data.own)
        return; const { phenomenon, handlingMethod, rootCause } = this.data; if (!phenomenon.trim() || !handlingMethod.trim() || !rootCause.trim()) {
        this.setData({ error: '异常现象、处理方法和问题根因均为必填' });
        return;
    } this.setData({ saving: true, error: '' }); try {
        if (this.draftVersion !== null && this.draftVersion !== this.data.own.version) {
            const yes = await new Promise(resolve => wx.showModal({ title: '服务端说明已更新', content: '已保留你的草稿。确认已核对最新说明，并使用当前草稿提交？', success: (r) => resolve(r.confirm), fail: () => resolve(false) }));
            if (!yes)
                return;
        }
        await (0, subscription_1.collectSubscriptionCredit)();
        await (0, request_1.request)(`/incidents/${this.data.id}/statement`, 'PUT', { version: this.data.own.version, phenomenon, handlingMethod, rootCause, longTermAction: this.data.longTermAction || null });
        (0, draft_1.clearDraft)(this.draftKey);
        this.dirty = false;
        this.setData({ draftNotice: '' });
        await this.load();
        wx.showToast({ title: '已提交审核', icon: 'success' });
    }
    catch (reason) {
        this.setData({ error: reason instanceof Error ? reason.message : '提交失败' });
    }
    finally {
        this.setData({ saving: false });
    } },
    approve(e) { this.review(e.currentTarget.dataset.id, e.currentTarget.dataset.version, 'APPROVE', ''); },
    returnStatement(e) { const { id, version } = e.currentTarget.dataset; wx.showModal({ title: '退回责任人修改', editable: true, placeholderText: '请填写明确的退回原因', success: result => { if (result.confirm && result.content?.trim())
            this.review(id, version, 'RETURN', result.content); } }); },
    async review(id, version, decision, comment) { if (this.data.reviewing)
        return; const statement = this.data.incident?.statements.find(s => s.id === id); if (!this.data.supervisor || !statement || statement.status !== 'SUBMITTED' || statement.version !== Number(version)) {
        wx.showToast({ title: '说明尚未提交或已更新，请刷新后审核', icon: 'none' });
        return;
    } this.setData({ reviewing: true }); try {
        await (0, subscription_1.collectSubscriptionCredit)();
        await (0, request_1.request)(`/incidents/statements/${id}/review`, 'POST', { version, decision, comment });
        await this.load();
        wx.showToast({ title: decision === 'APPROVE' ? '审核通过' : '已退回', icon: 'success' });
    }
    catch (reason) {
        wx.showToast({ title: reason instanceof Error ? reason.message : '审核失败', icon: 'none' });
    }
    finally {
        this.setData({ reviewing: false });
    } },
    completeAction(e) { const { id, version } = e.currentTarget.dataset; wx.showModal({ title: '提交整改完成说明', editable: true, placeholderText: '说明采取了什么措施及完成结果', success: result => { if (result.confirm && result.content?.trim())
            this.submitAction(id, version, result.content); } }); },
    async submitAction(id, version, note) { try {
        await (0, subscription_1.collectSubscriptionCredit)();
        await (0, request_1.request)(`/incidents/dossier/actions/${id}/complete`, 'POST', { version, note });
        await this.load();
        wx.showToast({ title: '已提交验收', icon: 'success' });
    }
    catch (reason) {
        wx.showToast({ title: reason instanceof Error ? reason.message : '提交失败', icon: 'none' });
    } }
});
