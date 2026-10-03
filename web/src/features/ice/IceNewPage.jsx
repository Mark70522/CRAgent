import { useEffect, useState } from 'react'
import { Alert, Button, Card, Input, Space, Typography, App } from 'antd'
import { useNavigate, useSearchParams } from 'react-router-dom'
import FieldsEditor from '../../components/FieldsEditor'
import { iceApi } from './iceApi'
import { formsApi } from '../change/changeApi'

/** Register a change request in ICE: fields derived by ice.from-change, edited here, then create-ice. */
export default function IceNewPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [catalog, setCatalog] = useState([])
  const [number, setNumber] = useState(params.get('number') || '')
  const [fields, setFields] = useState(null)
  const [hint, setHint] = useState('')
  const [empty, setEmpty] = useState([])
  const [busy, setBusy] = useState(false)

  useEffect(() => { formsApi.get('ice').then((c) => setCatalog(c.fields)).catch(() => {}) }, [])

  async function draft() {
    if (!number.trim()) return
    setBusy(true)
    try {
      const d = await iceApi.draft(number.trim())
      const f = d.fields && Object.keys(d.fields).length ? d.fields : { change_number: number.trim(), title: d.crTitle || '' }
      setFields(f); setHint(d.hint || ''); setEmpty(d.emptyFields || [])
    } catch (e) { message.error(e.message) } finally { setBusy(false) }
  }
  async function create() {
    setBusy(true)
    try { const r = await iceApi.create(number.trim(), fields); message.success(`已登记 ICE ${r.id}`); navigate(`/ices/${encodeURIComponent(r.id)}`) }
    catch (e) { message.error(e.message) } finally { setBusy(false) }
  }

  return (
    <div>
      <Typography.Title level={4} style={{ marginTop: 0 }}>登记 ICE</Typography.Title>
      <Card size="small" title="1. 从哪张变更单来" style={{ marginBottom: 12 }}>
        <Space>
          <Input value={number} onChange={(e) => setNumber(e.target.value)} placeholder="CHG0012345" style={{ width: 240 }} onPressEnter={draft} />
          <Button type="primary" onClick={draft} loading={busy}>按 from-change 算出字段</Button>
          <Button onClick={() => { setFields({ change_number: number.trim(), title: '' }); setHint(''); setEmpty([]) }}>直接填(表单 / JSON)</Button>
        </Space>
        {hint && <Alert style={{ marginTop: 12 }} type="warning" showIcon message={hint} />}
        {empty.length > 0 && <Alert style={{ marginTop: 12 }} type="info" showIcon message={`空着的字段:${empty.join(', ')},CR 里没有对应内容,自己填`} />}
      </Card>
      {fields && (
        <Card size="small" title="2. 字段" extra={<Button type="primary" onClick={create} loading={busy}>创建到 ICE</Button>}>
          <FieldsEditor catalog={catalog} value={fields} onChange={setFields} hide={['id', 'score']} />
        </Card>
      )}
    </div>
  )
}
