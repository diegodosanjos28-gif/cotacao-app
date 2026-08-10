"use client";

import { use } from "react";
import AuthGuard from "@/components/AuthGuard";
import Card from "@/components/Card";
import NavBar from "@/components/NavBar";
import { comparativo, mapaCompra } from "@/lib/api";
import {
  concentracaoFornecedorLider,
  itensAltaVariacao,
  itensCoberturaParcial,
  itensSemCotacao,
  maiorEconomiaItem,
  todosFornecedores,
} from "@/lib/comparativo";
import { formatarMoeda } from "@/lib/format";
import { useAsync } from "@/hooks/useAsync";

const LIMIAR_CONCENTRACAO_PCT = 60;

function AlertasContent({ cotacaoId }: { cotacaoId: string }) {
  const { data: itens, erro } = useAsync(
    () => comparativo(cotacaoId),
    [cotacaoId],
    "Não foi possível carregar os alertas.",
  );
  // Pedido mínimo só é respeitado no cenário Compra Equilibrada — é o cenário relevante
  // pra alertar "fornecedor abaixo do mínimo" (ver seção 6.5 da doc técnica).
  const { data: mapaEquilibrada } = useAsync(
    () => mapaCompra(cotacaoId, "EQUILIBRADA"),
    [cotacaoId],
    "",
  );

  if (erro) return <p className="mt-6 text-sm text-er">{erro}</p>;
  if (!itens) return <p className="mt-6 text-sm text-t2">Carregando...</p>;

  const totalFornecedores = todosFornecedores(itens).size;
  const semCotacao = itensSemCotacao(itens);
  const altaVariacao = itensAltaVariacao(itens, totalFornecedores);
  const coberturaParcial = itensCoberturaParcial(itens, totalFornecedores);
  const abaixoDoMinimo = mapaEquilibrada?.distribuicoes.filter((d) => d.abaixoDoPedidoMinimo) ?? [];
  const maiorEconomia = maiorEconomiaItem(itens);
  const concentracao = concentracaoFornecedorLider(itens);

  const critico = [
    ...semCotacao.map((item) => `${item.nomeProduto} ainda não tem nenhuma cotação.`),
    ...abaixoDoMinimo.map(
      (d) => `${d.fornecedorNome} está abaixo do pedido mínimo (falta ${formatarMoeda(d.faltaParaMinimo)}) na Compra Equilibrada.`,
    ),
  ];

  const atencao = [
    ...altaVariacao.map((item) => `${item.nomeProduto} tem variação de preço acima de 20% entre fornecedores.`),
    ...coberturaParcial.map((item) => `${item.nomeProduto} tem cobertura parcial — poucos fornecedores cotaram.`),
  ];

  const oportunidades = [
    ...(maiorEconomia
      ? [`Maior oportunidade de economia: ${maiorEconomia.item.nomeProduto} (${formatarMoeda(maiorEconomia.valor)}).`]
      : []),
    ...(concentracao && concentracao.percentual > LIMIAR_CONCENTRACAO_PCT
      ? [`${concentracao.nome} concentra ${concentracao.percentual.toFixed(0)}% dos melhores preços — vale diversificar fornecedores.`]
      : []),
  ];

  return (
    <div className="mt-6 grid gap-6 lg:grid-cols-[2fr_1fr]">
      <div className="space-y-4">
        <div className="rounded-lg border border-er/30 bg-card p-6">
          <div className="flex items-center justify-between">
            <h2 className="font-semibold text-er">Crítico</h2>
            <span className="rounded-full bg-er-d px-2 py-0.5 text-xs font-medium text-er">{critico.length}</span>
          </div>
          <ul className="mt-3 space-y-2 text-sm text-t2">
            {critico.length > 0 ? critico.map((msg, i) => <li key={i}>{msg}</li>) : <li>Nenhum alerta crítico.</li>}
          </ul>
        </div>

        <div className="rounded-lg border border-wa/30 bg-card p-6">
          <div className="flex items-center justify-between">
            <h2 className="font-semibold text-wa">Atenção</h2>
            <span className="rounded-full bg-wa-d px-2 py-0.5 text-xs font-medium text-wa">{atencao.length}</span>
          </div>
          <ul className="mt-3 space-y-2 text-sm text-t2">
            {atencao.length > 0 ? atencao.map((msg, i) => <li key={i}>{msg}</li>) : <li>Nenhum alerta de atenção.</li>}
          </ul>
        </div>

        <div className="rounded-lg border border-inf/30 bg-card p-6">
          <div className="flex items-center justify-between">
            <h2 className="font-semibold text-inf">Oportunidades e informações</h2>
            <span className="rounded-full bg-inf-d px-2 py-0.5 text-xs font-medium text-inf">{oportunidades.length}</span>
          </div>
          <ul className="mt-3 space-y-2 text-sm text-t2">
            {oportunidades.length > 0 ? oportunidades.map((msg, i) => <li key={i}>{msg}</li>) : <li>Nada a destacar por ora.</li>}
          </ul>
        </div>
      </div>

      <div className="space-y-4">
        <Card>
          <h2 className="font-semibold text-t1">Resumo por categoria</h2>
          <div className="mt-4 space-y-2">
            {[
              { label: "Crítico", valor: critico.length, cor: "bg-er" },
              { label: "Atenção", valor: atencao.length, cor: "bg-wa" },
              { label: "Oportunidades", valor: oportunidades.length, cor: "bg-inf" },
            ].map((cat) => {
              const max = Math.max(1, critico.length, atencao.length, oportunidades.length);
              return (
                <div key={cat.label}>
                  <div className="flex items-center justify-between text-xs text-t2">
                    <span>{cat.label}</span>
                    <span>{cat.valor}</span>
                  </div>
                  <div className="mt-0.5 h-2 w-full rounded-full bg-hov">
                    <div className={`h-2 rounded-full ${cat.cor}`} style={{ width: `${(cat.valor / max) * 100}%` }} />
                  </div>
                </div>
              );
            })}
          </div>
        </Card>

        <Card>
          <h2 className="font-semibold text-t1">Próximos passos</h2>
          <ul className="mt-3 space-y-2 text-sm text-t2">
            {semCotacao.length > 0 && <li>Colete cotações para os {semCotacao.length} produto(s) ainda sem oferta.</li>}
            {abaixoDoMinimo.length > 0 && <li>Ajuste o pedido dos fornecedores abaixo do mínimo antes de fechar.</li>}
            {critico.length === 0 && atencao.length === 0 && <li>Cotação em bom estado — revise o Mapa de Compra para fechar o pedido.</li>}
          </ul>
        </Card>
      </div>
    </div>
  );
}

export default function AlertasPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return (
    <AuthGuard>
      <NavBar />
      <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-8">
        <h1 className="text-2xl font-semibold tracking-tight text-t1">Alertas</h1>
        <AlertasContent cotacaoId={id} />
      </main>
    </AuthGuard>
  );
}
