"use client";

import { useState, FormEvent } from "react";
import { useRouter } from "next/navigation";
import { login } from "@/lib/api";
import { setAccessToken } from "@/lib/auth";
import { clearCotacaoAtivaId } from "@/lib/cotacaoAtiva";
import { getErrorMessage } from "@/lib/errors";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [senha, setSenha] = useState("");
  const [erro, setErro] = useState<string | null>(null);
  const [carregando, setCarregando] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setErro(null);
    setCarregando(true);
    try {
      const tokens = await login(email, senha);
      setAccessToken(tokens.accessToken);
      // Cada login é uma identidade nova — qualquer cotação "ativa" lembrada de uma
      // sessão anterior (outro usuário/tenant no mesmo navegador) não é válida aqui.
      clearCotacaoAtivaId();
      router.push("/");
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível entrar."));
    } finally {
      setCarregando(false);
    }
  }

  return (
    <main className="flex flex-1 items-center justify-center bg-bg px-6 py-10">
      <div className="grid w-full max-w-4xl overflow-hidden rounded-[20px] shadow-[0_20px_60px_rgba(13,11,10,.18)] lg:grid-cols-2">
        {/* Painel visual */}
        <div className="relative hidden flex-col justify-between overflow-hidden bg-[#16130F] p-10 lg:flex">
          <div
            className="absolute inset-0"
            style={{
              background:
                "radial-gradient(120% 120% at 100% 0%, rgba(255,128,0,.35) 0%, rgba(255,128,0,0) 55%), linear-gradient(160deg, #1a1712 0%, #16130f 60%, #100d0a 100%)",
            }}
          />
          <svg className="absolute -bottom-16 -right-16 h-72 w-72" viewBox="0 0 200 200" fill="none" aria-hidden="true">
            <circle cx="100" cy="100" r="90" stroke="var(--color-prx)" strokeWidth="1.5" opacity="0.35" />
            <circle cx="70" cy="130" r="50" fill="var(--color-prx)" opacity="0.18" />
            <rect
              x="90"
              y="30"
              width="70"
              height="70"
              rx="16"
              fill="var(--color-prx)"
              opacity="0.12"
              transform="rotate(18 125 65)"
            />
          </svg>
          <svg className="absolute -left-10 -top-10 h-40 w-40" viewBox="0 0 200 200" fill="none" aria-hidden="true">
            <rect x="20" y="20" width="120" height="120" rx="24" fill="var(--color-prx)" opacity="0.10" />
          </svg>

          <div className="relative z-10 flex items-center gap-2">
            <span className="rounded-[9px] bg-white px-3 py-2 text-base font-extrabold leading-none tracking-tight text-[#16130F] shadow-[0_5px_16px_rgba(0,0,0,.32)]">
              PR<span className="text-prx">X</span>
            </span>
          </div>

          <div className="relative z-10">
            <div className="mb-3 flex items-center gap-2.5 text-[10px] font-bold uppercase tracking-[1.3px] text-white/70">
              <span className="h-0.5 w-6 shrink-0 rounded-full bg-prx" />
              PRX Compras Inteligentes
            </div>
            <h2 className="mb-2 max-w-xs text-2xl font-bold leading-tight tracking-tight text-white">
              Cotações mais rápidas, <span className="text-prx">decisões mais inteligentes</span>
            </h2>
            <p className="max-w-xs text-[13px] leading-relaxed text-white/60">
              Compare fornecedores, acompanhe sua economia e feche a melhor compra em um só lugar.
            </p>
          </div>
        </div>

        {/* Formulário */}
        <div className="flex items-center justify-center bg-card p-8 sm:p-12">
          <form onSubmit={onSubmit} className="w-full max-w-sm space-y-5">
            <div>
              <h1 className="text-xl font-semibold text-t1">Sistema de Cotação PRX</h1>
              <p className="mt-1 text-sm text-t2">Entre com sua conta.</p>
            </div>

            <div className="space-y-1">
              <label htmlFor="email" className="text-sm font-medium text-t1">
                E-mail
              </label>
              <input
                id="email"
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
              />
            </div>

            <div className="space-y-1">
              <label htmlFor="senha" className="text-sm font-medium text-t1">
                Senha
              </label>
              <input
                id="senha"
                type="password"
                required
                value={senha}
                onChange={(e) => setSenha(e.target.value)}
                className="w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
              />
            </div>

            {erro && <p className="text-sm text-er">{erro}</p>}

            <button
              type="submit"
              disabled={carregando}
              className="w-full rounded-md bg-prx px-3 py-2.5 text-sm font-medium text-white hover:bg-prx-l disabled:opacity-50"
            >
              {carregando ? "Entrando..." : "Entrar"}
            </button>
          </form>
        </div>
      </div>
    </main>
  );
}
