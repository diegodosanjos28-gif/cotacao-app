// Controles de paginação server-side reutilizáveis, extraídos de GridProdutosSection —
// usado também por ProdutoAutocomplete, Dashboard e Histórico de Preços. Puro: só
// recebe números e um callback, sem depender de uma instância de tabela.

import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import Pagination from "@/components/Pagination";

describe("Pagination", () => {
  it("não renderiza nada quando o total é 0", () => {
    const { container } = render(<Pagination pageIndex={0} pageSize={20} total={0} onPageChange={vi.fn()} />);
    expect(container.innerHTML).toBe("");
  });

  it("mostra o intervalo exibido e o total (ex: 1–20 de 45)", () => {
    render(<Pagination pageIndex={0} pageSize={20} total={45} onPageChange={vi.fn()} />);
    expect(screen.getByText("1–20 de 45")).toBeTruthy();
  });

  it("na última página, o intervalo exibido é limitado ao total (não ultrapassa)", () => {
    render(<Pagination pageIndex={2} pageSize={20} total={45} onPageChange={vi.fn()} />);
    expect(screen.getByText("41–45 de 45")).toBeTruthy();
  });

  it("calcula o total de páginas com Math.ceil(total/pageSize)", () => {
    render(<Pagination pageIndex={0} pageSize={20} total={45} onPageChange={vi.fn()} />);
    expect(screen.getByText("Página 1 de 3")).toBeTruthy();
  });

  it("desabilita 'Anterior' na primeira página e habilita 'Próxima'", () => {
    render(<Pagination pageIndex={0} pageSize={20} total={45} onPageChange={vi.fn()} />);
    expect((screen.getByRole("button", { name: "Anterior" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Próxima" }) as HTMLButtonElement).disabled).toBe(false);
  });

  it("desabilita 'Próxima' na última página e habilita 'Anterior'", () => {
    render(<Pagination pageIndex={2} pageSize={20} total={45} onPageChange={vi.fn()} />);
    expect((screen.getByRole("button", { name: "Próxima" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Anterior" }) as HTMLButtonElement).disabled).toBe(false);
  });

  it("com total cabendo numa página só, ambos os botões ficam desabilitados", () => {
    render(<Pagination pageIndex={0} pageSize={20} total={5} onPageChange={vi.fn()} />);
    expect((screen.getByRole("button", { name: "Anterior" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Próxima" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("clicar em 'Próxima' chama onPageChange com pageIndex + 1", () => {
    const onPageChange = vi.fn();
    render(<Pagination pageIndex={0} pageSize={20} total={45} onPageChange={onPageChange} />);
    fireEvent.click(screen.getByRole("button", { name: "Próxima" }));
    expect(onPageChange).toHaveBeenCalledWith(1);
  });

  it("clicar em 'Anterior' chama onPageChange com pageIndex - 1", () => {
    const onPageChange = vi.fn();
    render(<Pagination pageIndex={2} pageSize={20} total={45} onPageChange={onPageChange} />);
    fireEvent.click(screen.getByRole("button", { name: "Anterior" }));
    expect(onPageChange).toHaveBeenCalledWith(1);
  });
});
