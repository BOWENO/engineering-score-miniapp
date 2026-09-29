let target:{kind:string;id:string;date?:string;token:string}|null=null
export function setTarget(kind:string,id='',date?:string){target={kind,id,date,token:getApp<IAppOption>().globalData.accessToken}}
export function takeTarget(kind:string){if(!target||target.kind!==kind)return null;const value=target;target=null;return value.token===getApp<IAppOption>().globalData.accessToken?value:null}
