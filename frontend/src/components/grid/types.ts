import "@tanstack/table-core";

// Extensão opaca de meta — DataGrid nunca lê estes campos por nome de coluna, só
// repassa as classes/handlers que cada tela definir. Toda lógica de domínio (o que
// uma edição significa, quais colunas existem) fica na config de colunas da tela.
declare module "@tanstack/table-core" {
  interface ColumnMeta<TData, TValue> {
    headerClassName?: string;
    cellClassName?: string | ((row: TData) => string);
  }

  interface TableMeta<TData> {
    onCellEdit?: (rowId: string, columnId: string, value: unknown) => void;
  }
}
