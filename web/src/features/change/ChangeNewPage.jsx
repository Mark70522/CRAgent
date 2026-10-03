import { useEffect, useState } from 'react'
import { Alert, Button, Card, Input, Select, Space, Table, Tag, Typography, App } from 'antd'
import { useNavigate } from 'react-router-dom'
import JsonForm from '../../components/JsonForm'
import { changeApi, formsApi } from './changeApi'

/** Template -> draft -> edit fields and tasks -> validate -> create. Same gate as Copilot: hard-rule errors block creation. */
export default function ChangeNewPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [templates, setTemplates] = useState([])
  const [catalog, setCatalog] = useState([])
  const [taskCatalog, setTaskCatalog] = useState([])
  const [tpl, setTpl] = useState('')
  const [servers, setServers] = useState('')
  const [start, setStart] = useState('')
  const [summary, setSummary] = useState('')
  const [notes, setNotes] = useState([])
  const [fields, setFields] = useState(null)
  const [tasks, setTasks] = useState([])
  const [result, setResult] = useState(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    changeApi.templates().then((t) => { setTemplates(t); if (t[0]) setTpl(t[0].name) }).catch((e) => message.error(e.message))
    formsApi.get('change').then((c) => setCatalog(c.fields)).catch(() => {})
    formsApi.get('task').then((c) => setTaskCatalog(c.fields)).catch(() => {})
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  async function draft() {
    setBusy(true); setResult(null)
    try {
      const d = await changeApi.draft({ template: tpl, servers, start, summary })
      setFields(d.draft.fields || {}); setTasks(d.draft.tasks || []); setNotes(d.notes || [])
    } catch (e) { message.error(e.message) } finally { setBusy(false) }
  }
  async function validate() {
    setBusy(true)
    try { setResult(await changeApi.validate(fields, tasks)) } catch (e) { message.error(e.message) } finally { setBusy(false) }
  }
  async function create() {
    setBusy(true)
    try { const r = await changeApi.create(fields, tasks); message.success(`已创建 ${r.number}`); navigate(`/changes/${encodeURIComponent(r.number)}`) }
    catch (e) { message.error(e.message) } finally { setBusy(false) }
  }

  const taskKeys = tasks.length ? [...new Set(tasks.flatMap((t) => Object.keys(t)))] : taskCatalog.filter((f) => !f.readonly).map((f) => f.key).slice(0, 5)
  const taskColumns = [
    ...taskKeys.map((k) => ({ title: (taskCatalog.find((f) => f.key === k) || {}).label || k, dataIndex: k, render: (v, _, i) => <Input size="small" value={v ?? ''} onChange={(e) => { const n = tasks.map((t, j) => j === i ? { ...t, [k]: e.target.value } : t); setTasks(n) }} /> })),
    { title: '', width: 60, render: (_, __, i) => <Button size="small" danger onClick={() => setTasks(tasks.filter((_, j) => j !== i))}>×</Button> },
  ]

  return (
    <div>
      <Typography.Title level={4} style={{ marginTop: 0 }}>新建变更单</Typography.Title>
      <Card size="small" title="1. 起草" style={{ marginBottom: 12 }}>
        <Space wrap>
          <Select value={tpl || undefined} onChange={setTpl} style={{ width: 260 }} placeholder="模板" options={templates.map((t) => ({ value: t.name, label: `${t.name} · ${t.description || ''}` }))} />
          <Input value={servers} onChange={(e) => setServers(e.target.value)} placeholder="服务器,逗号分隔" style={{ width: 260 }} />
          <Input value={start} onChange={(e) => setStart(e.target.value)} placeholder="计划开始 yyyy-MM-dd HH:mm:ss" style={{ width: 230 }} />
          <Input value={summary} onChange={(e) => setSummary(e.target.value)} placeholder="一句话摘要" style={{ width: 300 }} />
          <Button type="primary" onClick={draft} loading={busy} disabled={!tpl}>生成草稿</Button>
        </Space>
        {notes.length > 0 && <Alert style={{ marginTop: 12 }} type="warning" showIcon message={<ul style={{ margin: 0, paddingLeft: 18 }}>{notes.map((n) => <li key={n}>{n}</li>)}</ul>} />}
      </Card>

      {fields && (
        <>
          <Card size="small" title="2. 字段 · 把 <TODO: …> 都换成实际内容" style={{ marginBottom: 12 }}>
            <JsonForm catalog={catalog} value={fields} onChange={setFields} hide={['number', 'state', 'approval', 'close_code', 'sys_updated_on']} />
          </Card>
          <Card size="small" title="3. Tasks" style={{ marginBottom: 12 }} extra={<Button size="small" onClick={() => setTasks([...tasks, {}])}>加一行</Button>}>
            <Table rowKey={(_, i) => i} size="small" pagination={false} columns={taskColumns} dataSource={tasks} />
          </Card>
          <Card size="small" title="4. 校验与创建">
            <Space>
              <Button onClick={validate} loading={busy}>校验</Button>
              <Button type="primary" onClick={create} loading={busy} disabled={result && !result.passed}>创建到 ServiceNow</Button>
              <span style={{ color: '#888' }}>创建后提交审批仍由你完成;error 没清零后端也会拦。</span>
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
