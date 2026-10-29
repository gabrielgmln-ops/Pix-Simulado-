#!/usr/bin/env bash
# Teste de ponta a ponta: Pix Simulado (8082) + Ledger API real (8081).
# Pré-requisitos: as duas aplicações no ar e, no .env da Ledger, SEED_CLIENTE_* e
# SEED_CLIENTE2_* (segundo cliente = loja). Uso: bash scripts/e2e-ledger-real.sh
# Credenciais lidas dos .env (ignorados pelo git); nunca são impressas.
set -u
TMP=$(mktemp -d)
# Caminho do .env da Ledger (usuários SEED_*); padrão: repositório irmão ../ledger-api
ENVL="${LEDGER_ENV:-$(dirname "$0")/../../ledger-api/.env}"
v() { grep "^$1=" "$ENVL" | cut -d= -f2-; }
CLI="$(v SEED_CLIENTE_USERNAME):$(v SEED_CLIENTE_SENHA)"
LOJA="$(v SEED_CLIENTE2_USERNAME):$(v SEED_CLIENTE2_SENHA)"
L=localhost:8081/api/v1
P=localhost:8082/api/v1
J='Content-Type: application/json'
ok=0; falhou=0
confere() { # nome esperado obtido
  if [ "$2" = "$3" ]; then echo "  OK    $1 ($3)"; ok=$((ok+1)); else echo "  FALHA $1: esperado $2, veio $3"; falhou=$((falhou+1)); fi
}
campo() { python -c "import json,sys,decimal; d=json.load(sys.stdin, parse_float=decimal.Decimal); print(d$1)"; }
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

echo "1) Contas na Ledger, cada uma criada pelo próprio titular"
C_CLI=$(curl -s -u "$CLI" -X POST $L/contas -H "$J" -d '{"titular":"Cliente Sintetico"}' | campo "['id']")
C_LOJA=$(curl -s -u "$LOJA" -X POST $L/contas -H "$J" -d '{"titular":"Loja Sintetica Ltda"}' | campo "['id']")
echo "  conta do cliente: $C_CLI"; echo "  conta da loja:    $C_LOJA"
curl -s -u "$CLI" -X POST $L/depositos -H "$J" -H "Idempotency-Key: dep-$(date +%s%N)" \
  -d "{\"contaId\":\"$C_CLI\",\"valor\":100.00,\"descricao\":\"Saldo inicial sintetico\"}" >/dev/null
confere "saldo inicial do cliente" "100.00" "$(curl -s -u "$CLI" $L/contas/$C_CLI | campo "['saldo']")"

echo "2) Loja cadastra a chave no Pix (dono conferido na Ledger)"
CHAVE="loja.$(date +%s)@exemplo.com"
confere "cliente NÃO cadastra chave na conta da loja" 403 \
  "$(status -u "$CLI" -X POST $P/chaves -H "$J" -d "{\"tipo\":\"EMAIL\",\"valor\":\"$CHAVE\",\"contaId\":\"$C_LOJA\",\"titular\":{\"nome\":\"Loja Sintetica Ltda\",\"cpf\":\"12345678909\"}}")"
confere "loja cadastra a própria chave" 201 \
  "$(status -u "$LOJA" -X POST $P/chaves -H "$J" -d "{\"tipo\":\"EMAIL\",\"valor\":\"$CHAVE\",\"contaId\":\"$C_LOJA\",\"titular\":{\"nome\":\"Loja Sintetica Ltda\",\"cpf\":\"12345678909\"}}")"

echo "3) QR Code de R\$ 25,90 e pagamento pelo cliente"
QR=$(curl -s -X POST $P/qrcodes -H "$J" -d "{\"chave\":\"$CHAVE\",\"valor\":25.90}" | campo "['payload']")
E2E=$(curl -s -X POST $P/pagamentos/end-to-end-id | campo "['endToEndId']")
CORPO="{\"endToEndId\":\"$E2E\",\"contaOrigemId\":\"$C_CLI\",\"qrPayload\":\"$QR\"}"
confere "loja NÃO paga com a conta do cliente (anti-IDOR)" 403 "$(status -u "$LOJA" -X POST $P/pagamentos -H "$J" -d "$CORPO")"
R=$(curl -s -D "$TMP/h1" -u "$CLI" -X POST $P/pagamentos -H "$J" -d "$CORPO")
confere "pagamento liquidado na Ledger real" LIQUIDADO "$(echo "$R" | campo "['estado']")"
R2=$(curl -s -D "$TMP/h2" -u "$CLI" -X POST $P/pagamentos -H "$J" -d "$CORPO")
confere "repetição idempotente" true "$(grep -i '^Idempotent-Replayed' "$TMP/h2" | tr -d '\r' | awk '{print $2}')"
confere "repetição devolve a mesma transação da Ledger" "$(echo "$R" | campo "['ledgerTransacaoId']")" "$(echo "$R2" | campo "['ledgerTransacaoId']")"
confere "saldo do cliente após o Pix" "74.10" "$(curl -s -u "$CLI" $L/contas/$C_CLI | campo "['saldo']")"
confere "saldo da loja após o Pix" "25.90" "$(curl -s -u "$LOJA" $L/contas/$C_LOJA | campo "['saldo']")"

echo "4) Saldo insuficiente na Ledger real"
E2E2=$(curl -s -X POST $P/pagamentos/end-to-end-id | campo "['endToEndId']")
R3=$(curl -s -u "$CLI" -X POST $P/pagamentos -H "$J" -d "{\"endToEndId\":\"$E2E2\",\"contaOrigemId\":\"$C_CLI\",\"chave\":\"$CHAVE\",\"valor\":1000.00}")
confere "Pix de R\$ 1.000 recusado" SALDO_INSUFICIENTE "$(echo "$R3" | campo "['codigo']")"

echo "5) Devolução parcial pela loja"
ID=$(curl -s -X POST $P/pagamentos/$E2E/devolucoes/id | campo "['idDevolucao']")
DEV="{\"idDevolucao\":\"$ID\",\"valor\":5.00,\"motivo\":\"MD06\"}"
confere "cliente NÃO devolve o próprio pagamento" 403 "$(status -u "$CLI" -X POST $P/pagamentos/$E2E/devolucoes -H "$J" -d "$DEV")"
confere "loja devolve R\$ 5,00" LIQUIDADO "$(curl -s -u "$LOJA" -X POST $P/pagamentos/$E2E/devolucoes -H "$J" -d "$DEV" | campo "['estado']")"
confere "valorDevolvido no pagamento original" "5.00" "$(curl -s -u "$CLI" $P/pagamentos/$E2E | campo "['valorDevolvido']")"
confere "pagamento original continua LIQUIDADO" LIQUIDADO "$(curl -s -u "$CLI" $P/pagamentos/$E2E | campo "['estado']")"
confere "saldo do cliente após a devolução" "79.10" "$(curl -s -u "$CLI" $L/contas/$C_CLI | campo "['saldo']")"
confere "saldo da loja após a devolução" "20.90" "$(curl -s -u "$LOJA" $L/contas/$C_LOJA | campo "['saldo']")"

echo; echo "Resultado: $ok OK, $falhou falha(s)"
[ "$falhou" -eq 0 ]   # código de saída diferente de zero se algo falhou
