import { useEffect, useState } from 'react'
import { Alert, Button, Card, Input, Space, Statistic, Table, Tag, Typography, App } from 'antd'
import { useNavigate, useSearchParams } from 'react-router-dom'
import JsonEditor from '../../components/JsonEditor'
import { changeApi } from '../change/changeApi'
import { iceApi } from './iceApi'

/** Read an ICE score: by ICE id, or by CR number (ICE is 1:1 with the CR). Shows the score, the whole response and the history. */
export default function IceScorePage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [input, setInput] = useState(params.get('id') || '')
  const [res, setRes] = useState(null)
  const [busy, setBusy] = useState(false)
  const [recent, setRecent] = useState([])
  const [scoring, setScoring] = useState('')

  const loadRecent = () => iceApi.list().then(setRecent).catch(() => {})
  useEffect(() => { loadRecent(); if (params.get('id')) check(params.get('id')) }, []) // eslint-disable-line react-hooks/exhaustive-deps

  /** CR number -> the ICE linked to it; anything else is taken as the ICE id. */
  async function resolve(v) {
    if (!/^CHG/i.test(v)) return v
    const cr = await changeApi.get(v).catch(() => null)
    if (cr?.iceId) return cr.iceId
    throw new Error(`${v} 还没有关联的 ICE(本地没记录)。直接填 ICE 号查,或者先去登记 ICE`)
  }

  async function check(v) {
    const q = (v ?? input).trim()
    if (!q) return
    setBusy(true); setScoring(q)
    try {
      const id = await resolve(q)
      const r = await iceApi.score(id)
      setRes({ ...r, id, asked: q })
      if (r.hint) message.warning(r.hint, 8)
      loadRecent()
    } catch (e) { message.error(e.message) } finally { setBusy(false); setScoring('') }
  }

  const columns = [
    { title: 'ICE', dataIndex: 'id', width: 150, render: (v) => <a onClick={() => navigate(`/ices/${encodeURIComponent(v)}`)}>{v}</a> },
    { title: 'CR', dataIndex: 'changeNumber', width: 150 },
    { title: '标题', dataIndex: 'title', ellipsis: true },
    { title: '分数', dataIndex: 'score', width: 90, render: (v) => v != null && v !== '' ? <b>{v}</b> : <span style={{ color: '#bbb' }}>未查</span> },
    { title: '查于', dataIndex: 'scoreAt', width: 150, render: (v) => <span style={{ fontSize: 12, color: '#888' }}>{v}</span> },
    { title: '', width: 90, render: (_, r) => <Button size="small" loading={scoring === r.id} onClick={() => { setInput(r.id); check(r.id) }}>查分数</Button> },
  ]

  return (
    <div>
      <Typography.Title level={4} style={{ marginTop: 0 }}>查 ICE 分数</Typography.Title>
      <Card size="small" style={{ marginBottom: 12 }}>
        <Space wrap>
          <Input autoFocus style={{ width: 280 }} placeholder="ICE 号,或 CR 单号(CHG…)" value={input} onChange={(e) => setInput(e.target.value)} onPressEnter={() => check()} />
          <Button type="primary" onClick={() => check()} loading={busy} disabled={!input.trim()}>查分数</Button>
          <span style={{ color: '#999', fontSize: 12 }}>每次都调 ice-score 接口读最新的,结果记进这张 ICE 的分数历史</span>
        </Space>
      </Card>

      {res && (
        <Card size="small" style={{ marginBottom: 12 }} title={<span>ICE {res.id}{res.asked !== res.id && <Tag style={{ marginLeft: 8 }}>由 {res.asked} 找到</Tag>}</span>}
          extra={<a onClick={() => navigate(`/ices/${encodeURIComponent(res.id)}`)}>打开这张 ICE</a>}>
          <Space size="large" align="start" wrap>
            <Statistic title="分数" value={res.score || '—'} suffix={<span style={{ fontSize: 12, color: '#999' }}>{res.scoreAt}</span>} />
            {(res.scores || []).length > 1 && <div style={{ color: '#888', fontSize: 12, maxWidth: 520 }}>历史:{res.scores.map((s) => `${s.t} → ${s.score}`).join(' · ')}</div>}
          </Space>
          {res.hint && <Alert style={{ marginTop: 12 }} type="warning" showIcon message={res.hint} />}
          <div style={{ marginTop: 12, color: '#888', fontSize: 12 }}>接口返回的内容</div>
          <JsonEditor value={res.raw ?? res.fields ?? {}} readonly rows={10} />
        </Card>
      )}

      <Card size="small" title="本地的 ICE" styles={{ body: { padding: 0 } }}>
        <Table rowKey="id" size="small" columns={columns} dataSource={recent} pagination={{ pageSize: 10 }} locale={{ emptyText: '还没有' }} />
      </Card>
    </div>
  )
}
