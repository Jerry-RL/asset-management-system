import { Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from '@/lib/auth';
import { LoginPage } from '@/pages/LoginPage';
import { DashboardPage, ConsolidatePage } from '@/pages/DashboardPage';
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
          <Route index element={<DashboardPage />} />
          <Route path="dashboard/consolidate" element={<ConsolidatePage />} />
          {Object.entries(RESOURCES).map(([key, config]) => (
            <Route key={key} path={key} element={<ResourcePage config={config} />} />
          ))}
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AuthProvider>
  );
}
