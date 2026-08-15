-- Prompt 20: confirmação WhatsApp migra de Meta Message Template (type:"template")
-- para Service Message em texto livre (type:"text"), aproveitando a Customer Service
-- Window de 24h aberta pela própria mensagem recebida que originou a resposta (fluxo
-- é sempre síncrono nesta arquitetura). Ver docs seção 10 e whatsapp.envio.

-- Campos remanescentes de template Meta (decisão registrada no ticket, caminho 1):
-- tornados opcionais em vez de removidos — dado histórico/estrutura preservados pra
-- eventual reintrodução futura de um fluxo proativo fora da janela de 24h, sem caso
-- de uso hoje. Nenhum código de produção lê/escreve mais estas colunas para montar
-- a chamada real à Meta (ver WhatsappTextoLivreMensageriaService).
ALTER TABLE template_mensagem ALTER COLUMN nome_template_meta DROP NOT NULL;
ALTER TABLE template_mensagem ALTER COLUMN idioma DROP NOT NULL;

-- parametros_ordenados era pura mecânica de posição {{1}}/{{2}} do Meta Template
-- positional array (ver V30) — sob o modelo de substituição por nome (Prompt 20),
-- fica sem sentido (pior: enganoso, sugere que ordem importa quando não importa
-- mais). Ao contrário de descricao_parametros (mantida órfã — segue legível como
-- documentação humana), esta coluna não tem valor residual nenhum: DROP em vez de
-- preservar. Só existe dado de tenant de teste hoje (confirmado por db-schema-guardian
-- antes deste DROP).
ALTER TABLE template_mensagem DROP COLUMN parametros_ordenados;
