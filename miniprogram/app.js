"use strict";
App({
    globalData: {
        apiBaseUrl: 'https://www.testengineering.cloud/api',
        accessToken: wx.getStorageSync('access_token') || '',
        subscriptionTemplateIds: [],
        subscriptionRemembered: false
    }
});
