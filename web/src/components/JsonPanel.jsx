import { Collapse } from 'antd'

/** The record as JSON, two panels: the fields the program keeps, and the interface's raw response. */
export default function JsonPanel({ fields, raw, open = false }) {
  const pre = (o) => <pre style={{ fontSize: 12, margin: 0, maxHeight: 420, overflow: 'auto', background: '#F5F5F7', padding: 12, borderRadius: 10 }}>{JSON.stringify(o ?? {}, null, 2)}</pre>
  return (
    <Collapse size="small" defaultActiveKey={open ? ['f'] : []} items={[
      { key: 'f', label: '字段 JSON', children: pre(fields) },
      { key: 'r', label: '接口原始返回', children: pre(raw) },
    ]} />
  )
}
