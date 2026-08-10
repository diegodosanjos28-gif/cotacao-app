CREATE TABLE cotacao_produto_fornecedor (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cotacao_produto_id       UUID NOT NULL REFERENCES cotacao_produto(id) ON DELETE CASCADE,
    fornecedor_id            UUID NOT NULL REFERENCES fornecedor(id),
    texto_original           TEXT,
    preco_informado          NUMERIC(12, 2) NOT NULL,
    preco_unitario_calculado NUMERIC(12, 2),
    embalagem_qtd_confirmada INTEGER,
    -- CSV de motivos de classificação (MotivoConferencia) resolvidos na confirmação —
    -- ex.: 'BRAND_CHANGED,PACKAGE_QTY_CHANGED,LOW_CONFIDENCE_MATCH'. Informativo, não
    -- afeta status nem regras de negócio (ver ConfirmacaoRespostaService). 200 chars
    -- comporta os 10 motivos possíveis combinados com folga (editado in-place — migration
    -- pré-lançamento, ver decisão 6 da memória de projeto).
    tipo_embalagem_detectado VARCHAR(200),
    sem_estoque              BOOLEAN NOT NULL DEFAULT FALSE,
    confianca_match          NUMERIC(3, 2),
    -- DIVERGENCIA_PRECO/DIVERGENCIA_VOLUME removidos (editado in-place — nunca foram
    -- atribuídos em código; motivos de Atenção/Revisar viram metadado informativo em
    -- tipo_embalagem_detectado, não um status à parte — ver plano de refatoração).
    status                   VARCHAR(30) NOT NULL DEFAULT 'OK'
                                 CHECK (status IN ('OK', 'NAO_IDENTIFICADO', 'PENDENTE_CONFIRMACAO')),
    resolvido_por            UUID REFERENCES usuario(id),
    criado_em                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Lock otimista (@Version) — protege embalagem_qtd_confirmada contra escrita
    -- concorrente em AvisoService.resolver(). Dobrado aqui em vez de nova migration
    -- V15: nenhum ambiente persistente rodou migrations ainda (ver memória de projeto).
    versao                   BIGINT NOT NULL DEFAULT 0,

    UNIQUE (cotacao_produto_id, fornecedor_id)
);

CREATE INDEX idx_cpf_cotacao_produto ON cotacao_produto_fornecedor(cotacao_produto_id);
CREATE INDEX idx_cpf_fornecedor ON cotacao_produto_fornecedor(fornecedor_id);
