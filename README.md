# AuditGov: Framework de Observabilidade para Auditoria LGPD

## 1. Definição do Problema
A conformidade com a LGPD em sistemas governamentais exige a rastreabilidade absoluta de quem acessou dados sensíveis. Logs manuais são fragmentados e propensos a falhas.
→ **Lacuna:** Falta de correlação entre o fluxo técnico (Tracing) e o registro jurídico (Auditoria).

## 2. Proposta de Solução
Implementação de um mecanismo de auditoria baseado em **Distributed Tracing** (OpenTelemetry) que correlaciona cada transação de negócio a um registro em banco de dados relacional.
= **Equivalência:** Trace ID ≡ Evidência de Acesso Legal.

## 3. Metodologia de Desenvolvimento
* **Modelo de Processo:** Incremental com ciclos de 2 semanas (Sprints).
* **Ciclo de Vida:** Baseado no modelo V para garantir rastreabilidade entre Requisitos e Testes.
* **Critérios de Qualidade:** Observabilidade nativa, Baixo acoplamento e Integridade referencial.

## 4. Stack Tecnológica (Decisões de Projeto)
* **Backend:** Java 21 + Quarkus (Eficiência de recursos).
* **Observabilidade:** OpenTelemetry + Jaeger.
* **Persistência:** PostgreSQL (Armazenamento de logs estruturados).
* **Containerização:** Docker & Docker Compose.

## 5. Arquitetura Proposta (Córtex)

O sistema utiliza um **Interceptor** customizado que extrai o contexto de rastreio da requisição e o persiste em uma tabela de auditoria antes da execução da lógica de negócio.
