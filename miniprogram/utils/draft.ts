// Drafts belong to an authenticated user, never to the device's next account.
export function readDraft(key:string):any {try{return wx.getStorageSync(key)||null}catch{return null}}
export function saveDraft(key:string,value:any){if(!key)return;try{wx.setStorageSync(key,value)}catch{wx.showToast({title:'草稿保存失败，请勿离开页面',icon:'none'})}}
export function clearDraft(key:string){if(key)wx.removeStorageSync(key);wx.disableAlertBeforeUnload?.()}
export function protectDraft(){wx.enableAlertBeforeUnload?.({message:'尚有未提交内容，返回后可恢复文字草稿'})}
