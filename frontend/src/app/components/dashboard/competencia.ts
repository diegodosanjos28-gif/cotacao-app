// Utilitários de competência (mês "AAAA-MM") sem passar por Date — new Date("AAAA-MM-01")
// é interpretado como UTC meia-noite e, ao formatar de volta no fuso local (BRT,
// UTC-3), pode exibir o mês anterior (mesmo tipo de armadilha documentado em outras
// partes do sistema). Tudo aqui opera direto nos componentes ano/mês da string.

const MESES_PT = [
  "janeiro", "fevereiro", "março", "abril", "maio", "junho",
  "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
];

export function mesAtual(): string {
  const agora = new Date();
  return `${agora.getFullYear()}-${String(agora.getMonth() + 1).padStart(2, "0")}`;
}

export function somarMeses(mes: string, delta: number): string {
  const [anoStr, mesStr] = mes.split("-");
  let ano = Number(anoStr);
  let m = Number(mesStr) + delta;
  while (m > 12) {
    m -= 12;
    ano += 1;
  }
  while (m < 1) {
    m += 12;
    ano -= 1;
  }
  return `${ano}-${String(m).padStart(2, "0")}`;
}

export function labelMes(mes: string): string {
  const [anoStr, mesStr] = mes.split("-");
  const indice = Number(mesStr) - 1;
  const nome = MESES_PT[indice] ?? mesStr;
  return `${nome.charAt(0).toUpperCase()}${nome.slice(1)} de ${anoStr}`;
}

export function primeiroDiaMes(mes: string): string {
  return `${mes}-01`;
}

// new Date(ano, mesNum, 0) — dia 0 do mês seguinte é o último dia do mês pedido
// (aritmética de calendário local do JS); só o número do dia é extraído daqui, então
// não sofre o deslocamento de fuso do "new Date(string ISO)" citado no topo do arquivo.
export function ultimoDiaMes(mes: string): string {
  const [anoStr, mesStr] = mes.split("-");
  const ano = Number(anoStr);
  const mesNum = Number(mesStr);
  const ultimoDia = new Date(ano, mesNum, 0).getDate();
  return `${mes}-${String(ultimoDia).padStart(2, "0")}`;
}
