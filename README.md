# Estrutura de Agentes — Sistema de Cotação PRX

## Comece aqui

Leia `PLANO-DE-IMPLEMENTACAO.md` primeiro — é o roteiro completo: o ciclo de 6 estágios
que toda tarefa segue (Planejar → Implementar → Revisar → Testar → Verificar →
Commitar), a matriz de quando cada subagent entra, os portões de qualidade entre
módulos, e a ordem sequencial de construção do zero até o deploy (15/08/2026).

## Como instalar

1. Copie a pasta `.claude/` inteira (incluindo `agents/`) para a raiz do repositório do
   projeto. Copie também o `CLAUDE.md` para a raiz.
2. Comite tudo isso no git — subagents de projeto (`.claude/agents/`) são pensados para
   ficar versionados, não são configuração pessoal.
3. Abra o Claude Code na raiz do projeto. Os subagents já ficam disponíveis
   automaticamente (Claude decide quando chamar cada um, com base no campo
   `description` de cada arquivo).
4. Para forçar o uso de um específico: `Use o subagent security-reviewer pra revisar
   esse commit` (ou o nome equivalente na interface que você estiver usando).
5. Para editar/criar mais depois, use o comando `/agents` dentro do Claude Code.

## Por que só 5 e não 1 por módulo

Subagent bom é estreito: uma entrada, uma saída, um critério de quando disparar. Um
subagent "faz tudo" (ex: um "backend-agent" genérico) não ganha nada sobre você
simplesmente conversar direto na sessão principal — só perde contexto. Os 5 daqui
cobrem exatamente os pontos onde revisão/verificação isolada agrega valor real:
schema+RLS, porte de lógica delicada, segurança, testes, e infra.

A construção em si (escrever a feature nova) continua acontecendo na sessão principal,
seguindo os 11 prompts sequenciais da seção 12 da documentação técnica — os subagents
entram para revisar/testar o que foi construído, não para substituir esse processo.

## Quando abrir uma sessão paralela em vez de um subagent

Sessão paralela (worktree separado) é para workstreams que não se cruzam:
- Frontend Next.js evoluindo enquanto o backend termina o hardening — depois que o
  contrato de API estiver estável.
- O módulo de WhatsApp (Fase 2) — só depois que a Fase 1 estiver no ar; é um workstream
  isolado o suficiente para não valer a pena competir por contexto com o resto.
