package com.prx.cotacao.cotacao.respostafornecedor.parser.service;

import com.prx.cotacao.catalogo.repository.MarcaRepository;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Motor básico de matching/classificação de texto de produto — porte fiel de
 * calcSimilaridade/extrairPesoVolume/normTxt/conciliarProdutosCotados/detectarEmbalagem
 * (texto) do protótipo HTML validado com o cliente. Ver
 * /home/nicolas/.claude/plans/prompt-refatora-o-golden-goblet.md seções 0 e 4.
 */
@Service
public class MatchingProdutoService {

    private final MarcaRepository marcaRepository;

    public MatchingProdutoService(MarcaRepository marcaRepository) {
        this.marcaRepository = marcaRepository;
    }

    // ===================== Tabela de aliases de medida (peso/volume) =====================
    // Espelha MEDIDA_ALIASES do protótipo.
    private static final Map<String, List<String>> MEDIDA_ALIASES = new LinkedHashMap<>();
    static {
        MEDIDA_ALIASES.put("ml", List.of("ml", "mls", "mililitro", "mililitros"));
        MEDIDA_ALIASES.put("l", List.of("l", "lt", "lts", "litro", "litros", "ltr", "ltrs"));
        MEDIDA_ALIASES.put("kg", List.of("kg", "kgs", "kilo", "kilos", "quilo", "quilos",
                "quilograma", "quilogramas", "kilograma", "kilogramas"));
        MEDIDA_ALIASES.put("g", List.of("g", "gr", "grs", "grama", "gramas"));
    }

    // dimensao: "peso" para kg/g, "volume" para ml/l — espelha MEDIDA_DIM.
    private static final Map<String, String> MEDIDA_DIM = Map.of(
            "ml", "volume", "l", "volume", "kg", "peso", "g", "peso"
    );

    // Fator de conversão para a unidade-base da dimensão (g para peso, ml para volume)
    // — espelha MEDIDA_TO_BASE.
    private static final Map<String, BigDecimal> MEDIDA_TO_BASE = Map.of(
            "ml", BigDecimal.ONE,
            "l", BigDecimal.valueOf(1000),
            "kg", BigDecimal.valueOf(1000),
            "g", BigDecimal.ONE
    );

    // alias (limpo) -> unidade padrão — espelha MEDIDA_ALIAS_TO_STD.
    private static final Map<String, String> MEDIDA_ALIAS_TO_STD = buildAliasToStd();

    // Todos os aliases "crus" (com as formas originais), ordenados do mais longo para o
    // mais curto — para que a alternação do regex prefira "litros" a "l" quando ambos
    // seriam candidatos na mesma posição. Espelha MEDIDA_RAW_ALIASES.
    private static final List<String> MEDIDA_RAW_ALIASES = buildRawAliases();

    private static final String MEDIDA_PATTERN_SOURCE = MEDIDA_RAW_ALIASES.stream()
            .map(MatchingProdutoService::escapeRegexUnit)
            .collect(Collectors.joining("|"));

    // Primeiro match apenas (sem flag global) — espelha MEDIDA_REGEX_CAP, usado tanto
    // para EXTRAIR a medida (extrairPesoVolume) quanto para SUBSTITUIR a primeira
    // ocorrência por um marcador único (calcSimilaridade).
    private static final Pattern MEDIDA_REGEX_CAP = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(" + MEDIDA_PATTERN_SOURCE + ")\\b",
            Pattern.CASE_INSENSITIVE
    );

    // Todas as ocorrências — espelha MEDIDA_REGEX_G (flag 'g'), usado em
    // calcSimilaridadeSemVolume para remover TODAS as medidas do texto antes de comparar.
    private static final Pattern MEDIDA_REGEX_G = Pattern.compile(
            "\\d+(?:[.,]\\d+)?\\s*(?:" + MEDIDA_PATTERN_SOURCE + ")\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Set<String> STOPWORDS = Set.of(
            "de", "do", "da", "com", "para", "por", "em", "sem", "tipo", "marca"
    );

    private static final Pattern TOKEN_NUMERICO = Pattern.compile("^\\d+$");

    private static final BigDecimal LIMIAR_DIVERGENCIA_MEDIDA = new BigDecimal("0.001");
    private static final BigDecimal BONUS_MESMA_MEDIDA = new BigDecimal("0.15");
    private static final BigDecimal BONUS_VALOR_BRUTO_IGUAL = new BigDecimal("0.05");

    private static final BigDecimal SCORE_MINIMO_MATCH = new BigDecimal("0.6");
    private static final BigDecimal RAZAO_EMBALAGEM = new BigDecimal("3.0");

    // ===================== Marcas conhecidas (extrairMarca) =====================
    // A lista fixa de marcas (antiga MARCAS_CONHECIDAS hardcoded) virou o catálogo
    // marca (V16) — global (tenant_id NULL) + extensões por tenant — para permitir
    // adicionar marca sem redeploy. Ver marcaRepository.findNomesOrdenadosPorEspecificidade().

    private static final Set<String> PALAVRAS_EXCLUIDAS_MARCA = Set.of(
            "Com", "Sem", "Para", "Por", "Tipo", "Sabor", "Lata", "Pet", "Garrafa", "Vidro"
    );

    // ===================== Detecção de embalagem por TEXTO (detectarEmbalagemPorTexto) =====
    private static final Pattern PADRAO_TIPO_CAIXA = Pattern.compile("\\b(caixa|cx)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PADRAO_TIPO_FARDO = Pattern.compile("\\b(fardo|fd)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PADRAO_TIPO_PACOTE = Pattern.compile("\\b(pacote|pct|pack)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PADRAO_TIPO_EMBALAGEM_GENERICA = Pattern.compile("\\bembalagem\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern PADRAO_QTD_EMBALAGEM_1 = Pattern.compile(
            "(?:caixa|cx|fardo|fd|pacote|pct|pack|embalagem)\\s*(?:com|c\\s*/?\\s*)\\s*(\\d+)"
                    + "(?!\\s*[.,]\\d)"
                    + "(?!\\s*(?:kg|kgs|g|gr|grs|grama|gramas|ml|mls|l|lt|lts|litro|litros|mg)\\b)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PADRAO_QTD_EMBALAGEM_2 = Pattern.compile(
            "(\\d+)\\s*(?:un(?:id(?:ades?)?)?)\\s*(?:por|/)\\s*(?:caixa|cx|fardo|fd|pacote|pct)",
            Pattern.CASE_INSENSITIVE
    );

    private static Map<String, String> buildAliasToStd() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : MEDIDA_ALIASES.entrySet()) {
            String std = entry.getKey();
            for (String alias : entry.getValue()) {
                map.put(cleanUnitToken(alias), std);
            }
        }
        return map;
    }

    private static List<String> buildRawAliases() {
        List<String> lista = new ArrayList<>();
        for (List<String> aliases : MEDIDA_ALIASES.values()) {
            lista.addAll(aliases);
        }
        lista.sort((a, b) -> b.length() - a.length());
        return lista;
    }

    private static String escapeRegexUnit(String s) {
        return s.replaceAll("([.*+?^${}()|\\[\\]\\\\])", "\\\\$1");
    }

    // cleanUnitToken: remove acentos, minúsculo, trim, remove ponto(s) final(is), colapsa espaços.
    private static String cleanUnitToken(String s) {
        if (s == null) {
            return "";
        }
        String semAcento = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        String limpo = semAcento.toLowerCase().strip()
                .replaceAll("\\.+$", "")
                .replaceAll("\\s+", " ");
        return limpo;
    }

    /**
     * Remove acentos, converte para minúsculo, colapsa espaços múltiplos. Espelha
     * normTxt do protótipo LITERALMENTE — ao contrário da versão anterior deste método,
     * NÃO remove pontuação (isso é feito localmente, no ponto certo do algoritmo, por
     * calcSimilaridade/calcSimilaridadeSemVolume — ver ressalva no relatório de porte).
     */
    public String normTxt(String texto) {
        if (texto == null) {
            return "";
        }
        String semAcento = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        String minusculo = semAcento.toLowerCase();
        return minusculo.replaceAll("\\s+", " ").strip();
    }

    /**
     * Extrai a primeira medida de peso/volume do texto (valor + unidade normalizada +
     * dimensão + valor convertido para a unidade-base). Retorna null se não encontrar.
     * Opera sobre o texto BRUTO (não normalizado via normTxt) — espelha extrairPesoVolume.
     */
    public PesoVolume extrairPesoVolume(String texto) {
        if (texto == null || texto.isEmpty()) {
            return null;
        }
        Matcher m = MEDIDA_REGEX_CAP.matcher(texto);
        if (!m.find()) {
            return null;
        }
        BigDecimal valor;
        try {
            valor = new BigDecimal(m.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
        String clean = cleanUnitToken(m.group(2));
        String std = MEDIDA_ALIAS_TO_STD.getOrDefault(clean, clean);
        String dimensao = MEDIDA_DIM.get(std);
        BigDecimal valorBase = dimensao != null ? valor.multiply(MEDIDA_TO_BASE.get(std)) : valor;
        return new PesoVolume(valor, std, dimensao, valorBase);
    }

    /**
     * Calcula score de similaridade entre o texto cotado (fornecedor ou lista colada) e o
     * nome de referência (produto do catálogo/lista base). Porte fiel de calcSimilaridade
     * do protótipo — preserva atalho de igualdade exata, regra de dimensão/valorBase,
     * substituição de marcador de medida, stopwords, regra de token numérico (só casa por
     * igualdade exata) vs não-numérico (igualdade OU substring em qualquer direção), e os
     * bônus +0.15 (mesma medida) / +0.05 (valor bruto igual, mesmo com unidades diferentes
     * — literal do protótipo, não uma inconsistência minha).
     */
    public BigDecimal calcSimilaridade(String txtCotado, String nomeReferencia) {
        String a = normTxt(txtCotado);
        String b = normTxt(nomeReferencia);
        if (a.equals(b)) {
            return BigDecimal.ONE;
        }

        PesoVolume pvA = extrairPesoVolume(txtCotado);
        PesoVolume pvB = extrairPesoVolume(nomeReferencia);

        boolean mesmaDimensao = pvA != null && pvB != null
                && pvA.dimensao() != null && pvB.dimensao() != null
                && pvA.dimensao().equals(pvB.dimensao());
        boolean mesmaMedida = mesmaDimensao
                && pvA.valorBase().subtract(pvB.valorBase()).abs().compareTo(LIMIAR_DIVERGENCIA_MEDIDA) <= 0;

        // Medida é atributo obrigatório de compatibilidade quando presente nas duas
        // descrições: mesma dimensão mas valorBase diverge → produtos diferentes, mesmo
        // que o nome seja idêntico.
        if (mesmaDimensao && !mesmaMedida) {
            return BigDecimal.ZERO;
        }

        String aCmp = mesmaMedida ? MEDIDA_REGEX_CAP.matcher(a).replaceFirst("medidaxx") : a;
        String bCmp = mesmaMedida ? MEDIDA_REGEX_CAP.matcher(b).replaceFirst("medidaxx") : b;

        List<String> tokensA = tokenizar(aCmp, true);
        List<String> tokensB = tokenizar(bCmp, true);

        List<String> sigA = tokensA.stream().filter(t -> !STOPWORDS.contains(t)).toList();
        List<String> sigB = tokensB.stream().filter(t -> !STOPWORDS.contains(t)).toList();

        if (sigA.isEmpty() || sigB.isEmpty()) {
            return BigDecimal.ZERO;
        }

        int matches = 0;
        for (String ta : sigA) {
            boolean numA = TOKEN_NUMERICO.matcher(ta).matches();
            boolean achou = false;
            for (String tb : sigB) {
                boolean numB = TOKEN_NUMERICO.matcher(tb).matches();
                if (numA || numB) {
                    // Números só "casam" se forem EXATAMENTE iguais (nunca por substring).
                    if (ta.equals(tb)) {
                        achou = true;
                        break;
                    }
                } else if (tb.equals(ta) || tb.contains(ta) || ta.contains(tb)) {
                    achou = true;
                    break;
                }
            }
            if (achou) {
                matches++;
            }
        }

        double score = (double) matches / Math.max(sigA.size(), sigB.size());
        if (mesmaMedida) {
            score += BONUS_MESMA_MEDIDA.doubleValue();
        } else if (pvA != null && pvB != null && pvA.valor().compareTo(pvB.valor()) == 0) {
            // Compara o VALOR BRUTO (não o valorBase) — preservado literalmente do
            // protótipo mesmo parecendo inconsistente com a regra de dimensão acima.
            score += BONUS_VALOR_BRUTO_IGUAL.doubleValue();
        }
        score = Math.min(score, 1.0);
        return BigDecimal.valueOf(score);
    }

    /**
     * Mesma tokenização de calcSimilaridade, mas removendo TODAS as ocorrências de medida
     * do texto antes de comparar — usada como 2ª passada quando a comparação normal não
     * atinge score suficiente (possível divergência de volume). Sem bônus de medida.
     */
    public BigDecimal calcSimilaridadeSemVolume(String txtCotado, String nomeReferencia) {
        String a = MEDIDA_REGEX_G.matcher(normTxt(txtCotado)).replaceAll("").replaceAll("\\s+", " ").strip();
        String b = MEDIDA_REGEX_G.matcher(normTxt(nomeReferencia)).replaceAll("").replaceAll("\\s+", " ").strip();
        if (a.isEmpty() || b.isEmpty()) {
            return BigDecimal.ZERO;
        }

        List<String> tokensA = tokenizar(a, false);
        List<String> tokensB = tokenizar(b, false);

        List<String> sigA = tokensA.stream().filter(t -> !STOPWORDS.contains(t)).toList();
        List<String> sigB = tokensB.stream().filter(t -> !STOPWORDS.contains(t)).toList();
        if (sigA.isEmpty() || sigB.isEmpty()) {
            return BigDecimal.ZERO;
        }

        int matches = 0;
        for (String ta : sigA) {
            boolean achou = sigB.stream().anyMatch(tb -> tb.equals(ta) || tb.contains(ta) || ta.contains(tb));
            if (achou) {
                matches++;
            }
        }
        double score = (double) matches / Math.max(sigA.size(), sigB.size());
        return BigDecimal.valueOf(score);
    }

    // tokensX = txt.replace(/[^a-z0-9\s]/g,' ').split(/\s+/).filter(...)
    // permiteNumeroCurto=true replica o filtro de calcSimilaridade (t.length>1 OU numérico);
    // permiteNumeroCurto=false replica o filtro de calcSimilaridadeSemVolume (só t.length>1).
    private List<String> tokenizar(String texto, boolean permiteNumeroCurto) {
        String limpo = texto.replaceAll("[^a-z0-9\\s]", " ");
        String[] partes = limpo.split("\\s+");
        List<String> tokens = new ArrayList<>();
        for (String p : partes) {
            if (p.length() > 1 || (permiteNumeroCurto && TOKEN_NUMERICO.matcher(p).matches())) {
                tokens.add(p);
            }
        }
        return tokens;
    }

    /**
     * Detecta divergência de peso/volume entre o nome solicitado (item da lista base) e o
     * nome oferecido (resposta do fornecedor): mesma dimensão, mas valorBase diferente.
     * Retorna null se não houver medida nos dois textos, ou se a dimensão não bater, ou se
     * os valores forem equivalentes.
     */
    public DivergenciaVolume detectarDivergenciaVolume(String nomeSolicitado, String nomeOferecido) {
        PesoVolume pvSol = extrairPesoVolume(nomeSolicitado);
        PesoVolume pvOf = extrairPesoVolume(nomeOferecido);
        if (pvSol == null || pvOf == null) {
            return null;
        }
        if (pvSol.dimensao() == null || pvOf.dimensao() == null || !pvSol.dimensao().equals(pvOf.dimensao())) {
            return null;
        }
        if (pvSol.valorBase().subtract(pvOf.valorBase()).abs().compareTo(LIMIAR_DIVERGENCIA_MEDIDA) > 0) {
            return new DivergenciaVolume(
                    formatarValorUnidade(pvSol.valor(), pvSol.unidade()),
                    formatarValorUnidade(pvOf.valor(), pvOf.unidade()),
                    true
            );
        }
        return null;
    }

    private String formatarValorUnidade(BigDecimal valor, String unidade) {
        return valor.stripTrailingZeros().toPlainString() + unidade;
    }

    /**
     * Extrai a marca do nome do produto: primeiro tenta o catálogo de marcas conhecidas
     * (globais + do tenant atual, via RLS — substring, mais específica/longa ganha); se
     * não achar, cai para a heurística de "palavra capitalizada" excluindo um conjunto de
     * palavras comuns não-marca.
     */
    public String extrairMarca(String nome) {
        if (nome == null) {
            return null;
        }
        String n = normTxt(nome);
        for (String marca : marcaRepository.findNomesOrdenadosPorEspecificidade()) {
            if (n.contains(marca)) {
                return marca;
            }
        }
        String[] palavras = nome.split("\\s+");
        for (String w : palavras) {
            if (w.isEmpty()) {
                continue;
            }
            char c0 = w.charAt(0);
            boolean pareceMaiuscula = String.valueOf(c0).equals(String.valueOf(c0).toUpperCase());
            if (w.length() > 2 && pareceMaiuscula && !Character.isDigit(c0)
                    && !PALAVRAS_EXCLUIDAS_MARCA.contains(w)) {
                return normTxt(w);
            }
        }
        return null;
    }

    /**
     * Detecta tipo de embalagem (caixa/fardo/pacote/embalagem) por REGEX DE TEXTO e tenta
     * extrair a quantidade interna (ex.: "caixa com 12"). Sem relação com preço — espelha
     * detectarEmbalagem(texto) do protótipo. Não confundir com
     * {@link #detectarEmbalagemPorPreco}, a heurística de razão de preço já existente
     * neste backend (usada hoje por FornecedorRespostaService).
     */
    public EmbalagemDetectada detectarEmbalagemPorTexto(String texto) {
        if (texto == null) {
            return null;
        }
        String tipoEmbalagem;
        if (PADRAO_TIPO_CAIXA.matcher(texto).find()) {
            tipoEmbalagem = "caixa";
        } else if (PADRAO_TIPO_FARDO.matcher(texto).find()) {
            tipoEmbalagem = "fardo";
        } else if (PADRAO_TIPO_PACOTE.matcher(texto).find()) {
            tipoEmbalagem = "pacote";
        } else if (PADRAO_TIPO_EMBALAGEM_GENERICA.matcher(texto).find()) {
            tipoEmbalagem = "embalagem";
        } else {
            return null;
        }

        Integer qtdInterna = null;
        Matcher m1 = PADRAO_QTD_EMBALAGEM_1.matcher(texto);
        Matcher m2 = PADRAO_QTD_EMBALAGEM_2.matcher(texto);
        if (m1.find()) {
            qtdInterna = Integer.parseInt(m1.group(1));
        } else if (m2.find()) {
            qtdInterna = Integer.parseInt(m2.group(1));
        }
        return new EmbalagemDetectada(tipoEmbalagem, qtdInterna);
    }

    /**
     * Para cada nome a casar, encontra o produto do catálogo com maior score.
     * Score >= 0.6 → MATCHED; score < 0.6 → NOT_MATCHED.
     * Assinatura/comportamento preservados — único efeito colateral esperado da reescrita
     * de calcSimilaridade é a mudança de QUAIS pares atingem o limiar 0.6, não a lógica
     * deste método em si.
     */
    public List<ResultadoConciliacao> conciliarProdutosCotados(
            List<ProdutoCatalogo> catalogo,
            List<String> nomesParaCasar
    ) {
        return nomesParaCasar.stream()
                .map(nome -> encontrarMelhorCandidato(catalogo, nome))
                .toList();
    }

    /**
     * Mesma busca de melhor candidato usada por {@link #conciliarProdutosCotados}, mas
     * para um único nome — extraída para permitir matching INCREMENTAL (catálogo
     * crescendo a cada linha processada) em {@code CotacaoListaService}, sem duplicar a
     * lógica de "maior score vence" aqui.
     */
    public ResultadoConciliacao encontrarMelhorCandidato(List<ProdutoCatalogo> catalogo, String nome) {
        UUID melhorId = null;
        BigDecimal melhorScore = BigDecimal.ZERO;

        for (ProdutoCatalogo produto : catalogo) {
            BigDecimal score = calcSimilaridade(nome, produto.nome());
            if (score.compareTo(melhorScore) > 0) {
                melhorScore = score;
                melhorId = produto.id();
            }
        }

        boolean matched = melhorScore.compareTo(SCORE_MINIMO_MATCH) >= 0;
        return new ResultadoConciliacao(nome, matched ? melhorId : null, melhorScore, matched);
    }

    /**
     * Detecta se precoItem pode ser de embalagem (caixa/fardo) em vez de unidade, por RAZÃO
     * DE PREÇO (mediana dos outros preços da resposta) ou por palavra de embalagem no texto.
     * Renomeado de detectarEmbalagem para detectarEmbalagemPorPreco — mesma assinatura e
     * mesmo comportamento, só o nome mudou, para não colidir semanticamente com
     * {@link #detectarEmbalagemPorTexto} (heurística de texto do protótipo, adicionada
     * nesta mesma leva de mudanças).
     */
    public boolean detectarEmbalagemPorPreco(
            BigDecimal precoItem,
            List<BigDecimal> outrosPrecos,
            String textoLinha
    ) {
        // Verifica palavras de embalagem no texto
        if (textoLinha != null) {
            String textoNorm = textoLinha.toLowerCase();
            if (textoNorm.contains("caixa") || textoNorm.contains("cx")
                    || textoNorm.contains("fardo") || textoNorm.contains("fd")
                    || textoNorm.contains("pacote") || textoNorm.contains("pct")) {
                return true;
            }
        }

        // Verifica razão precoItem / mediana
        if (precoItem != null && outrosPrecos != null && !outrosPrecos.isEmpty()) {
            BigDecimal mediana = calcularMediana(outrosPrecos);
            if (mediana.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal razao = precoItem.divide(mediana, 4, RoundingMode.HALF_UP);
                if (razao.compareTo(RAZAO_EMBALAGEM) >= 0) {
                    return true;
                }
            }
        }

        return false;
    }

    private BigDecimal calcularMediana(List<BigDecimal> valores) {
        List<BigDecimal> ordenados = new ArrayList<>(valores);
        ordenados.sort(BigDecimal::compareTo);
        int n = ordenados.size();
        if (n % 2 == 1) {
            return ordenados.get(n / 2);
        } else {
            BigDecimal soma = ordenados.get(n / 2 - 1).add(ordenados.get(n / 2));
            return soma.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        }
    }
}
