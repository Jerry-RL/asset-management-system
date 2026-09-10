import { Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from '@/lib/auth';
import { LoginPage } from '@/pages/LoginPage';
import { HomePage } from '@/pages/HomePage';
import { DashboardPage, ConsolidatePage } from '@/pages/DashboardPage';
import { AssetMapPage } from '@/pages/AssetMapPage';
import { ReportsPage } from '@/pages/ReportsPage';
import { ApprovalPage } from '@/pages/ApprovalPage';
import { PaymentConfirmPage } from '@/pages/PaymentConfirmPage';
import { AgentReportsPage } from '@/pages/AgentReportsPage';
import { AssetDossierPage } from '@/pages/AssetDossierPage';
import { ContractTemplatesPage } from '@/pages/ContractTemplatesPage';
import { ContractDetailPage } from '@/pages/ContractDetailPage';
import { OpsCalendarPage } from '@/pages/OpsCalendarPage';
import { DunningAutoPage } from '@/pages/DunningAutoPage';
import { HelpPage } from '@/pages/HelpPage';
import { SystemDictionaryPage } from '@/pages/SystemDictionaryPage';
import { OrgStructurePage } from '@/pages/OrgStructurePage';
import { AdminLayout } from '@/components/AdminLayout';
import { ResourcePage } from '@/components/ResourcePage';
import { RESOURCES } from '@/pages/modules';

function Protected({ children }: { children: React.ReactNode }) {
  const { token } = useAuth();
  if (!token) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

export default function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route
          path="/"
          element={
            <Protected>
              <AdminLayout />
            </Protected>
          }
        >
          <Route index element={<HomePage />} />
          <Route path="help" element={<HelpPage />} />
          <Route path="dashboard" element={<DashboardPage />} />
          <Route path="ops-calendar" element={<OpsCalendarPage />} />
          <Route path="dunning/auto" element={<DunningAutoPage />} />
          <Route path="dashboard/consolidate" element={<ConsolidatePage />} />
          <Route path="asset-map" element={<AssetMapPage />} />
          <Route path="reports" element={<ReportsPage />} />
          <Route path="approvals" element={<ApprovalPage />} />
          <Route path="payments/pending-confirm" element={<PaymentConfirmPage />} />
          <Route path="intelligence/reports" element={<AgentReportsPage />} />
          <Route path="assets/:assetId/dossier" element={<AssetDossierPage />} />
          <Route path="contract-templates" element={<ContractTemplatesPage />} />
          <Route path="contracts/:contractId" element={<ContractDetailPage />} />
          <Route path="system/dict" element={<SystemDictionaryPage />} />
          <Route path="org/structure" element={<OrgStructurePage />} />
          {Object.entries(RESOURCES).map(([key, config]) => (
            <Route key={key} path={key} element={<ResourcePage config={config} />} />
          ))}
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AuthProvider>
  );
}
