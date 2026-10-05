import {
  AppstoreOutlined, BulbOutlined, CalendarOutlined, CheckSquareOutlined, DashboardOutlined, DatabaseOutlined,
  EditOutlined, FileTextOutlined, FormOutlined, HistoryOutlined, LineChartOutlined, PlusCircleOutlined, RiseOutlined,
  SafetyCertificateOutlined, SyncOutlined,
} from '@ant-design/icons'

// Leaf key = route path. Groups are section headers, like a macOS sidebar.
export const menuItems = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '总览' },
  {
    key: 'cockpit', type: 'group', label: '日课',
    children: [
      { key: '/today', icon: <CalendarOutlined />, label: '今天' },
      { key: '/todos', icon: <CheckSquareOutlined />, label: '任务' },
      { key: '/knowledge', icon: <BulbOutlined />, label: '知识沉淀' },
      { key: '/review', icon: <RiseOutlined />, label: '复盘' },
    ],
  },
  {
    key: 'change', type: 'group', label: '变更单',
    children: [
      { key: '/changes', icon: <FileTextOutlined />, label: '变更单列表' },
      { key: '/changes/new', icon: <PlusCircleOutlined />, label: '新建变更单' },
      { key: '/changes/update', icon: <EditOutlined />, label: '更新变更单' },
      { key: '/history', icon: <HistoryOutlined />, label: '历史与分类' },
      { key: '/templates', icon: <AppstoreOutlined />, label: '模板' },
    ],
  },
  {
    key: 'ice', type: 'group', label: 'ICE',
    children: [
      { key: '/ices', icon: <SafetyCertificateOutlined />, label: 'ICE 列表' },
      { key: '/ices/new', icon: <PlusCircleOutlined />, label: '登记 ICE' },
      { key: '/ices/update', icon: <SyncOutlined />, label: '更新 ICE' },
      { key: '/ices/score', icon: <LineChartOutlined />, label: '查 ICE 分数' },
    ],
  },
  {
    key: 'more', type: 'group', label: '其他',
    children: [
      { key: '/ledger', icon: <DatabaseOutlined />, label: '本地台账' },
      { key: '/forms', icon: <FormOutlined />, label: '字段目录' },
    ],
  },
]
