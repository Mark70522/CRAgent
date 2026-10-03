import { useEffect, useState } from 'react'
import { Button, Card, Input, List, Popconfirm, Space, Tag, Typography, App } from 'antd'
import { useSearchParams } from 'react-router-dom'
import http from '../../api/request'

/** The templates under knowledge/templates/, edited as YAML. Both the new-change page and Copilot read these files. */
export default function TemplatesPage() {
  const { message } = App.useApp()
  const [params] = useSearchParams()
  const [list, setList] = useState([])
  const [name, setName] = useState(params.get('name') || '')
  const [yaml, setYaml] = useState('')
  const [dirty, setDirty] = useState(false)

  async function load() { try { const t = await http.get('/templates'); setList(t); if (!name && t[0]) open(t[0].name) } catch (e) { message.error(e.message) } }
  async function open(n) { try { const r = await http.get(`/template-files/${encodeURIComponent(n)}`); setName(n); setYaml(r.yaml); setDirty(false) } catch (e) { message.error(e.message) } }
  useEffect(() => { load(); if (name) open(name) }, []) // eslint-disable-line react-hooks/exhaustive-deps

  async function save() { try { await http.put(`/template-files/${encodeURIComponent(name)}`, { yaml }); message.success('已保存'); setDirty(false); load() } catch (e) { message.error(e.message) } }
  async function remove() { try { await http.delete(`/template-files/${encodeURIComponent(name)}`); message.success('已删除'); setName(''); setYaml(''); load() } catch (e) { message.error(e.message) } }
  function create() { const n = window.prompt('新模板名(字母数字和 -)'); if (!n) return; setName(n); setDirty(true); setYaml(`name: ${n}\ndescription: \nmatch_keywords: []\nfields:\n  type: normal\n  risk: Moderate\nshort_description_pattern: "[CHANGE] {ci_list} - {summary}"\ndefault_duration_minutes: 240\ntasks:\n  - order: 10\n    short_description: Pre-check\n    duration_minutes: 30\n    assignment_group: "{ci_owner_group}"\ndescription_sections:\n  - heading: 变更对象\n    hint: \n`) }

  return (
    <div>
      <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 12 }}>
        <Typography.Title level={4} style={{ margin: 0 }}>模板</Typography.Title>
        <Button onClick={create}>新模板</Button>
      </Space>
      <div style={{ display: 'grid', gridTemplateColumns: '280px 1fr', gap: 12 }}>
        <Card size="small" title="knowledge/templates" styles={{ body: { padding: 0 } }}>
          <List size="small" dataSource={list} renderItem={(t) => (
            <List.Item onClick={() => open(t.name)} style={{ cursor: 'pointer', background: t.name === name ? '#e6f4ff' : undefined, padding: '8px 12px' }}>
              <div><div style={{ fontWeight: 500 }}>{t.name}</div><div style={{ fontSize: 12, color: '#888' }}>{t.description}</div><div style={{ marginTop: 2 }}>{(t.matchKeywords || []).slice(0, 4).map((k) => <Tag key={k} style={{ fontSize: 11 }}>{k}</Tag>)}<span style={{ fontSize: 11, color: '#999' }}>{t.taskCount} task</span></div></div>
            </List.Item>
          )} />
        </Card>
        <Card size="small" title={name || '选一个模板'} extra={name && <Space><Button type="primary" size="small" onClick={save} disabled={!dirty}>保存</Button><Popconfirm title="删除这个模板文件?" onConfirm={remove}><Button size="small" danger>删除</Button></Popconfirm></Space>}>
          {name ? <Input.TextArea value={yaml} onChange={(e) => { setYaml(e.target.value); setDirty(true) }} rows={30} spellCheck={false} style={{ fontFamily: 'Consolas, Menlo, monospace', fontSize: 12.5 }} /> : <span style={{ color: '#999' }}>左边选一个,或「新模板」</span>}
          <div style={{ fontSize: 12, color: '#999', marginTop: 8 }}>fields 里的键名是你们接口的标准名;{'{ci_list}'} {'{summary}'} {'{ci_owner_group}'} {'{environment}'} 会在起草时替换。保存即生效,不用重启。</div>
        </Card>
      </div>
    </div>
  )
}
