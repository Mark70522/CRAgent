import { Routes, Route, Navigate } from 'react-router-dom'
import AppLayout from '../layouts/AppLayout'
import Dashboard from '../pages/Dashboard'
import ChangeListPage from '../features/change/ChangeListPage'
import ChangeDetailPage from '../features/change/ChangeDetailPage'
import ChangeNewPage from '../features/change/ChangeNewPage'
import IceListPage from '../features/ice/IceListPage'
import IceDetailPage from '../features/ice/IceDetailPage'
import IceNewPage from '../features/ice/IceNewPage'
import IceScorePage from '../features/ice/IceScorePage'
import LedgerPage from '../features/ledger/LedgerPage'
import FormsPage from '../features/forms/FormsPage'
import HistoryPage from '../features/history/HistoryPage'
import TemplatesPage from '../features/history/TemplatesPage'
import TodayPage from '../features/cockpit/TodayPage'
import TodosPage from '../features/cockpit/TodosPage'
import KnowledgePage from '../features/cockpit/KnowledgePage'
import OpenForEdit from '../components/OpenForEdit'
import { changeApi } from '../features/change/changeApi'
import { iceApi } from '../features/ice/iceApi'

export default function AppRouter() {
  return (
    <Routes>
      <Route path="/" element={<AppLayout />}>
        <Route index element={<Navigate to="/today" replace />} />
        <Route path="dashboard" element={<Dashboard />} />
        <Route path="today" element={<TodayPage />} />
        <Route path="todos" element={<TodosPage />} />
        <Route path="knowledge" element={<KnowledgePage />} />
        <Route path="changes" element={<ChangeListPage />} />
        <Route path="changes/new" element={<ChangeNewPage />} />
        <Route path="changes/update" element={<OpenForEdit title="更新变更单" base="/changes" placeholder="单号,如 CHG0012345" list={() => changeApi.list()} />} />
        <Route path="changes/:number" element={<ChangeDetailPage />} />
        <Route path="history" element={<HistoryPage />} />
        <Route path="templates" element={<TemplatesPage />} />
        <Route path="ices" element={<IceListPage />} />
        <Route path="ices/new" element={<IceNewPage />} />
        <Route path="ices/score" element={<IceScorePage />} />
        <Route path="ices/update" element={<OpenForEdit title="更新 ICE" base="/ices" placeholder="ICE 号" list={() => iceApi.list()} />} />
        <Route path="ices/:id" element={<IceDetailPage />} />
        <Route path="ledger" element={<LedgerPage />} />
        <Route path="forms" element={<FormsPage />} />
        <Route path="*" element={<div style={{ padding: 24 }}>404 页面不存在</div>} />
      </Route>
    </Routes>
  )
}
