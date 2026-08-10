"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const ABAS = [
  { href: "/admin", label: "Tenants" },
  { href: "/admin/administradores", label: "Administradores" },
] as const;

// Sub-navegação interna do painel admin — não compete com "Painel Admin"/"Trocar
// tenant" da NavBar principal, que operam num nível acima (identidade/contexto).
export default function AdminSubNav() {
  const pathname = usePathname();
  return (
    <nav className="flex gap-1 border-b border-bdr">
      {ABAS.map(({ href, label }) => {
        const ativo = pathname === href;
        return (
          <Link
            key={href}
            href={href}
            className={`border-b-2 px-3 py-2 text-sm font-medium transition-colors ${
              ativo ? "border-prx text-t1" : "border-transparent text-t2 hover:text-t1"
            }`}
          >
            {label}
          </Link>
        );
      })}
    </nav>
  );
}
