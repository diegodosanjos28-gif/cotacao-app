package com.prx.cotacao.cotacao.respostafornecedor.parser.dto;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * fornecedor.prazoEntregaPadrao é texto livre ("2 dias úteis", "até 5 dias", "entrega
 * imediata") — não um campo estruturado. Pra ordenar fornecedores por prazo no cenário
 * Melhor Prazo, extrai heuristicamente o primeiro número seguido de "dia(s)" do texto.
 *
 * Não é uma garantia de interpretação correta pra todo texto livre possível — é uma
 * heurística deliberadamente simples (a alternativa seria estruturar o campo no cadastro
 * do fornecedor, fora do escopo deste prompt). Texto sem número de dias reconhecível
 * (nulo, vazio, ou formato inesperado) retorna null e é tratado como "prazo desconhecido"
 * pelo MapaCompraService — ordenado por último, nunca descartado da comparação.
 */
public final class PrazoEntregaParser {

    private static final Pattern DIAS = Pattern.compile("(\\d+)\\s*dias?", Pattern.CASE_INSENSITIVE);

    private PrazoEntregaParser() {}

    public static Integer extrairDias(String prazoTexto) {
        if (prazoTexto == null || prazoTexto.isBlank()) return null;
        Matcher m = DIAS.matcher(prazoTexto);
        if (!m.find()) return null;
        return Integer.parseInt(m.group(1));
    }
}
