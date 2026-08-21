// HallFornecedorCard — os 2 modos (ativa/histórico) isolados do fetch de
// HallFornecedores: conteúdo exato renderizado por fornecedor.

import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import HallFornecedorCard from "../HallFornecedorCard";
import { FornecedorHistoricoResponse, FornecedorRespostaResumo } from "@/lib/types";

function makeRespostaResumo(overrides: Partial<FornecedorRespostaResumo> = {}): FornecedorRespostaResumo {
  return {
    fornecedorId: "f-1",
    nome: "Atacadão Silva",
    status: "PENDENTE",
    respondeuEm: null,
    itensCotados: 0,
    ...overrides,
  };
}

function makeHistorico(overrides: Partial<FornecedorHistoricoResponse> = {}): FornecedorHistoricoResponse {
  return {
    fornecedorId: "f-1",
    nome: "Atacadão Silva",
    cotacoesParticipadas: 5,
    coberturaMediaPct: 82,
    tempoRespostaMedioMinutos: 45,
    selo: "AGIL",
    ...overrides,
  };
}

describe("HallFornecedorCard — modo ativa", () => {
  it("fornecedor pendente mostra 'Ainda não respondeu' e cobertura '—'", () => {
    render(<HallFornecedorCard modo="ativa" fornecedor={makeRespostaResumo({ status: "PENDENTE" })} itensListaBase={20} />);

    expect(screen.getByText("Ainda não respondeu")).toBeTruthy();
    expect(screen.getByText("Aguardando resposta")).toBeTruthy();
    expect(screen.getByText("—")).toBeTruthy();
    expect(screen.getByText("/ 20 itens")).toBeTruthy();
  });

  it("fornecedor que respondeu mostra tempo relativo e cobertura numérica", () => {
    const respondeuEm = new Date(Date.now() - 5 * 60_000).toISOString();
    render(
      <HallFornecedorCard
        modo="ativa"
        fornecedor={makeRespostaResumo({ status: "PROCESSADO", respondeuEm, itensCotados: 14 })}
        itensListaBase={20}
      />,
    );

    expect(screen.getByText("Aguardando sua aprovação")).toBeTruthy();
    expect(screen.getByText("14")).toBeTruthy();
    expect(screen.getByText("/ 20 itens")).toBeTruthy();
    expect(screen.getByText("Respondeu há 5 min", { exact: false })).toBeTruthy();
  });

  it("fornecedor confirmado mostra 'Confirmado', não 'Aguardando sua aprovação'", () => {
    const respondeuEm = new Date(Date.now() - 5 * 60_000).toISOString();
    render(
      <HallFornecedorCard
        modo="ativa"
        fornecedor={makeRespostaResumo({ status: "CONFIRMADO", respondeuEm, itensCotados: 20 })}
        itensListaBase={20}
      />,
    );

    expect(screen.getByText("Confirmado")).toBeTruthy();
    expect(screen.queryByText("Aguardando sua aprovação")).toBeNull();
  });
});

describe("HallFornecedorCard — modo histórico", () => {
  it("mostra o selo de confiabilidade AGIL", () => {
    render(<HallFornecedorCard modo="historico" fornecedor={makeHistorico({ selo: "AGIL" })} />);

    expect(screen.getByText("Resposta ágil")).toBeTruthy();
  });

  it("mostra o selo REGULAR", () => {
    render(<HallFornecedorCard modo="historico" fornecedor={makeHistorico({ selo: "REGULAR" })} />);

    expect(screen.getByText("Resposta regular")).toBeTruthy();
  });

  it("mostra o selo ATRASA", () => {
    render(<HallFornecedorCard modo="historico" fornecedor={makeHistorico({ selo: "ATRASA" })} />);

    expect(screen.getByText("Costuma atrasar")).toBeTruthy();
  });

  it("cotacoesParticipadas === 1 usa singular ('1 cotação')", () => {
    render(<HallFornecedorCard modo="historico" fornecedor={makeHistorico({ cotacoesParticipadas: 1 })} />);

    expect(screen.getByText("Participou de 1 cotação", { exact: false })).toBeTruthy();
  });

  it("cotacoesParticipadas > 1 usa plural ('N cotações')", () => {
    render(<HallFornecedorCard modo="historico" fornecedor={makeHistorico({ cotacoesParticipadas: 5 })} />);

    expect(screen.getByText("Participou de 5 cotações", { exact: false })).toBeTruthy();
  });

  it("arredonda coberturaMediaPct e mostra os cards de Cotações/Resp. média", () => {
    render(
      <HallFornecedorCard
        modo="historico"
        fornecedor={makeHistorico({ coberturaMediaPct: 82.6, cotacoesParticipadas: 7, tempoRespostaMedioMinutos: 42 })}
      />,
    );

    expect(screen.getByText("83")).toBeTruthy();
    expect(screen.getByText("7")).toBeTruthy();
    expect(screen.getByText("42 min")).toBeTruthy();
  });

  it("tempo médio de resposta acima de 60min é formatado em horas", () => {
    render(<HallFornecedorCard modo="historico" fornecedor={makeHistorico({ tempoRespostaMedioMinutos: 125 })} />);

    expect(screen.getByText("2h05")).toBeTruthy();
  });
});
