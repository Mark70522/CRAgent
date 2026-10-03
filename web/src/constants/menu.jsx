import { DashboardOutlined, FileTextOutlined, SafetyCertificateOutlined, DatabaseOutlined, SettingOutlined } from '@ant-design/icons'

// Leaf key = route path.
export const menuItems = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '总览' },
  {
    key: 'change',
    icon: <FileTextOutlined />,
    label: '变更单',
    children: [
      { key: '/changes', label: '变更单列表' },
      { key: '/changes/new', label: '新建变更单' },
    ],
  },
  {
    key: 'ice',
    icon: <SafetyCertificateOutlined />,
    label: 'ICE',
    children: [
      { key: '/ices', label: 'ICE 列表' },
      { key: '/ices/new', label: '登记 ICE' },
    ],
  },
  { key: '/ledger', icon: <DatabaseOutlined />, label: '本地台账' },
  {
    key: 'settings',
    icon: <SettingOutlined />,
    label: '设置',
    children: [
      { key: '/forms', label: '字段目录' },
    ],
  },
]
