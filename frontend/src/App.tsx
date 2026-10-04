import { Toaster } from "@/components/ui/toaster";
import { Toaster as Sonner } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Routes, Route } from "react-router-dom";
import { AuthProvider } from "./hooks/useAuth";
import { AdminAuthProvider } from "./hooks/useAdminAuth";
import AdminLayout from "./components/layout/AdminLayout";
import AdminNotFound from "./pages/admin/AdminNotFound";
import AdminSecurity from "./pages/admin/AdminSecurity";
import AdminLogin from "./pages/admin/AdminLogin";
import Dashboard from "./pages/admin/Dashboard";
import UserAdmin from "./pages/admin/UserAdmin";
import BillingAdmin from "./pages/admin/BillingAdmin";
import AiAdmin from "./pages/admin/AiAdmin";
import SafetyAdmin from "./pages/admin/SafetyAdmin";
import IntegrationAdmin from "./pages/admin/IntegrationAdmin";

/**
 * Admin 入口（默认导出）：维持原有简中流程，
 * 不得 import 任何 i18n 模块/locale，由 main.tsx 按路径精确分支加载。
 */
const queryClient = new QueryClient();

const App = () => (
  <QueryClientProvider client={queryClient}>
    <AuthProvider>
      <AdminAuthProvider>
        <TooltipProvider>
          <Toaster />
          <Sonner />
          <BrowserRouter>
            <Routes>
              <Route path="/admin/login" element={<AdminLogin />} />

              {/* Admin Routes */}
              <Route element={<AdminLayout />}>
                <Route path="/admin" element={<Dashboard />} />
                <Route path="/admin/users" element={<UserAdmin />} />
                <Route path="/admin/billing" element={<BillingAdmin />} />
                <Route path="/admin/ai" element={<AiAdmin />} />
                <Route path="/admin/security" element={<AdminSecurity />} />
                <Route path="/admin/safety" element={<SafetyAdmin />} />
                <Route path="/admin/integration" element={<IntegrationAdmin />} />
              </Route>

              {/* 404（admin 专属，不加载 i18n） */}
              <Route path="*" element={<AdminNotFound />} />
            </Routes>
          </BrowserRouter>
        </TooltipProvider>
      </AdminAuthProvider>
    </AuthProvider>
  </QueryClientProvider>
);

export default App;
