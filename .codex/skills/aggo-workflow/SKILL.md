---
name: aggo-workflow
description: Enforces branch naming, issue tracking, and commit conventions. Activate this skill whenever starting a new feature, hotfix, or CI task.
---

# Aggo Workflow Skill

Este skill define o workflow obrigatório para o desenvolvimento de novas funcionalidades, correções e alterações de CI no projeto Aggo.

## Regras Obrigatórias

1. **Issue Tracking**: Toda nova feature ou tarefa deve ter um Issue correspondente. Se o usuário solicitar uma feature sem fornecer um Issue ID, você deve solicitar o ID antes de iniciar.
2. **Nomenclatura de Branch**: Todo trabalho deve ser realizado em uma branch seguindo o padrão:
   - `feature/{issue-id}` para novas funcionalidades.
   - `hotfix/{issue-id}` para correções de bugs em produção.
   - `ci/{issue-id}` para alterações em fluxos de CI/CD.
3. **Commits**: O issue deve ser referenciado em todos os commits. O usuário solicitante deve ter criado/comitado o issue antes do início do desenvolvimento.
4. **Verificação de Branch**: Antes de iniciar qualquer alteração de código, verifique se a branch atual está correta. Caso contrário, proponha a criação/troca da branch.

## Processo ao Iniciar uma Feature

Ao realizar uma nova feature, siga estes passos:

1. **Ativação**: Ative este skill imediatamente.
2. **Identificação**: Pergunte: "Qual é o ID do Issue para esta feature?" (caso não tenha sido fornecido).
3. **Preparação**: Verifique a branch atual com `git branch --show-current`. Se não seguir o padrão `{tipo}/{issue-id}`, utilize `git checkout -b {tipo}/{issue-id}`.
4. **Execução**: Prossiga com o desenvolvimento seguindo os demais skills técnicos da Aggo.
5. **Finalização**: Ao preparar o commit, garanta que o ID do Issue esteja presente na mensagem.
