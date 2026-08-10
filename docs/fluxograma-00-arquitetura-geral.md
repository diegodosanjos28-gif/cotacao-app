# Fluxograma 00 — Arquitetura Geral e Pipeline de Requisição

Visão geral da stack e do caminho que toda requisição autenticada percorre antes de
tocar o banco. Detalhe do multi-tenancy/RLS está no Fluxograma 02.

## Stack

```mermaid
flowchart LR
    subgraph Frontend["Next.js 16 (SPA, client-side only)"]
        FE["React + TypeScript + Tailwind v4<br/>JWT em localStorage"]
    end
    subgraph Backend["Spring Boot 3 / Java 21"]
        BE["Controllers -> Services -> Repositories"]
    end
    subgraph DB["PostgreSQL"]
        PG["Schema único + RLS por tenant_id"]
    end
    FE -->|"HTTPS/JSON, Bearer token"| BE
    BE -->|"JDBC (role cotacao_app, sem BYPASSRLS)"| PG
```

## Pipeline de uma requisição autenticada

```mermaid
flowchart TD
    A["Requisição HTTP chega"] --> B["JwtAuthFilter"]
    B --> C{"Token presente, assinatura e tipo=access válidos?"}
    C -->|"Não"| D["Segue sem Authentication"]
    D --> E["Spring Security bloqueia com 401 padrão (não é o ProblemDetail customizado)"]
    C -->|"Sim"| F["Authentication setado com tenantId + papel"]
    F --> G["TenantFilter (roda depois do JwtAuthFilter)"]
    G --> H["Controller/Service processa a regra de negócio"]
    H --> I["TenantAwareDataSource abre conexão"]
    I --> J["SET app.current_tenant_id / app.is_admin na sessão Postgres"]
    J --> K["TenantAwareTransactionManager habilita (ou não) o Hibernate Filter"]
    K --> L["Query roda"]
    L --> M["Postgres RLS avalia a policy tenant_isolation"]
    M --> N["Resposta volta ao cliente"]
    H -.->|"finally"| O["TenantContext.clear()"]
```

Ver Fluxograma 02 para o detalhe de cada uma dessas etapas (bypass de admin, filtro
Hibernate, policies de RLS por join-path).

## Camadas de defesa contra vazamento cross-tenant (resumo)

```mermaid
flowchart TD
    A["1. Hibernate @Filter (TenantAuditEntity)"] --> B["2. TenantAwareRepositoryImpl.findById via JPQL (não EntityManager.find, que ignora o filtro)"]
    B --> C["3. Postgres RLS (policy tenant_isolation)"]
    C --> D["4. Validação manual de posse em services específicos (ex: AvisoService, por causa das duas tabelas sem tenant_id direto)"]
    D --> E["5. DbRoleGuard — aborta o boot se a conexão de runtime for superuser/BYPASSRLS"]
```

`cotacao_produto` e `cotacao_produto_fornecedor` não têm coluna `tenant_id` direta e
não estendem `TenantAuditEntity` — dependem só das camadas 3 e 4.
