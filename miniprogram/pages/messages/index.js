"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const navigation_1 = require("../../utils/navigation");
const display_1 = require("../../utils/display");
const request_1 = require("../../utils/request");
const subscription_1 = require("../../utils/subscription");
Page({ sequence: 0, data: { items: [], filter: 'all', hasMore: false, total: 0, unread: 0, awaiting: 0, loading: false, error: '' }, onShow() { (0, subscription_1.refreshSubscriptionHarvesting)(); this.load(); }, onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()); }, async load(append = false) { const sequence = ++this.sequence; this.setData({ loading: true, error: '' }); try {
        const inbox = await (0, request_1.request)('/notifications?limit=50&offset=' + (append ? this.data.items.length : 0) + '&unreadOnly=' + (this.data.filter === 'unread'));
        if (sequence !== this.sequence)
            return;
        const rows = inbox.items.map(item => ({ ...item, createdAt: (0, display_1.chinaTime)(item.createdAt) }));
        this.setData({ items: append ? [...this.data.items, ...rows.filter(r => !this.data.items.some(i => i.id === r.id))] : rows, unread: inbox.unread, awaiting: inbox.awaitingAcknowledgement, total: inbox.total, hasMore: inbox.hasMore });
    }
    catch (reason) {
        if (sequence === this.sequence)
            this.setData({ error: reason instanceof Error ? reason.message : '消息加载失败' });
    }
    finally {
        if (sequence === this.sequence)
            this.setData({ loading: false });
    } },
    more() { if (!this.data.loading && this.data.hasMore)
        this.load(true); }, changeFilter(e) { this.setData({ filter: e.currentTarget.dataset.filter, items: [], hasMore: false }); this.load(); },
    async open(e) { const item = this.data.items[Number(e.currentTarget.dataset.index)]; if (!item)
        return; try {
        await (0, subscription_1.collectSubscriptionCredit)();
        if (!item.readAt)
            await (0, request_1.request)(`/notifications/${item.id}/read`, 'POST', { acknowledge: item.sourceType !== 'SCHEDULE_ASSIGNMENT' && item.requiresAcknowledgement });
        await this.load();
        if (item.sourceType === 'EQUIPMENT_INCIDENT' && item.sourceId)
            wx.navigateTo({ url: `/pages/incident-detail/index?id=${item.sourceId}` });
        else if (item.sourceType === 'PERFORMANCE_CASE' || item.sourceType === 'SCHEDULE_ASSIGNMENT') {
            const kind = item.sourceType === 'PERFORMANCE_CASE' ? 'score' : 'schedule';
            const date = await (0, request_1.request)('/notifications/' + item.id + '/source-date');
            (0, navigation_1.setTarget)(kind, item.sourceId || '', date);
            wx.switchTab({ url: '/pages/' + kind + '/index' });
        }
    }
    catch (reason) {
        wx.showToast({ title: reason instanceof Error ? reason.message : '消息处理失败', icon: 'none' });
    } }
});
