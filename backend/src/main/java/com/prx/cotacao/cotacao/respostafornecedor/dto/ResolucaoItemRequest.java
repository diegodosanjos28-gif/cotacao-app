package com.prx.cotacao.cotacao.respostafornecedor.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Como o operador resolveu (ou aceitou) um item da Conferência ao confirmar. Ver
 * ConfirmacaoRespostaService para as regras de cada {@link TipoResolucao} — só é
 * aplicada a itens que a recomputação classificou como REVISAR (item OK/ATENCAO com
 * resolução associada é rejeitado, ver ConfirmacaoRespostaService).
 *
 * <p>Exatamente um entre {@code itemBaseId}/{@code textoOriginalExtra} deve vir
 * preenchido: {@code itemBaseId} para resolver um item que já tem correspondência na
 * lista base; {@code textoOriginalExtra} para um item extra do fornecedor
 * ({@code itemBaseId == null} no {@code ItemConferenciaResponse}), identificado pelo
 * {@code textoOriginal} do candidato — mesmo padrão de casamento já usado por
 * {@code SELECIONAR_CANDIDATO}. {@code ADICIONAR_A_LISTA}/{@code ASSOCIAR_A_ITEM} só
 * fazem sentido com {@code textoOriginalExtra}; {@code ASSOCIAR_A_ITEM} exige também
 * {@code associarAItemBaseId} (o item base existente ao qual associar).</p>
 *
 * <p>Exceção à regra acima: {@code ADICIONAR_CANDIDATO_A_LISTA} exige os DOIS
 * campos ao mesmo tempo — {@code itemBaseId} identifica o item MULTIPLE_OPTIONS de
 * origem, {@code textoOriginalExtra} identifica qual candidato dentro dele está
 * sendo transformado em item novo (mesmo padrão de casamento de
 * {@code SELECIONAR_CANDIDATO}), e {@code textoOriginalSelecionado}/
 * {@code precoInformado} carregam o texto/preço finais (editáveis pelo operador,
 * obrigatórios, validados {@code > 0}).</p>
 */
public record ResolucaoItemRequest(
        UUID itemBaseId,
        String textoOriginalExtra,
        @NotNull TipoResolucao tipo,
        String textoOriginalSelecionado,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal precoInformado,
        Integer embalagemQtd,
        Boolean semEstoque,
        UUID associarAItemBaseId
) {}
