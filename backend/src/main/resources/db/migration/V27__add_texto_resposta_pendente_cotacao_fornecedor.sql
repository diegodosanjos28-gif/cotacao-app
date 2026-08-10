-- Prompt 15 — unificação Web/WhatsApp do processamento de resposta de fornecedor.
-- Guarda o texto bruto da última resposta de fornecedor ainda não confirmada, para
-- reabrir a Conferência (GET .../resposta-persistida) sem depender de linhas já
-- persistidas em cotacao_produto_fornecedor — que só passam a existir depois de
-- POST .../confirmar, para os dois canais (RespostaFornecedorCoreService nunca
-- persiste automaticamente). Nullable e sem CHECK: é rascunho de UI, não dado de
-- negócio confirmado; fica vazio na maior parte do tempo (nenhuma resposta ainda, ou
-- a última resposta já foi confirmada/cancelada).
ALTER TABLE cotacao_fornecedor ADD COLUMN texto_resposta_pendente TEXT;
