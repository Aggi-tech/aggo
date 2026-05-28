---
name: aggo-workflow
description: Enforces branch naming, issue tracking, and commit conventions. Activate this skill whenever starting a new feature, hotfix, or CI task.
---

# Aggo Workflow Skill

Este skill define o workflow obrigatório para o desenvolvimento de novas funcionalidades, correções e alterações de CI no projeto Aggo.

## Regras Obrigatórias

1. **Issue Tracking**: Toda nova feature ou tarefa deve ter um Issue correspondente. 
   - Se o usuário não fornecer um Issue ID pré-existente, você **deve** criar um novo utilizando o GitHub CLI (`gh issue create`).
   - O título e a descrição do issue devem ser baseados na solicitação do usuário.
   - Após a criação, utilize o ID gerado para as etapas seguintes.
2. **Nomenclatura de Branch**: Todo trabalho deve ser realizado em uma branch seguindo o padrão:
   - `feature/{issue-id}` para novas funcionalidades.
   - `hotfix/{issue-id}` para correções de bugs em produção.
   - `ci/{issue-id}` para alterações em fluxos de CI/CD.
3. **Commits**: O issue deve ser referenciado em todos os commits. As mensagens devem ser bem descritivas, porém concisas, com no máximo 20 palavras.
4. **Verificação de Branch**: Antes de iniciar qualquer alteração de código, verifique se a branch atual está correta. Caso contrário, proponha a criação/troca da branch.

## Processo ao Iniciar uma Feature

Ao realizar uma nova feature, siga estes passos:

1. **Ativação**: Ative este skill imediatamente.
2. **Identificação/Criação**: 
   - Utilize a ferramenta `ask_user` para apresentar o seguinte formulário:
     - Opção 1: "Não tem issue criado. Planejar agora." (Selecionar esta opção para criar um novo issue via `gh issue create`).
     - Opção 2: "Inserir issue" (Campo de texto para o usuário fornecer o ID existente).
   - Se a Opção 1 for escolhida, execute: `gh issue create --title "Título" --body "Descrição"` e capture o ID do issue criado.
   - Se a Opção 2 for escolhida, utilize o ID fornecido.
3. **Preparação**: Verifique a branch atual com `git branch --show-current`. Se não seguir o padrão `{tipo}/{issue-id}`, utilize `git checkout -b {tipo}/{issue-id}`.
4. **Execução**: Prossiga com o desenvolvimento seguindo os demais skills técnicos da Aggo.
5. **Finalização**: Ao preparar o commit, garanta que o ID do Issue esteja presente na mensagem.

