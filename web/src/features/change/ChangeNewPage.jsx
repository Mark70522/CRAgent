import { useEffect, useState } from 'react'
import { Alert, Button, Card, Checkbox, Input, Segmented, Select, Space, Steps, Tag, Typography, App } from 'antd'
import JsonEditor from '../../components/JsonEditor'
import { useNavigate } from 'react-router-dom'
import FieldsEditor from '../../components/FieldsEditor'
import TasksEditor from '../../components/TasksEditor'
import { toPayload } from '../../components/fieldTypes'
import { changeApi, formsApi } from './changeApi'

/** Template -> draft -> edit fields and tasks -> validate -> create. Same gate as Copilot: hard-rule errors block creation. */
export default function ChangeNewPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [source, setSource] = useState('template')
  const [template, setTemplate] = useState()
  const [copyFrom, setCopyFrom] = useState('')
  const [copyLive, setCopyLive] = useState(false)
  const [raw, setRaw] = useState({ fields: {}, tasks: [] })
  const [templates, setTemplates] = useState([])
  const [catalog, setCatalog] = useState([])
  const [taskCatalog, setTaskCatalog] = useState([])
  const [notes, setNotes] = useState([])
  const [fields, setFields] = useState(null)
  const [tasks, setTasks] = useState([])
  const [result, setResult] = useState(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    changeApi.templates().then((t) => { setTemplates(t); if (t[0]) setTemplate(t[0].name) }).catch((e) => message.error(e.message))
    formsApi.get('change').then((c) => setCatalog(c.fields)).catch(() => {})
    formsApi.get('task').then((c) => setTaskCatalog(c.fields)).catch(() => {})
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  function load(f, t, n = []) { setFields(f || {}); setTasks(t || []); setNotes(n); setResult(null) }

  async function fromTemplate() {
    setBusy(true)
    try { const d = await changeApi.draft({ template }); load(d.draft.fields, d.draft.tasks, d.notes) }
    catch (e) { message.error(e.message) } finally { setBusy(false) }
  }

  /** Copy an old CR: its editable fields and tasks; read-only keys (number, state, sys_id ...) stay behind. */
  async function fromOld() {
    const number = copyFrom.trim()
    if (!number) return
    setBusy(true)
    try {
      const rec = await changeApi.get(number, copyLive)
      const drop = (cat, extra) => new Set([...cat.filter((f) => f.readonly).map((f) => f.key), ...extra])
      const strip = (o, keys) => Object.fromEntries(Object.entries(o || {}).filter(([k]) => !keys.has(k) && !k.startsWith('sys_')))
      const f = strip(rec.fields, drop(catalog, ['number', 'state', 'approval', 'close_code']))
      const t = (Array.isArray(rec.tasks) ? rec.tasks : []).map((x) => strip(x && x.fields ? x.fields : x, drop(taskCatalog, ['number', 'state'])))
      if (!Object.keys(f).length) message.warning(`${number} 没有可复制的字段`)
      load(f, t, [`从 ${number} 复制,计划时间和标题请按这次改`])
    } catch (e) { message.error(e.message) } finally { setBusy(false) }
  }

  function fromJson() {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) { message.error('要一个 JSON 对象'); return }
    if (raw.fields && typeof raw.fields === 'object') load(raw.fields, Array.isArray(raw.tasks) ? raw.tasks : [])
    else load(raw, [])
  }
  async function validate() {
    setBusy(true)
    try { setResult(await changeApi.validate(fields, tasks)) } catch (e) { message.error(e.message) } finally { setBusy(false) }
  }
  async function create() {
    setBusy(true)
    try { const r = await changeApi.create(toPayload(catalog, fields), tasks.map((t) => toPayload(taskCatalog, t))); message.success(`已创建 ${r.number}`); navigate(`/changes/${encodeURIComponent(r.number)}`) }
    catch (e) { message.error(e.message) } finally { setBusy(false) }
  }

  const step = !fields ? 0 : result ? (result.passed ? 3 : 2) : 1

  return (
    <div>
      <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 16 }} wrap>
        <Typography.Title level={4} style={{ margin: 0 }}>新建变更单</Typography.Title>
        <Steps size="small" current={step} style={{ maxWidth: 640 }} items={[{ title: '起草' }, { title: '改字段和 task' }, { title: '校验' }, { title: '创建' }]} />
      </Space>

      <Card size="small" title="起草" style={{ marginBottom: 12 }} extra={<Segmented size="small" value={source} onChange={setSource} options={[{ value: 'template', label: '用模板' }, { value: 'copy', label: '复制旧单' }, { value: 'json', label: '直接 JSON' }, { value: 'copilot', label: '让 Copilot 建' }]} />}>
        {source === 'template' && (
          <Space wrap>
            <Select style={{ minWidth: 360 }} placeholder="选一个模板" value={template} onChange={setTemplate} options={templates.map((t) => ({ value: t.name, label: `${t.name} · ${t.description || ''}` }))} />
            <Button type="primary" onClick={fromTemplate} loading={busy} disabled={!template}>生成草稿</Button>
          </Space>
        )}
        {source === 'copy' && (
          <Space wrap>
            <Input style={{ width: 220 }} placeholder="旧单号,如 CHG0031234" value={copyFrom} onChange={(e) => setCopyFrom(e.target.value)} onPressEnter={fromOld} />
            <Checkbox checked={copyLive} onChange={(e) => setCopyLive(e.target.checked)}>从接口实时读(不勾就读本地留存)</Checkbox>
            <Button type="primary" onClick={fromOld} loading={busy} disabled={!copyFrom.trim()}>复制过来</Button>
            <span style={{ color: '#999', fontSize: 12 }}>单号、状态、审批这类只读字段不带过来;时间记得改</span>
          </Space>
        )}
        {source === 'json' && (
          <>
            <JsonEditor value={raw} onChange={setRaw} rows={12} />
            <Space style={{ marginTop: 8 }}>
              <Button type="primary" onClick={fromJson}>用这个 JSON</Button>
              <span style={{ color: '#999', fontSize: 12 }}>{'格式 { "fields": {…}, "tasks": […] },也可以只贴 fields 那一层'}</span>
            </Space>
          </>
        )}
        {source === 'copilot' && (
          <Typography.Paragraph style={{ margin: 0 }}>
            在 IntelliJ 的 Copilot Chat 里直接说要建什么单,比如:
            <Typography.Text code copyable>按 app-release 模板起草一张变更单,照 CHG0031234 的写法</Typography.Text>
            <br />Copilot 起草、校验,你确认后它调用 create_change 创建。建好的单在 <a onClick={() => navigate('/changes')}>变更单列表</a> 里。
          </Typography.Paragraph>
        )}
        {notes.length > 0 && <Alert style={{ marginTop: 12 }} type="warning" showIcon message={<ul style={{ margin: 0, paddingLeft: 18 }}>{notes.map((n) => <li key={n}>{n}</li>)}</ul>} />}
      </Card>

      {fields && (
        <>
          <Card size="small" title="字段" extra={<span style={{ color: '#999', fontSize: 12 }}>把 &lt;TODO: …&gt; 都换成实际内容</span>} style={{ marginBottom: 12 }}>
            <FieldsEditor catalog={catalog} value={fields} onChange={setFields} hide={['number', 'state', 'approval', 'close_code', 'sys_updated_on']} />
          </Card>
          <Card size="small" title={`Tasks · ${tasks.length}`} style={{ marginBottom: 12 }}>
            <TasksEditor catalog={taskCatalog} value={tasks} onChange={setTasks} />
          </Card>
          <Card size="small" title="发出去的请求体" style={{ marginBottom: 12 }} extra={<span style={{ color: '#999', fontSize: 12 }}>create-change 收到的就是这个,按 cr-agent.yml 的 body 模板和 field-map 渲染</span>}>
            <JsonEditor value={{ fields, tasks }} onChange={(v) => { if (v && typeof v === 'object') { if (v.fields) setFields(v.fields); if (Array.isArray(v.tasks)) setTasks(v.tasks) } }} rows={14} />
          </Card>
          <Card size="small" title="校验与创建">
            <Space wrap>
              <Button onClick={validate} loading={busy}>校验</Button>
              <Button type="primary" onClick={create} loading={busy} disabled={!result || !result.passed}>创建到 ServiceNow</Button>
              <span style={{ color: '#888', fontSize: 12 }}>先校验,硬规则通过了才能创建;创建后提交审批仍由你完成。</span>
            </Space>
            {result && (
              <div style={{ marginTop: 12 }}>
                <Tag color={result.passed ? 'green' : 'red'}>{result.passed ? '硬规则通过' : `${result.errorCount} 个 error,${result.warningCount} 个 warning`}</Tag>
                {result.violations.map((v, i) => (
                  <div key={i} style={{ marginTop: 6 }}><Tag color={v.severity === 'error' ? 'red' : 'orange'}>{v.ruleId}</Tag><span style={{ color: '#888', marginRight: 6 }}>{v.field}</span>{v.message}{v.suggestion && <div style={{ color: '#888', marginLeft: 8 }}>建议:{v.suggestion}</div>}</div>
                ))}
              </div>
            )}
          </Card>
        </>
      )}
    </div>
  )
}
