"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/components/AuthProvider";
import type { Papel } from "@/lib/types";

export default function AuthGuard({
  children,
  papelRequerido,
}: {
  children: React.ReactNode;
  papelRequerido?: Papel;
}) {
  const router = useRouter();
  const { ready: authReady, authenticated, papel, tenantId } = useAuth();
  const [decidido, setDecidido] = useState(false);

  useEffect(() => {
    // Espera o silent refresh do bootstrap (AuthProvider) terminar antes de decidir
    // qualquer coisa — sem isso, um reload numa tela autenticada piscaria pro login
    // antes do cookie httpOnly ter chance de restaurar a sessão.
    if (!authReady) return;

    if (!authenticated) {
      router.replace("/login");
      return;
    }
    // Usuário está autenticado mas não tem o papel exigido pela rota — volta pro
    // dashboard, não pro login (não é um problema de sessão).
    if (papelRequerido && papel !== papelRequerido) {
      router.replace("/");
      return;
    }
    // Páginas de negócio (sem papelRequerido, abertas a OPERADOR_CLIENTE) exigem um
    // tenant selecionado quando quem está logado é ADMIN_PRX — sem isso o backend
    // devolve 403 (TenantFilter) porque o admin ainda não escolheu "navegar" nenhum
    // tenant. Páginas com papelRequerido="ADMIN_PRX" (o próprio painel admin) não
    // passam por essa regra: tenant ausente é o estado normal delas.
    if (!papelRequerido && papel === "ADMIN_PRX" && tenantId === null) {
      router.replace("/selecionar-tenant");
      return;
    }
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setDecidido(true);
  }, [authReady, authenticated, papel, tenantId, router, papelRequerido]);

  if (!decidido) return null;
  return <>{children}</>;
}
