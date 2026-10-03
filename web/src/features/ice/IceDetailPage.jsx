import { useEffect, useState } from 'react'
import { Alert, Button, Card, Space, Statistic, Tag, Typography, App } from 'antd'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import JsonForm, { diffFields } from '../../components/JsonForm'
import JsonPanel from '../../components/JsonPanel'
import { iceApi } from './iceApi'
import { formsApi } from '../change/changeApi'

/** One ICE record: read, edit and save, score with history. */
export default function IceDetailPage() {
  const { message } = App.useApp()
  const { id } = useParams()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const [catalog, setCatalog] = useState([])
  const [rec, setRec] = useState(null)
  const [err, setErr] = useState('')
  const [editing, setEditing] = useState(false)
  const [value, setValue] = useState({})
  const [busy, setBusy] = useState(false)

  async function load(live) {
    setErr(''); setBusy(true)
    try { const r = await iceApi.get(id, live); setRec(r); setValue(r.fields || {}); setEditing(params.get('edit') === '1' && !editing ? true : false) }
    catch (e) { setErr(e.message); if (!live) setRec(null) }
    finally { setBusy(false) }
  }
  useEffect(() => { formsApi.get('ice').then((c) => setCatalog(c.fields)).catch(() => {}); load(params.get('live') === '1') }, [id]) // eslint-disable-line react-hooks/exhaustive-deps

  async function save() {
    const d = diffFields(rec.fields, value)
    if (!Object.keys(d).length) { message.info('没有改动'); return }
    setBusy(true)
    try { await iceApi.update(id, d); message.success(`已保存 ${Object.keys(d).length} 个字段`); await load(false) }
    catch (e) { message.error(e.message) } finally { setBusy(false) }
  }
  async function score() {
    setBusy(true)
    try { const r = await iceApi.score(id); message.success(r.score ? `分数 ${r.score}` : '接口没返回分数'); if (r.hint) message.warning(r.hint, 8); await load(false) }
    catch (e) { message.error(e.message) } finally { setBusy(false) }
  }
  async function learn(keys) {
    const sub = {}; keys.forEach((k) => { sub[k] = value[k] })
    try { const r = await formsApi.learn('ice', sub); message.success(`收进目录:${r.added.join(', ')}`); setCatalog((await formsApi.get('ice')).fields) }
    catch (e) { message.error(e.message) }
  }

  return (
    <div>
      <Space align="start" style={{ width: '100%', justifyContent: 'space-between', marginBottom: 12 }} wrap>
        <div>
          <Typography.Title level={4} style={{ margin: 0 }}>ICE {id} <span style={{ fontWeight: 400, color: '#666' }}>{rec?.title}</span></Typography.Title>
          <Space style={{ marginTop: 6 }} wrap>
            {rec?.changeNumber ? <Tag color="blue"><a onClick={() => navigate(`/changes/${encodeURIComponent(rec.changeNumber)}`)}>CR {rec.changeNumber}</a></Tag> : rec && <Tag color="orange">未关联变更单</Tag>}
            {rec?.fetchedAt && <Tag>{({ get: '读于', create: '建于', update: '改于', score: '评分于' })[rec.action] || '读于'} {rec.fetchedAt}</Tag>}
            {rec?.task && <Tag>任务 {rec.task.id}</Tag>}
          </Space>
        </div>
        <Space>
          {!editing && rec && <Button onClick={score} loading={busy}>查分数</Button>}
          {!editing && <Button onClick={() => load(true)} loading={busy}>从接口刷新</Button>}
          {!editing && rec && <Button type="primary" onClick={() => setEditing(true)}>编辑</Button>}
          {editing && <Button type="primary" onClick={save} loading={busy}>保存到 ICE</Button>}
          {editing && <Button onClick={() => { setValue(rec.fields); setEditing(false) }}>取消</Button>}
        </Space>
      </Space>

      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 12 }} action={<Button size="small" onClick={() => load(true)}>从接口读</Button>} />}

      {rec && (
        <>
          {(rec.scores || []).length > 0 && (
            <Card size="small" style={{ marginBottom: 12 }}>
              <Space size="large">
                <Statistic title="最新分数" value={rec.score} suffix={<span style={{ fontSize: 12, color: '#999' }}>{rec.scoreAt}</span>} />
                <div style={{ color: '#888', fontSize: 12 }}>历史:{rec.scores.map((s) => `${s.t} → ${s.score}`).join(' · ')}</div>
              </Space>
            </Card>
          )}
          <Card size="small" style={{ marginBottom: 12 }}>
            <JsonForm catalog={catalog} value={value} original={rec.fields} onChange={setValue} readonly={!editing} onLearn={learn} hide={['id']} />
          </Card>
          <JsonPanel fields={rec.fields} raw={rec.raw} />
        </>
      )}
    </div>
  )
}
