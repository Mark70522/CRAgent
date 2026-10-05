import { useState } from 'react'
import { Button, Layout, Menu, Tooltip } from 'antd'
import { CheckOutlined, MenuFoldOutlined, MenuUnfoldOutlined } from '@ant-design/icons'
import { useNavigate, useLocation, Outlet } from 'react-router-dom'
import { menuItems } from '../constants/menu'

const { Sider, Content } = Layout
const KEY = 'cr-agent.sider.collapsed'

const remembered = () => { try { return localStorage.getItem(KEY) === '1' } catch { return false } }
const remember = (v) => { try { localStorage.setItem(KEY, v ? '1' : '0') } catch { /* private mode: just not remembered */ } }

// Collapsed: section headers become thin dividers, items show only their icon (name on hover).
const railItems = menuItems.flatMap((it, i) => (it.type === 'group' ? [...(i ? [{ type: 'divider', key: `d-${it.key}` }] : []), ...it.children] : [it]))

/** Frosted sidebar with grouped sections + a centered page, macOS style. The sidebar folds to an icon rail. */
export default function AppLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const [collapsed, setCollapsed] = useState(remembered)
  const toggle = (v) => { setCollapsed(v); remember(v) }
  // on a detail page: "update" when it was opened for editing, otherwise the list
  const editing = new URLSearchParams(location.search).get('edit') === '1'
  const own = ['/changes/new', '/changes/update', '/ices/new', '/ices/update', '/ices/score']
  const selected = own.includes(location.pathname) ? location.pathname
    : location.pathname.startsWith('/changes/') ? (editing ? '/changes/update' : '/changes')
    : location.pathname.startsWith('/ices/') ? (editing ? '/ices/update' : '/ices') : location.pathname

  return (
    <Layout style={{ height: '100vh' }}>
      <Sider className={`app-sider${collapsed ? ' is-collapsed' : ''}`} width={232} collapsedWidth={68} trigger={null}
        collapsed={collapsed} breakpoint="lg" onBreakpoint={(narrow) => { if (narrow) setCollapsed(true); else setCollapsed(remembered()) }}>
        <div className="app-brand">
          <div className="app-brand-icon"><CheckOutlined /></div>
          {!collapsed && (
            <div className="app-brand-text">
              <div className="app-brand-name">cr-agent</div>
              <div className="app-brand-sub">日课 · 变更单 · ICE</div>
            </div>
          )}
        </div>
        <div className="app-fold">
          <Tooltip title={collapsed ? '展开侧边栏' : '收起侧边栏'} placement="right">
            <Button type="text" size="small" icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />} onClick={() => toggle(!collapsed)}>
              {!collapsed && '收起'}
            </Button>
          </Tooltip>
        </div>
        <div className="app-menu-scroll">
          <Menu mode="inline" inlineCollapsed={collapsed} items={collapsed ? railItems : menuItems} selectedKeys={[selected]} onClick={(e) => navigate(e.key)} />
        </div>
        {!collapsed && <div className="app-sider-foot">和 Copilot 读写同一批文件</div>}
      </Sider>

      <Content className="app-main">
        <div className="app-page">
          <Outlet />
        </div>
      </Content>
    </Layout>
  )
}
