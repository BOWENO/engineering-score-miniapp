interface ApiResponse<T> { code: string; message: string; data: T; requestId: string }

export class ApiError extends Error {
  constructor(public code: string, message: string, public statusCode: number, public requestId?: string) {
    super(message + (requestId ? `（编号：${requestId}）` : ''))
  }
}

// Retain the same operation key when the server may have committed but its response was lost.
const pendingWrites = new Map<string, string>()
function expireSession(status: number) {
  if (status !== 401) return
  getApp<IAppOption>().globalData.accessToken = ''
  wx.removeStorageSync('access_token')
}

function idempotencyKey(): string {
  return `${Date.now()}-${Math.random().toString(16).slice(2)}-${Math.random().toString(16).slice(2)}`
}

export function request<T>(path: string, method: 'GET' | 'POST' | 'PUT' = 'GET', data?: unknown,
                           extraHeader: Record<string, string> = {}): Promise<T> {
  const app = getApp<IAppOption>()
  const fingerprint = JSON.stringify([app.globalData.accessToken, method, path, data])
  const writeKey = method === 'GET' ? '' : (extraHeader['Idempotency-Key'] || extraHeader['X-Idempotency-Key'] || pendingWrites.get(fingerprint) || idempotencyKey())
  if (writeKey) pendingWrites.set(fingerprint, writeKey)
  return new Promise((resolve, reject) => {
    wx.request<ApiResponse<T>>({
      url: `${app.globalData.apiBaseUrl}${path}`,
      method,
      data,
      header: { ...(app.globalData.accessToken ? { Authorization: `Bearer ${app.globalData.accessToken}` } : {}),
        ...(method === 'GET' ? {} : { 'Idempotency-Key': writeKey, 'X-Idempotency-Key': writeKey }), ...extraHeader },
      success(result) {
        expireSession(result.statusCode)
        if (result.statusCode < 500 && result.data?.code && result.data.code !== 'IDEMPOTENCY_IN_PROGRESS') pendingWrites.delete(fingerprint)
        if (result.statusCode >= 200 && result.statusCode < 300 && result.data?.code === 'OK') resolve(result.data.data)
        else {
          reject(new ApiError(result.data?.code || 'REQUEST_FAILED', result.data?.message || '请求失败', result.statusCode, result.data?.requestId))
        }
      },
      fail: () => reject(new ApiError('NETWORK_ERROR', '网络连接失败，请稍后重试', 0))
    })
  })
}

export function uploadImage(path: string): Promise<string> {
  const app = getApp<IAppOption>()
  const fingerprint = JSON.stringify([app.globalData.accessToken, 'UPLOAD', path])
  const writeKey = pendingWrites.get(fingerprint) || idempotencyKey()
  pendingWrites.set(fingerprint, writeKey)
  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: `${app.globalData.apiBaseUrl}/attachments`,
      filePath: path,
      name: 'file',
      header: { ...(app.globalData.accessToken ? { Authorization: `Bearer ${app.globalData.accessToken}` } : {}),
        'Idempotency-Key': writeKey, 'X-Idempotency-Key': writeKey },
      success(result) {
        expireSession(result.statusCode)
        try {
          const body = JSON.parse(result.data) as ApiResponse<{ id: string }>
          if (result.statusCode < 500 && body?.code && body.code !== 'IDEMPOTENCY_IN_PROGRESS') pendingWrites.delete(fingerprint)
          if (result.statusCode >= 200 && result.statusCode < 300 && body.code === 'OK') resolve(body.data.id)
          else reject(new ApiError(body.code || 'UPLOAD_FAILED', body.message || '图片上传失败', result.statusCode, body.requestId))
        } catch (_) { reject(new ApiError('UPLOAD_FAILED', result.statusCode === 401 ? '登录已失效，请重新登录' : '图片上传响应格式错误', result.statusCode)) }
      },
      fail: () => reject(new ApiError('NETWORK_ERROR', '图片上传失败，请检查网络连接', 0))
    })
  })
}
