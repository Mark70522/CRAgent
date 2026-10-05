import { useEffect, useState } from 'react'
import { Button, DatePicker, Input, InputNumber, Select, Space, Switch, Table, TimePicker } from 'antd'
import { DeleteOutlined, LinkOutlined, PlusOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'

const DATE = 'YYYY-MM-DD'
const DATETIME = 'YYYY-MM-DD HH:mm:ss'
const TIME = 'HH:mm:ss'

const parse = (v, fmt) => { if (!v) return null; const d = dayjs(String(v).replace('T', ' '), fmt); return d.isValid() ? d : (dayjs(v).isValid() ? dayjs(v) : null) }

/** The format a value is already in, so it goes back the same way: "01:30" stays HH:mm, "…01:30:00" keeps seconds. */
function fmtOf(f, v, def) {
  if (f.format) return f.format
  const s = v == null ? '' : String(v)
  if (def === TIME) return /^\d{1,2}:\d{2}$/.test(s) ? 'HH:mm' : TIME
  if (def === DATETIME) return /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}$/.test(s) ? 'YYYY-MM-DD HH:mm' : DATETIME
  return def
}
const opts = (f) => (f.options || []).map((o) => (typeof o === 'string' ? { value: o, label: o } : o))
const isObj = (v) => v != null && typeof v === 'object' && !Array.isArray(v)

/**
 * One field's editor for any catalog type. Values keep the JSON type they came with: a number that arrived as
 * "3" goes back as "3", a list stays a list, a reference object stays an object.
 *
 * @param {Object}   f        catalog entry {key, type, options, format, readonly, ...}
 * @param {*}        value    current value (any JSON)
 * @param {Function} onChange (next) => void
 */
export default function FieldControl({ f, value, onChange, disabled, status, size, compact }) {
  const common = { disabled, status, size }
  const v = value
  switch (f.type) {
    case 'textarea':
      return <Input.TextArea {...common} value={v ?? ''} autoSize={compact ? { minRows: 1, maxRows: 6 } : { minRows: 3, maxRows: 14 }} onChange={(e) => onChange(e.target.value)} />
    case 'number':
      return <InputNumber {...common} style={{ width: '100%' }} value={v === '' || v == null ? null : Number(v)}
        onChange={(n) => onChange(n == null ? '' : typeof v === 'string' ? String(n) : n)} />
    case 'boolean': {
      const on = v === true || String(v).toLowerCase() === 'true'
      return <div><Switch size={size === 'small' ? 'small' : 'default'} disabled={disabled} checked={on} onChange={(x) => onChange(typeof v === 'string' ? String(x) : x)} /></div>
    }
    case 'select':
      return <Select {...common} style={{ width: '100%' }} allowClear showSearch value={v === '' || v == null ? undefined : v} options={opts(f)} onChange={(x) => onChange(x ?? '')} />
    case 'multiselect': {
      const asString = typeof v === 'string'
      const arr = Array.isArray(v) ? v : asString && v ? v.split(',').map((s) => s.trim()).filter(Boolean) : []
      return <Select {...common} mode="multiple" style={{ width: '100%' }} allowClear value={arr} options={opts(f)} onChange={(x) => onChange(asString ? x.join(',') : x)} />
    }
    case 'date':
      return <DatePicker {...common} style={{ width: '100%' }} placeholder="选日期" format={f.format || DATE} value={parse(v, f.format || DATE)} onChange={(d) => onChange(d ? d.format(f.format || DATE) : '')} />
    case 'datetime': {
      const fmt = fmtOf(f, v, DATETIME)
      return <DatePicker {...common} style={{ width: '100%' }} placeholder="选日期和时间" showTime={{ format: 'HH:mm', minuteStep: 5 }} format={fmt} value={parse(v, fmt)} onChange={(d) => onChange(d ? d.format(fmt) : '')} />
    }
    case 'time': {
      const fmt = fmtOf(f, v, TIME)
      const d = v ? dayjs(`2000-01-01 ${v}`, `YYYY-MM-DD ${fmt}`) : null
      return <TimePicker {...common} style={{ width: '100%' }} placeholder="选时间" format={fmt} value={d && d.isValid() ? d : null} onChange={(x) => onChange(x ? x.format(fmt) : '')} />
    }
    case 'email':
      return <Input {...common} type="email" value={v ?? ''} onChange={(e) => onChange(e.target.value)} />
    case 'url':
      return <Input {...common} value={v ?? ''} onChange={(e) => onChange(e.target.value)}
        suffix={v ? <a href={v} target="_blank" rel="noreferrer"><LinkOutlined /></a> : <span />} />
    case 'list': {
      const arr = Array.isArray(v) ? v : v ? [v] : []
      return <Select {...common} mode="tags" open={false} suffixIcon={null} style={{ width: '100%' }} tokenSeparators={[',', '\n']} placeholder="输入后回车"
        value={arr.map(String)} onChange={(x) => onChange(x.map((s, i) => (typeof arr[i] === 'number' && String(arr[i]) === s ? arr[i] : s)))} />
    }
    case 'object':
      return <ObjectEditor value={isObj(v) ? v : {}} onChange={onChange} disabled={disabled} />
    case 'table':
      return <TableEditor value={Array.isArray(v) ? v : []} onChange={onChange} disabled={disabled} />
    case 'reference':
      return <ReferenceEditor value={v} onChange={onChange} disabled={disabled} size={size} />
    case 'json':
      return <JsonBox value={v} onChange={onChange} disabled={disabled} />
    default:
      return <Input {...common} value={isObj(v) || Array.isArray(v) ? JSON.stringify(v) : v ?? ''} onChange={(e) => onChange(e.target.value)} />
  }
}

/** {value, display_value}: shows the name, keeps the id; a plain string becomes {value}. */
function ReferenceEditor({ value, onChange, disabled, size }) {
  const obj = isObj(value) ? value : { value: value ?? '' }
  const set = (k, x) => onChange({ ...obj, [k]: x })
  return (
    <Space.Compact style={{ width: '100%' }}>
      <Input size={size} disabled={disabled} style={{ width: '58%' }} placeholder="显示名 display_value" value={obj.display_value ?? ''} onChange={(e) => set('display_value', e.target.value)} />
      <Input size={size} disabled={disabled} style={{ width: '42%', color: '#6E6E73' }} placeholder="ID value" value={obj.value ?? ''} onChange={(e) => set('value', e.target.value)} />
    </Space.Compact>
  )
}

/** Flat object as key / value rows. */
function ObjectEditor({ value, onChange, disabled }) {
  const rows = Object.entries(value)
  const write = (list) => onChange(Object.fromEntries(list))
  return (
    <div>
      {rows.map(([k, x], i) => (
        <Space.Compact key={i} style={{ width: '100%', marginBottom: 6 }}>
          <Input disabled={disabled} style={{ width: '35%' }} placeholder="键" value={k} onChange={(e) => write(rows.map((r, j) => (j === i ? [e.target.value, r[1]] : r)))} />
          <Input disabled={disabled} style={{ width: '65%' }} placeholder="值" value={typeof x === 'object' && x !== null ? JSON.stringify(x) : x ?? ''} onChange={(e) => write(rows.map((r, j) => (j === i ? [r[0], typeof r[1] === 'number' && e.target.value !== '' && !isNaN(e.target.value) ? Number(e.target.value) : e.target.value] : r)))} />
          {!disabled && <Button icon={<DeleteOutlined />} onClick={() => write(rows.filter((_, j) => j !== i))} />}
        </Space.Compact>
      ))}
      {!disabled && <Button size="small" icon={<PlusOutlined />} onClick={() => write([...rows, [`key${rows.length + 1}`, '']])}>加一项</Button>}
    </div>
  )
}

/** Array of flat objects as an editable table; columns are the union of keys. */
function TableEditor({ value, onChange, disabled }) {
  const [newCol, setNewCol] = useState('')
  const keys = [...new Set(value.flatMap((r) => Object.keys(r || {})))]
  const setCell = (i, k, x) => onChange(value.map((r, j) => (j === i ? { ...r, [k]: x } : r)))
  const columns = [
    ...keys.map((k) => ({ title: k, dataIndex: k, render: (x, _, i) => <Input size="small" disabled={disabled} value={typeof x === 'object' && x !== null ? JSON.stringify(x) : x ?? ''} onChange={(e) => setCell(i, k, e.target.value)} /> })),
    ...(disabled ? [] : [{ title: '', width: 40, render: (_, __, i) => <Button size="small" type="text" danger icon={<DeleteOutlined />} onClick={() => onChange(value.filter((_, j) => j !== i))} /> }]),
  ]
  return (
    <div>
      <Table rowKey={(_, i) => i} size="small" pagination={false} columns={columns} dataSource={value} locale={{ emptyText: '空' }} scroll={{ x: true }} />
      {!disabled && (
        <Space style={{ marginTop: 6 }} wrap>
          <Button size="small" icon={<PlusOutlined />} onClick={() => onChange([...value, Object.fromEntries(keys.map((k) => [k, '']))])}>加一行</Button>
          <Space.Compact size="small">
            <Input size="small" style={{ width: 120 }} placeholder="新列名" value={newCol} onChange={(e) => setNewCol(e.target.value)} />
            <Button size="small" disabled={!newCol.trim() || keys.includes(newCol.trim())} onClick={() => { const c = newCol.trim(); onChange((value.length ? value : [{}]).map((r) => ({ ...r, [c]: '' }))); setNewCol('') }}>加列</Button>
          </Space.Compact>
        </Space>
      )}
    </div>
  )
}

/** Any JSON, edited as text; applied only while it parses. */
function JsonBox({ value, onChange, disabled }) {
  const [text, setText] = useState(() => JSON.stringify(value ?? null, null, 2))
  const [err, setErr] = useState('')
  useEffect(() => { if (!err) setText(JSON.stringify(value ?? null, null, 2)) }, [value]) // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <div>
      <Input.TextArea disabled={disabled} value={text} autoSize={{ minRows: 3, maxRows: 16 }} spellCheck={false}
        style={{ fontFamily: '"SF Mono", ui-monospace, Menlo, Consolas, monospace', fontSize: 12.5, borderColor: err ? '#FF3B30' : undefined }}
        onChange={(e) => { setText(e.target.value); try { onChange(JSON.parse(e.target.value)); setErr('') } catch (x) { setErr(x.message) } }} />
      {err && <div style={{ color: '#FF3B30', fontSize: 12, marginTop: 4 }}>JSON 有错,没生效:{err}</div>}
    </div>
  )
}
