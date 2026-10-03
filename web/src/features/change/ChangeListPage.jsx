import { useEffect, useState } from 'react'
import { Button, Input, Select, Space, Table, Tag, Typography, App } from 'antd'
import { useNavigate } from 'react-router-dom'
import { changeApi } from './changeApi'

/** Every change request this machine has read, created or updated (the local ledger), plus "read by number". */
export default function ChangeListPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [rows, setRows] = useState([])
  const [loading, setLoading] = useState(false)
  const [q, setQ] = useState('')
  const [state, setState] = useState('')
  const [number, setNumber] = useState('')

  async function load() {
    setLoading(true)
    try { setRows(await changeApi.list({ q, state })) } catch (e) { message.error(e.message) } finally { setLoading(false) }
  }
  useEffect(() => { load() }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const states = [...new Set(rows.map((r) => r.state).filter(Boolean))].map((s) => ({ value: s, label: s }))

  const columns = [
    { title: '单号', dataIndex: 'id', width: 150, render: (v) => <a onClick={() => navigate(`/changes/${encodeURIComponent(v)}`)}>{v}</a> },
    { title: '标题', dataIndex: 'title', ellipsis: true },
    { title: '状态', dataIndex: 'state', width: 120, render: (v) => v ? <Tag>{v}</Tag> : '' },
    { title: '硬规则', dataIndex: 'passed', width: 90, render: (v) => v === false ? <Tag color="red">不合规</Tag> : v === true ? <Tag color="green">通过</Tag> : '' },
    { title: '最近', dataIndex: 'action', width: 80, render: (v) => ({ get: '读', create: '建', update: '改' }[v] || v) },
    { title: '时间', dataIndex: 'fetchedAt', width: 150 },
  ]

  return (
    <div>
      <Typography.Title level={4} style={{ marginTop: 0 }}>变更单</Typography.Title>
      <Space style={{ marginBottom: 16 }} wrap>
        <Input.Search placeholder="输单号从接口读取,如 CHG0012345" value={number} onChange={(e) => setNumber(e.target.value)} enterButton="读取"
          onSearch={(v) => v.trim() && navigate(`/changes/${encodeURIComponent(v.trim())}?live=1`)} style={{ width: 340 }} />
        <Button type="primary" onClick={() => navigate('/changes/new')}>新建变更单</Button>
      </Space>
      <Space style={{ marginBottom: 12 }} wrap>
        <Input placeholder="筛:单号、标题、状态" value={q} onChange={(e) => setQ(e.target.value)} onPressEnter={load} allowClear style={{ width: 240 }} />
        <Select placeholder="状态" value={state || undefined} onChange={(v) => setState(v || '')} allowClear options={states} style={{ width: 160 }} />
        <Button onClick={load}>筛选</Button>
      </Space>
      <Table rowKey="id" size="small" columns={columns} dataSource={rows} loading={loading} pagination={{ pageSize: 20, showTotal: (t) => `共 ${t} 张` }}
        locale={{ emptyText: '还没有。输单号读一张,或者新建。' }} />
    </div>
  )
}
