"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.refreshSubscriptionHarvesting = refreshSubscriptionHarvesting;
exports.collectSubscriptionCredit = collectSubscriptionCredit;
const request_1 = require("./request");
const STORAGE_KEY = 'subscription_credit_daily';
const DAILY_LIMIT = 3;
async function refreshSubscriptionHarvesting(config) {
    const app = getApp();
    if (wx.getStorageSync('login_method') === 'password') {
        disable(app);
        return;
    }
    try {
        const current = config || await (0, request_1.request)('/notifications/subscription-config');
        if (!current.enabled || !current.eligible || !['ACCEPTED', 'EXHAUSTED'].includes(current.permissionStatus) || !current.templateIds.length) {
            disable(app);
            return;
        }
        const setting = await new Promise((resolve, reject) => wx.getSetting({ withSubscriptions: true, success: resolve, fail: reject }));
        const items = setting?.subscriptionsSetting?.itemSettings || {};
        const remembered = current.templateIds.filter(id => items[id] === 'accept');
        app.globalData.subscriptionTemplateIds = remembered;
        app.globalData.subscriptionRemembered = remembered.length > 0;
    }
    catch (_) {
        disable(app);
    }
}
async function collectSubscriptionCredit() {
    const app = getApp(), ids = app.globalData.subscriptionTemplateIds || [];
    if (wx.getStorageSync('login_method') === 'password' || !app.globalData.subscriptionRemembered || !ids.length || daily().count >= DAILY_LIMIT)
        return;
    try {
        const result = await new Promise((resolve, reject) => wx.requestSubscribeMessage({ tmplIds: ids, success: resolve, fail: reject }));
        const accepted = ids.filter(id => result[id] === 'accept');
        if (accepted.length) {
            increaseDaily();
            await (0, request_1.request)('/notifications/subscriptions', 'POST', { decisions: result, acceptedTemplateIds: accepted });
        }
        else if (ids.some(id => result[id] === 'reject' || result[id] === 'ban')) {
            disable(app);
            await (0, request_1.request)('/notifications/subscriptions', 'POST', { decisions: result, acceptedTemplateIds: [] });
        }
    }
    catch (_) { /* 通知额度采集不得阻断业务操作 */ }
}
function disable(app) { app.globalData.subscriptionTemplateIds = []; app.globalData.subscriptionRemembered = false; }
function day() { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; }
function daily() { const value = wx.getStorageSync(STORAGE_KEY); return value?.date === day() ? value : { date: day(), count: 0 }; }
function increaseDaily() { const value = daily(); wx.setStorageSync(STORAGE_KEY, { date: value.date, count: value.count + 1 }); }
