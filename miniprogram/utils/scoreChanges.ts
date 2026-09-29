import { Change, PendingChange } from './scoreWatch'
export function pollScoreChanges(since:string):PendingChange {
  const app=getApp<IAppOption>(),token=app.globalData.accessToken
  let task:{abort:()=>void}|undefined
  const promise=new Promise<Change>((resolve,reject)=>{
    if(!token){reject(new Error('登录已失效'));return}
    task=wx.request({url:app.globalData.apiBaseUrl+'/score-changes?since='+encodeURIComponent(since),timeout:30000,
      header:{Authorization:'Bearer '+token},
      success(result){
        const body=result.data as {code:string;data:Change}
        if(app.globalData.accessToken!==token){reject(new Error('登录状态已变化'));return}
        if(result.statusCode===200&&body?.code==='OK'&&typeof body.data?.revision==='string')resolve(body.data)
        else reject(new Error('自动更新连接失败'))
      },fail:()=>reject(new Error('自动更新连接中断'))})
  })
  return {promise,cancel:()=>task?.abort()}
}

