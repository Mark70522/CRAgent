import { useEffect, useState } from 'react'
import { Alert, Button, Card, Input, Modal, Space, Table, Tag, Typography, App } from 'antd'
import { useNavigate } from 'react-router-dom'
import http from '../../api/request'

/**
 * Feed the system your historical change requests, see them grouped by service + title pattern,
 * and turn a group into a template that both the "new change" page and Copilot's create-cr use.
 */
export default function HistoryPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [groups, setGroups] = useState([])
  const [loading, setLoading] = useState(false)
  const [numbers, setNumbers] = useState('')
  const [records, setRecords] = useState('')
  const [result, setResult] = useState(null)
  const [naming, setNaming] = useState(null)   // group being saved as template
  const [name, setName] = useState('')

  async function load() { setLoading(true); try { setGroups(await http.get('/history/groups')) } catch (e) { message.error(e.message) } finally { setLoading(false) } }
  useEffect(() => { load() }, []) // eslint-disable-line react-hooks/exhaustive-deps

  async function doImport() {
    const nums = numbers.split(/[\s,;]+/).map((s) => s.trim()).filter(Boolean)
    let recs = []
    if (records.trim()) {
      try { const parsed = JSON.parse(records); recs = Array.isArray(parsed) ? parsed : parsed.records || parsed.result || [parsed] }
      catch (e) { message.error('记录 JSON 解析失败:' + e.message); return }
    }
    if (!nums.length && !recs.length) { message.info('贴单号或 JSON'); return }
    setLoading(true)
    try { const r = await http.post('/history/import', { numbers: nums, records: recs }); setResult(r); setNumbers(''); setRecords(''); load() }
    catch (e) { message.error(e.message) } finally { setLoading(false) }
  }
  async function saveTemplate() {
    try { const r = await http.post('/history/template', { groupKey: naming.key, name }); message.success(`模板已写入 ${r.file}`); setNaming(null); load() }
    catch (e) { message.error(e.message) }
  }

  const columns = [
    { title: '服务', dataIndex: 'service', width: 160 },
    { title: '标题模式', dataIndex: 'pattern', ellipsis: true, render: (v) => <span style={{ fontFamily: 'Consolas, monospace', fontSize: 12 }}>{v}</span> },
    { title: '数量', dataIndex: 'count', width: 70 },
    { title: '最近一张', width: 220, render: (_, g) => <a onClick={() => navigate(`/changes/${encodeURIComponent(g.latestNumber)}`)}>{g.latestNumber}</a> },
    { title: '一致的字段', width: 260, render: (_, g) => <span style={{ fontSize: 12 }}>{Object.entries(g.commonFields).slice(0, 4).map(([k, v]) => <Tag key={k} title={`${k}=${v} (${g.fieldAgreement[k]}%)`}>{k}: {String(v).slice(0, 18)}</Tag>)}{Object.keys(g.commonFields).length > 4 && <span style={{ color: '#999' }}>+{Object.keys(g.commonFields).length - 4}</span>}</span> },
    { title: '模板', width: 200, render: (_, g) => g.templateName ? <Space size={4}><Tag color="green">{g.templateName}</Tag><Button size="small" type="text" onClick={() => navigate(`/templates?name=${encodeURIComponent(g.templateName)}`)}>编辑</Button></Space> : <Button size="small" onClick={() => { setNaming(g); setName('') }}>存成模板</Button> },
  ]

  return (
    <div>
      <Typography.Title level={4} style={{ marginTop: 0 }}>历史变更单</Typography.Title>
      <Card size="small" title="导入" style={{ marginBottom: 12 }} extra={<span style={{ color: '#999', fontSize: 12 }}>两种都行;导入只读接口,不写任何东西</span>}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 2fr', gap: 12 }}>
          <div>
            <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>按单号读取(逗号、空格或换行分隔),走 get-change</div>
            <Input.TextArea rows={6} value={numbers} onChange={(e) => setNumbers(e.target.value)} placeholder={'CHG0012345\nCHG0012346'} />
          </div>
          <div>
            <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>或者贴接口导出的 JSON(数组,每个对象一张单,task 放在 "tasks")</div>
            <Input.TextArea rows={6} value={records} onChange={(e) => setRecords(e.target.value)} placeholder='[{"number":"CHG0012345","business_service":"Order Portal","short_description":"...","tasks":[...]}]' style={{ fontFamily: 'Consolas, monospace', fontSize: 12 }} />
          </div>
        </div>
        <Space style={{ marginTop: 10 }}>
          <Button type="primary" onClick={doImport} loading={loading}>导入</Button>
          {result && <span style={{ fontSize: 12, color: '#888' }}>单号:{result.byNumber.imported.length} 成功 / {result.byNumber.failed.length} 失败 · JSON:{result.byRecord.imported.length} 成功 / {result.byRecord.failed.length} 失败</span>}
        </Space>
        {result && (result.byNumber.failed.length > 0 || result.byRecord.failed.length > 0) && <Alert style={{ marginTop: 10 }} type="warning" message={<ul style={{ margin: 0, paddingLeft: 18 }}>{[...result.byNumber.failed, ...result.byRecord.failed].map((f, i) => <li key={i}>{f}</li>)}</ul>} />}
      </Card>
      <Card size="small" title="按服务和标题模式分组" extra={<span style={{ color: '#999', fontSize: 12 }}>服务器名、日期、数字已抹掉;一组里 ≥60% 一致的字段会成为模板默认值</span>}>
        <Table rowKey="key" size="small" loading={loading} columns={columns} dataSource={groups} pagination={{ pageSize: 25 }} locale={{ emptyText: '还没有历史单。上面导入几张就有分组了。' }}
          expandable={{ expandedRowRender: (g) => <div style={{ fontSize: 12 }}><div style={{ color: '#888' }}>单号:{g.numbers.join(', ')}</div><div style={{ marginTop: 4 }}>最近标题:{g.latestTitle}</div><div style={{ marginTop: 4 }}>{Object.entries(g.commonFields).map(([k, v]) => <div key={k}><code>{k}</code> = {String(v)} <span style={{ color: '#999' }}>({g.fieldAgreement[k]}%)</span></div>)}</div></div> }} />
      </Card>
      <Modal title="存成模板" open={!!naming} onOk={saveTemplate} onCancel={() => setNaming(null)} okText="写入 knowledge/templates">
        {naming && <div style={{ marginBottom: 10, color: '#666' }}>{naming.service} · {naming.pattern} · {naming.count} 张</div>}
        <Input placeholder="模板名(留空按服务和模式自动起)" value={name} onChange={(e) => setName(e.target.value)} />
        <div style={{ fontSize: 12, color: '#999', marginTop: 8 }}>写入后在「模板」页可以改 JSON;新建变更单和 Copilot 的 create-cr 立刻能选到。</div>
      </Modal>
    </div>
  )
}
