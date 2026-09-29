"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ApiError = void 0;
exports.request = request;
exports.uploadImage = uploadImage;
class ApiError extends Error {
    constructor(code, message, statusCode, requestId) {
        super(message + (requestId ? `（编号：${requestId}）` : ''));
        this.code = code;
        this.statusCode = statusCode;
        this.requestId = requestId;
    }
}
exports.ApiError = ApiError;
// Retain the same operation key when the server may have committed but its response was lost.
const pendingWrites = new Map();
function expireSession(status) {
    if (status !== 401)
        return;
    getApp().globalData.accessToken = '';
    wx.removeStorageSync('access_token');
}
function idempotencyKey() {
    return `${Date.now()}-${Math.random().toString(16).slice(2)}-${Math.random().toString(16).slice(2)}`;
}
function request(path, method = 'GET', data, extraHeader = {}) {
    const app = getApp();
    const fingerprint = JSON.stringify([app.globalData.accessToken, method, path, data]);
    const writeKey = method === 'GET' ? '' : (extraHeader['Idempotency-Key'] || extraHeader['X-Idempotency-Key'] || pendingWrites.get(fingerprint) || idempotencyKey());
    if (writeKey)
        pendingWrites.set(fingerprint, writeKey);
    return new Promise((resolve, reject) => {
        wx.request({
            url: `${app.globalData.apiBaseUrl}${path}`,
            method,
            data,
            header: { ...(app.globalData.accessToken ? { Authorization: `Bearer ${app.globalData.accessToken}` } : {}),
                ...(method === 'GET' ? {} : { 'Idempotency-Key': writeKey, 'X-Idempotency-Key': writeKey }), ...extraHeader },
            success(result) {
                expireSession(result.statusCode);
                if (result.statusCode < 500 && result.data?.code && result.data.code !== 'IDEMPOTENCY_IN_PROGRESS')
                    pendingWrites.delete(fingerprint);
                if (result.statusCode >= 200 && result.statusCode < 300 && result.data?.code === 'OK')
                    resolve(result.data.data);
                else {
                    reject(new ApiError(result.data?.code || 'REQUEST_FAILED', result.data?.message || '请求失败', result.statusCode, result.data?.requestId));
                }
            },
            fail: () => reject(new ApiError('NETWORK_ERROR', '网络连接失败，请稍后重试', 0))
        });
    });
}
function uploadImage(path) {
    const app = getApp();
    const fingerprint = JSON.stringify([app.globalData.accessToken, 'UPLOAD', path]);
    const writeKey = pendingWrites.get(fingerprint) || idempotencyKey();
    pendingWrites.set(fingerprint, writeKey);
    return new Promise((resolve, reject) => {
        wx.uploadFile({
            url: `${app.globalData.apiBaseUrl}/attachments`,
            filePath: path,
            name: 'file',
            header: { ...(app.globalData.accessToken ? { Authorization: `Bearer ${app.globalData.accessToken}` } : {}),
                'Idempotency-Key': writeKey, 'X-Idempotency-Key': writeKey },
            success(result) {
                expireSession(result.statusCode);
                try {
                    const body = JSON.parse(result.data);
                    if (result.statusCode < 500 && body?.code && body.code !== 'IDEMPOTENCY_IN_PROGRESS')
                        pendingWrites.delete(fingerprint);
                    if (result.statusCode >= 200 && result.statusCode < 300 && body.code === 'OK')
                        resolve(body.data.id);
                    else
                        reject(new ApiError(body.code || 'UPLOAD_FAILED', body.message || '图片上传失败', result.statusCode, body.requestId));
                }
                catch (_) {
                    reject(new ApiError('UPLOAD_FAILED', result.statusCode === 401 ? '登录已失效，请重新登录' : '图片上传响应格式错误', result.statusCode));
                }
            },
            fail: () => reject(new ApiError('NETWORK_ERROR', '图片上传失败，请检查网络连接', 0))
        });
    });
}
