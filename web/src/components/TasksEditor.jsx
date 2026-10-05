import { useRef, useState } from 'react'
import { Button, Segmented, Space, Table, Tooltip, App } from 'antd'
import { DeleteOutlined, FieldTimeOutlined, HolderOutlined, PlusOutlined } from '@ant-design/icons'
import FieldControl from './FieldControl'
import JsonEditor from './JsonEditor'
import { guessType } from './fieldTypes'

import { moveRow, reflowTimes, renumber } from './tasksLogic'

// textarea has no width: the description column takes what is left.
const WIDTH = { text: 150, json: 280, table: 360, object: 280, datetime: 178, date: 140, time: 110, boolean: 70, number: 100, reference: 240, list: 200, select: 150, multiselect: 200, email: 180, url: 180 }

/**
 * The tasks of a change as a table driven by the task catalog (knowledge/forms/task.json): time pickers,
 * description, any other field type. Drag a row by its handle to reorder; order is renumbered after every
 * move, add and delete. "按顺序排时间" lays the tasks back to back from the first start, keeping each duration.
 */
export default function TasksEditor({ catalog = [], value = [], onChange }) {
  const { message } = App.useApp()
  const [mode, setMode] = useState('table')
  const [armed, setArmed] = useState(null)
  const [over, setOver] = useState(null)
  const from = useRef(null)

  const editable = catalog.filter((f) => !f.readonly && f.key !== 'order' && f.section !== '关闭').sort((a, b) => (a.order ?? 999) - (b.order ?? 999))
  const known = new Set(catalog.map((f) => f.key))
  const extra = [...new Set(value.flatMap((t) => Object.keys(t)))].filter((k) => !known.has(k)).map((k) => ({ key: k, label: k, type: guessType(k, value.find((t) => k in t)?.[k]) }))
  const fields = [...editable, ...extra]
  const dt = fields.filter((f) => f.type === 'datetime')
  const startKey = (dt.find((f) => /start/i.test(f.key)) || dt[0])?.key
  const endKey = (dt.find((f) => /end/i.test(f.key)) || dt[1])?.key

  const write = (list) => onChange(renumber(list))
  const setCell = (i, k, x) => onChange(value.map((t, j) => (j === i ? { ...t, [k]: x } : t)))
  const move = (a, b) => { const n = moveRow(value, a, b); if (n !== value) onChange(n) }
  const reset = () => { from.current = null; setArmed(null); setOver(null) }

  function reflow() {
    const next = reflowTimes(value, startKey, endKey)
    if (!next) { message.warning('先给第一个 task 选开始时间'); return }
    onChange(next)
  }

  const columns = [
    { title: '', width: 34, render: (_, __, i) => (
      <Tooltip title="按住拖动排序"><span className="task-handle" onMouseDown={() => setArmed(i)} onMouseUp={() => setArmed(null)}><HolderOutlined /></span></Tooltip>
    ) },
    { title: '顺序', dataIndex: 'order', width: 60, render: (v) => <span style={{ color: '#6E6E73', fontVariantNumeric: 'tabular-nums' }}>{v}</span> },
    ...fields.map((f) => ({
      title: <span>{f.label || f.key}{f.required && <span style={{ color: '#FF3B30' }}> *</span>}</span>,
      dataIndex: f.key,
      width: f.type === 'textarea' ? undefined : f.key === 'short_description' ? 200 : WIDTH[f.type] || 150,
      onCell: () => (f.type === 'textarea' ? { style: { minWidth: 200 } } : {}),
      render: (_, t, i) => <FieldControl f={f} value={t[f.key]} onChange={(x) => setCell(i, f.key, x)} size="small" compact />,
    })),
    { title: '', width: 44, fixed: 'right', render: (_, __, i) => <Button size="small" type="text" danger icon={<DeleteOutlined />} onClick={() => write(value.filter((_, j) => j !== i))} /> },
  ]

  return (
    <div>
      <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 10 }} wrap>
        <Segmented size="small" value={mode} onChange={setMode} options={[{ value: 'table', label: '表格' }, { value: 'json', label: 'JSON' }]} />
        {mode === 'table' && (
          <Space>
            {startKey && endKey && <Button size="small" icon={<FieldTimeOutlined />} onClick={reflow}>按顺序排时间</Button>}
            <Button size="small" icon={<PlusOutlined />} onClick={() => write([...value, {}])}>加一行</Button>
          </Space>
        )}
      </Space>
      {mode === 'table'
        ? <Table rowKey={(_, i) => i} size="small" pagination={false} columns={columns} dataSource={value} scroll={{ x: 'max-content' }} locale={{ emptyText: '没有 task,点「加一行」' }}
            onRow={(_, i) => ({
              draggable: armed === i,
              className: [over === i && from.current !== null && from.current !== i ? (from.current < i ? 'task-drop-below' : 'task-drop-above') : '', from.current === i ? 'task-dragging' : ''].join(' '),
              onDragStart: (e) => { from.current = i; e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', String(i)) },
              onDragOver: (e) => { if (from.current === null) return; e.preventDefault(); if (over !== i) setOver(i) },
              onDrop: (e) => { e.preventDefault(); move(from.current, i); reset() },
              onDragEnd: reset,
            })} />
        : <JsonEditor value={value} onChange={(v) => Array.isArray(v) && onChange(v)} rows={14} />}
      <div style={{ color: '#86868B', fontSize: 12, marginTop: 8 }}>拖左边的 ⠿ 调顺序,顺序号自动按 10、20、30… 重排。列来自「字段目录 → task」。</div>
    </div>
  )
}
