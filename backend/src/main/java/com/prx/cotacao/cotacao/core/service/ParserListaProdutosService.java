package com.prx.cotacao.cotacao.core.service;

import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.LinhaParseada;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ParserListaProdutosService {

    // ===================== Tabela de aliases de unidade =====================
    // Espelha UNIT_ALIASES da Especificação Técnica do Motor de Conferência do
    // Fornecedor (seção 5 — Parser da lista base). Mesmo espírito do dicionário
    // MEDIDA_ALIASES em MatchingProdutoService: forma-padrão-curta -> variantes
    // reconhecidas (abreviação, plural, forma por extenso).
    private static final Map<String, List<String>> UNIT_ALIASES = new LinkedHashMap<>();
    static {
        UNIT_ALIASES.put("un", List.of("un", "und", "unid", "unidade", "unidades"));
        UNIT_ALIASES.put("fd", List.of("fd", "f", "fardo", "fardos"));
        UNIT_ALIASES.put("cx", List.of("cx", "cxa", "caixa", "caixas"));
        UNIT_ALIASES.put("pct", List.of("pct", "pacote", "pacotes"));
        UNIT_ALIASES.put("kg", List.of("kg", "kilo", "quilo", "kilos", "quilos"));
        UNIT_ALIASES.put("g", List.of("g", "gr", "grama", "gramas"));
        UNIT_ALIASES.put("ml", List.of("ml", "mililitro"));
        UNIT_ALIASES.put("l", List.of("l", "lt", "litro", "litros"));
        UNIT_ALIASES.put("dz", List.of("dz", "dzs", "dúzia", "dúzias", "duzia", "duzias"));
        UNIT_ALIASES.put("galao", List.of("galao", "galão", "gl"));
        UNIT_ALIASES.put("fr", List.of("fr", "frasco", "frascos"));
        UNIT_ALIASES.put("gf", List.of("gf", "garrafa", "garrafas"));
        UNIT_ALIASES.put("lata", List.of("lata", "latas"));
        UNIT_ALIASES.put("pt", List.of("pote", "potes", "pt"));
        UNIT_ALIASES.put("sc", List.of("sc", "saco", "sacos"));
        UNIT_ALIASES.put("rl", List.of("rl", "rolo", "rolos"));
        UNIT_ALIASES.put("ta", List.of("tambor", "tambores", "ta"));
        // "bandej" foi incluído aqui como alias de si mesmo (assim como toda outra chave
        // nova abreviada: fr, gf, pt, sc, rl, ta, disp, kit, cento) para casar com o caso
        // de validação manual pedido ("1bandej ovos") — a tabela original fornecida para
        // este porte listava só "bandeja", "bandejas", "bdj", sem "bandej" em si. Ver
        // relatório de porte: sinalizado, não assumido silenciosamente.
        UNIT_ALIASES.put("bandej", List.of("bandej", "bandeja", "bandejas", "bdj"));
        UNIT_ALIASES.put("disp", List.of("display", "disp"));
        UNIT_ALIASES.put("kit", List.of("kit", "kits"));
        UNIT_ALIASES.put("cento", List.of("cento", "centos"));
    }

    // alias (limpo: sem acento, minúsculo) -> forma-padrão-curta — espelha normalizeUnit.
    private static final Map<String, String> UNIT_ALIAS_TO_STD = buildAliasToStd();

    // Todos os aliases "crus" (com as formas originais), ordenados do mais longo para o
    // mais curto — para que a alternação do regex prefira a forma mais específica (ex.:
    // "unidades" em vez de "un") quando ambas seriam candidatas na mesma posição.
    private static final List<String> UNIT_RAW_ALIASES = buildRawAliases();

    private static final String UNIT_PATTERN_SOURCE = UNIT_RAW_ALIASES.stream()
            .map(ParserListaProdutosService::escapeRegexUnit)
            .collect(Collectors.joining("|"));

    // Padrão principal: quantidade + unidade + nome
    private static final Pattern PADRAO_PRINCIPAL = Pattern.compile(
            "^(\\d+(?:[.,]\\d+)?)\\s*(" + UNIT_PATTERN_SOURCE + ")\\s+(.+)$",
            Pattern.CASE_INSENSITIVE
    );

    // Fallback: linha começa com número seguido de espaço e texto (Padrão 2 da
    // especificação: "qtd + resto").
    private static final Pattern PADRAO_NUMERO_SEM_UNIDADE = Pattern.compile(
            "^(\\d+(?:[.,]\\d+)?)\\s+(.+)$"
    );

    // Linhas que só têm números devem ser ignoradas
    private static final Pattern PADRAO_SO_NUMEROS = Pattern.compile(
            "^\\d+(?:[.,]\\d+)?$"
    );

    public List<LinhaParseada> parsear(String texto) {
        List<LinhaParseada> resultado = new ArrayList<>();
        if (texto == null || texto.isBlank()) {
            return resultado;
        }

        String[] linhas = texto.split("\\r?\\n");
        for (String linha : linhas) {
            String linhaAparada = linha.strip();

            // Ignora linhas em branco
            if (linhaAparada.isEmpty()) {
                continue;
            }

            // Ignora linhas que só têm números
            if (PADRAO_SO_NUMEROS.matcher(linhaAparada).matches()) {
                continue;
            }

            resultado.add(parsearLinha(linhaAparada));
        }

        return resultado;
    }

    private LinhaParseada parsearLinha(String linha) {
        // Tenta padrão principal: quantidade + unidade + nome
        Matcher m = PADRAO_PRINCIPAL.matcher(linha);
        if (m.matches()) {
            BigDecimal quantidade = parsearQuantidade(m.group(1));
            String unidade = normalizeUnit(m.group(2));
            String nomeProduto = m.group(3).strip();
            return new LinhaParseada(linha, quantidade, unidade, nomeProduto, true);
        }

        // Fallback: linha começa com número + espaço + texto sem unidade reconhecida.
        // A especificação descreve um Padrão 2 que tenta reconhecer uma unidade no início
        // do "resto" antes de desistir — mas como PADRAO_PRINCIPAL já usa \s* (zero ou
        // mais espaços) entre quantidade e unidade sobre o MESMO dicionário, qualquer
        // entrada em que isso casaria já teria casado em PADRAO_PRINCIPAL acima; essa
        // segunda tentativa seria código morto e nunca exercitável nesta implementação,
        // então não foi replicada (ver relatório de porte).
        Matcher mFallback = PADRAO_NUMERO_SEM_UNIDADE.matcher(linha);
        if (mFallback.matches()) {
            BigDecimal quantidade = BigDecimal.ONE;
            String nomeProduto = linha.strip();
            return new LinhaParseada(linha, quantidade, "un", nomeProduto, false);
        }

        // Linha não começa com número: quantidade=1, unidade=un, nome=linha inteira
        return new LinhaParseada(linha, BigDecimal.ONE, "un", linha.strip(), false);
    }

    private BigDecimal parsearQuantidade(String texto) {
        // Aceita vírgula ou ponto como separador decimal
        return new BigDecimal(texto.replace(',', '.'));
    }

    // Converte um token de unidade capturado (ex.: "und", "Fardos", "dúzias") para a
    // forma-padrão-curta (un/fd/cx/pct/kg/ml/l/dz) via UNIT_ALIAS_TO_STD. Se não
    // reconhecido, mantém o texto original limpo (minúsculo/sem acento) — espelha
    // normalizeUnit da especificação.
    private String normalizeUnit(String raw) {
        String limpo = cleanUnitToken(raw);
        return UNIT_ALIAS_TO_STD.getOrDefault(limpo, limpo);
    }

    private static Map<String, String> buildAliasToStd() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : UNIT_ALIASES.entrySet()) {
            String std = entry.getKey();
            for (String alias : entry.getValue()) {
                map.put(cleanUnitToken(alias), std);
            }
        }
        return map;
    }

    private static List<String> buildRawAliases() {
        List<String> lista = new ArrayList<>();
        for (List<String> aliases : UNIT_ALIASES.values()) {
            lista.addAll(aliases);
        }
        lista.sort((a, b) -> b.length() - a.length());
        return lista;
    }

    private static String escapeRegexUnit(String s) {
        return s.replaceAll("([.*+?^${}()|\\[\\]\\\\])", "\\\\$1");
    }

    // Remove acentos, converte para minúsculo, remove ponto(s) final(is) e colapsa
    // espaços — mesma normalização usada em MatchingProdutoService.cleanUnitToken.
    private static String cleanUnitToken(String s) {
        if (s == null) {
            return "";
        }
        String semAcento = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return semAcento.toLowerCase().strip()
                .replaceAll("\\.+$", "")
                .replaceAll("\\s+", " ");
    }
}
