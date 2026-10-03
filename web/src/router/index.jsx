import { Routes, Route, Navigate } from 'react-router-dom'
import AppLayout from '../layouts/AppLayout'
import Dashboard from '../pages/Dashboard'
import ChangeListPage from '../features/change/ChangeListPage'
import ChangeDetailPage from '../features/change/ChangeDetailPage'
import ChangeNewPage from '../features/change/ChangeNewPage'
import IceListPage from '../features/ice/IceListPage'
import IceDetailPage from '../features/ice/IceDetailPage'
import IceNewPage from '../features/ice/IceNewPage'
import LedgerPage from '../features/ledger/LedgerPage'
import FormsPage from '../features/forms/FormsPage'

export default function AppRouter() {
  return (
    <Routes>
      <Route path="/" element={<AppLayout />}>
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="dashboard" element={<Dashboard />} />
        <Route path="changes" element={<ChangeListPage />} />
        <Route path="changes/new" element={<ChangeNewPage />} />
        <Route path="changes/:number" element={<ChangeDetailPage />} />
        <Route path="ices" element={<IceListPage />} />
        <Route path="ices/new" element={<IceNewPage />} />
        <Route path="ices/:id" element={<IceDetailPage />} />
        <Route path="ledger" element={<LedgerPage />} />
        <Route path="forms" element={<FormsPage />} />
        <Route path="*" element={<div style={{ padding: 24 }}>404 页面不存在</div>} />
      </Route>
    </Routes>
  )
}
