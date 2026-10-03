import { Layout, Menu } from 'antd'
import { useNavigate, useLocation, Outlet } from 'react-router-dom'
import { menuItems } from '../constants/menu'

const { Header, Sider, Content } = Layout

/** Top bar + left menu + routed content. */
export default function AppLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  // highlight the list item while on a detail page
  const selected = location.pathname.startsWith('/changes/') && location.pathname !== '/changes/new' ? '/changes'
    : location.pathname.startsWith('/ices/') && location.pathname !== '/ices/new' ? '/ices' : location.pathname

  return (
    <Layout style={{ height: '100vh' }}>
      <Sider theme="dark" width={220}>
        <div style={{ height: 56, color: '#fff', display: 'flex', alignItems: 'center', paddingLeft: 20, fontWeight: 600 }}>
          cr-agent
        </div>
        <Menu
          theme="dark"
          mode="inline"
          items={menuItems}
          selectedKeys={[selected]}
          defaultOpenKeys={['cockpit', 'change', 'ice', 'settings']}
          onClick={(e) => navigate(e.key)}
        />
      </Sider>

      <Layout>
        <Header style={{ background: '#fff', paddingInline: 20, display: 'flex', alignItems: 'center', justifyContent: 'space-between', borderBottom: '1px solid #f0f0f0' }}>
          <span style={{ fontSize: 16, fontWeight: 500 }}>日课 · 变更单 · ICE</span>
          <span style={{ color: '#888', fontSize: 12 }}>和 Copilot 读写同一批文件</span>
        </Header>
        <Content style={{ margin: 16, padding: 24, background: '#fff', borderRadius: 8, overflow: 'auto' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
