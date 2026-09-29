"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.pollScoreChanges = pollScoreChanges;
function pollScoreChanges(since) {
    const app = getApp(), token = app.globalData.accessToken;
    let task;
    const promise = new Promise((resolve, reject) => {
        if (!token) {
            reject(new Error('登录已失效'));
            return;
        }
        task = wx.request({ url: app.globalData.apiBaseUrl + '/score-changes?since=' + encodeURIComponent(since), timeout: 30000,
            header: { Authorization: 'Bearer ' + token },
            success(result) {
                const body = result.data;
                if (app.globalData.accessToken !== token) {
                    reject(new Error('登录状态已变化'));
                    return;
                }
                if (result.statusCode === 200 && body?.code === 'OK' && typeof body.data?.revision === 'string')
                    resolve(body.data);
                else
                    reject(new Error('自动更新连接失败'));
            }, fail: () => reject(new Error('自动更新连接中断')) });
    });
    return { promise, cancel: () => task?.abort() };
}
