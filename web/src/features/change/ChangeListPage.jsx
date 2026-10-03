import { useEffect, useState } from 'react'
import { Button, Card, Input, Select, Space, Table, Tag, Typography, App } from 'antd'
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import JsonPanel from '../../components/JsonPanel'
import { changeApi } from './changeApi'

/** Every change request this machine has read, created or updated (the local ledger). Expand a row for its JSON. */
export default function ChangeListPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [rows, setRows] = useState([])
  const [detail, setDetail] = useState({})
  const [loading, setLoading] = useState(false)
  const [q, setQ] = useState('')
  const [state, setState] = useState('')

  async function load() {
    setLoading(true)
    try { setRows(await changeApi.list({ q, state })) } catch (e) { message.error(e.message) } finally { setLoading(false) }
  }
  useEffect(() => { load() }, []) // eslint-disable-line react-hooks/exhaustive-deps

  async function expand(expanded, r) {
    if (!expanded || detail[r.id]) return
    try { setDetail({ ...detail, [r.id]: await changeApi.get(r.id) }) } catch (e) { message.error(e.message) }
  }

  const states = [...new Set(rows.map((r) => r.state).filter(Boolean))].map((s) => ({ value: s, label: s }))
  const columns = [
    { title: '单号', dataIndex: 'id', width: 150, render: (v) => <a onClick={() => navigate(`/changes/${encodeURIComponent(v)}`)}>{v}</a> },
    { title: '标题', dataIndex: 'title', ellipsis: true },
    { title: '状态', dataIndex: 'state', width: 120, render: (v) => v ? <Tag>{v}</Tag> : '' },
    { title: '硬规则', dataIndex: 'passed', width: 90, render: (v) => v === false ? <Tag color="red">不合规</Tag> : v === true ? <Tag color="green">通过</Tag> : '' },
    { title: '最近', dataIndex: 'fetchedAt', width: 160, render: (v, r) => <span style={{ fontSize: 12, color: '#888' }}>{({ get: '读', create: '建', update: '改' })[r.action] || r.action} {v}</span> },
    {
      title: '操作', width: 170, fixed: 'right',
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" onClick={() => navigate(`/changes/${encodeURIComponent(r.id)}?edit=1`)}>更新</Button>
          <Button size="small" type="text" onClick={() => navigate(`/changes/${encodeURIComponent(r.id)}`)}>详情</Button>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 16 }} wrap>
        <Typography.Title level={4} style={{ margin: 0 }}>变更单</Typography.Title>
        <Space>
          <Input placeholder="按单号从接口读取,如 CHG0012345" allowClear style={{ width: 280 }} onPressEnter={(e) => e.target.value.trim() && navigate(`/changes/${encodeURIComponent(e.target.value.trim())}?live=1`)} suffix={<SearchOutlined style={{ color: '#bbb' }} />} />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/changes/new')}>新建变更单</Button>
        </Space>
      </Space>
      <Card size="small" styles={{ body: { padding: 12 } }}>
        <Space style={{ marginBottom: 12 }} wrap>
          <Input placeholder="筛:单号、标题、状态" value={q} onChange={(e) => setQ(e.target.value)} onPressEnter={load} allowClear style={{ width: 240 }} />
          <Select placeholder="状态" value={state || undefined} onChange={(v) => setState(v || '')} allowClear options={states} style={{ width: 160 }} />
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          <span style={{ color: '#999', fontSize: 12 }}>{rows.length} 张 · 展开一行看 JSON</span>
        </Space>
        <Table rowKey="id" size="small" columns={columns} dataSource={rows} loading={loading} scroll={{ x: 900 }} pagination={{ pageSize: 20, showTotal: (t) => `共 ${t} 张` }}
          expandable={{ onExpand: expand, expandedRowRender: (r) => detail[r.id] ? <JsonPanel fields={detail[r.id].fields} raw={detail[r.id].raw} open /> : <span style={{ color: '#999' }}>读取中 …</span> }}
          locale={{ emptyText: '还没有。在上面输单号读一张,或者新建。' }} />
      </Card>
    </div>
  )
}
