"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { getPapel, getTenantId, isAuthenticated } from "@/lib/auth";
import type { Papel } from "@/lib/types";

export default function AuthGuard({
  children,
  papelRequerido,
}: {
  children: React.ReactNode;
  papelRequerido?: Papel;
}) {
  const router = useRouter();
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (!isAuthenticated()) {
      router.replace("/login");
      return;
    }
    // Usuário está autenticado mas não tem o papel exigido pela rota — volta pro
    // dashboard, não pro login (não é um problema de sessão).
    if (papelRequerido && getPapel() !== papelRequerido) {
      router.replace("/");
      return;
    }
    // Páginas de negócio (sem papelRequerido, abertas a OPERADOR_CLIENTE) exigem um
    // tenant selecionado quando quem está logado é ADMIN_PRX — sem isso o backend
    // devolve 403 (TenantFilter) porque o admin ainda não escolheu "navegar" nenhum
    // tenant. Páginas com papelRequerido="ADMIN_PRX" (o próprio painel admin) não
    // passam por essa regra: tenant ausente é o estado normal delas.
    if (!papelRequerido && getPapel() === "ADMIN_PRX" && getTenantId() === null) {
      router.replace("/selecionar-tenant");
      return;
    }
    // Sincroniza com localStorage (fonte externa) — é exatamente o caso de uso
    // que useEffect existe pra cobrir, apesar do lint genérico contra setState em efeito.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setReady(true);
  }, [router, papelRequerido]);

  if (!ready) return null;
  return <>{children}</>;
}
