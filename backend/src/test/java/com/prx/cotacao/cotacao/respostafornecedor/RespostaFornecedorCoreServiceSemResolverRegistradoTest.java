package com.prx.cotacao.cotacao.respostafornecedor;

import com.prx.cotacao.cotacao.core.repository.CotacaoProdutoRepository;
import com.prx.cotacao.cotacao.core.repository.CotacaoRepository;
import com.prx.cotacao.cotacao.mensagem.service.PrecoReferenciaService;
import com.prx.cotacao.cotacao.respostafornecedor.parser.service.ConciliacaoRespostaService;
import com.prx.cotacao.cotacao.respostafornecedor.parser.service.ParserRespostaFornecedorService;
import com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.ParametroResolucaoFornecedor;
import com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.ResolvedorFornecedorRespostaStrategy;
import com.prx.cotacao.cotacao.respostafornecedor.processor.RespostaFornecedorCoreService;
import com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.web.ParametroResolucaoWeb;
import com.prx.cotacao.cotacao.respostafornecedor.processor.resolver.whats.ParametroResolucaoWhatsapp;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoFornecedorRepository;
import com.prx.cotacao.cotacao.respostafornecedor.repository.CotacaoProdutoFornecedorRepository;
import com.prx.cotacao.cotacao.respostafornecedor.service.ClassificacaoConferenciaService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Tipo de parâmetro sem resolver registrado no map de dispatch de
 * {@link RespostaFornecedorCoreService#processar} → {@code IllegalStateException}.
 * Unitário puro (Mockito), sem contexto Spring — como {@code ParametroResolucaoFornecedor}
 * só tem duas subclasses reais (Web/WhatsApp), não existe "terceiro canal desconhecido"
 * de verdade em produção. A única forma de alcançar essa branch é construir
 * deliberadamente uma lista de resolvedores incompleta, faltando o de WhatsApp.
 */
class RespostaFornecedorCoreServiceSemResolverRegistradoTest {

    /** Strategy falsa que só aceita {@link ParametroResolucaoWeb} — nunca deveria ser
     * chamada neste teste, já que o parâmetro usado é {@link ParametroResolucaoWhatsapp}. */
    private static class StrategyFalsaQueSoAceitaWeb implements ResolvedorFornecedorRespostaStrategy {
        @Override
        public UUID resolver(UUID cotacaoId, ParametroResolucaoFornecedor parametro) {
            throw new UnsupportedOperationException("não deveria ser invocada neste teste");
        }

        @Override
        public Class<? extends ParametroResolucaoFornecedor> tipoAceito() {
            return ParametroResolucaoWeb.class;
        }
    }

    @Test
    void processar_com_tipo_de_parametro_sem_resolver_registrado_lanca_illegal_state() {
        RespostaFornecedorCoreService core = new RespostaFornecedorCoreService(
                mock(CotacaoRepository.class),
                mock(CotacaoProdutoRepository.class),
                mock(CotacaoProdutoFornecedorRepository.class),
                mock(CotacaoFornecedorRepository.class),
                mock(ParserRespostaFornecedorService.class),
                mock(ConciliacaoRespostaService.class),
                mock(ClassificacaoConferenciaService.class),
                mock(PrecoReferenciaService.class),
                List.of(new StrategyFalsaQueSoAceitaWeb()));

        assertThrows(IllegalStateException.class, () -> core.processar(
                UUID.randomUUID(), new ParametroResolucaoWhatsapp("qualquer"), "qualquer"));
    }
}
