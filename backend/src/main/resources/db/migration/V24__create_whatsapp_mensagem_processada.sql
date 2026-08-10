-- Idempotência de mensagens do webhook Meta por message_id (wamid). Sem RLS de
-- propósito — mesma exceção à regra 1 do CLAUDE.md documentada em V11
-- (refresh_token): esta tabela não carrega dado de tenant (o tenant só é conhecido
-- DEPOIS de resolver o número do remetente; a checagem de idempotência roda antes e
-- independente disso — mensagem duplicada de um número desconhecido também precisa
-- ser reconhecida como duplicata sem reprocessar o descarte/log). Todo acesso é por PK
-- exata (message_id), nunca por scan.
--
-- PROTEÇÃO DE SEGURANÇA: numero_origem é um número de telefone que não pode ser isolado
-- por tenant (um número não pertence a um tenant). O repository desta tabela é intencionalmente
-- vazio (nenhum método custom). Se for necessário implementar busca/listagem por numero_origem
-- no futuro (auditoria/admin), isso deve ser feito com segurança explícita (endpoint admin-only,
-- logs, validação de contexto) — ver comentário em MensagemProcessadaWhatsappRepository.
CREATE TABLE whatsapp_mensagem_processada (
    message_id    VARCHAR(255) PRIMARY KEY,
    numero_origem VARCHAR(20)  NOT NULL,
    processado_em TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Roteamento de WhatsApp (Fase 3, seção 10.4 da doc técnica): "cotação em andamento" é
-- escopada por criado_por (usuário), não só por tenant — dois operadores do mesmo
-- tenant mandando mensagem ao mesmo tempo não podem se cruzar. Índice parcial dedicado
-- porque esta query roda em TODO webhook recebido (path quente); idx_cotacao_atividade
-- (tenant_id, ultima_atividade_em) não inclui criado_por/canal_origem e obrigaria a
-- revisitar todas as EM_ANDAMENTO do tenant. Lidera por tenant_id, mesmo padrão de
-- todos os índices deste schema, alinhado ao predicado que o Hibernate Filter injeta.
CREATE INDEX idx_cotacao_whatsapp_usuario ON cotacao (tenant_id, criado_por, ultima_atividade_em DESC)
    WHERE status = 'EM_ANDAMENTO' AND canal_origem = 'WHATSAPP';
