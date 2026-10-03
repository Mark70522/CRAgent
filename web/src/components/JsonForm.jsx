import { useMemo } from 'react'
import { Button, Input, Select, Space, Tag, Typography } from 'antd'

/**
 * A form that knows nothing about the record: it renders whatever the field catalog says
 * (knowledge/forms/<kind>.json) and shows keys the record has but the catalog does not under "其他字段".
 *
 * @param {Array}    catalog   [{ key, label, type, section, required, readonly, options, order, hint }]
 * @param {Object}   value     { key: string }  (all values are strings - what the interface returned)
 * @param {Object}   original  the values before editing; changed fields are highlighted
 * @param {Function} onChange  (nextValue) => void
 * @param {boolean}  readonly  show only
 * @param {Function} onLearn   (keys) => void  "收进目录" for unknown keys; omit to hide the button
 * @param {Array}    hide      keys not to show at all
 */
export default function JsonForm({ catalog = [], value = {}, original, onChange, readonly = false, onLearn, hide = [] }) {
  const sections = useMemo(() => {
    const known = new Set(catalog.map((f) => f.key))
    const sorted = [...catalog].filter((f) => !hide.includes(f.key)).sort((a, b) => (a.order ?? 999) - (b.order ?? 999))
    const groups = []
    const byName = {}
    for (const f of sorted) {
      const name = f.section || '基本'
      if (!byName[name]) { byName[name] = { name, fields: [] }; groups.push(byName[name]) }
      byName[name].fields.push(f)
    }
    const unknown = Object.keys(value).filter((k) => !known.has(k) && !hide.includes(k))
    return { groups, unknown }
  }, [catalog, value, hide])

  const set = (key, v) => onChange && onChange({ ...value, [key]: v })
  const changed = (key) => original && String(original[key] ?? '') !== String(value[key] ?? '')

  const control = (f) => {
    const v = value[f.key] ?? ''
    const disabled = readonly || f.readonly
    const common = { disabled, status: changed(f.key) ? 'warning' : undefined }
    switch (f.type) {
      case 'textarea':
        return <Input.TextArea {...common} value={v} autoSize={{ minRows: 3, maxRows: 14 }} onChange={(e) => set(f.key, e.target.value)} />
      case 'select':
        return <Select {...common} value={v || undefined} allowClear style={{ width: '100%' }} options={(f.options || []).map((o) => typeof o === 'string' ? { value: o, label: o } : o)} onChange={(x) => set(f.key, x ?? '')} />
      case 'boolean':
        return <Select {...common} value={v || undefined} allowClear style={{ width: '100%' }} options={[{ value: 'true', label: 'true' }, { value: 'false', label: 'false' }]} onChange={(x) => set(f.key, x ?? '')} />
      case 'datetime':
        return <Input {...common} value={v} placeholder="yyyy-MM-dd HH:mm:ss" onChange={(e) => set(f.key, e.target.value)} />
      default:
        return <Input {...common} value={v} onChange={(e) => set(f.key, e.target.value)} />
    }
  }

  const field = (f) => (
    <div key={f.key} style={{ gridColumn: f.type === 'textarea' ? '1 / -1' : undefined, display: 'flex', flexDirection: 'column', gap: 4 }}>
      <label style={{ fontSize: 12, color: '#888' }}>
        {f.label || f.key}{f.required && <span style={{ color: '#cf1322' }}> *</span>}
        {f.label && f.label !== f.key && <span style={{ marginLeft: 6, color: '#bbb' }}>{f.key}</span>}
        {changed(f.key) && <Tag color="orange" style={{ marginLeft: 6 }}>改了</Tag>}
      </label>
      {control(f)}
      {f.hint && <span style={{ fontSize: 12, color: '#aaa' }}>{f.hint}</span>}
    </div>
  )

  return (
    <div>
      {sections.groups.map((g) => (
        <div key={g.name} style={{ marginBottom: 20 }}>
          <Typography.Text type="secondary" style={{ fontSize: 12, letterSpacing: 1 }}>{g.name.toUpperCase()}</Typography.Text>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: '12px 18px', marginTop: 8 }}>
            {g.fields.map(field)}
          </div>
        </div>
      ))}
      {sections.unknown.length > 0 && (
        <div style={{ marginBottom: 20 }}>
          <Space>
            <Typography.Text type="secondary" style={{ fontSize: 12, letterSpacing: 1 }}>其他字段 · 接口返回了,目录里还没有</Typography.Text>
            {onLearn && !readonly && <Button size="small" onClick={() => onLearn(sections.unknown)}>全部收进目录</Button>}
          </Space>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: '12px 18px', marginTop: 8 }}>
            {sections.unknown.map((k) => field({ key: k, label: k, type: String(value[k] ?? '').length > 120 ? 'textarea' : 'text' }))}
          </div>
        </div>
      )}
    </div>
  )
}

/** The keys whose value differs from the original: what an update should send. */
export function diffFields(original = {}, value = {}) {
  const out = {}
  for (const k of Object.keys(value)) if (String(original[k] ?? '') !== String(value[k] ?? '')) out[k] = value[k]
  return out
}
