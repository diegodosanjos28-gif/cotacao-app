# Fluxograma 02 — Multi-tenancy e Row-Level Security

## Como o tenant é resolvido e propagado até o banco

```mermaid
flowchart TD
    A["JwtAuthFilter já setou Authentication com tenantId + papel"] --> B["TenantFilter"]
    B --> C{"Path é exatamente /auth/login ou /auth/refresh?"}
    C -->|"Sim"| D["TenantContext.setAdmin(true) — bypass de RLS, sem tenant conhecido ainda"]
    C -->|"Não"| E{"papel == ADMIN_PRX?"}
    E -->|"Sim"| D
    E -->|"Não (OPERADOR_CLIENTE)"| F["TenantContext.set(tenantId), isAdmin=false"]
    D --> G["Controller/Service roda"]
    F --> G
    G --> H["TenantAwareDataSource.getConnection()"]
    H --> I["SELECT set_config('app.current_tenant_id', ?, false), set_config('app.is_admin', ?, false)"]
    I --> J["TenantAwareTransactionManager.doBegin()"]
    J --> K{"isAdmin?"}
    K -->|"Sim"| L["Hibernate Filter NUNCA é habilitado nesta sessão"]
    K -->|"Não"| M["Habilita o Filter 'tenantFilter' com o tenantId atual"]
    L --> N["Query executa"]
    M --> N
    N --> O["Postgres RLS avalia a policy (independe do que a aplicação fez)"]
    O --> P["Resposta"]
    G -.->|"finally, sempre"| Q["TenantContext.clear()"]
```

## Duas camadas independentes — por que ambas existem

```mermaid
flowchart LR
    subgraph App["Camada de aplicação (Hibernate @Filter)"]
        A1["Entidades que estendem TenantAuditEntity:<br/>Tenant, Usuario, Fornecedor, Produto, Cotacao, FornecedorProduto, TenantTelefoneAutorizado"]
    end
    subgraph DB["Camada de banco (Postgres RLS) — sempre ativa, independe de bug de app"]
        B1["Toda tabela com tenant_id: policy tenant_isolation"]
        B2["cotacao_produto: isolado só via join (cotacao_id -> cotacao.tenant_id)"]
        B3["cotacao_produto_fornecedor: isolado só via join duplo (-> cotacao_produto -> cotacao)"]
    end
    A1 -.->|"filtro de conveniência, pode ter bug de configuração"| B1
    B2 -.->|"sem @Filter — única defesa é RLS + validação manual no service"| App
    B3 -.->|"sem @Filter — única defesa é RLS + validação manual no service"| App
```

`cotacao_produto` e `cotacao_produto_fornecedor` são as duas entidades sem
`tenant_id` direto. Por isso `AvisoService.resolver()` faz uma validação manual extra
de posse (ver Fluxograma 05) — não dá pra confiar só no filtro Hibernate ali.

## Bypass de administrador (ADMIN_PRX)

```mermaid
flowchart TD
    A["Usuário autenticado com papel=ADMIN_PRX"] --> B["TenantContext.setAdmin(true)"]
    B --> C["Hibernate Filter nunca habilitado para esse usuário"]
    C --> D["RLS libera tudo via is_admin_request() = true"]
    D --> E{"Endpoint chamado é um endpoint comum (/cotacoes, /fornecedores, /produtos)?"}
    E -->|"Sim — não existe painel admin dedicado hoje"| F["ADMIN_PRX vê dados de TODOS os tenants misturados nessas listagens (nenhum controller filtra por papel)"]
    E -->|"Não existe rota /admin/* real"| G["/admin/** está reservado no SecurityConfig mas nenhum controller mapeia nada ali"]
```

## Boot-time safety net — DbRoleGuard

```mermaid
flowchart TD
    A["Aplicação inicia (SmartInitializingSingleton, antes do Tomcat abrir a porta)"] --> B["Consulta pg_roles para o usuário da conexão de runtime"]
    B --> C{"rolsuper OR rolbypassrls?"}
    C -->|"Sim"| D["throw IllegalStateException — boot abortado, app nunca chega a servir requisição"]
    C -->|"Não (é 'cotacao_app', role sem BYPASSRLS)"| E["Boot continua normalmente"]
```

Existe porque, num incidente já registrado neste projeto, todo ambiente (incluindo
testes) conectava com o mesmo role usado pelas migrations — que no Postgres é
superuser por bootstrap, e superuser ignora RLS incondicionalmente. As migrations
(Flyway) continuam rodando com o role admin/superuser; só a conexão de runtime do
app é que precisa ser o role restrito.
