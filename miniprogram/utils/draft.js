"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.readDraft = readDraft;
exports.saveDraft = saveDraft;
exports.clearDraft = clearDraft;
exports.protectDraft = protectDraft;
// Drafts belong to an authenticated user, never to the device's next account.
function readDraft(key) { try {
    return wx.getStorageSync(key) || null;
}
catch {
    return null;
} }
function saveDraft(key, value) { if (!key)
    return; try {
    wx.setStorageSync(key, value);
}
catch {
    wx.showToast({ title: '草稿保存失败，请勿离开页面', icon: 'none' });
} }
function clearDraft(key) { if (key)
    wx.removeStorageSync(key); wx.disableAlertBeforeUnload?.(); }
function protectDraft() { wx.enableAlertBeforeUnload?.({ message: '尚有未提交内容，返回后可恢复文字草稿' }); }
