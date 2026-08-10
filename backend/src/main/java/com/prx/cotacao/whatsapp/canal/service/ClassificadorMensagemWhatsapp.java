package com.prx.cotacao.whatsapp.canal.service;

import com.prx.cotacao.whatsapp.canal.dto.ResultadoClassificacao;
import com.prx.cotacao.whatsapp.canal.enums.TipoMensagemWhatsapp;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Classificador de mensagem (doc técnica, seção 10.3): a PRIMEIRA linha da mensagem
 * precisa ser um dos dois marcadores {@code LISTA_PRODUTOS}/{@code RESPOSTA_FORNECEDOR}
 * (mesmo nome do enum {@link TipoMensagemWhatsapp}) — sem isso, não há classificação.
 * Não existe mais heurística de conteúdo (a antiga detecção por "R$" foi removida):
 * universo fechado de 2 alvos, nada além disso é aceito.
 *
 * <p>Tolerância à digitação em duas camadas: (1) normalização — maiúsculas, sem
 * acento, separador (espaço/underscore/hífen) colapsado — cobre "lista_produtos",
 * "Lista-Produtos", "LISTA  PRODUTOS"; (2) similaridade por distância de Levenshtein
 * contra as formas canônicas normalizadas, limiar 0.75 — cobre erro de digitação
 * (letra faltando/trocada/transposta, ex. "LISTA PRODUTO" sem o S final). Um match
 * exato tem distância 0 (score 1.0), então a mesma fórmula cobre alias exato e typo sem
 * precisar de dois caminhos de código que poderiam divergir na borda.</p>
 *
 * <p>Se a primeira linha não atingir o limiar contra nenhum dos dois alvos, ou empatar
 * exatamente entre os dois acima do limiar (ambíguo), o resultado é
 * {@link Optional#empty()} — "formato não reconhecido". {@link WhatsappWebhookService}
 * trata isso ANTES de chamar qualquer pipeline de persistência.</p>
 */
@Service
public class ClassificadorMensagemWhatsapp {

    private static final double LIMIAR_SIMILARIDADE = 0.75;

    private static final Pattern SEPARADORES = Pattern.compile("[\\s_-]+");

    // Formas canônicas derivadas do próprio nome do enum (normalizadas do mesmo jeito
    // que a primeira linha da mensagem) — nunca hardcoded como string solta, pra não
    // dessincronizar silenciosamente se TipoMensagemWhatsapp for renomeado.
    private static final String CANONICO_LISTA = normalizar(TipoMensagemWhatsapp.LISTA_PRODUTOS.name());
    private static final String CANONICO_RESPOSTA = normalizar(TipoMensagemWhatsapp.RESPOSTA_FORNECEDOR.name());

    public Optional<ResultadoClassificacao> classificar(String texto) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }

        int quebra = texto.indexOf('\n');
        String primeiraLinha = quebra == -1 ? texto : texto.substring(0, quebra);
        String corpo = quebra == -1 ? "" : texto.substring(quebra + 1);

        String linhaNormalizada = normalizar(primeiraLinha);
        if (linhaNormalizada.isEmpty()) {
            return Optional.empty();
        }

        double simLista = similaridade(linhaNormalizada, CANONICO_LISTA);
        double simResposta = similaridade(linhaNormalizada, CANONICO_RESPOSTA);

        boolean listaOk = simLista >= LIMIAR_SIMILARIDADE;
        boolean respostaOk = simResposta >= LIMIAR_SIMILARIDADE;

        // Empate exato acima do limiar = ambíguo, tratado como não reconhecido. Os dois
        // scores vêm da mesma fórmula determinística (razão de inteiros), nunca de
        // fontes de arredondamento distintas — comparação de double por igualdade é
        // segura aqui.
        if (listaOk && respostaOk && simLista == simResposta) {
            return Optional.empty();
        }
        if (listaOk && simLista >= simResposta) {
            return Optional.of(new ResultadoClassificacao(TipoMensagemWhatsapp.LISTA_PRODUTOS, corpo));
        }
        if (respostaOk) {
            return Optional.of(new ResultadoClassificacao(TipoMensagemWhatsapp.RESPOSTA_FORNECEDOR, corpo));
        }
        return Optional.empty();
    }

    // Uppercase + remove diacríticos (NFD + strip de marcas combinantes) + colapsa
    // espaço/underscore/hífen num único espaço + strip.
    private static String normalizar(String s) {
        String semAcento = Normalizer.normalize(s.strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return SEPARADORES.matcher(semAcento.toUpperCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    private static double similaridade(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        int distancia = levenshtein(a, b);
        int maiorTamanho = Math.max(a.length(), b.length());
        return maiorTamanho == 0 ? 1.0 : 1.0 - ((double) distancia / maiorTamanho);
    }

    // DP clássico O(n*m) — strings curtas (uma linha de cabeçalho), sem preocupação de
    // performance; matriz completa por clareza, sem otimização de espaço.
    private static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int custo = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + custo);
            }
        }
        return dp[a.length()][b.length()];
    }
}
