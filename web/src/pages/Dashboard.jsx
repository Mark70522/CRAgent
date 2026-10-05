import { useEffect, useState } from 'react'
import { Alert, Card, Col, Row, Statistic, Tag, Typography } from 'antd'
import { Link } from 'react-router-dom'
import http from '../api/request'

/** What this installation is wired to, in one glance: the same data as the `status` tool. */
export default function Dashboard() {
  const [s, setS] = useState(null)
  const [records, setRecords] = useState([])
  const [err, setErr] = useState('')

  useEffect(() => {
    http.get('/status').then(setS).catch((e) => setErr(e.message))
    http.get('/records').then(setRecords).catch(() => {})
  }, [])

  const count = (kind) => records.filter((r) => r.kind === kind).length
  const check = s?.configCheck

  return (
    <div>
      <Typography.Title level={4} style={{ marginTop: 0 }}>总览</Typography.Title>
      {err && <Alert type="error" message={err} style={{ marginBottom: 16 }} />}
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={12} lg={6}><Card><Statistic title="留存的变更单" value={count('change')} /><Link to="/changes">查看 ›</Link></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="留存的 ICE" value={count('ice')} /><Link to="/ices">查看 ›</Link></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="跟踪的 task" value={count('task')} /><Link to="/ledger">台账 ›</Link></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="配置" value={check ? (check.ok ? '正常' : `${check.problems.length} 个问题`) : '…'} valueStyle={{ color: check && !check.ok ? '#FF3B30' : undefined }} /></Card></Col>
      </Row>
      {s && (
        <Card title="接口与配置" size="small">
          <p><Tag>ServiceNow</Tag> {s.serviceNow}</p>
          <p><Tag>ICE</Tag> {s.ice}</p>
          <p><Tag>工具</Tag> {s.tools?.mode} · {s.tools?.enabledTools}</p>
          {check?.problems?.length > 0 && (
            <Alert type="warning" showIcon message="cr-agent.yml 里有问题" description={<ul style={{ margin: 0, paddingLeft: 18 }}>{check.problems.map((p) => <li key={p}>{p}</li>)}</ul>} />
          )}
          {check?.notes?.length > 0 && (
            <ul style={{ color: '#888', marginTop: 12, paddingLeft: 18 }}>{check.notes.map((n) => <li key={n}>{n}</li>)}</ul>
          )}
        </Card>
      )}
    </div>
  )
}
