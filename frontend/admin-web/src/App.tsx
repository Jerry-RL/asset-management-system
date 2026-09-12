import { Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from '@/lib/auth';
import { CompanyProvider } from '@/lib/company';
import { MenuProvider } from '@/lib/menu';
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
import { SystemMenuPage } from '@/pages/SystemMenuPage';
import { OrgStructurePage } from '@/pages/OrgStructurePage';
import { ProjectFormPage } from '@/pages/ProjectFormPage';
import ProjectDetailPage from '@/pages/ProjectDetailPage';
import { AssetFormPage } from '@/pages/AssetFormPage';
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
      <CompanyProvider>
        <MenuProvider>
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
              {/* 资产新增/编辑：独立分组表单（多级联动 + 字典下拉 + 图片预览） */}
              <Route path="assets/create" element={<AssetFormPage />} />
              <Route path="assets/:id/edit" element={<AssetFormPage />} />
              <Route path="contract-templates" element={<ContractTemplatesPage />} />
              <Route path="contracts/:contractId" element={<ContractDetailPage />} />
              <Route path="system/dict" element={<SystemDictionaryPage />} />
              {/* 菜单管理：专用页面（树 + 抽屉 + 校验），不再走 ResourcePage */}
              <Route path="system/menus" element={<SystemMenuPage />} />
              <Route path="org/structure" element={<OrgStructurePage />} />
              {/* 项目新增/编辑：两步走（基本信息 + 分区配置） */}
              <Route path="projects/create" element={<ProjectFormPage />} />
              <Route path="projects/:id/edit" element={<ProjectFormPage />} />
              {/* 项目详情页：聚合统计 + 分区/楼层下的资产分布 */}
              <Route path="projects/:id" element={<ProjectDetailPage />} />
              {Object.entries(RESOURCES).map(([key, config]) => (
                <Route key={key} path={key} element={<ResourcePage config={config} />} />
              ))}
            </Route>
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </MenuProvider>
      </CompanyProvider>
    </AuthProvider>
  );
}
