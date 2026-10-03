import { useEffect, useState } from 'react'
import { Button, Input, Space, Table, Tag, Typography, App } from 'antd'
import { useNavigate } from 'react-router-dom'
import { iceApi } from './iceApi'

/** ICE records this machine has touched, one per change request. */
export default function IceListPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [rows, setRows] = useState([])
  const [loading, setLoading] = useState(false)
  const [q, setQ] = useState('')

  async function load() {
    setLoading(true)
    try { setRows(await iceApi.list({ q })) } catch (e) { message.error(e.message) } finally { setLoading(false) }
  }
  useEffect(() => { load() }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const columns = [
    { title: 'ICE 号', dataIndex: 'id', width: 160, render: (v) => <a onClick={() => navigate(`/ices/${encodeURIComponent(v)}`)}>{v}</a> },
    { title: '变更单', dataIndex: 'changeNumber', width: 150, render: (v) => v ? <a onClick={() => navigate(`/changes/${encodeURIComponent(v)}`)}>{v}</a> : <Tag color="orange">未关联</Tag> },
    { title: '标题', dataIndex: 'title', ellipsis: true },
    { title: '分数', dataIndex: 'score', width: 90, render: (v, r) => v != null ? <span title={r.scoreAt}>{v}</span> : '' },
    { title: '最近', dataIndex: 'action', width: 80, render: (v) => ({ get: '读', create: '建', update: '改', score: '评分' }[v] || v) },
    { title: '时间', dataIndex: 'fetchedAt', width: 150 },
  ]

  return (
    <div>
      <Typography.Title level={4} style={{ marginTop: 0 }}>ICE</Typography.Title>
      <Space style={{ marginBottom: 16 }} wrap>
        <Input.Search placeholder="输 ICE 号从接口读取" enterButton="读取" onSearch={(v) => v.trim() && navigate(`/ices/${encodeURIComponent(v.trim())}?live=1`)} style={{ width: 320 }} />
        <Button type="primary" onClick={() => navigate('/ices/new')}>登记 ICE</Button>
        <Input placeholder="筛" value={q} onChange={(e) => setQ(e.target.value)} onPressEnter={load} allowClear style={{ width: 200 }} />
        <Button onClick={load}>筛选</Button>
      </Space>
      <Table rowKey="id" size="small" columns={columns} dataSource={rows} loading={loading} pagination={{ pageSize: 20 }} locale={{ emptyText: '还没有' }} />
    </div>
  )
}
