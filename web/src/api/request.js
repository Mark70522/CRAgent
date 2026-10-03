import axios from 'axios'

// One axios instance. cr-agent answers every /api/v1 call with { code, message, data }.
const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 60000, // a real ServiceNow call can take a while
  headers: { 'Content-Type': 'application/json' },
})

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && body.code === 200) return body.data
    const err = new Error(body?.message || '请求失败')
    err.code = body?.code
    return Promise.reject(err)
  },
  (err) => {
    const msg = err.response?.data?.message || err.message || '网络错误'
    return Promise.reject(new Error(msg))
  }
)

export default http
