import { useEffect, useState } from 'react'
import { Button, Input, Space, Tag } from 'antd'

/**
 * Edit a record as the JSON that will be sent, verbatim. Whatever you type is the request body;
 * nothing is renamed or dropped. Invalid JSON is flagged and not applied.
 */
export default function JsonEditor({ value, onChange, rows = 18, readonly = false }) {
  const [text, setText] = useState(() => JSON.stringify(value ?? {}, null, 2))
  const [err, setErr] = useState('')

  // outside changes (form mode, reload) refresh the text unless the user is mid-edit with an error
  useEffect(() => { if (!err) setText(JSON.stringify(value ?? {}, null, 2)) }, [value]) // eslint-disable-line react-hooks/exhaustive-deps

  const apply = (t) => {
    setText(t)
    try { const o = JSON.parse(t); setErr(''); onChange && onChange(o) }
    catch (e) { setErr(e.message) }
  }
  const pretty = () => { try { setText(JSON.stringify(JSON.parse(text), null, 2)); setErr('') } catch (e) { setErr(e.message) } }

  return (
    <div>
      <Input.TextArea value={text} onChange={(e) => apply(e.target.value)} rows={rows} readOnly={readonly} spellCheck={false}
        style={{ fontFamily: 'Consolas, Menlo, monospace', fontSize: 12.5, borderColor: err ? '#ff4d4f' : undefined }} />
      <Space style={{ marginTop: 6 }}>
        {err ? <Tag color="red">JSON 有错:{err}</Tag> : <Tag color="green">JSON 合法 · 发出去的就是这个</Tag>}
        {!readonly && <Button size="small" onClick={pretty}>格式化</Button>}
        {!readonly && <Button size="small" onClick={() => navigator.clipboard?.writeText(text)}>复制</Button>}
      </Space>
    </div>
  )
}
