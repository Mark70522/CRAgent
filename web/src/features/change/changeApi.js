import http from '../../api/request'

export const changeApi = {
  list:     (q = {})               => http.get('/changes', { params: q }),
  get:      (number, live = false) => http.get(`/changes/${encodeURIComponent(number)}`, { params: live ? { live: 1 } : {} }),
  create:   (fields, tasks, taskId) => http.post('/changes', { fields, tasks, taskId }),
  update:   (number, fields)       => http.put(`/changes/${encodeURIComponent(number)}`, { fields }),
  draft:    (body)                 => http.post('/changes/draft', body),
  validate: (fields, tasks)        => http.post('/changes/validate', { fields, tasks }),
  tasks:    (number)               => http.get(`/changes/${encodeURIComponent(number)}/tasks`),
  addTask:  (number, fields)       => http.post(`/changes/${encodeURIComponent(number)}/tasks`, { fields }),
  cancelTask: (id, fields = {})    => http.post(`/tasks/${encodeURIComponent(id)}/cancel`, { fields }),
  closeTask:  (id, fields = {})    => http.post(`/tasks/${encodeURIComponent(id)}/close`, { fields }),
  templates: ()                    => http.get('/templates'),
}

export const formsApi = {
  get:   (kind)         => http.get(`/forms/${kind}`),
  save:  (kind, fields) => http.put(`/forms/${kind}`, { fields }),
  learn: (kind, record) => http.post(`/forms/${kind}/learn`, { record }),
}
