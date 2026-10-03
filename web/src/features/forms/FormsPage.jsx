import { useEffect, useState } from 'react'
import { Button, Input, Segmented, Select, Space, Switch, Table, Typography, App } from 'antd'
import { formsApi } from '../change/changeApi'

const TYPES = ['text', 'textarea', 'select', 'datetime', 'number', 'boolean'].map((t) => ({ value: t, label: t }))

/** The field catalogs behind every form (knowledge/forms/*.json): edit labels, types, sections, options; add or drop fields. */
export default function FormsPage() {
  const { message } = App.useApp()
  const [kind, setKind] = useState('change')
  const [fields, setFields] = useState([])
  const [file, setFile] = useState('')
  const [dirty, setDirty] = useState(false)

  async function load(k) {
    try { const c = await formsApi.get(k); setFields(c.fields); setFile(c.file); setDirty(false) } catch (e) { message.error(e.message) }
  }
  useEffect(() => { load(kind) }, [kind]) // eslint-disable-line react-hooks/exhaustive-deps

  const set = (i, patch) => { setFields(fields.map((f, j) => j === i ? { ...f, ...patch } : f)); setDirty(true) }
  const move = (i, d) => { const n = [...fields]; const j = i + d; if (j < 0 || j >= n.length) return; [n[i], n[j]] = [n[j], n[i]]; setFields(n.map((f, k) => ({ ...f, order: k + 1 }))); setDirty(true) }
  async function save() {
    try { await formsApi.save(kind, fields); message.success('已保存'); load(kind) } catch (e) { message.error(e.message) }
  }

  const columns = [
    { title: 'key', dataIndex: 'key', width: 200, render: (v, _, i) => <Input size="small" value={v} onChange={(e) => set(i, { key: e.target.value })} /> },
    { title: '标签', dataIndex: 'label', width: 160, render: (v, _, i) => <Input size="small" value={v || ''} onChange={(e) => set(i, { label: e.target.value })} /> },
    { title: '类型', dataIndex: 'type', width: 120, render: (v, _, i) => <Select size="small" value={v || 'text'} options={TYPES} style={{ width: '100%' }} onChange={(x) => set(i, { type: x })} /> },
    { title: '分组', dataIndex: 'section', width: 110, render: (v, _, i) => <Input size="small" value={v || ''} onChange={(e) => set(i, { section: e.target.value })} /> },
    { title: '选项(逗号)', dataIndex: 'options', render: (v, r, i) => r.type === 'select' ? <Input size="small" value={(v || []).join(',')} onChange={(e) => set(i, { options: e.target.value.split(',').map((s) => s.trim()).filter(Boolean) })} /> : <span style={{ color: '#ccc' }}>—</span> },
    { title: '必填', dataIndex: 'required', width: 70, render: (v, _, i) => <Switch size="small" checked={!!v} onChange={(x) => set(i, { required: x })} /> },
    { title: '只读', dataIndex: 'readonly', width: 70, render: (v, _, i) => <Switch size="small" checked={!!v} onChange={(x) => set(i, { readonly: x })} /> },
    { title: '', width: 130, render: (_, __, i) => <Space size={4}><Button size="small" onClick={() => move(i, -1)}>↑</Button><Button size="small" onClick={() => move(i, 1)}>↓</Button><Button size="small" danger onClick={() => { setFields(fields.filter((_, j) => j !== i)); setDirty(true) }}>×</Button></Space> },
  ]

  return (
    <div>
      <Typography.Title level={4} style={{ marginTop: 0 }}>字段目录</Typography.Title>
      <Space style={{ marginBottom: 12 }} wrap>
        <Segmented value={kind} onChange={setKind} options={[{ value: 'change', label: '变更单' }, { value: 'task', label: 'task' }, { value: 'ice', label: 'ICE' }]} />
        <Button onClick={() => { setFields([...fields, { key: '', label: '', type: 'text', section: '其他', order: fields.length + 1 }]); setDirty(true) }}>加一个字段</Button>
        <Button type="primary" onClick={save} disabled={!dirty}>保存</Button>
        <span style={{ color: '#888' }}>{file}</span>
      </Space>
      <p style={{ color: '#888', marginTop: 0 }}>表单按这里渲染。接口字段名(key)是你们接口的名字;标签、类型、分组只影响显示。接口多返回的字段可以在详情页"收进目录"。</p>
      <Table rowKey={(_, i) => i} size="small" pagination={false} columns={columns} dataSource={fields} />
    </div>
  )
}
