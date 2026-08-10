# CLAUDE.md — Sistema de Cotação PRX

Contexto do projeto para qualquer sessão do Claude Code neste repositório.

## O que é este projeto

Sistema web multi-tenant de cotação de produtos para pequeno varejo, substituindo um
fluxo manual via WhatsApp. Stack: Next.js (frontend) + Spring Boot (backend) +
PostgreSQL com Row-Level Security (isolamento multi-tenant). Deploy: droplet único na
DigitalOcean, banco gerenciado em instância separada.

Documentos de referência (leia antes de decisões de arquitetura):
- `docs/documentacao-tecnica-sistema-cotacao-prx.md` — modelo de dados, API, lógica de
  parsing/matching, prompts de construção por módulo.
- `proposta-comercial-sistema-cotacao-prx.docx` — escopo contratado (v1, prazo 15/08)
  incluindo o módulo WhatsApp simplificado.
- `PLANO-DE-IMPLEMENTACAO.md` — ordem de construção módulo a módulo, e o ciclo de
  6 estágios (Planejar → Implementar → Revisar → Testar → Verificar → Commitar) que
  toda tarefa deste projeto segue. Leia antes de começar qualquer módulo novo.

## Ciclo de trabalho (resumo — detalhes em PLANO-DE-IMPLEMENTACAO.md)

Toda tarefa: planejar → implementar → revisar (subagent relevante, nunca a sessão
revisando a si mesma) → testar (`test-writer`) → verificar (rodar testes, conferir
veredito) → commit pequeno e único. Não pule o estágio de revisão/teste sob pressão de
prazo — é onde o bug caro nasce. Prefira sessão nova por módulo do roadmap a uma
mega-sessão contínua — contexto velho de um módulo já commitado não ajuda o próximo.

## Regras que nenhum subagent ou sessão pode violar

1. Toda tabela com dado de tenant tem `tenant_id` + policy de RLS. Sem exceção.
2. `cotacao_produto_fornecedor.embalagem_qtd_confirmada` é snapshot imutável por linha
   de cotação — nunca sobrescrever `produto.embalagem_qtd_sugerida` a partir de uma
   única resposta de fornecedor.
3. Portes de lógica do protótipo original (parsing, matching) preservam comportamento
   exato — não é uma oportunidade de "melhorar" o algoritmo sem pedido explícito.
4. Netdata/Dozzle nunca ficam expostos no firewall público — acesso só via Tailscale.

## Política de subagents

- Migration ou entidade JPA nova/alterada → **sempre** rodar `db-schema-guardian`
  antes de considerar a mudança concluída.
- Porte de lógica de parsing/matching do protótipo → usar `parser-porter`, não
  reimplementar à mão na sessão principal.
- Qualquer commit tocando auth, JWT, isolamento multi-tenant, ou o webhook do WhatsApp
  → `security-reviewer` antes do commit.
- Feature de negócio finalizada e funcionando → `test-writer` antes de seguir para a
  próxima feature.
- Docker, deploy, CI/CD, monitoramento → `deploy-ops`.

## Quando NÃO usar subagent (fazer na sessão principal mesmo)

- Decisões de arquitetura e trade-offs — exigem o contexto acumulado da conversa.
- Qualquer coisa que precise de planejamento em etapas visível — subagents executam
  direto, sem modo de planejamento interativo.
- Construção sequencial de uma feature nova do zero — siga os Prompts 1-11 da seção 12
  da documentação técnica na sessão principal; subagents entram *depois*, para revisar
  ou testar o que foi construído.
