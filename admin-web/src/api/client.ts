export interface ApiResponse<T> {
  code: string
  message: string
  data: T
  requestId: string
}

const pendingWrites = new Map<string, string>()
const pendingUploads = new WeakMap<File, Map<string, string>>()

export async function apiGet<T>(path: string, signal?:AbortSignal): Promise<T> {
  return request<T>(path, { method: 'GET', signal })
}

export async function apiPost<T>(path: string, body: unknown, method: 'POST' | 'PUT' = 'POST'): Promise<T> {
  return request<T>(path, { method, body: JSON.stringify(body) })
}

export async function apiDelete<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, { method: 'DELETE', ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
}

async function request<T>(path: string, init: RequestInit): Promise<T> {
  const token = localStorage.getItem('access_token')
  const method = (init.method || 'GET').toUpperCase()
  const fingerprint = JSON.stringify([token, method, path, init.body])
  const write = method !== 'GET' && method !== 'HEAD'
  const key = write ? pendingWrites.get(fingerprint) || crypto.randomUUID() : ''
  if (write) pendingWrites.set(fingerprint, key)
  const response = await safeFetch(`/api${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      ...(write ? { 'Idempotency-Key': key } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    credentials: 'include'
  })
  if (response.status === 401 && !path.includes('/auth/admin/login') && !path.includes('/auth/account/login')) {
    localStorage.removeItem('access_token'); window.location.reload()
  }
  let body: ApiResponse<T>
  try { body = await response.json() } catch { throw new Error(response.status===401?'登录已失效，请重新登录':`服务响应异常（HTTP ${response.status}），请稍后重试`) }
  if (response.status < 500 && body?.code && body.code !== 'IDEMPOTENCY_IN_PROGRESS') pendingWrites.delete(fingerprint)
  if (!response.ok || body.code !== 'OK') {
    throw new Error((body.message || '请求失败')+(body.requestId?`（编号：${body.requestId}）`:''))
  }
  return body.data
}

export async function apiDownload(path: string): Promise<Blob> {
  const token = localStorage.getItem('access_token')
  const response = await safeFetch(`/api${path}`, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
  expireSession(response,path)
  if (!response.ok) {
    let body:ApiResponse<unknown>|null=null
    try { body=await response.json() } catch {}
    throw new Error(body?.message||`下载失败（HTTP ${response.status}），请稍后重试`)
  }
  return response.blob()
}

export async function apiUpload<T>(path: string, file: File): Promise<T> {
  const token = localStorage.getItem('access_token')
  const fingerprint = JSON.stringify([token, path])
  const keys = pendingUploads.get(file) || new Map<string, string>()
  const key = keys.get(fingerprint) || crypto.randomUUID()
  keys.set(fingerprint, key); pendingUploads.set(file, keys)
  const form = new FormData(); form.append('file', file)
  const response = await safeFetch(`/api${path}`, { method: 'POST', body: form,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), 'Idempotency-Key': key } })
  expireSession(response,path)
  let body:ApiResponse<T>
  try { body=await response.json() } catch { throw new Error(`上传失败（HTTP ${response.status}），请稍后重试`) }
  if (response.status < 500 && body?.code && body.code !== 'IDEMPOTENCY_IN_PROGRESS') keys.delete(fingerprint)
  if (!response.ok || body.code !== 'OK') throw new Error((body.message || '上传失败')+(body.requestId?`（编号：${body.requestId}）`:''))
  return body.data
}
function expireSession(response:Response,path:string){
  if(response.status===401&&!path.startsWith('/auth/')){localStorage.removeItem('access_token');window.location.reload()}
}
async function safeFetch(input:RequestInfo|URL,init?:RequestInit):Promise<Response>{
  try{return await fetch(input,init)}catch{throw new Error('网络连接失败，请检查网络后重试')}
}
