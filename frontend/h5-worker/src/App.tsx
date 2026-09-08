import { Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from '@/lib/auth';
import { Shell } from '@/components/Shell';
import { LoginPage } from '@/pages/LoginPage';
import { TasksPage } from '@/pages/TasksPage';
import { AssetQueryPage } from '@/pages/AssetQueryPage';
import { ChargePage } from '@/pages/ChargePage';
import { InspectPage } from '@/pages/InspectPage';
import { RepairPage } from '@/pages/RepairPage';
import { DunningPage } from '@/pages/DunningPage';
import { ApprovalPage } from '@/pages/ApprovalPage';
import { VacatePage } from '@/pages/VacatePage';
import { MeterPage } from '@/pages/MeterPage';
import { PaymentConfirmPage } from '@/pages/PaymentConfirmPage';
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
        <Route
          path="/"
          element={
            <Protected>
              <Shell />
            </Protected>
          }
        >
          <Route index element={<TasksPage />} />
          <Route path="asset-query" element={<AssetQueryPage />} />
          <Route path="charge" element={<ChargePage />} />
          <Route path="inspect" element={<InspectPage />} />
          <Route path="repair" element={<RepairPage />} />
          <Route path="dunning" element={<DunningPage />} />
          <Route path="approval" element={<ApprovalPage />} />
          <Route path="vacate" element={<VacatePage />} />
          <Route path="meter" element={<MeterPage />} />
          <Route path="payment-confirm" element={<PaymentConfirmPage />} />
          <Route path="my" element={<MyPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AuthProvider>
  );
}
