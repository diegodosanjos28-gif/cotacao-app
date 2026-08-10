package com.prx.cotacao.cotacao.respostafornecedor.parser.service;

import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.EmbalagemDetectada;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.LinhaFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.parser.dto.ResultadoResposta;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ParserRespostaFornecedorService {

    // Porte fiel do padrão numérico de parseCotacaoFornecedor do protótipo
    // (COTA&TESTA - 14.07 - V5.html): 1-3 dígitos, grupos opcionais de milhar
    // ".XXX", separador decimal (vírgula OU ponto) com 1-2 dígitos. Usado nos dois
    // ramos de match de preço (com e sem separador textual antes do número) e em
    // PRECO_TESTE_RE — mantido em um único lugar para não divergir entre os dois usos.
    //
    // DESVIO INTENCIONAL do protótipo: acrescenta "(?!\d)" ao final. Sem isso,
    // "Feijão 1,600kg - R$11,29" combina com "1,60" (dentro de "1,600") porque o
    // protótipo aceita a primeira ocorrência de 1-2 dígitos decimais que achar —
    // esse é um bug real do protótipo original, reproduzido lá mas não aqui. Com o
    // lookahead negativo, a tentativa em "1,600" falha (o "0" seguinte é dígito) e o
    // regex de preço continua avançando até casar "11,29", que é o preço correto.
    // Ver ParserRespostaFornecedorServiceTest para o caso de teste.
    private static final String NUM_DECIMAL = "\\d{1,3}(?:\\.\\d{3})*[,.]\\d{1,2}(?!\\d)";

    // Ramo 1 do protótipo: desc + separador textual ([\s\-–—:.=]+, com "R$" opcional
    // no meio) + preço + sufixo textual opcional (cada/un/unid, descartado). find()
    // (não matches()) porque texto antes e depois do preço não pode invalidar a
    // linha inteira — grupo 1 é lazy (.*?), então o match mais à esquerda/mais curto
    // "ganha", exatamente como o comportamento de String.match() no protótipo.
    private static final Pattern PADRAO_PRECO_PRINCIPAL = Pattern.compile(
            "(.*?)[\\s\\-–—:.=]+\\s*R?\\$?\\s*(" + NUM_DECIMAL + ")\\s*(?:cada|un|unid)?",
            Pattern.CASE_INSENSITIVE
    );

    // Ramo 2 (fallback) do protótipo: desc + espaço + preço no FIM da linha, sem
    // separador textual nem "R$". Na prática o Ramo 1 já cobre quase todos os casos
    // (a classe de separador já inclui espaço), mas o protótipo tenta os dois via
    // "||" e portamos os dois por fidelidade.
    private static final Pattern PADRAO_PRECO_FALLBACK = Pattern.compile(
            "(.*?)\\s+(" + NUM_DECIMAL + ")\\s*$"
    );

    // Guarda de validade do preço extraído, espelhando "preco>0 && preco<100000" do
    // protótipo.
    private static final BigDecimal PRECO_MAXIMO = new BigDecimal("100000");

    // Resultado bruto de uma tentativa de match de preço: descrição ainda sem o
    // strip de quantidade/unidade (group 1) e a string do preço ainda não parseada
    // (group 2, formato bruto "1.234,56"/"4,89"/"4.89"/"4,5").
    private record MatchPreco(String descBruta, String precoStr) {
    }

    // Padrões de sem estoque
    private static final Pattern PADRAO_SEM_ESTOQUE = Pattern.compile(
            "sem\\s+estoque|n[aã]o\\s+tem|nao\\s+tem|indispon[ií]vel|falta",
            Pattern.CASE_INSENSITIVE
    );

    // Padrões de linhas a ignorar
    private static final Pattern PADRAO_SKIP = Pattern.compile(
            "bom\\s+dia|boa\\s+tarde|boa\\s+noite|pagamento|entrega|prazo|observ|att|atenciosamente|abra[cç]o|obrigado",
            Pattern.CASE_INSENSITIVE
    );

    // ===== Campos novos (parseCotacaoFornecedorEnhanced do protótipo) =====

    // Desmembra linha com múltiplos produtos separados por "|"/";" — só desmembra se pelo
    // menos 2 das partes resultantes tiverem um padrão de preço reconhecível (evita quebrar
    // uma linha só porque tem um "|" decorativo). Espelha desmembrarLinhaFornecedor +
    // PRECO_TESTE_RE do protótipo. Reaproveita NUM_DECIMAL (não duplica a string do
    // regex em dois lugares — ver comentário em NUM_DECIMAL).
    private static final Pattern PRECO_TESTE_RE = Pattern.compile(NUM_DECIMAL);
    private static final char[] SEPARADORES_MULTIPLOS_PRODUTOS = {'|', ';'};

    // Quantidade/unidade informada no INÍCIO da linha, antes da descrição (ex.: "3 cx Sazon...").
    private static final Pattern PADRAO_QTD_INICIO = Pattern.compile(
            "^(\\d+)\\s*(un|fd|f|cx|pct|kg|ml|lt?|dz)\\s+", Pattern.CASE_INSENSITIVE
    );

    // Linha do tipo "consultar"/"a combinar"/etc — preço ainda não informado pelo fornecedor.
    private static final Pattern PADRAO_BULLET_INICIO = Pattern.compile("^[-–—•*]\\s*");
    private static final Pattern PADRAO_CONSULTAR = Pattern.compile(
            "\\b(consultar|a\\s+combinar|sob\\s+consulta|ver\\s+preço|sob\\s+demanda)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PADRAO_CONSULTAR_STRIP = Pattern.compile(
            "[-–—:]\\s*(consultar|a\\s+combinar|sob\\s+consulta|ver\\s+preço|sob\\s+demanda).*",
            Pattern.CASE_INSENSITIVE
    );

    private final MatchingProdutoService matchingProduto;

    public ParserRespostaFornecedorService(MatchingProdutoService matchingProduto) {
        this.matchingProduto = matchingProduto;
    }

    public ResultadoResposta parsear(String texto) {
        if (texto == null || texto.isBlank()) {
            return new ResultadoResposta("", List.of());
        }

        String[] linhas = texto.split("\\r?\\n");
        String nomeFornecedor = "";
        List<LinhaFornecedor> resultado = new ArrayList<>();
        boolean primeiraLinhaUtil = true;

        for (String linha : linhas) {
            String linhaAparada = linha.strip();

            // Ignora linhas em branco
            if (linhaAparada.isEmpty()) {
                continue;
            }

            if (primeiraLinhaUtil) {
                primeiraLinhaUtil = false;
                // Primeira linha não-vazia só é tratada como nome do fornecedor se
                // ela não parecer uma linha de produto/preço. O texto colado na tela
                // de entrada (canal web) não tem cabeçalho de fornecedor — o nome já
                // é escolhido antes, via dropdown — então tratar a primeira linha
                // como nome incondicionalmente engolia o primeiro produto real
                // (bug: "Sazon Legumes 60g - R$ 4,89" como 1ª linha era descartado).
                if (!pareceLinhaDeProduto(linhaAparada)) {
                    nomeFornecedor = linhaAparada;
                    continue;
                }
            }

            // Desmembra ANTES do parsing linha-a-linha: uma linha com mais de um produto
            // separado por "|"/";" vira múltiplos registros independentes.
            for (String subLinha : desmembrarLinhaFornecedor(linhaAparada)) {
                resultado.add(parsearLinhaFornecedor(subLinha));
            }
        }

        return new ResultadoResposta(nomeFornecedor, resultado);
    }

    // Heurística para decidir se uma linha parece conter um produto (preço
    // reconhecível ou marcação de "sem estoque"), usada só para não engolir a
    // primeira linha como nome de fornecedor quando ela na verdade já é um item.
    // Usa a MESMA extração de preço de parsearLinhaFornecedor (extrairMatchPreco) —
    // não um padrão à parte que possa divergir com o tempo.
    private boolean pareceLinhaDeProduto(String linha) {
        return extrairMatchPreco(linha) != null || PADRAO_SEM_ESTOQUE.matcher(linha).find();
    }

    private List<String> desmembrarLinhaFornecedor(String linha) {
        for (char sep : SEPARADORES_MULTIPLOS_PRODUTOS) {
            if (linha.indexOf(sep) == -1) {
                continue;
            }
            List<String> partes = new ArrayList<>();
            for (String parte : linha.split(Pattern.quote(String.valueOf(sep)))) {
                String p = parte.strip();
                if (!p.isEmpty()) {
                    partes.add(p);
                }
            }
            if (partes.size() < 2) {
                continue;
            }
            long comPreco = partes.stream().filter(p -> PRECO_TESTE_RE.matcher(p).find()).count();
            if (comPreco >= 2) {
                return partes;
            }
        }
        return List.of(linha);
    }

    private LinhaFornecedor parsearLinhaFornecedor(String linha) {
        // Verifica se deve ser ignorada
        if (PADRAO_SKIP.matcher(linha).find()) {
            return new LinhaFornecedor(linha, null, null, false, true,
                    null, null, null, false, null, null);
        }

        // Verifica sem estoque
        if (PADRAO_SEM_ESTOQUE.matcher(linha).find()) {
            String nomeProduto = extrairNomeParaSemEstoque(linha);
            return new LinhaFornecedor(linha, nomeProduto, null, true, false,
                    null, null, "unidade", false, null, null);
        }

        // Tenta extrair preço (Ramo 1, senão Ramo 2 — ver PADRAO_PRECO_PRINCIPAL/
        // PADRAO_PRECO_FALLBACK). find() (não matches()) porque a linha inteira não
        // precisa bater com o padrão — texto antes ("Produto - ") e depois ("cada",
        // "/un") do valor são esperados e não podem invalidar a extração.
        MatchPreco matchPreco = extrairMatchPreco(linha);
        if (matchPreco != null) {
            BigDecimal preco = parsearPreco(matchPreco.precoStr());
            String nomeProduto = extrairDescComPreco(matchPreco.descBruta());

            // Guarda de validade: espelha "preco>0 && preco<100000 && desc.length>2"
            // do protótipo. DESVIO INTENCIONAL: o protótipo simplesmente descarta a
            // linha (return, sem adicionar a items[]) quando a guarda reprova. Este
            // backend nunca descarta uma linha de resposta do fornecedor em
            // silêncio — quem decide como sinalizar ao usuário (ex.: "não
            // reconhecida") é o caller (FornecedorRespostaService), então aqui
            // caímos para o fluxo de "sem preço reconhecível" abaixo em vez de
            // descartar.
            if (preco.compareTo(BigDecimal.ZERO) > 0 && preco.compareTo(PRECO_MAXIMO) < 0
                    && nomeProduto.length() > 2) {
                // Campos novos — referência interna, não alteram a interpretação do preço.
                EmbalagemDetectada embalagemDetectada = matchingProduto.detectarEmbalagemPorTexto(linha);
                String marcaOferecida = matchingProduto.extrairMarca(nomeProduto);
                Integer qtdInformada = null;
                String unInformada = null;
                Matcher mQtd = PADRAO_QTD_INICIO.matcher(linha);
                if (mQtd.find()) {
                    qtdInformada = Integer.parseInt(mQtd.group(1));
                    unInformada = mQtd.group(2).toLowerCase();
                }

                return new LinhaFornecedor(linha, nomeProduto, preco, false, false,
                        embalagemDetectada, marcaOferecida, "unidade", false, qtdInformada, unInformada);
            }
        }

        // Linha sem preço reconhecível: verifica se é do tipo "consultar"/"a combinar".
        String cleanLine = PADRAO_BULLET_INICIO.matcher(linha).replaceFirst("").strip();
        if (cleanLine.length() > 3 && PADRAO_CONSULTAR.matcher(cleanLine).find()) {
            String descClean = PADRAO_CONSULTAR_STRIP.matcher(cleanLine).replaceAll("").strip();
            if (descClean.length() > 3) {
                String marcaOferecida = matchingProduto.extrairMarca(descClean);
                return new LinhaFornecedor(linha, descClean, null, false, false,
                        null, marcaOferecida, "sem_preco", true, null, null);
            }
        }

        // Linha não reconhecida — nenhum preço extraível e sem estoque não marcado.
        // preco fica null (nunca vira R$0,00 silencioso aqui — quem decide como
        // sinalizar isso pro usuário é o caller, ver FornecedorRespostaService).
        // Preserva o texto como nome do produto para que o caller possa decidir.
        return new LinhaFornecedor(linha, linha, null, false, false,
                null, null, null, false, null, null);
    }

    // Tenta o Ramo 1 (com separador textual / R$ opcional) e, se não casar, o Ramo 2
    // (fallback, preço obrigatoriamente no fim da linha) — replica o "||" entre os
    // dois .match() de parseCotacaoFornecedor. Retorna null se nenhum casar.
    private MatchPreco extrairMatchPreco(String linha) {
        Matcher m = PADRAO_PRECO_PRINCIPAL.matcher(linha);
        if (!m.find()) {
            m = PADRAO_PRECO_FALLBACK.matcher(linha);
            if (!m.find()) {
                return null;
            }
        }
        return new MatchPreco(m.group(1), m.group(2));
    }

    // Porte de "desc=precoMatch[1].replace(/^[\d]+\s*(un|fd|f|cx|pct|kg|ml|lt?|dz)\s+/i,'').trim();
    // if(!desc)desc=precoMatch[1].trim();" — remove só a primeira ocorrência do
    // prefixo de quantidade/unidade (replaceFirst ~ replace sem /g), com fallback
    // para o texto original quando o strip esvazia a string. Reaproveita
    // PADRAO_QTD_INICIO (mesmo regex de unidade usado para extrair qtdInformada/
    // unInformada) para não duplicar essa lista de unidades em dois padrões.
    private String extrairDescComPreco(String descBruta) {
        String desc = PADRAO_QTD_INICIO.matcher(descBruta).replaceFirst("").strip();
        if (desc.isEmpty()) {
            desc = descBruta.strip();
        }
        return desc;
    }

    private String extrairNomeParaSemEstoque(String linha) {
        // Remove a expressão de sem-estoque e retorna o restante como nome do produto
        String nome = PADRAO_SEM_ESTOQUE.matcher(linha).replaceAll("").strip();
        // Remove separadores residuais como " - " ou ":"
        nome = nome.replaceAll("[-:]+$", "").strip();
        if (nome.isEmpty()) {
            return linha;
        }
        return nome;
    }

    // Porte de parsePrecoDinheiro do protótipo. NUM_DECIMAL garante que o texto
    // recebido aqui só contém dígitos e, no máximo, pontos (milhar) + um separador
    // decimal final (vírgula OU ponto) com 1-2 dígitos — então o ramo "só ponto,
    // última parte com mais de 2 dígitos" do protótipo é teoricamente inalcançável
    // partindo de PADRAO_PRECO_PRINCIPAL/FALLBACK, mas mantido por fidelidade ao
    // original (ex.: chamada futura direta com outro texto).
    private BigDecimal parsearPreco(String textoPreco) {
        String t = textoPreco.replaceAll("[R$\\s]", "");
        if (t.contains(",") && t.contains(".")) {
            int ultimaVirgula = t.lastIndexOf(',');
            int ultimoPonto = t.lastIndexOf('.');
            if (ultimaVirgula > ultimoPonto) {
                // Formato BR: 1.234,56 -> ponto é milhar, vírgula é decimal.
                t = t.replace(".", "").replace(",", ".");
            } else {
                // Formato US: 1,234.56 -> vírgula é milhar, ponto é decimal.
                t = t.replace(",", "");
            }
        } else if (t.contains(",")) {
            t = t.replace(',', '.');
        } else if (t.contains(".")) {
            int ultimoPonto = t.lastIndexOf('.');
            if (t.length() - ultimoPonto - 1 > 2) {
                // Ponto como milhar (parte final com mais de 2 dígitos não é decimal).
                t = t.replace(".", "");
            }
            // Senão: ponto já é o separador decimal, usa o texto como está.
        }
        return new BigDecimal(t);
    }
}
