import { useEffect, useState } from 'react'
import { Alert, Button, Card, Col, Form, Input, Row, Segmented, Select, Space, Steps, Table, Tag, Typography, App } from 'antd'
import JsonEditor from '../../components/JsonEditor'
import { useNavigate } from 'react-router-dom'
import FieldsEditor from '../../components/FieldsEditor'
import { changeApi, formsApi } from './changeApi'

/** Template -> draft -> edit fields and tasks -> validate -> create. Same gate as Copilot: hard-rule errors block creation. */
export default function ChangeNewPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [form] = Form.useForm()
  const [templates, setTemplates] = useState([])
  const [catalog, setCatalog] = useState([])
  const [taskCatalog, setTaskCatalog] = useState([])
  const [notes, setNotes] = useState([])
  const [fields, setFields] = useState(null)
  const [tasks, setTasks] = useState([])
  const [result, setResult] = useState(null)
  const [busy, setBusy] = useState(false)
  const [taskMode, setTaskMode] = useState('table')

  useEffect(() => {
    changeApi.templates().then((t) => { setTemplates(t); if (t[0]) form.setFieldValue('template', t[0].name) }).catch((e) => message.error(e.message))
    formsApi.get('change').then((c) => setCatalog(c.fields)).catch(() => {})
    formsApi.get('task').then((c) => setTaskCatalog(c.fields)).catch(() => {})
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  async function draft() {
    let v; try { v = await form.validateFields() } catch { return }
    setBusy(true); setResult(null)
    try {
      const d = await changeApi.draft(v)
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

  const step = !fields ? 0 : result ? (result.passed ? 3 : 2) : 1
  const taskKeys = tasks.length ? [...new Set(tasks.flatMap((t) => Object.keys(t)))] : taskCatalog.filter((f) => !f.readonly).map((f) => f.key).slice(0, 5)
  const taskColumns = [
    ...taskKeys.map((k) => ({ title: (taskCatalog.find((f) => f.key === k) || {}).label || k, dataIndex: k, render: (v, _, i) => <Input size="small" value={v ?? ''} onChange={(e) => setTasks(tasks.map((t, j) => j === i ? { ...t, [k]: e.target.value } : t))} /> })),
    { title: '', width: 50, render: (_, __, i) => <Button size="small" type="text" danger onClick={() => setTasks(tasks.filter((_, j) => j !== i))}>×</Button> },
  ]

  return (
    <div>
      <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 16 }} wrap>
        <Typography.Title level={4} style={{ margin: 0 }}>新建变更单</Typography.Title>
        <Steps size="small" current={step} style={{ maxWidth: 640 }} items={[{ title: '起草' }, { title: '改字段和 task' }, { title: '校验' }, { title: '创建' }]} />
      </Space>

      <Card size="small" title="起草" style={{ marginBottom: 12 }}>
        <Form form={form} layout="vertical" requiredMark={false}>
          <Row gutter={16}>
            <Col xs={24} md={8}><Form.Item name="template" label="模板" rules={[{ required: true, message: '选一个模板' }]}><Select placeholder="模板" options={templates.map((t) => ({ value: t.name, label: `${t.name} · ${t.description || ''}` }))} /></Form.Item></Col>
            <Col xs={24} md={8}><Form.Item name="servers" label="服务器(逗号分隔,查清单)" rules={[{ required: true, message: '至少一台' }]}><Input placeholder="srv-app-01, srv-app-02" /></Form.Item></Col>
            <Col xs={24} md={8}><Form.Item name="start" label="计划开始" rules={[{ required: true, message: '要有开始时间' }]}><Input placeholder="2026-10-11 01:00:00" /></Form.Item></Col>
            <Col xs={24} md={16}><Form.Item name="summary" label="一句话摘要(进标题)" rules={[{ required: true, message: '写一句' }]}><Input placeholder="2026-10 Windows monthly security patches" /></Form.Item></Col>
            <Col xs={24} md={8} style={{ display: 'flex', alignItems: 'flex-end' }}><Form.Item><Button type="primary" onClick={draft} loading={busy}>{fields ? '重新生成草稿' : '生成草稿'}</Button></Form.Item></Col>
          </Row>
        </Form>
        {notes.length > 0 && <Alert type="warning" showIcon message={<ul style={{ margin: 0, paddingLeft: 18 }}>{notes.map((n) => <li key={n}>{n}</li>)}</ul>} />}
      </Card>

      {fields && (
        <>
          <Card size="small" title="字段" extra={<span style={{ color: '#999', fontSize: 12 }}>把 &lt;TODO: …&gt; 都换成实际内容</span>} style={{ marginBottom: 12 }}>
            <FieldsEditor catalog={catalog} value={fields} onChange={setFields} hide={['number', 'state', 'approval', 'close_code', 'sys_updated_on']} />
          </Card>
          <Card size="small" title="Tasks" style={{ marginBottom: 12 }} extra={<Space><Segmented size="small" value={taskMode} onChange={setTaskMode} options={[{ value: 'table', label: '表格' }, { value: 'json', label: 'JSON' }]} />{taskMode === 'table' && <Button size="small" onClick={() => setTasks([...tasks, {}])}>加一行</Button>}</Space>}>
            {taskMode === 'table'
              ? <Table rowKey={(_, i) => i} size="small" pagination={false} columns={taskColumns} dataSource={tasks} locale={{ emptyText: '没有 task' }} />
              : <JsonEditor value={tasks} onChange={(v) => Array.isArray(v) && setTasks(v)} rows={12} />}
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
