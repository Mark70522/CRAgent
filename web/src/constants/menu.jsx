import { DashboardOutlined, FileTextOutlined, SafetyCertificateOutlined, DatabaseOutlined, SettingOutlined, CalendarOutlined } from '@ant-design/icons'

// Leaf key = route path.
export const menuItems = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '总览' },
  {
    key: 'cockpit',
    icon: <CalendarOutlined />,
    label: '日课',
    children: [
      { key: '/today', label: '今天' },
      { key: '/todos', label: '任务' },
      { key: '/knowledge', label: '知识沉淀' },
    ],
  },
  {
    key: 'change',
    icon: <FileTextOutlined />,
    label: '变更单',
    children: [
      { key: '/changes', label: '变更单列表' },
      { key: '/changes/new', label: '新建变更单' },
      { key: '/changes/update', label: '更新变更单' },
      { key: '/history', label: '历史与分类' },
      { key: '/templates', label: '模板' },
    ],
  },
  {
    key: 'ice',
    icon: <SafetyCertificateOutlined />,
    label: 'ICE',
    children: [
      { key: '/ices', label: 'ICE 列表' },
      { key: '/ices/new', label: '登记 ICE' },
      { key: '/ices/update', label: '更新 ICE' },
      { key: '/ices/score', label: '查 ICE 分数' },
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
