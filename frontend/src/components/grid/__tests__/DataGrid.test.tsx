// DataGrid é o componente compartilhado de tabela (headless, via TanStack Table)
// usado por todas as telas do sistema. Estes testes cobrem só a mecânica genérica
// (passthrough de classes, colSpan, onRowClick, extraRows, expansão de linha) —
// nenhuma lógica de domínio deve aparecer aqui nem no componente.

import { describe, expect, it, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { useReactTable, getCoreRowModel, getExpandedRowModel, ColumnDef, Row } from "@tanstack/react-table";
import DataGrid from "@/components/grid/DataGrid";

interface Item {
  id: string;
  nome: string;
}

const COLUNAS: ColumnDef<Item>[] = [
  { accessorKey: "id", header: "ID", meta: { headerClassName: "th-id", cellClassName: "td-id" } },
  { accessorKey: "nome", header: "Nome" },
];

function Harness({
  data,
  onRowClick,
  extraRows,
  renderRowDetail,
  loading,
}: {
  data: Item[];
  onRowClick?: (row: Item) => void;
  extraRows?: React.ReactNode;
  renderRowDetail?: (row: Row<Item>) => React.ReactNode;
  loading?: boolean;
}) {
  const table = useReactTable({
    data,
    columns: COLUNAS,
    getCoreRowModel: getCoreRowModel(),
    getExpandedRowModel: getExpandedRowModel(),
  });

  return (
    <DataGrid
      table={table}
      wrapperClassName="wrapper-classe"
      tableClassName="table-classe"
      theadClassName="thead-classe"
      tbodyClassName="tbody-classe"
      onRowClick={onRowClick}
      extraRows={extraRows}
      renderRowDetail={renderRowDetail}
      loading={loading}
      loadingContent={
        <tr>
          <td colSpan={2}>Carregando...</td>
        </tr>
      }
      emptyContent={
        <tr>
          <td colSpan={2}>Nenhum item encontrado</td>
        </tr>
      }
    />
  );
}

describe("DataGrid", () => {
  it("repassa as classes de wrapper/table/thead/tbody exatamente como recebidas", () => {
    const { container } = render(<Harness data={[{ id: "1", nome: "A" }]} />);

    expect(container.querySelector(".wrapper-classe")).toBeTruthy();
    expect(container.querySelector("table.table-classe")).toBeTruthy();
    expect(container.querySelector("thead.thead-classe")).toBeTruthy();
    expect(container.querySelector("tbody.tbody-classe")).toBeTruthy();
  });

  it("repassa theadRowClassName pra <tr> de cabeçalho, não pro <thead>", () => {
    function ComTheadRowHarness() {
      const table = useReactTable({
        data: [{ id: "1", nome: "A" }] as Item[],
        columns: COLUNAS,
        getCoreRowModel: getCoreRowModel(),
      });
      return <DataGrid table={table} theadRowClassName="linha-cabecalho" />;
    }

    const { container } = render(<ComTheadRowHarness />);

    expect(container.querySelector("thead")?.className).toBe("");
    expect(container.querySelector("thead > tr.linha-cabecalho")).toBeTruthy();
  });

  it("não renderiza <div> wrapper quando wrapperClassName não é passado", () => {
    function SemWrapperHarness() {
      const table = useReactTable({
        data: [{ id: "1", nome: "A" }] as Item[],
        columns: COLUNAS,
        getCoreRowModel: getCoreRowModel(),
      });
      return <DataGrid table={table} tableClassName="table-classe" />;
    }

    const { container } = render(<SemWrapperHarness />);

    expect(container.querySelector("div")).toBeNull();
    expect(container.querySelector("table.table-classe")).toBeTruthy();
  });

  it("repassa headerClassName/cellClassName de meta por coluna", () => {
    const { container } = render(<Harness data={[{ id: "1", nome: "A" }]} />);

    expect(container.querySelector("th.th-id")).toBeTruthy();
    expect(container.querySelector("td.td-id")?.textContent).toBe("1");
  });

  it("mostra emptyContent com colSpan correto quando não há linhas", () => {
    render(<Harness data={[]} />);

    const celula = screen.getByText("Nenhum item encontrado");
    expect(celula.closest("td")?.getAttribute("colspan")).toBe("2");
  });

  it("mostra loadingContent em vez das linhas quando loading=true", () => {
    render(<Harness data={[{ id: "1", nome: "A" }]} loading />);

    expect(screen.getByText("Carregando...")).toBeTruthy();
    expect(screen.queryByText("A")).toBeNull();
  });

  it("chama onRowClick com o dado original da linha", () => {
    const onRowClick = vi.fn();
    render(<Harness data={[{ id: "1", nome: "A" }]} onRowClick={onRowClick} />);

    fireEvent.click(screen.getByText("A").closest("tr")!);

    expect(onRowClick).toHaveBeenCalledWith({ id: "1", nome: "A" });
  });

  it("renderiza extraRows antes das linhas mapeadas", () => {
    const { container } = render(
      <Harness
        data={[{ id: "1", nome: "A" }]}
        extraRows={
          <tr>
            <td colSpan={2} className="linha-extra">
              Nova linha
            </td>
          </tr>
        }
      />,
    );

    const linhas = container.querySelectorAll("tbody > tr");
    expect(linhas[0].querySelector(".linha-extra")).toBeTruthy();
    expect(linhas[1].textContent).toContain("A");
  });

  it("só renderiza renderRowDetail quando a linha está expandida", () => {
    function HarnessExpansivel() {
      const table = useReactTable({
        data: [{ id: "1", nome: "A" }],
        columns: [
          ...COLUNAS,
          {
            id: "toggle",
            header: "",
            cell: ({ row }) => (
              <button onClick={() => row.toggleExpanded()}>toggle</button>
            ),
          },
        ],
        getCoreRowModel: getCoreRowModel(),
        getExpandedRowModel: getExpandedRowModel(),
      });

      return (
        <DataGrid
          table={table}
          renderRowDetail={(row) => <span data-testid="detalhe">Detalhe de {row.original.nome}</span>}
        />
      );
    }

    render(<HarnessExpansivel />);

    expect(screen.queryByTestId("detalhe")).toBeNull();

    fireEvent.click(screen.getByText("toggle"));

    expect(screen.getByTestId("detalhe").textContent).toBe("Detalhe de A");
  });
});
