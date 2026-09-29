"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const request_1 = require("../../utils/request");
const subscription_1 = require("../../utils/subscription");
const roleNames = { TECHNICIAN: '技术员', ASSISTANT_ENGINEER: '助理工程师', SUPERVISOR: '主管', DEPARTMENT_MANAGER: '部长' };
Page({
    data: { user: null, bindingToken: '', wechatVerified: false, avatarText: '', rolesText: '', username: '', password: '', currentPassword: '', newPassword: '', confirmPassword: '', subscriptionEnabled: false, subscriptionEligible: false, subscriptionStatus: 'NOT_REQUESTED', subscriptionStatusText: '未申请', subscriptionCredits: 0, templateIds: [], loading: false, error: '' },
    onShow() { if (getApp().globalData.accessToken)
        this.loadUser(); },
    async loadUser() { this.setData({ loading: true, error: '' }); try {
        const [user, config] = await Promise.all([(0, request_1.request)('/me'), (0, request_1.request)('/notifications/subscription-config').catch(() => ({ enabled: false, templateIds: [], eligible: false, permissionStatus: 'NOT_CONFIGURED', credits: 0 }))]);
        this.setData({ user, avatarText: user.displayName.slice(0, 1), rolesText: user.roles.map(role => roleNames[role] || role).join('、'), subscriptionEnabled: config.enabled, subscriptionEligible: config.eligible && wx.getStorageSync('login_method') !== 'password', subscriptionStatus: config.permissionStatus, subscriptionStatusText: subscriptionText(config.permissionStatus, config.credits), subscriptionCredits: config.credits, templateIds: config.templateIds });
    }
    catch (reason) {
        this.clearSession();
        this.setData({ error: reason instanceof Error ? reason.message : '登录状态已失效' });
    }
    finally {
        this.setData({ loading: false });
    } },
    inputUsername(e) { this.setData({ username: e.detail.value }); }, inputPassword(e) { this.setData({ password: e.detail.value }); }, inputCurrent(e) { this.setData({ currentPassword: e.detail.value }); }, inputNew(e) { this.setData({ newPassword: e.detail.value }); }, inputConfirm(e) { this.setData({ confirmPassword: e.detail.value }); },
    async accountLogin() { if (this.data.loading)
        return; const username = this.data.username.trim(), password = this.data.password; if (!username || !password) {
        this.setData({ error: '请输入账号和密码' });
        return;
    } this.setData({ loading: true, error: '' }); try {
        const token = await (0, request_1.request)('/auth/account/login', 'POST', { username, password, clientType: 'MINIPROGRAM' });
        wx.setStorageSync('login_method', 'password');
        this.saveToken(token.accessToken);
        await this.loadUser();
        this.setData({ password: '', bindingToken: '', wechatVerified: false });
        if (this.data.user && !this.data.user.passwordChangeRequired)
            wx.reLaunch({ url: '/pages/home/index' });
    }
    catch (reason) {
        this.setData({ error: reason instanceof Error ? reason.message : '账号登录失败' });
    }
    finally {
        this.setData({ loading: false });
    } },
    cancelBinding() { this.setData({ bindingToken: '', wechatVerified: false, password: '', error: '' }); },
    async switchAccount() { if (this.data.loading)
        return; this.setData({ loading: true }); try {
        await (0, request_1.request)('/auth/logout', 'POST');
    }
    catch (_) { }
    finally {
        this.clearSession();
        wx.reLaunch({ url: '/pages/profile/index' });
    } },
    async bindAccount() { const username = this.data.username.trim(), password = this.data.password; if (!username || !password) {
        this.setData({ error: '请输入工号和密码' });
        return;
    } this.setData({ loading: true, error: '' }); try {
        const token = await (0, request_1.request)('/auth/wechat/bind-account', 'POST', { bindingToken: this.data.bindingToken, username, password });
        wx.setStorageSync('login_method', 'wechat');
        this.saveToken(token.accessToken);
        const user = await (0, request_1.request)('/me');
        this.setData({ user, bindingToken: '', wechatVerified: false, avatarText: user.displayName.slice(0, 1), rolesText: user.roles.map(role => roleNames[role] || role).join('、'), currentPassword: password, password: '' });
        if (!user.passwordChangeRequired)
            wx.switchTab({ url: '/pages/home/index' });
    }
    catch (reason) {
        this.setData({ error: reason instanceof Error ? reason.message : '工号绑定失败' });
    }
    finally {
        this.setData({ loading: false });
    } },
    async wechatLogin() { this.setData({ loading: true, error: '' }); try {
        const result = await (0, request_1.request)('/auth/wechat/login', 'POST', { code: await getWechatCode() });
        if (result.bindingRequired && result.bindingToken) {
            this.setData({ bindingToken: result.bindingToken, wechatVerified: true, password: '' });
            return;
        }
        if (!result.accessToken)
            throw new Error('微信登录响应无效');
        wx.setStorageSync('login_method', 'wechat');
        this.saveToken(result.accessToken);
        await this.loadUser();
        wx.switchTab({ url: '/pages/home/index' });
    }
    catch (reason) {
        this.setData({ error: reason instanceof Error ? reason.message : '微信登录失败' });
    }
    finally {
        this.setData({ loading: false });
    } },
    async changePassword() { if (this.data.newPassword !== this.data.confirmPassword) {
        this.setData({ error: '两次输入的新密码不一致' });
        return;
    } if (!/^(?=.*[A-Za-z])(?=.*\d).{8,128}$/.test(this.data.newPassword)) {
        this.setData({ error: '新密码至少8位，并且必须同时包含字母和数字' });
        return;
    } this.setData({ loading: true, error: '' }); try {
        const token = await (0, request_1.request)('/auth/account/password', 'POST', { currentPassword: this.data.currentPassword, newPassword: this.data.newPassword });
        this.saveToken(token.accessToken);
        this.setData({ currentPassword: '', newPassword: '', confirmPassword: '' });
        await this.loadUser();
        wx.showToast({ title: '密码修改成功', icon: 'success' });
        wx.switchTab({ url: '/pages/home/index' });
    }
    catch (reason) {
        this.setData({ error: reason instanceof Error ? reason.message : '密码修改失败' });
    }
    finally {
        this.setData({ loading: false });
    } },
    async bindWechat() { this.setData({ loading: true, error: '' }); try {
        await (0, request_1.request)('/auth/wechat/bind', 'POST', { code: await getWechatCode() });
        await this.loadUser();
        wx.showToast({ title: '微信绑定成功', icon: 'success' });
    }
    catch (reason) {
        this.setData({ error: reason instanceof Error ? reason.message : '微信绑定失败' });
    }
    finally {
        this.setData({ loading: false });
    } },
    async subscribeWechat() { if (wx.getStorageSync('login_method') === 'password' || !this.data.templateIds.length)
        return; try {
        const result = await new Promise((resolve, reject) => wx.requestSubscribeMessage({ tmplIds: this.data.templateIds, success: resolve, fail: reject }));
        await (0, request_1.request)('/notifications/subscriptions', 'POST', { decisions: result, acceptedTemplateIds: this.data.templateIds.filter(id => result[id] === 'accept') });
        await this.loadUser();
        const accepted = this.data.templateIds.some(id => result[id] === 'accept');
        if (accepted)
            (0, subscription_1.refreshSubscriptionHarvesting)();
        wx.showToast({ title: accepted ? '微信提醒已开启' : '未开启微信提醒', icon: accepted ? 'success' : 'none' });
    }
    catch (reason) {
        this.setData({ error: reason instanceof Error ? reason.message : '订阅提醒失败' });
    } },
    goMessages() { wx.navigateTo({ url: '/pages/messages/index' }); }, goHistory() { wx.switchTab({ url: '/pages/score/index' }); },
    async logout() { try {
        await (0, request_1.request)('/auth/logout', 'POST');
    }
    catch (_) { } this.clearSession(); this.setData({ user: null, bindingToken: '', wechatVerified: false, rolesText: '', username: '', password: '', error: '' }); wx.showToast({ title: '已退出', icon: 'success' }); },
    saveToken(token) { const app = getApp(); app.globalData.subscriptionTemplateIds = []; app.globalData.subscriptionRemembered = false; app.globalData.accessToken = token; wx.setStorageSync('access_token', token); }, clearSession() { const app = getApp(); app.globalData.accessToken = ''; app.globalData.subscriptionTemplateIds = []; app.globalData.subscriptionRemembered = false; wx.removeStorageSync('access_token'); wx.removeStorageSync('login_method'); wx.removeStorageSync('subscription_credit_daily'); this.setData({ user: null, currentPassword: '', newPassword: '', confirmPassword: '', subscriptionEligible: false, templateIds: [] }); }
});
function getWechatCode() { return new Promise((resolve, reject) => wx.login({ success: r => r.code ? resolve(r.code) : reject(new Error('微信登录失败')), fail: reject })); }
function subscriptionText(status, credits) { if (status === 'ACCEPTED')
    return '已开启'; if (status === 'EXHAUSTED')
    return '已订阅，可在日常操作中接收后续提醒'; if (status === 'REJECTED')
    return '上次选择了拒绝'; if (status === 'BANNED')
    return '已在微信中关闭'; if (status === 'NOT_CONFIGURED')
    return '系统尚未配置'; return '尚未申请'; }
