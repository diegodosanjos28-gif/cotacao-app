// Porte fiel de exportarConfNotaPDF do protótipo (COTA&TESTA - 14.07 - V5.html): monta um
// documento HTML autossuficiente (sem biblioteca de PDF) e dispara a impressão do navegador
// numa janela nova. montarHtmlConferenciaNota é pura (testável); exportarConferenciaNota é o
// efeito colateral (window.open/print).

import { Cotacao, ComparativoItemResponse } from "./types";
import { DetalheFornecedor, detalhamentoPorFornecedor } from "./comparativo";
import { formatarMoeda, formatarQuantidade } from "./format";

function formatarDataPdf(iso: string | null): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleDateString("pt-BR");
}

function dataParaArquivo(iso: string | null): string {
  if (!iso) return "sem-data";
  const d = new Date(iso);
  const dia = String(d.getDate()).padStart(2, "0");
  const mes = String(d.getMonth() + 1).padStart(2, "0");
  const ano = d.getFullYear();
  return `${dia}-${mes}-${ano}`;
}

// Só finalizadaEm é usado abaixo (data do documento/nome do arquivo) — Pick em vez de
// Cotacao inteira pra aceitar chamadores que não têm o objeto Cotacao completo (ex.:
// ConferenciaNotaModal do Dashboard, que só tem o card agregado do carrossel).
export function montarHtmlConferenciaNota(
  cotacao: Pick<Cotacao, "finalizadaEm">,
  detalhes: DetalheFornecedor[],
): { html: string; tituloArquivo: string } {
  const totalItens = detalhes.reduce((soma, d) => soma + d.itens.length, 0);
  const totalGeral = detalhes.reduce((soma, d) => soma + d.totalFornecedor, 0);
  const dataCotacao = formatarDataPdf(cotacao.finalizadaEm);
  const agora = new Date();
  const geradoEm = `${agora.toLocaleDateString("pt-BR")} às ${agora.toLocaleTimeString("pt-BR", {
    hour: "2-digit",
    minute: "2-digit",
  })}`;
  const tituloArquivo = `conferencia-de-nota-${dataParaArquivo(cotacao.finalizadaEm)}`;

  const blocos = detalhes
    .map((d, idx) => {
      const linhas = d.itens
        .map((item, i) => {
          const bg = i % 2 === 0 ? "#ffffff" : "#f8f9fa";
          return `<tr style="background:${bg}">
            <td style="padding:5px 8px;font-size:11px;color:#333;border-bottom:1px solid #eee">${item.nomeProduto}</td>
            <td style="padding:5px 8px;font-size:11px;color:#555;text-align:center;border-bottom:1px solid #eee;white-space:nowrap">${formatarQuantidade(item.quantidade)} ${item.unidade}</td>
            <td style="padding:5px 8px;font-size:11px;color:#555;text-align:right;border-bottom:1px solid #eee;font-family:monospace">${formatarMoeda(item.precoUnitario)}</td>
            <td style="padding:5px 8px;font-size:11px;font-weight:700;color:#1a1a1a;text-align:right;border-bottom:1px solid #eee;font-family:monospace">${formatarMoeda(item.total)}</td>
          </tr>`;
        })
        .join("");
      return `<div style="${idx > 0 ? "page-break-before:always;" : ""}margin-bottom:16px;border:1px solid #dde3e8;border-radius:8px;overflow:hidden">
        <div style="display:flex;align-items:center;justify-content:space-between;padding:8px 12px;background:#f0f4f7;border-bottom:1px solid #dde3e8">
          <span style="font-size:13px;font-weight:700;color:#1a1a1a">${d.nomeFornecedor}</span>
          <span style="font-size:12px;color:#555">${d.itens.length} ${d.itens.length === 1 ? "item" : "itens"} · <strong style="color:#FF8000">${formatarMoeda(d.totalFornecedor)}</strong></span>
        </div>
        <table style="width:100%;border-collapse:collapse">
          <thead><tr style="background:#e8edf2">
            <th style="padding:5px 8px;font-size:9.5px;text-transform:uppercase;letter-spacing:.5px;color:#777;font-weight:600;text-align:left">Produto</th>
            <th style="padding:5px 8px;font-size:9.5px;text-transform:uppercase;letter-spacing:.5px;color:#777;font-weight:600;text-align:center">Qtd</th>
            <th style="padding:5px 8px;font-size:9.5px;text-transform:uppercase;letter-spacing:.5px;color:#777;font-weight:600;text-align:right">Unit.</th>
            <th style="padding:5px 8px;font-size:9.5px;text-transform:uppercase;letter-spacing:.5px;color:#777;font-weight:600;text-align:right">Total</th>
          </tr></thead>
          <tbody>${linhas}</tbody>
        </table>
      </div>`;
    })
    .join("");

  const html = `<!DOCTYPE html><html lang="pt-BR"><head><meta charset="UTF-8"><title>${tituloArquivo}</title>
<style>
  *{box-sizing:border-box;margin:0;padding:0}
  body{font-family:'Segoe UI',Arial,sans-serif;background:#fff;color:#1a1a1a;padding:24px 28px}
  @page{size:A4 portrait;margin:18mm 16mm}
  @media print{body{padding:0}button{display:none!important}.no-print{display:none!important}}
</style></head><body>
  <div style="display:flex;align-items:flex-start;justify-content:space-between;margin-bottom:20px;padding-bottom:14px;border-bottom:2px solid #FF8000">
    <div>
      <div style="font-size:9px;text-transform:uppercase;letter-spacing:1px;color:#FF8000;font-weight:700;margin-bottom:4px">PRX Compras Inteligentes</div>
      <h1 style="font-size:20px;font-weight:800;color:#1a1a1a;letter-spacing:-0.5px">Conferência de Nota</h1>
      <p style="font-size:11.5px;color:#666;margin-top:3px">Cotação de <strong>${dataCotacao}</strong> · ${detalhes.length} fornecedor${detalhes.length === 1 ? "" : "es"} · ${totalItens} itens</p>
    </div>
    <div style="text-align:right">
      <div style="font-size:10px;color:#888;margin-bottom:2px">Total Geral</div>
      <div style="font-size:22px;font-weight:800;color:#FF8000;font-family:monospace">${formatarMoeda(totalGeral)}</div>
      <div style="font-size:10px;color:#aaa;margin-top:2px">Gerado em ${geradoEm}</div>
    </div>
  </div>
  <div>${blocos}</div>
  <div style="margin-top:20px;padding-top:12px;border-top:1px solid #eee;display:flex;justify-content:space-between;align-items:center">
    <span style="font-size:10px;color:#bbb">PRX Compras Inteligentes · Documento gerado automaticamente</span>
    <span style="font-size:11px;font-weight:700;color:#1a1a1a">Total: ${formatarMoeda(totalGeral)}</span>
  </div>
  <div class="no-print" style="position:fixed;bottom:20px;right:20px">
    <button onclick="window.print()" style="background:#FF8000;color:#fff;border:none;padding:10px 20px;border-radius:8px;font-size:13px;font-weight:600;cursor:pointer;box-shadow:0 4px 12px rgba(255,128,0,.35)">🖨️ Imprimir / Salvar PDF</button>
  </div>
</body></html>`;

  return { html, tituloArquivo };
}

// Retorna false quando o navegador bloqueou a janela (pop-up) — quem chama decide
// como avisar o operador (achado do usuário, 2026-08-21: alert() nativo não combina
// com o resto da UI; substituído por uma mensagem inline no próprio modal).
export function exportarConferenciaNota(cotacao: Pick<Cotacao, "finalizadaEm">, itens: ComparativoItemResponse[]): boolean {
  const detalhes = detalhamentoPorFornecedor(itens);
  const { html } = montarHtmlConferenciaNota(cotacao, detalhes);
  const janela = window.open("", "_blank", "width=900,height=700");
  if (!janela) return false;
  janela.document.write(html);
  janela.document.close();
  setTimeout(() => janela.print(), 600);
  return true;
}
