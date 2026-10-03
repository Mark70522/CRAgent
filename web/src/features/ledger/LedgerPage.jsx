import { useEffect, useState } from 'react'
import { Input, Segmented, Space, Table, Tag, Typography, App } from 'antd'
import { useNavigate } from 'react-router-dom'
import http from '../../api/request'

/** Everything read, created or updated on this machine: cockpit/records/. Read-only here; open a row to act on it. */
export default function LedgerPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [rows, setRows] = useState([])
  const [kind, setKind] = useState('all')
  const [q, setQ] = useState('')

  useEffect(() => { http.get('/records').then(setRows).catch((e) => message.error(e.message)) }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const list = rows.filter((r) => (kind === 'all' || r.kind === kind) && (!q || [r.id, r.title, r.state, r.changeNumber].join(' ').toLowerCase().includes(q.toLowerCase())))
  const open = (r) => r.kind === 'change' ? navigate(`/changes/${encodeURIComponent(r.id)}`) : r.kind === 'ice' ? navigate(`/ices/${encodeURIComponent(r.id)}`) : r.changeNumber && navigate(`/changes/${encodeURIComponent(r.changeNumber)}`)

  const columns = [
    { title: '类型', dataIndex: 'kind', width: 80, render: (v) => <Tag color={{ change: 'blue', ice: 'purple', task: 'default' }[v]}>{{ change: 'CR', ice: 'ICE', task: 'task' }[v] || v}</Tag> },
    { title: '编号', dataIndex: 'id', width: 170, render: (v, r) => <a onClick={() => open(r)}>{v}</a> },
    { title: '标题', dataIndex: 'title', ellipsis: true },
    { title: '变更单', dataIndex: 'changeNumber', width: 140 },
    { title: '状态', dataIndex: 'state', width: 110 },
    { title: '最近', dataIndex: 'action', width: 80 },
    { title: '时间', dataIndex: 'fetchedAt', width: 150 },
    { title: '首次', dataIndex: 'firstSeen', width: 150 },
  ]

  return (
    <div>
      <Typography.Title level={4} style={{ marginTop: 0 }}>本地台账</Typography.Title>
      <Space style={{ marginBottom: 12 }} wrap>
        <Segmented value={kind} onChange={setKind} options={[{ value: 'all', label: '全部' }, { value: 'change', label: 'CR' }, { value: 'ice', label: 'ICE' }, { value: 'task', label: 'task' }]} />
        <Input placeholder="筛" value={q} onChange={(e) => setQ(e.target.value)} allowClear style={{ width: 240 }} />
        <span style={{ color: '#888' }}>{list.length} 条 · 文件在 cockpit/records/</span>
      </Space>
      <Table rowKey={(r) => r.kind + ':' + r.id} size="small" columns={columns} dataSource={list} pagination={{ pageSize: 25 }} />
    </div>
  )
}
