import { Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from '@/lib/auth';
import { Shell } from '@/components/Shell';
import { LoginPage } from '@/pages/LoginPage';
import { HomePage } from '@/pages/HomePage';
import { AssetsPage } from '@/pages/AssetsPage';
import { AssetDetailPage } from '@/pages/AssetDetailPage';
import { ScanAssetPage } from '@/pages/ScanAssetPage';
import { BillsPage } from '@/pages/BillsPage';
import { BillDetailPage } from '@/pages/BillDetailPage';
import { RepairPage } from '@/pages/RepairPage';
import { TenderPage } from '@/pages/TenderPage';
import { MessagesPage } from '@/pages/MessagesPage';
import { ContractsPage } from '@/pages/ContractsPage';
import { VacatePage } from '@/pages/VacatePage';
import { MyPage } from '@/pages/MyPage';

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
        {/* 一产一码公开扫码页，免登录 */}
        <Route path="/scan/:id" element={<ScanAssetPage />} />
        <Route
          path="/"
          element={
            <Protected>
              <Shell />
            </Protected>
          }
        >
          <Route index element={<HomePage />} />
          <Route path="assets" element={<AssetsPage />} />
          <Route path="assets/:id" element={<AssetDetailPage />} />
          <Route path="bills" element={<BillsPage />} />
          <Route path="bills/:id" element={<BillDetailPage />} />
          <Route path="repair" element={<RepairPage />} />
          <Route path="tender" element={<TenderPage />} />
          <Route path="messages" element={<MessagesPage />} />
          <Route path="contracts" element={<ContractsPage />} />
          <Route path="vacate" element={<VacatePage />} />
          <Route path="my" element={<MyPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AuthProvider>
  );
}
