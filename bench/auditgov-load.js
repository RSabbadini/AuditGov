// auditgov-load.js - carga constante para medir a latencia do endpoint auditado (k6).
//
// Variaveis (passadas com -e NOME=valor):
//   TOKENS_FILE  OBRIGATORIA. Arquivo com um token JWT por linha (o run-benchmark.sh o gera com
//                scripts/mint-token.sh). O usuario virtual N usa a linha N (volta ao inicio se faltarem).
//   VUS          usuarios virtuais simultaneos (padrao 10)
//   DURATION     duracao da medicao (padrao 60s)
//   BASE_URL     endereco da aplicacao (padrao http://localhost:8080)
//   OUT          arquivo JSON de saida do resumo (padrao bench/results/ultimo.json)
//
// Cada usuario virtual envia POST /api/v1/cidadaos/consulta sem pausa (carga em malha fechada),
// com o token de um operador proprio (bench_<n>). A validacao do JWT acontece em TODOS os modos de
// execucao (com e sem auditoria); assim, a diferenca entre os modos e so o custo da auditoria.
// O resumo grava p50, p95 e p99 da latencia em milissegundos, a vazao (requisicoes por segundo),
// o percentual de erros (resposta fora de 2xx, o que inclui 401 por token expirado) e o total de
// requisicoes enviadas (para conferir com o numero de evidencias gravadas).
import http from 'k6/http';
import { check } from 'k6';

// Contexto de inicializacao: roda uma vez por usuario virtual, antes da carga.
if (!__ENV.TOKENS_FILE) {
  throw new Error('Informe -e TOKENS_FILE=<arquivo com um token JWT por linha> (o run-benchmark.sh faz isso).');
}
const TOKENS = open(__ENV.TOKENS_FILE)
  .split('\n')
  .map((linha) => linha.trim())
  .filter((linha) => linha.length > 0);
if (TOKENS.length === 0) {
  throw new Error('O arquivo de tokens esta vazio: ' + __ENV.TOKENS_FILE);
}

export const options = {
  summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'avg'],
  scenarios: {
    carga: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.DURATION || '60s',
    },
  },
};

const URL = `${__ENV.BASE_URL || 'http://localhost:8080'}/api/v1/cidadaos/consulta`;
const BODY = JSON.stringify({ cpf: '12345678900' });

export default function () {
  const token = TOKENS[(__VU - 1) % TOKENS.length];
  const res = http.post(URL, BODY, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
  });
  check(res, { 'status 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  const latencia = data.metrics.http_req_duration.values;
  const resumo = {
    p50_ms: latencia['med'],
    p95_ms: latencia['p(95)'],
    p99_ms: latencia['p(99)'],
    rps: data.metrics.http_reqs.values.rate,
    erro_pct: (data.metrics.http_req_failed.values.rate || 0) * 100,
    reqs: data.metrics.http_reqs.values.count,
  };
  const saida = __ENV.OUT || 'bench/results/ultimo.json';
  return {
    [saida]: JSON.stringify(resumo),
    stdout: JSON.stringify(resumo) + '\n',
  };
}
