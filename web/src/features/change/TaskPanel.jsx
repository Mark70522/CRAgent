import { useEffect, useState } from 'react'
import { Button, Collapse, Input, Modal, Popconfirm, Space, Table, Tag, Typography, App } from 'antd'
import FieldsEditor from '../../components/FieldsEditor'
import { changeApi, formsApi } from './changeApi'

/**
 * The tasks of one change: what the interface returned with the record, and the ones created / cancelled / closed
 * from here (tracked in the local ledger). Three separate interface calls, as agreed: create-task, cancel-task, close-task.
 */
export default function TaskPanel({ number, recordTasks = [] }) {
  const { message } = App.useApp()
  const [catalog, setCatalog] = useState([])
  const [tracked, setTracked] = useState([])
  const [adding, setAdding] = useState(false)
  const [draft, setDraft] = useState({})
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState({})

  async function load() {
    try { const r = await changeApi.tasks(number); setTracked(r.tracked || []) } catch (e) { message.error(e.message) }
  }
  useEffect(() => { formsApi.get('task').then((c) => setCatalog(c.fields)).catch(() => {}); load() }, [number]) // eslint-disable-line react-hooks/exhaustive-deps

  async function add() {
    setBusy(true)
    try { await changeApi.addTask(number, draft); message.success('task 已创建'); setAdding(false); setDraft({}); load() }
    catch (e) { message.error(e.message) } finally { setBusy(false) }
  }
  async function act(kind, id) {
    try {
      const fields = note[id] ? { close_notes: note[id] } : {}
      if (kind === 'cancel') await changeApi.cancelTask(id, fields); else await changeApi.closeTask(id, fields)
      message.success(kind === 'cancel' ? '已取消' : '已关闭'); load()
    } catch (e) { message.error(e.message) }
  }

  const idOf = (t) => t.sys_id || t.id || t.number || ''
  const cols = recordTasks.length ? Object.keys(recordTasks[0]).slice(0, 7) : []
  const recordColumns = cols.map((c) => ({ title: c, dataIndex: c, ellipsis: true }))

  const trackedColumns = [
    { title: 'ID', dataIndex: 'id', width: 160 },
    { title: '标题', render: (_, r) => r.fields?.short_description || r.title || '' },
    { title: '状态', width: 110, render: (_, r) => r.fields?.state ? <Tag>{r.fields.state}</Tag> : '' },
    { title: '最近', dataIndex: 'action', width: 80, render: (v) => ({ create: '建', cancel: '取消', close: '关闭' }[v] || v) },
    { title: '时间', dataIndex: 'fetchedAt', width: 150 },
    {
      title: '操作', width: 260,
      render: (_, r) => (
        <Space>
          <Input size="small" placeholder="说明(可选)" value={note[r.id] || ''} onChange={(e) => setNote({ ...note, [r.id]: e.target.value })} style={{ width: 120 }} />
          <Popconfirm title="取消这个 task?" onConfirm={() => act('cancel', r.id)}><Button size="small" danger>取消</Button></Popconfirm>
          <Popconfirm title="关闭这个 task?" onConfirm={() => act('close', r.id)}><Button size="small">关闭</Button></Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <Space style={{ marginBottom: 8 }}>
        <Typography.Text strong>Tasks</Typography.Text>
        <Button size="small" type="primary" onClick={() => setAdding(true)}>加一个 task</Button>
      </Space>
      {recordTasks.length > 0 && (
        <Collapse size="small" style={{ marginBottom: 12 }} items={[{ key: 'r', label: `接口返回的 task(${recordTasks.length})`, children:
          <Table rowKey={(t, i) => idOf(t) || i} size="small" pagination={false} columns={recordColumns} dataSource={recordTasks} /> }]} />
      )}
      <Table rowKey="id" size="small" pagination={false} columns={trackedColumns} dataSource={tracked}
        locale={{ emptyText: '这里还没有建过、取消过或关闭过的 task' }} />

      <Modal title={`给 ${number} 加一个 task`} open={adding} onOk={add} okText="创建到 ServiceNow" confirmLoading={busy} onCancel={() => setAdding(false)} width={820}>
        <FieldsEditor catalog={catalog} value={draft} onChange={setDraft} hide={['sys_id', 'number', 'state', 'close_notes']} />
      </Modal>
    </div>
  )
}
