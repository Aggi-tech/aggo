# GEMINI.md - Aggo Project Instructions

Este arquivo contém as instruções e mapeamentos de skills para o Gemini CLI neste repositório.

## Skills Disponíveis

Sempre ative a skill apropriada ao iniciar uma tarefa:

- **Arquitetura**: `activate_skill(name="aggo-architecture")` - Leis de reflexão zero e limites de pacotes.
- **Workflow**: `activate_skill(name="aggo-workflow")` - Padrões de branch, issues e commits.
- **Codecs**: `activate_skill(name="aggo-codec")` - Implementação de pontes de tipos de dados.
- **Dialetos**: `activate_skill(name="aggo-dialect")` - Implementação de DML e DDL.
- **Migrações**: `activate_skill(name="aggo-migration")` - Versionamento de schema.
- **Query DSL**: `activate_skill(name="aggo-query-dsl")` - Escrita de queries Select/Insert/Update/Delete.
- **Predicados**: `activate_skill(name="aggo-predicate-operator")` - Novos operadores WHERE.
- **Schema**: `activate_skill(name="aggo-schema-table")` - Definição de tabelas e colunas.

## Mandatos Principais

1. **Reflexão Zero**: Proibido o uso de `KClass` ou introspecção em tempo de execução no `schema/`, `query/`, `dsl/` ou `render/`.
2. **Workflow**: Toda feature deve ter um Issue ID e branch `{feature|hotfix|ci}/{issue-id}`.
3. **Testes**: Novos recursos devem ter testes de unidade (RendererTest) e integração (IntegrationTest).

## Localização das Skills
As definições completas estão em `.claude/skills/`.
