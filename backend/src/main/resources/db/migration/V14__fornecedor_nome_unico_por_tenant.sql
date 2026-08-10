-- Backstop no banco pra corrida de criação concorrente com o mesmo nome (a validação
-- de aplicação em FornecedorService já cobre o caso comum). Índice parcial: só entre
-- fornecedores não-inativos, porque inativar (soft-delete) libera o nome pra reuso.
CREATE UNIQUE INDEX idx_fornecedor_tenant_nome_ativo
    ON fornecedor (tenant_id, lower(nome))
    WHERE status <> 'INATIVO';
