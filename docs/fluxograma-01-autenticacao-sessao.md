# Fluxograma 01 — Autenticação e Sessão

## Login

```mermaid
flowchart TD
    A["Usuário abre /login"] --> B["Preenche email + senha"]
    B --> C["Submit -> POST /auth/login"]
    C --> D{"Usuário existe, está ativo e senha confere?"}
    D -->|"Não (qualquer um dos 3 motivos)"| E["401 'Credenciais inválidas'"]
    E --> F["Mensagem exibida abaixo do campo senha (mesma mensagem para e-mail inexistente, usuário inativo ou senha errada — não há enumeração de usuário)"]
    D -->|"Sim"| G["Gera access token (15 min) + refresh token (7 dias, jti novo)"]
    G --> H["Frontend salva os 2 tokens no localStorage"]
    H --> I["Redireciona para /"]
```

Não há rate limiting, captcha ou bloqueio por tentativas — login errado repetido
nunca é throttled hoje.

## AuthGuard (toda rota exceto /login)

```mermaid
flowchart TD
    A["Página protegida monta"] --> B{"Existe accessToken no localStorage?"}
    B -->|"Não"| C["router.replace('/login')"]
    B -->|"Sim (não valida expiração aqui, só presença)"| D["ready = true, renderiza a página"]
    D --> E["Página dispara seus fetches normalmente"]
    E --> F{"Fetch retorna 401?"}
    F -->|"Sim"| G["Ver fluxo de refresh abaixo"]
```

## Requisição autenticada e renovação automática (401 → refresh → retry)

```mermaid
flowchart TD
    A["Qualquer fetch autenticado"] --> B{"Resposta 401?"}
    B -->|"Não"| Z["Segue normalmente"]
    B -->|"Sim"| C{"Já existe um refresh em andamento (refreshInFlight)?"}
    C -->|"Sim"| D["Aguarda a MESMA Promise (dedup — evita N chamadas simultâneas a /auth/refresh)"]
    C -->|"Não"| E["POST /auth/refresh com o refreshToken salvo"]
    E --> F{"Refresh token existe, é válido e não expirou (checagem final no banco, não só no JWT)?"}
    F -->|"Não"| G["logout(): limpa tokens + window.location.href = '/login' (redirect duro)"]
    F -->|"Sim, mas já estava marcado 'usado' (replay)"| H["401 'Token já utilizado — todos os tokens foram revogados'"]
    H --> I["Backend revoga TODOS os refresh tokens desse usuário (mass revoke por usuario_id)"]
    I --> G
    F -->|"Sim, válido e ainda não usado"| J["Marca usado=true, emite NOVO par access+refresh (rotação real — jti novo)"]
    D --> K["Retry da requisição original, 1x só, com o novo access token"]
    J --> K
    K --> L{"Sucesso?"}
    L -->|"Sim"| Z
    L -->|"Não (outro erro, não outro 401)"| M["Erro exibido via getErrorMessage no componente"]
    G --> N["Promise original rejeita com ApiError('Sessão expirada', 401)"]
```

Ponto importante: como a revogação em massa acontece por `usuario_id`, um refresh
token roubado e reaproveitado (replay) derruba **todas** as sessões do usuário,
inclusive a legítima que já havia girado para um token novo.

## Logout

```mermaid
flowchart LR
    A["Clique em 'Sair' na NavBar"] --> B["logout(): limpa localStorage"]
    B --> C["window.location.href = '/login' (hard redirect, sem confirmação prévia)"]
```
