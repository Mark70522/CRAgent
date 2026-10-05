import { useState } from 'react'
import { Button, Layout, Menu } from 'antd'
import { CheckOutlined, MenuOutlined } from '@ant-design/icons'
import { useNavigate, useLocation, Outlet } from 'react-router-dom'
import { menuItems } from '../constants/menu'

const { Sider, Content } = Layout

/** Frosted sidebar with grouped sections + a centered page, macOS style. */
export default function AppLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const [collapsed, setCollapsed] = useState(false)
  // on a detail page: "update" when it was opened for editing, otherwise the list
  const editing = new URLSearchParams(location.search).get('edit') === '1'
  const own = ['/changes/new', '/changes/update', '/ices/new', '/ices/update', '/ices/score']
  const selected = own.includes(location.pathname) ? location.pathname
    : location.pathname.startsWith('/changes/') ? (editing ? '/changes/update' : '/changes')
    : location.pathname.startsWith('/ices/') ? (editing ? '/ices/update' : '/ices') : location.pathname

  return (
    <Layout style={{ height: '100vh' }}>
      <Sider className="app-sider" width={232} breakpoint="lg" collapsedWidth={0} trigger={null} collapsed={collapsed} onBreakpoint={setCollapsed}>
        <div className="app-brand">
          <div className="app-brand-icon"><CheckOutlined /></div>
          <div>
            <div className="app-brand-name">cr-agent</div>
            <div className="app-brand-sub">日课 · 变更单 · ICE</div>
          </div>
        </div>
        <div className="app-menu-scroll">
          <Menu mode="inline" items={menuItems} selectedKeys={[selected]} onClick={(e) => navigate(e.key)} />
        </div>
        <div className="app-sider-foot">和 Copilot 读写同一批文件</div>
      </Sider>

      <Content className="app-main">
        <Button className="app-sider-toggle" type="text" icon={<MenuOutlined />} onClick={() => setCollapsed(!collapsed)} />
        <div className="app-page">
          <Outlet />
        </div>
      </Content>
    </Layout>
  )
}
