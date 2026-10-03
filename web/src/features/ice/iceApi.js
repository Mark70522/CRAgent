import http from '../../api/request'

export const iceApi = {
  list:   (q = {})            => http.get('/ices', { params: q }),
  get:    (id, live = false)  => http.get(`/ices/${encodeURIComponent(id)}`, { params: live ? { live: 1 } : {} }),
  create: (changeNumber, fields, taskId) => http.post('/ices', { changeNumber, fields, taskId }),
  update: (id, fields)        => http.put(`/ices/${encodeURIComponent(id)}`, { fields }),
  score:  (id)                => http.get(`/ices/${encodeURIComponent(id)}/score`),
  draft:  (number)            => http.get('/ices/draft', { params: { number } }),
}
