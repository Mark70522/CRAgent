import { useEffect, useState } from 'react'
import { Button, Card, Input, List, Space, Tag, Typography, App } from 'antd'
import { useNavigate } from 'react-router-dom'

/**
 * "Update" entry: type a number, read the latest from the interface and open it in edit mode.
 * Below, the records this machine touched recently, one click to edit.
 */
export default function OpenForEdit({ title, base, placeholder, list }) {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [id, setId] = useState('')
  const [recent, setRecent] = useState([])

  useEffect(() => { list().then((r) => setRecent(r.slice(0, 10))).catch((e) => message.error(e.message)) }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const open = (v, live) => v && navigate(`${base}/${encodeURIComponent(v.trim())}?edit=1${live ? '&live=1' : ''}`)

  return (
    <div>
      <Typography.Title level={4} style={{ marginTop: 0 }}>{title}</Typography.Title>
      <Card size="small" style={{ marginBottom: 12 }}>
        <Space wrap>
          <Input autoFocus style={{ width: 280 }} placeholder={placeholder} value={id} onChange={(e) => setId(e.target.value)} onPressEnter={() => open(id, true)} />
          <Button type="primary" onClick={() => open(id, true)} disabled={!id.trim()}>从接口读出来改</Button>
          <span style={{ color: '#999', fontSize: 12 }}>读最新的一版再改,保存时只发改动的字段</span>
        </Space>
      </Card>
      <Card size="small" title="最近处理过的" styles={{ body: { padding: 0 } }}>
        <List size="small" dataSource={recent} locale={{ emptyText: '还没有' }} renderItem={(r) => (
          <List.Item style={{ padding: '8px 12px' }} actions={[<a key="e" onClick={() => open(r.id, false)}>改本地这版</a>, <a key="l" onClick={() => open(r.id, true)}>读最新再改</a>]}>
            <Space><a onClick={() => open(r.id, false)}>{r.id}</a><span>{r.title}</span>{r.state && <Tag>{r.state}</Tag>}<span style={{ color: '#999', fontSize: 12 }}>{r.fetchedAt}</span></Space>
          </List.Item>
        )} />
      </Card>
    </div>
  )
}
