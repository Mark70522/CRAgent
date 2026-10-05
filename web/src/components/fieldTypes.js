// Every field type the forms can edit. Same list as FormCatalog.TYPES on the server.
export const FIELD_TYPES = [
  { value: 'text', label: 'text · 单行文本' },
  { value: 'textarea', label: 'textarea · 多行文本' },
  { value: 'number', label: 'number · 数字' },
  { value: 'boolean', label: 'boolean · 是/否' },
  { value: 'select', label: 'select · 单选' },
  { value: 'multiselect', label: 'multiselect · 多选' },
  { value: 'date', label: 'date · 日期' },
  { value: 'datetime', label: 'datetime · 日期时间' },
  { value: 'time', label: 'time · 时间' },
  { value: 'email', label: 'email · 邮箱' },
  { value: 'url', label: 'url · 链接' },
  { value: 'list', label: 'list · 数组(简单值)' },
  { value: 'object', label: 'object · 键值对' },
  { value: 'table', label: 'table · 对象数组(表格)' },
  { value: 'reference', label: 'reference · 引用 {value, display_value}' },
  { value: 'json', label: 'json · 任意 JSON' },
]

// Types that need options in the catalog.
export const WITH_OPTIONS = ['select', 'multiselect']

// Types that take a whole row in the form grid.
export const WIDE = ['textarea', 'json', 'table', 'object']

// How a reference field is written back: its id, its display text, or the whole object.
export const REFERENCE_SEND = [
  { value: 'value', label: '发 value(ID)' },
  { value: 'display_value', label: '发 display_value(名字)' },
  { value: 'object', label: '发整个对象' },
]

const simple = (v) => v == null || ['string', 'number', 'boolean'].includes(typeof v)
const isObj = (v) => v != null && typeof v === 'object' && !Array.isArray(v)

/** Same rules as FormCatalog.guessType on the server: a field not in the catalog is shown by what its value is. */
export function guessType(key, v) {
  if (isObj(v)) {
    if ('display_value' in v || ('value' in v && 'link' in v)) return 'reference'
    return Object.values(v).every(simple) ? 'object' : 'json'
  }
  if (Array.isArray(v)) {
    if (v.every(simple)) return 'list'
    if (v.every((x) => isObj(x) && Object.values(x).every(simple))) return 'table'
    return 'json'
  }
  if (typeof v === 'boolean') return 'boolean'
  if (typeof v === 'number') return 'number'
  const s = v == null ? '' : String(v)
  const k = key.toLowerCase()
  if (/^\d{4}-\d{2}-\d{2}$/.test(s)) return 'date'
  if (/^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}/.test(s)) return 'datetime'
  if (/^\d{2}:\d{2}(:\d{2})?$/.test(s)) return 'time'
  if (!s && (k.includes('date') || k.endsWith('_on') || k.endsWith('_at'))) return 'datetime'
  if (/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(s)) return 'email'
  if (/^https?:\/\/\S+$/.test(s)) return 'url'
  if (s.length > 120 || s.includes('\n')) return 'textarea'
  return 'text'
}

/** Equality by content (lists / objects too), for "changed" marks and update diffs. */
export const same = (a, b) => JSON.stringify(a ?? '') === JSON.stringify(b ?? '')

/** What actually goes to the interface: reference fields are sent the way their catalog entry says (default: the id). */
export function toPayload(catalog = [], fields = {}) {
  const byKey = Object.fromEntries(catalog.map((f) => [f.key, f]))
  const out = {}
  for (const [k, v] of Object.entries(fields)) {
    const f = byKey[k]
    if (f?.type === 'reference' && isObj(v)) {
      const send = f.send || 'value'
      out[k] = send === 'object' ? v : (v[send] ?? v.value ?? '')
    } else out[k] = v
  }
  return out
}
