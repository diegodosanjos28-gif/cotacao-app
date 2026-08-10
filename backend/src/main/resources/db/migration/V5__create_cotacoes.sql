CREATE TABLE cotacao (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES tenant(id),
    criado_por           UUID REFERENCES usuario(id),
    titulo               VARCHAR(255) NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'RASCUNHO'
                             CHECK (status IN ('RASCUNHO', 'EM_ANDAMENTO', 'FINALIZADA', 'CANCELADA')),
    canal_origem         VARCHAR(20) NOT NULL DEFAULT 'WEB'
                             CHECK (canal_origem IN ('WEB', 'WHATSAPP')),
    ultima_atividade_em  TIMESTAMPTZ,
    cenario_selecionado  VARCHAR(20)
                             CHECK (cenario_selecionado IN ('MENOR_PRECO', 'EQUILIBRADA', 'MELHOR_PRAZO')),
    finalizada_em        TIMESTAMPTZ,
    criado_em            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cotacao_tenant ON cotacao(tenant_id);
CREATE INDEX idx_cotacao_status ON cotacao(tenant_id, status);
CREATE INDEX idx_cotacao_atividade ON cotacao(tenant_id, ultima_atividade_em)
    WHERE status = 'EM_ANDAMENTO';
