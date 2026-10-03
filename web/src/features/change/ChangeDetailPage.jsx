import { useEffect, useState } from 'react'
import { Alert, Button, Card, Space, Tag, Typography, App } from 'antd'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { diffFields } from '../../components/JsonForm'
import FieldsEditor from '../../components/FieldsEditor'
import JsonPanel from '../../components/JsonPanel'
import TaskPanel from './TaskPanel'
import { changeApi, formsApi } from './changeApi'

/** One change request: read (local copy or live), edit and save only what changed, its tasks, its ICE. */
export default function ChangeDetailPage() {
  const { message } = App.useApp()
  const { number } = useParams()
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
    try { const r = await changeApi.get(number, live); setRec(r); setValue(r.fields || {}); setEditing(params.get('edit') === '1' && !editing ? true : false) }
    catch (e) { setErr(e.message); if (!live) setRec(null) }
    finally { setBusy(false) }
  }
  useEffect(() => { formsApi.get('change').then((c) => setCatalog(c.fields)).catch(() => {}); load(params.get('live') === '1') }, [number]) // eslint-disable-line react-hooks/exhaustive-deps

  async function save() {
    const d = diffFields(rec.fields, value)
    if (!Object.keys(d).length) { message.info('没有改动'); return }
    setBusy(true)
    try { await changeApi.update(number, d); message.success(`已保存 ${Object.keys(d).length} 个字段`); await load(false) }
    catch (e) { message.error(e.message) } finally { setBusy(false) }
  }
  async function learn(keys) {
    const sub = {}; keys.forEach((k) => { sub[k] = value[k] })
    try { const r = await formsApi.learn('change', sub); message.success(`收进目录:${r.added.join(', ')}`); setCatalog((await formsApi.get('change')).fields) }
    catch (e) { message.error(e.message) }
  }

  const f = rec?.fields || {}
  const vios = rec?.violations || []

  return (
    <div>
      <Space align="start" style={{ width: '100%', justifyContent: 'space-between', marginBottom: 12 }} wrap>
        <div>
          <Typography.Title level={4} style={{ margin: 0 }}>{number} <span style={{ fontWeight: 400, color: '#666' }}>{f.short_description}</span></Typography.Title>
          <Space style={{ marginTop: 6 }} wrap>
            {f.state && <Tag>{f.state}</Tag>}
            {f.approval && <Tag color={/approved/i.test(f.approval) ? 'green' : /rejected/i.test(f.approval) ? 'red' : undefined}>审批 {f.approval}</Tag>}
            {rec && (rec.passed ? <Tag color="green">硬规则通过</Tag> : <Tag color="red">{vios.filter((v) => v.severity === 'error').length} 处不合规</Tag>)}
            {rec?.fetchedAt && <Tag color="default">{({ get: '读于', create: '建于', update: '改于' })[rec.action] || '读于'} {rec.fetchedAt}</Tag>}
            {rec?.iceId ? <Tag color="blue"><a onClick={() => navigate(`/ices/${encodeURIComponent(rec.iceId)}`)}>ICE {rec.iceId}</a></Tag>
              : rec && <Tag color="orange"><a onClick={() => navigate(`/ices/new?number=${encodeURIComponent(number)}`)}>未登 ICE,去登记</a></Tag>}
            {rec?.task && <Tag>任务 {rec.task.id}</Tag>}
          </Space>
        </div>
        <Space>
          {!editing && <Button onClick={() => load(true)} loading={busy}>从接口刷新</Button>}
          {!editing && rec && <Button type="primary" onClick={() => setEditing(true)}>编辑</Button>}
          {editing && <Button type="primary" onClick={save} loading={busy}>保存到 ServiceNow</Button>}
          {editing && <Button onClick={() => { setValue(rec.fields); setEditing(false) }}>取消</Button>}
        </Space>
      </Space>

      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 12 }} action={<Button size="small" onClick={() => load(true)}>从接口读</Button>} />}

      {rec && (
        <>
          <Card size="small" style={{ marginBottom: 12 }}>
            <FieldsEditor catalog={catalog} value={value} original={rec.fields} onChange={setValue} readonly={!editing} onLearn={learn} hide={['number']} />
          </Card>
          {vios.length > 0 && (
            <Card size="small" title="硬规则校验" style={{ marginBottom: 12 }}>
              {vios.map((v, i) => (
                <div key={i} style={{ marginBottom: 6 }}>
                  <Tag color={v.severity === 'error' ? 'red' : 'orange'}>{v.ruleId}</Tag>
                  <span style={{ color: '#888', marginRight: 6 }}>{v.field}</span>{v.message}
                  {v.suggestion && <div style={{ color: '#888', marginLeft: 8 }}>建议:{v.suggestion}</div>}
                </div>
              ))}
            </Card>
          )}
          <Card size="small" style={{ marginBottom: 12 }}>
            <TaskPanel number={number} recordTasks={rec.tasks || []} />
          </Card>
          <JsonPanel fields={rec.fields} raw={rec.raw} />
        </>
      )}
    </div>
  )
}
