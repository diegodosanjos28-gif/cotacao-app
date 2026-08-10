-- Guarda o tenant que um ADMIN_PRX escolheu navegar ("impersonar") no momento em que
-- este refresh token foi emitido — não é o tenant do usuário (usuario.tenant_id
-- continua NULL para ADMIN_PRX), é o tenant da sessão de navegação escolhida via
-- POST /auth/selecionar-tenant. Para OPERADOR_CLIENTE, sempre igual a usuario.tenant_id.
-- Para ADMIN_PRX no painel admin puro (sem tenant escolhido), sempre NULL.
--
-- Sem esta coluna, a escolha de tenant do admin se perderia a cada renovação de
-- access token via /auth/refresh (que hoje deriva tenant_id sempre de usuario.tenant_id,
-- e usuario.tenant_id nunca é preenchido para ADMIN_PRX).
ALTER TABLE refresh_token ADD COLUMN tenant_id_impersonado UUID NULL REFERENCES tenant(id);
