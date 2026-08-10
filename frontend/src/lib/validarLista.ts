// Validação "ao digitar" da lista de produtos — só um hint visual client-side.
// O parser autoritativo continua sendo ParserListaProdutosService no backend,
// acionado apenas no envio; esta função nunca decide o que é ou não aceito.
// Espelha validarListaExec() do protótipo COTA&TESTA V5.

export interface AvisoLinha {
  numero: number;
  mensagem: string;
}

// "unid" é alias de "un" (fornecedores e o próprio usuário escrevem "24 unid leite de
// coco menina 200 ml") — sem ele a linha caía em RE_QTD_SEM_UNIDADE e virava falso
// positivo de "falta a unidade".
//
// Lista de aliases DEVE bater 1:1 com UNIT_ALIASES de ParserListaProdutosService.java
// (backend) — mantida manualmente aqui, não importada, pois este arquivo é só um hint
// visual client-side (ver nota no topo do arquivo) e o backend é quem decide de fato.
// Se o backend ganhar uma unidade nova, replique aqui também, senão a unidade nova
// passa a ser marcada erroneamente como aviso de formato incorreto no cliente.
const UNIDADE_ALIASES =
  "un|und|unid|unidade|unidades" +
  "|fd|f|fardo|fardos" +
  "|cx|cxa|caixa|caixas" +
  "|pct|pacote|pacotes" +
  "|kg|kilo|kilos|quilo|quilos" +
  "|g|gr|grama|gramas" +
  "|ml|mililitro" +
  "|l|lt|litro|litros" +
  "|dz|dzs|d[uú]zias?|duzias?" +
  "|galao|galão|gl" +
  "|fr|frasco|frascos" +
  "|gf|garrafa|garrafas" +
  "|lata|latas" +
  "|pote|potes|pt" +
  "|sc|saco|sacos" +
  "|rl|rolo|rolos" +
  "|tambor|tambores|ta" +
  "|bandej|bandeja|bandejas|bdj" +
  "|display|disp" +
  "|kit|kits" +
  "|cento|centos";
const RE_QTD_UNIDADE = new RegExp(`^(\\d+)\\s*(${UNIDADE_ALIASES})\\s+`, "i");
const RE_QTD_SEM_UNIDADE = /^(\d+)\s+\S/;
const RE_CABECALHO = /^[a-záéíóúâêôãõç\s]{2,25}$/i;
const RE_MULTIPLOS_ITENS = new RegExp(`,\\s*\\d+\\s*(${UNIDADE_ALIASES})?\\s+`, "i");

// Usado fora do textarea da lista também: ao adicionar um item novo à lista base a
// partir da Conferência do Fornecedor (item extra / spin-off de MULTIPLE_OPTIONS), o
// texto final precisa seguir o mesmo formato "quantidade + unidade + produto" — sem
// isso, o item entra na lista base sem poder ser reparseado depois (reenvio da lista
// trataria a linha inteira como nome do produto, prejudicando o matching).
export function comecaComQuantidadeEUnidade(texto: string): boolean {
  return RE_QTD_UNIDADE.test(texto.trim());
}

export function linhasNaoVazias(texto: string): string[] {
  return texto
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l.length > 0);
}

// `numero` é o número REAL da linha no textarea (linhas em branco consomem um número),
// para casar com o gutter de numeração de ListaProdutosSection. Numerar só as linhas
// não-vazias fazia o "L25" do aviso apontar para a linha errada assim que houvesse uma
// linha em branco no meio da lista colada.
export function validarLinhasLista(texto: string): AvisoLinha[] {
  const avisos: AvisoLinha[] = [];
  texto.split("\n").forEach((linhaBruta, i) => {
    const linha = linhaBruta.trim();
    if (linha.length === 0) return;
    const numero = i + 1;
    if (linha.length <= 3) {
      avisos.push({ numero, mensagem: "Linha muito curta — pode ser cabeçalho" });
      return;
    }
    if (RE_QTD_UNIDADE.test(linha)) {
      if (RE_MULTIPLOS_ITENS.test(linha)) {
        avisos.push({ numero, mensagem: "Possível múltiplos itens na mesma linha" });
      }
      return;
    }
    if (RE_QTD_SEM_UNIDADE.test(linha)) {
      avisos.push({ numero, mensagem: "Falta a unidade após a quantidade (un, fd, cx...)" });
      return;
    }
    if (RE_CABECALHO.test(linha) && !/\d/.test(linha)) {
      avisos.push({ numero, mensagem: "Parece cabeçalho — remova ou adicione qtd+unidade" });
      return;
    }
    avisos.push({ numero, mensagem: "Não começa com quantidade + unidade" });
  });
  return avisos;
}
