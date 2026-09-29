interface IAppOption {
  globalData: { apiBaseUrl: string; accessToken: string; subscriptionTemplateIds: string[]; subscriptionRemembered: boolean }
}

declare const wx: {
  request<T = any>(options: any): any
  uploadFile(options: any): any
  [key: string]: any
}
declare function App<T extends Record<string, any>>(options: T & ThisType<T>): void
declare function Page<T extends Record<string, any>>(options: T & ThisType<T & { setData(data: Record<string, any>, callback?: () => void): void }>): void
declare function getApp<T>(): T

declare namespace WechatMiniprogram {
  interface BaseEvent { currentTarget: { dataset: Record<string, any> }; target: { dataset: Record<string, any> }; detail?: any }
  interface Input extends BaseEvent { detail: { value: string; cursor?: number; keyCode?: number } }
  interface PickerChange extends BaseEvent { detail: { value: string } }
  interface TextareaInput extends BaseEvent { detail: { value: string; cursor?: number } }
  interface SwitchChange extends BaseEvent { detail: { value: boolean } }
}
