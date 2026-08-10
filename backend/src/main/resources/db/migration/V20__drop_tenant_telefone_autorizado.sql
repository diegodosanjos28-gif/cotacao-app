-- tenant_telefone_autorizado nunca chegou a ser consumida por nenhum
-- controller/service (ver TenantTelefoneAutorizado.java, removida junto com esta
-- migration) — sem dado de produção a preservar. Identificação de telefone no
-- WhatsApp passa a ser por usuário, não por tenant (V21).
-- IF EXISTS: V9 criou a tabela sem RLS/policy nenhuma, então não há policy pra
-- derrubar aqui — sem o IF EXISTS esta migration falharia com "policy does not
-- exist" (achado do db-schema-guardian).
DROP POLICY IF EXISTS tenant_isolation ON tenant_telefone_autorizado;
DROP TABLE tenant_telefone_autorizado;
