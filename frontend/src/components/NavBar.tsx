"use client";

import { useEffect, useState } from "react";
import Image from "next/image";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useAuth } from "@/components/AuthProvider";
import { getCotacaoAtivaId, setCotacaoAtivaId } from "@/lib/cotacaoAtiva";
import { buscarTenant, logout } from "@/lib/api";
import Modal from "@/components/Modal";
import NovaCotacaoForm from "@/components/NovaCotacaoForm";

// Ícones fiéis ao protótipo validado (COTA&TESTA - 14.07 - V5.html, classe .ni:
// 15x15, viewBox 24, stroke currentColor 2px, round caps).
function IconDashboard() {
  return (
    <svg className="h-[15px] w-[15px] shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <rect x="3" y="3" width="8" height="8" rx="1.5" />
      <rect x="13" y="3" width="8" height="8" rx="1.5" />
      <rect x="3" y="13" width="8" height="8" rx="1.5" />
      <rect x="13" y="13" width="8" height="8" rx="1.5" />
    </svg>
  );
}
function IconEntrada() {
  return (
    <svg className="h-[15px] w-[15px] shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" />
      <rect x="8" y="2" width="8" height="4" rx="1" />
    </svg>
  );
}
function IconComparativo() {
  return (
    <svg className="h-[15px] w-[15px] shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M18 20V10M12 20V4M6 20v-6" />
    </svg>
  );
}
function IconMapa() {
  return (
    <svg className="h-[15px] w-[15px] shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M1 6v16l7-4 8 4 7-4V2l-7 4-8-4-7 4z" />
      <path d="M8 2v16M16 6v16" />
    </svg>
  );
}
// Alertas não existe como aba no protótipo validado — ícone de sino escolhido pra
// manter a mesma linguagem visual dos demais (stroke 2px, viewBox 24, round caps).
function IconAlertas() {
  return (
    <svg className="h-[15px] w-[15px] shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
      <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
    </svg>
  );
}
function IconHistorico() {
  return (
    <svg className="h-[15px] w-[15px] shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="10" />
      <polyline points="12 6 12 12 16 14" />
    </svg>
  );
}
// Não existe no protótipo (tela nova, ADMIN_PRX) — escudo mantém a mesma linguagem
// visual dos demais ícones (stroke 2px, viewBox 24, round caps).
function IconAdmin() {
  return (
    <svg className="h-[15px] w-[15px] shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 2 4 5v6c0 5 3.4 8.7 8 10 4.6-1.3 8-5 8-10V5l-8-3z" />
    </svg>
  );
}
// Setas cruzadas — "trocar tenant" (navegar como um tenant específico), mesma
// linguagem visual dos demais ícones.
function IconTrocarTenant() {
  return (
    <svg className="h-[15px] w-[15px] shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M17 2l4 4-4 4M3 12v-2a4 4 0 0 1 4-4h14M7 22l-4-4 4-4M21 12v2a4 4 0 0 1-4 4H3" />
    </svg>
  );
}
// Não existe no protótipo (tela nova, OPERADOR_CLIENTE) — mesma linguagem visual.
function IconTelefone() {
  return (
    <svg className="h-[15px] w-[15px] shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.127.96.362 1.903.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.907.338 1.85.573 2.81.7A2 2 0 0 1 22 16.92z" />
    </svg>
  );
}

const ABAS = [
  { segmento: "entrada", label: "Entrada de Dados", Icon: IconEntrada },
  { segmento: "comparativo", label: "Comparativo", Icon: IconComparativo },
  { segmento: "mapa", label: "Mapa de Compra", Icon: IconMapa },
  { segmento: "alertas", label: "Alertas", Icon: IconAlertas },
] as const;

function tabClass(ativo: boolean) {
  return `flex items-center gap-1.5 whitespace-nowrap rounded-md px-3 py-1.5 text-[12.5px] font-medium transition-colors ${
    ativo
      ? "bg-prx/15 text-white shadow-[inset_0_-2px_0_var(--color-prx)]"
      : "text-white/70 hover:bg-white/5 hover:text-white"
  }`;
}

export default function NavBar() {
  const pathname = usePathname();
  const router = useRouter();
  const match = pathname.match(/^\/cotacoes\/([^/]+)\/([^/]+)/);
  const cotacaoIdDaUrl = match?.[1];
  const segmentoAtivo = match?.[2];

  // A URL manda quando traz um id; fora de uma tela de cotação (ex: Visão Geral),
  // cai pra última cotação ativa lembrada nesta sessão (localStorage).
  const [cotacaoAtivaId, setCotacaoAtivaIdState] = useState<string | null>(null);
  const [alvoSegmento, setAlvoSegmento] = useState<string | null>(null);
  const [tenantNome, setTenantNome] = useState<string | null>(null);
  const { papel, tenantId } = useAuth();

  useEffect(() => {
    if (cotacaoIdDaUrl) setCotacaoAtivaId(cotacaoIdDaUrl);
    // Sincroniza com localStorage (fonte externa) — mesmo padrão do AuthGuard.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setCotacaoAtivaIdState(cotacaoIdDaUrl ?? getCotacaoAtivaId());
  }, [cotacaoIdDaUrl]);

  // Nome do tenant navegado só existe pra exibição (o JWT só carrega o id) — busca
  // uma vez por id e ignora resposta tardia se o componente desmontar/trocar antes.
  useEffect(() => {
    if (!tenantId) {
      // Sincroniza com a claim do token (fonte externa) — mesmo padrão já usado
      // acima para papel/cotacaoAtivaId.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setTenantNome(null);
      return;
    }
    let ignorar = false;
    buscarTenant(tenantId)
      .then((t) => {
        if (!ignorar) setTenantNome(t.nomeFantasia);
      })
      .catch(() => {
        if (!ignorar) setTenantNome(null);
      });
    return () => {
      ignorar = true;
    };
  }, [tenantId]);

  const cotacaoId = cotacaoIdDaUrl ?? cotacaoAtivaId;

  function onCriada(novaId: string) {
    setCotacaoAtivaId(novaId);
    setCotacaoAtivaIdState(novaId);
    const destino = alvoSegmento;
    setAlvoSegmento(null);
    if (destino) router.push(`/cotacoes/${novaId}/${destino}`);
  }

  return (
    <>
      <header className="sticky top-0 z-40 flex h-14 items-center gap-6 bg-[#16130F] px-6 shadow-[0_6px_22px_rgba(0,0,0,.18),inset_0_-2px_0_var(--color-prx)]">
        <Link href="/" className="flex shrink-0 items-center gap-2.5">
          <span className="flex shrink-0 items-center justify-center rounded-lg border border-white/[.14] bg-black px-2.5 py-[7px]">
            <Image src="/logo-prx.png" alt="PRX" width={29} height={20} className="h-5 w-auto object-contain" />
          </span>
          <span className="text-[10px] font-medium uppercase leading-tight tracking-[.9px] text-white/50">
            Cotações e Compras
          </span>
        </Link>

        <nav className="flex flex-1 items-center gap-1 overflow-x-auto">
          <Link href="/" className={tabClass(pathname === "/")}>
            <IconDashboard />
            Visão Geral
          </Link>
          {ABAS.map(({ segmento, label, Icon }) => {
            const ativo = segmento === segmentoAtivo;
            if (cotacaoId) {
              return (
                <Link key={segmento} href={`/cotacoes/${cotacaoId}/${segmento}`} className={tabClass(ativo)}>
                  <Icon />
                  {label}
                </Link>
              );
            }
            // Sem cotação ativa: o clique abre o modal de criação em vez de navegar.
            return (
              <button key={segmento} type="button" onClick={() => setAlvoSegmento(segmento)} className={tabClass(false)}>
                <Icon />
                {label}
              </button>
            );
          })}
        </nav>

        <div className="flex shrink-0 items-center gap-3">
          <Link
            href="/historico-precos"
            className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded-md border-[1.5px] px-3.5 py-1.5 text-xs font-semibold transition-all ${
              pathname === "/historico-precos"
                ? "border-prx bg-prx text-white shadow-[0_2px_8px_rgba(255,128,0,.25)]"
                : "border-prx/35 bg-prx/[.08] text-prx hover:-translate-y-px hover:border-prx hover:bg-prx hover:text-white hover:shadow-[0_2px_10px_rgba(255,128,0,.3)]"
            }`}
          >
            <IconHistorico />
            Histórico de Preços
          </Link>
          {papel === "OPERADOR_CLIENTE" && (
            <Link
              href="/meus-telefones"
              className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded-md px-3 py-1.5 text-xs font-semibold transition-colors ${
                pathname === "/meus-telefones" ? "bg-white/10 text-white" : "text-white/70 hover:bg-white/5 hover:text-white"
              }`}
            >
              <IconTelefone />
              Meus Telefones
            </Link>
          )}
          {papel === "ADMIN_PRX" && (
            <>
              <Link
                href="/admin"
                className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded-md px-3 py-1.5 text-xs font-semibold transition-colors ${
                  pathname.startsWith("/admin") ? "bg-white/10 text-white" : "text-white/70 hover:bg-white/5 hover:text-white"
                }`}
              >
                <IconAdmin />
                Painel Admin
              </Link>
              <Link
                href="/selecionar-tenant"
                className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded-md px-3 py-1.5 text-xs font-semibold transition-colors ${
                  pathname === "/selecionar-tenant" ? "bg-white/10 text-white" : "text-white/70 hover:bg-white/5 hover:text-white"
                }`}
              >
                <IconTrocarTenant />
                {tenantId ? `Trocar tenant (${tenantNome ?? "..."})` : "Trocar tenant"}
              </Link>
            </>
          )}
          <button onClick={logout} className="text-sm text-white/70 hover:text-white">
            Sair
          </button>
        </div>
      </header>

      <Modal open={alvoSegmento !== null} onClose={() => setAlvoSegmento(null)} title="Nova cotação">
        <p className="mb-4 text-sm text-t2">
          Não há uma cotação ativa. Crie uma para acessar{" "}
          {ABAS.find((a) => a.segmento === alvoSegmento)?.label ?? "esta seção"}.
        </p>
        <NovaCotacaoForm onCriada={onCriada} autoFocus />
      </Modal>
    </>
  );
}
