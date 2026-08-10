-- Histórico de Preços (tela nova, ver docs técnica §11): a consulta busca TODAS as
-- cotações FINALIZADA do tenant ordenadas por finalizada_em desc, sem paginação.
-- idx_cotacao_status (tenant_id, status), de V5, não cobre essa ordenação. Índice
-- parcial (só linhas FINALIZADA) evita indexar RASCUNHO/EM_ANDAMENTO/CANCELADA, que
-- não participam desta consulta nem têm finalizada_em preenchido. Sem tabela nova:
-- o histórico é derivado ao vivo de cotacao_produto_fornecedor/cotacao_produto/
-- cotacao/produto, não escrito em background na finalização.
CREATE INDEX idx_cotacao_finalizada ON cotacao (tenant_id, status, finalizada_em DESC)
    WHERE status = 'FINALIZADA';
