import { Link, Navigate, Outlet, useLocation } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { useAdminAuth } from "@/hooks/useAdminAuth";

const navItems = [
  { path: "/admin", label: "仪表盘" },
  { path: "/admin/users", label: "用户管理" },
  { path: "/admin/billing", label: "积分查询" },
  { path: "/admin/ai", label: "AI 质检" },
  { path: "/admin/safety", label: "安全运营" },
  { path: "/admin/integration", label: "服务联通" },
];

const AdminLayout = () => {
  const { admin, token, logout, loading } = useAdminAuth();
  const location = useLocation();

  if (!loading && !token) {
    return <Navigate to="/admin/login" replace />;
  }

  return (
    <div className="min-h-screen bg-slate-100">
      <div className="mx-auto max-w-7xl p-4 md:p-6">
        <div className="mb-4 flex flex-col gap-3 rounded-xl bg-white p-4 shadow-sm md:flex-row md:items-center md:justify-between">
          <div>
            <h1 className="text-xl font-semibold">AISocialGame 管理台</h1>
            <p className="text-sm text-slate-500">{admin?.displayName ?? "管理员"}</p>
          </div>
          <Button variant="outline" onClick={logout}>退出管理台</Button>
        </div>

        <div className="grid grid-cols-1 gap-4 md:grid-cols-[220px_1fr]">
          <aside className="rounded-xl bg-white p-3 shadow-sm">
            <nav className="space-y-1">
              {navItems.map((item) => {
                const active = location.pathname === item.path;
                return (
                  <Link
                    key={item.path}
                    to={item.path}
                    className={`block rounded-lg px-3 py-2 text-sm ${active ? "bg-blue-600 text-white" : "text-slate-700 hover:bg-slate-100"}`}
                  >
                    {item.label}
                  </Link>
                );
              })}
            </nav>
          </aside>

          <section className="rounded-xl bg-white p-4 shadow-sm">
            <Outlet />
          </section>
        </div>
      </div>
    </div>
  );
};

export default AdminLayout;
