CREATE TABLE tenant (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nome_fantasia VARCHAR(255) NOT NULL,
    razao_social  VARCHAR(255),
    cnpj          VARCHAR(18) UNIQUE,
    status        VARCHAR(20) NOT NULL DEFAULT 'TRIAL'
                      CHECK (status IN ('ATIVO', 'SUSPENSO', 'TRIAL')),
    plano         VARCHAR(50),
    criado_em     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
