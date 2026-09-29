App<IAppOption>({
  globalData: {
    apiBaseUrl: 'https://www.testengineering.cloud/api',
    accessToken: wx.getStorageSync('access_token') || '',
    subscriptionTemplateIds: [],
    subscriptionRemembered: false
  }
})
