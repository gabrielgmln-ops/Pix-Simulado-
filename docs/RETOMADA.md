# Pix Simulado - nota de retomada

Atualizado em 28/10/2026, ao encerrar a sessão do Claude Code na nuvem.
Próxima sessão: Claude Code no PC (Windows), vault `C:\SegundoCerebro`.

## Estado atual

**Etapas 0, 1 e 2 prontas.** 85 testes verdes (unitários + integração com
PostgreSQL real via Testcontainers). Árvore de trabalho limpa.

| Etapa | Escopo | Status | Commit |
|---|---|---|---|
| 0 | Setup: Gradle, Flyway, Docker Compose, Testcontainers | ✅ | `1365cc1` |
| 1 | Chaves Pix (CPF, e-mail, aleatória) com validação | ✅ | `57f6e31` |
| 2 | QR Code: BR Code EMV + imagem PNG (ZXing) | ✅ | `b80c7e3` |
| - | Nota de retomada | ✅ | `bda4836` |
| 3 | Transferência via Ledger, idempotência por EndToEndId | ⏳ próxima | |
| 4 | Devolução (lançamento inverso, original preservado) | ⏳ | |
| 5 | Swagger completo, cobertura, CI no GitHub Actions | ⏳ | |

O hash desta própria atualização é o último do `git log`.

## Restaurar o projeto

```powershell
# Opção A - GitHub (depois que o repositório existir e receber o push)
git clone https://github.com/gabrielgmln-ops/pix-simulado.git

# Opção B - bundle baixado da conversa da nuvem
git clone pix-simulado-backup.bundle pix-simulado
cd pix-simulado
git remote remove origin   # o bundle aparece como "origin"
git remote add origin https://github.com/gabrielgmln-ops/pix-simulado.git
git push -u origin main

.\gradlew.bat test         # requer JDK 21 e Docker Desktop aberto
```

## Ambiente no PC (Windows)

- A porta 8080 é do AgentService e a 8081 é da Ledger API: rodar o Pix com
  `SERVER_PORT=8082`.
- Se o PostgreSQL da Ledger ocupar a 5432, usar `DB_PORT=5433` no `.env` do Pix.
- `.env` sai de `.env.example` e nunca vai para o git.

## Contrato da Ledger API (lido do repositório Projeto-B-A em 28/10)

- `POST /api/v1/transferencias`: cabeçalho obrigatório `Idempotency-Key`;
  corpo `{contaOrigemId: UUID, contaDestinoId: UUID, valor: BigDecimal (até 2 casas, positivo), descricao: até 140}`.
- `GET /api/v1/transferencias/{id}`, `POST /api/v1/depositos`,
  `GET /api/v1/contas/{id}`, `GET /api/v1/contas/{id}/extrato`.
- Mesma `Idempotency-Key` com corpo diferente: a Ledger responde 422.
- A Operação Cofre (26/10) adicionou HTTP Basic e dono por conta na Ledger, mas
  **não foi commitada**. Conferir o estado real em `C:\SegundoCerebro\ledger-api`.

## Decisões tomadas

- Kotlin 2.1 + Spring Boot 3.5.6 + Java 21; Testcontainers fixado em 1.21.4
  (a 1.21.3 não conecta no Docker Engine 29).
- Dinheiro: BigDecimal / NUMERIC(19,2); mais de 2 casas é recusado, nunca arredondado.
- Erros RFC 9457 com `codigo` estável; exceções de negócio em hierarquia sealed
  (`EntradaInvalida` 400, `RecursoNaoEncontrado` 404, `Conflito` 409, `RegraNegocio` 422).
- Chaves: exclusão lógica; índice único parcial só entre ATIVAS; limite de 5 por
  conta protegido por advisory lock do PostgreSQL; CPF do titular mascarado.
- QR Code estático não é persistido; leitura confere o CRC16 e não restringe o
  tipo da chave (QRs de outros bancos podem ter telefone/CNPJ).
- A cidade no QR é a da instituição (`PIX_CIDADE`).

## Pendências e decisões para a Etapa 3

1. `contaId` das chaves passa a exigir UUID (id da conta na Ledger), via nova
   migração Flyway. Nunca alterar migração já aplicada.
2. EndToEndId no formato do Bacen: `E` + ISPB (8) + `yyyyMMddHHmm` UTC + 11
   alfanuméricos = 32 caracteres. É o `Idempotency-Key` enviado à Ledger.
3. Idempotência no Pix: repetição devolve o mesmo resultado; mesmo EndToEndId
   com corpo diferente = 422.
4. Pagar por chave ou por payload de QR (`BrCode.ler`); o valor do QR, se houver,
   prevalece.
5. Timeout da Ledger (resultado incerto): consultar antes de repetir; nunca
   debitar duas vezes.
6. Credenciais da Ledger por variável de ambiente (`LEDGER_USUARIO`,
   `LEDGER_SENHA`) se o HTTP Basic estiver ativo; a `LEDGER_API_KEY` atual pode
   ser substituída.
7. Testes com a Ledger simulada (WireMock ou MockRestServiceServer) e teste de
   concorrência com o mesmo EndToEndId.

## O que o senhor precisa fornecer

- Criar o repositório vazio `gabrielgmln-ops/pix-simulado` no GitHub.
- Confirmar se a Operação Cofre da Ledger será commitada antes da Etapa 3.

## Pendência no vault

- Acrescentar o Pix Simulado ao `MOC Projetos.md` (seção Estado e link
  `[[Pix Simulado]]` em Notas da área). Não foi possível pela nuvem.
