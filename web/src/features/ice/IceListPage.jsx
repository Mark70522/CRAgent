import { useEffect, useState } from 'react'
import { Button, Card, Input, Space, Table, Tag, Tooltip, Typography, App } from 'antd'
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import JsonPanel from '../../components/JsonPanel'
import { iceApi } from './iceApi'

/**
 * Every ICE record this machine has touched (one per change request). Three things you do with ICE are
 * right here: 登记 (top), 更新 (per row, opens the record in edit mode), 查分数 (per row, reads live).
 * Expand a row to see the JSON.
 */
export default function IceListPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [rows, setRows] = useState([])
  const [detail, setDetail] = useState({})   // id -> full record (for the JSON panel)
  const [loading, setLoading] = useState(false)
  const [scoring, setScoring] = useState('')
  const [q, setQ] = useState('')

  async function load() {
    setLoading(true)
    try { setRows(await iceApi.list({ q })) } catch (e) { message.error(e.message) } finally { setLoading(false) }
  }
  useEffect(() => { load() }, []) // eslint-disable-line react-hooks/exhaustive-deps

  async function score(id) {
    setScoring(id)
    try { const r = await iceApi.score(id); message.success(r.score ? `${id} 分数 ${r.score}` : '接口没返回分数'); if (r.hint) message.warning(r.hint, 8); load() }
    catch (e) { message.error(e.message) } finally { setScoring('') }
  }
  async function expand(expanded, r) {
    if (!expanded || detail[r.id]) return
    try { setDetail({ ...detail, [r.id]: await iceApi.get(r.id) }) } catch (e) { message.error(e.message) }
  }

  const columns = [
    { title: 'ICE 号', dataIndex: 'id', width: 170, render: (v) => <a onClick={() => navigate(`/ices/${encodeURIComponent(v)}`)}>{v}</a> },
    { title: '变更单', dataIndex: 'changeNumber', width: 150, render: (v) => v ? <a onClick={() => navigate(`/changes/${encodeURIComponent(v)}`)}>{v}</a> : <Tag color="orange">未关联</Tag> },
    { title: '标题', dataIndex: 'title', ellipsis: true },
    { title: '状态', dataIndex: 'state', width: 110, render: (v) => v ? <Tag>{v}</Tag> : '' },
    { title: '分数', dataIndex: 'score', width: 110, render: (v, r) => v != null && v !== '' ? <Tooltip title={`评于 ${r.scoreAt || ''}`}><b>{v}</b></Tooltip> : <span style={{ color: '#bbb' }}>未查</span> },
    { title: '最近', dataIndex: 'fetchedAt', width: 150, render: (v, r) => <span style={{ fontSize: 12, color: '#888' }}>{({ get: '读', create: '登记', update: '更新', score: '评分' })[r.action] || r.action} {v}</span> },
    {
      title: '操作', width: 230, fixed: 'right',
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" onClick={() => navigate(`/ices/${encodeURIComponent(r.id)}?edit=1`)}>更新</Button>
          <Button size="small" loading={scoring === r.id} onClick={() => score(r.id)}>查分数</Button>
          <Button size="small" type="text" onClick={() => navigate(`/ices/${encodeURIComponent(r.id)}`)}>详情</Button>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 16 }} wrap>
        <Typography.Title level={4} style={{ margin: 0 }}>ICE</Typography.Title>
        <Space>
          <Input placeholder="按 ICE 号从接口读取" allowClear style={{ width: 240 }} onPressEnter={(e) => e.target.value.trim() && navigate(`/ices/${encodeURIComponent(e.target.value.trim())}?live=1`)} suffix={<SearchOutlined style={{ color: '#bbb' }} />} />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/ices/new')}>登记 ICE</Button>
        </Space>
      </Space>
      <Card size="small" styles={{ body: { padding: 12 } }}>
        <Space style={{ marginBottom: 12 }}>
          <Input placeholder="筛:ICE 号、变更单、标题" value={q} onChange={(e) => setQ(e.target.value)} onPressEnter={load} allowClear style={{ width: 260 }} />
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          <span style={{ color: '#999', fontSize: 12 }}>{rows.length} 条 · 展开一行看 JSON</span>
        </Space>
        <Table rowKey="id" size="small" columns={columns} dataSource={rows} loading={loading} scroll={{ x: 1000 }} pagination={{ pageSize: 20, showTotal: (t) => `共 ${t} 条` }}
          expandable={{ onExpand: expand, expandedRowRender: (r) => detail[r.id] ? <JsonPanel fields={detail[r.id].fields} raw={detail[r.id].raw} open /> : <span style={{ color: '#999' }}>读取中 …</span> }}
          locale={{ emptyText: '还没有 ICE 记录。点「登记 ICE」从一张变更单登记,或在上面输 ICE 号读取。' }} />
      </Card>
    </div>
  )
}
