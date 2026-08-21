// Tipos espelhando os DTOs/entidades do backend (com.prx.cotacao.*). Ver
// documentacao-tecnica-sistema-cotacao-prx.md seção 5 pra visão geral da API.

export type CotacaoStatus = "RASCUNHO" | "EM_ANDAMENTO" | "FINALIZADA" | "CANCELADA";
export type CanalOrigem = "WEB" | "WHATSAPP";
export type CenarioSelecionado = "MENOR_PRECO" | "EQUILIBRADA" | "MELHOR_PRAZO";
export type StatusItem = "OK" | "NAO_IDENTIFICADO" | "PENDENTE_CONFIRMACAO";
export type FornecedorStatus = "ATIVO" | "PENDENTE_DADOS" | "INATIVO";
export type OrigemCadastro = "MANUAL" | "WHATSAPP_AUTO";
export type Papel = "ADMIN_PRX" | "OPERADOR_CLIENTE";

export interface Cotacao {
  id: string;
  criadoPor: string | null;
  titulo: string;
  status: CotacaoStatus;
  canalOrigem: CanalOrigem;
  listaRevisada: boolean;
  ultimaAtividadeEm: string | null;
  cenarioSelecionado: CenarioSelecionado | null;
  finalizadaEm: string | null;
  criadoEm: string;
  atualizadoEm: string | null;
}

export interface Fornecedor {
  id: string;
  nome: string;
  prazoEntregaPadrao: string | null;
  condicaoPagamentoPadrao: string | null;
  pedidoMinimoPadrao: number | null;
  observacoesPadrao: string | null;
  status: FornecedorStatus;
  origemCadastro: OrigemCadastro;
  criadoEm: string;
  atualizadoEm: string | null;
}

export interface FornecedorRequest {
  nome: string;
  prazoEntregaPadrao?: string | null;
  condicaoPagamentoPadrao?: string | null;
  pedidoMinimoPadrao?: number | null;
  observacoesPadrao?: string | null;
  status?: FornecedorStatus | null;
}

export interface Produto {
  id: string;
  nome: string;
  marca: string | null;
  pesoVolumeValor: number | null;
  pesoVolumeUnidade: string | null;
  unidadePadrao: string | null;
  embalagemQtdSugerida: number | null;
  criadoEm: string;
}

export interface ItemListaResponse {
  id: string;
  textoOriginal: string;
  quantidade: number;
  unidade: string;
  produtoIdEncontrado: string | null;
  scoreMatch: number;
  matched: boolean;
  // Prompt 12: true quando já existe cotacao_produto_fornecedor vinculado (item
  // conferido/confirmado) — trava edição de produto/quantidade/unidade no grid.
  temRespostaFornecedorConfirmada: boolean;
}

// Grid Unificado de Entrada de Dados (Prompt 12) — adição manual de item.
// Exatamente um de produtoId/nomeProdutoLivre deve ser informado.
export interface AdicionarItemCotacaoRequest {
  produtoId?: string | null;
  nomeProdutoLivre?: string | null;
  quantidade: number;
  unidade: string;
  // Item manual não tem um texto original de verdade (nunca veio de paste), mas o
  // operador pode digitar uma anotação livre mesmo assim.
  textoOriginal?: string | null;
}

// Resposta do modal "Colar do WhatsApp" (POST .../produtos/importar-texto).
// duplicado=true significa que a linha NÃO foi inserida (produto já vivo na cotação).
export interface ImportarTextoItemResponse {
  id: string | null;
  textoOriginal: string;
  quantidade: number;
  unidade: string;
  produtoIdEncontrado: string | null;
  matched: boolean;
  duplicado: boolean;
}

export interface ItemRespostaResponse {
  id: string;
  textoOriginal: string;
  nomeProduto: string;
  precoInformado: number;
  precoUnitarioCalculado: number;
  semEstoque: boolean;
  confiancaMatch: number;
  status: StatusItem;
}

export interface PrecoFornecedor {
  fornecedorId: string;
  nomeFornecedor: string;
  precoInformado: number;
  precoUnitarioCalculado: number;
  semEstoque: boolean;
  status: StatusItem;
  divergenciaComparativa: boolean;
}

export interface ComparativoItemResponse {
  cotacaoProdutoId: string;
  produtoId: string | null;
  nomeProduto: string;
  quantidade: number;
  unidade: string;
  precosPorFornecedor: PrecoFornecedor[];
}

// KPIs de "Economia de Cotações" (Dashboard) — GET /cotacoes/economia-resumo,
// agregados no backend sobre TODAS as cotações FINALIZADA do tenant (não uma janela
// paginada). fornecedorMaisCompetitivoNome/Contagem vêm null quando nenhuma cotação
// finalizada tem sequer um item com oferta válida.
export interface EconomiaResumoResponse {
  cotacoesProcessadas: number;
  economiaAcumulada: number;
  mediaEconomiaPct: number;
  fornecedorMaisCompetitivoNome: string | null;
  fornecedorMaisCompetitivoContagem: number | null;
}

// Carrossel "Cotações Registradas" (Dashboard) — GET /cotacoes/economia-carrossel,
// paginado por cursor/keyset (nextCursor é opaco, só repassar de volta).
export interface CotacaoFinalizadaCursorResponse {
  id: string;
  finalizadaEm: string;
  itensCotados: number;
  valorTotalComprado: number;
  economizado: number;
  economizadoPct: number;
  fornecedoresCount: number;
}

export interface CotacaoFinalizadaCursorPage {
  items: CotacaoFinalizadaCursorResponse[];
  nextCursor: string | null;
  hasMore: boolean;
  // Total de cotações que batem com o filtro atual (inicio/fim) — não o total já
  // carregado no cliente.
  totalElements: number;
}

// Painel "Variação de Preço por Produto" (Dashboard) — GET /cotacoes/variacao-preco.
export interface ItemVariacaoPreco {
  produtoId: string;
  nomeProduto: string;
  menorPreco: number;
  maiorPreco: number;
  spreadPct: number;
  fornecedorMenorNome: string;
  fornecedorMaiorNome: string;
}

export interface VariacaoPrecoResponse {
  produtos: ItemVariacaoPreco[];
  totalProdutosComparados: number;
}

// Painel "Concentração de Fornecedores" (Dashboard) — GET /cotacoes/concentracao-fornecedores.
export interface ItemConcentracaoFornecedor {
  fornecedorId: string;
  nomeFornecedor: string;
  valorComprado: number;
  sharePct: number;
  acimaDoLimite: boolean;
}

export interface ConcentracaoFornecedoresResponse {
  fornecedores: ItemConcentracaoFornecedor[];
  limiteDependenciaPct: number;
  algumEmRisco: boolean;
}

export interface EditarItemCotacaoRequest {
  quantidade: number;
  unidade: string;
  produtoId?: string | null;
  // Nome digitado sem match no catálogo — resolve-ou-cria pelo mesmo pipeline do
  // parser de lista. Usado quando o item não tinha produto identificado e o operador
  // digita um nome novo em vez de escolher um já cadastrado.
  nomeProdutoLivre?: string | null;
}

export interface ItemDistribuicao {
  cotacaoProdutoId: string;
  produtoNome: string;
  quantidade: number;
  unidade: string;
  precoUnitario: number;
  subtotal: number;
  menorPreco: boolean;
  ajustadoManualmente: boolean;
}

export interface DistribuicaoFornecedor {
  fornecedorId: string;
  fornecedorNome: string;
  itens: ItemDistribuicao[];
  totalFornecedor: number;
  pedidoMinimo: number | null;
  abaixoDoPedidoMinimo: boolean;
  faltaParaMinimo: number | null;
  prazoEntregaPadrao: string | null;
  condicaoPagamentoPadrao: string | null;
  // Fornecedor.status === "PENDENTE_DADOS" — só pode vir true em MENOR_PRECO/
  // MELHOR_PRAZO (EQUILIBRADA nunca escala um fornecedor com esse status).
  dadosPendentes: boolean;
}

// Fornecedor com oferta válida nesta cotação mas cadastro incompleto
// (status PENDENTE_DADOS) — sempre reportado, independente do cenário pedido,
// mesmo quando o fornecedor não aparece em `distribuicoes` (caso de EQUILIBRADA).
export interface FornecedorPendente {
  fornecedorId: string;
  fornecedorNome: string;
}

export interface ProdutoSemFornecedor {
  cotacaoProdutoId: string;
  produtoNome: string;
  quantidade: number;
  unidade: string;
}

// Item com oferta válida, removido da ordem por ajuste manual do operador —
// diferente de ProdutoSemFornecedor (que não tem oferta nenhuma).
export interface ItemRemovidoManualmente {
  cotacaoProdutoId: string;
  produtoNome: string;
  quantidade: number;
  unidade: string;
}

export interface MapaCompraResponse {
  cenario: CenarioSelecionado;
  distribuicoes: DistribuicaoFornecedor[];
  produtosSemFornecedor: ProdutoSemFornecedor[];
  totalGeral: number;
  economiaComparadaAoPior: number;
  itensRemovidosManualmente: ItemRemovidoManualmente[];
  temAjustesManuais: boolean;
  fornecedoresComDadosPendentes: FornecedorPendente[];
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface TokenResponse {
  accessToken: string;
}

export interface MensagemResponse {
  mensagem: string;
}

// Espelha com.prx.cotacao.cotacao.CotacaoProdutoFornecedor — /avisos/{cpfId}/resolver
// ainda devolve a entidade direto (não um DTO), ao contrário dos outros endpoints.
export interface ResolverAvisoResponse {
  id: string;
  cotacaoProdutoId: string;
  fornecedorId: string;
  textoOriginal: string | null;
  precoInformado: number;
  precoUnitarioCalculado: number | null;
  embalagemQtdConfirmada: number | null;
  tipoEmbalagemDetectado: string | null;
  semEstoque: boolean;
  confiancaMatch: number | null;
  status: StatusItem;
  resolvidoPor: string | null;
  criadoEm: string;
  versao: number | null;
}

// ── Fluxo sequencial de fornecedores da cotação + Conferência ───────────────
// Espelha com.prx.cotacao.cotacao.{CotacaoFornecedorStatus,StatusConferencia,
// MotivoConferencia,TipoResolucao} e os DTOs em cotacao/dto/.

export type CotacaoFornecedorStatus = "PENDENTE" | "PROCESSADO" | "CONFIRMADO";

export interface CotacaoFornecedorResponse {
  id: string;
  fornecedorId: string;
  nomeFornecedor: string | null;
  ordem: number;
  status: CotacaoFornecedorStatus;
}

export interface AdicionarFornecedorCotacaoRequest {
  fornecedorId?: string | null;
  novoFornecedor?: FornecedorRequest | null;
}

export type StatusConferencia = "OK" | "ATENCAO" | "REVISAR";

export type MotivoConferencia =
  | "BRAND_CHANGED"
  | "WEIGHT_CHANGED"
  | "WEIGHT_ADDED"
  | "VOLUME_ADDED"
  | "PACKAGE_QTY_ADDED"
  | "PACKAGE_QTY_CHANGED"
  | "PACKAGE_PRICE_SUSPECTED"
  | "MULTIPLE_OPTIONS"
  | "EXTRA_ITEM"
  | "LOW_CONFIDENCE_MATCH";

export interface CandidatoResposta {
  textoOriginal: string;
  marcaOferecida: string | null;
  // Nullable: o record CandidatoResposta do backend usa BigDecimal, e o parser deixa
  // preco null quando a linha do fornecedor não traz nenhum valor reconhecível
  // ("1 fardo sal realta"). A Conferência é justamente onde o operador informa esse
  // preço que faltou — ver ResolucaoInline em ConferenciaModal.
  precoInformado: number | null;
  confiancaMatch: number;
  semEstoque: boolean;
}

export interface ItemConferenciaResponse {
  itemBaseId: string | null;
  nomeItemBase: string | null;
  status: StatusConferencia;
  motivos: MotivoConferencia[];
  candidatos: CandidatoResposta[];
  preservado: boolean;
  precoAnteriorConfirmado: number | null;
}

export interface ContadoresConferencia {
  total: number;
  ok: number;
  atencao: number;
  revisar: number;
}

export interface PreviewRespostaResponse {
  contadores: ContadoresConferencia;
  itens: ItemConferenciaResponse[];
}

export type TipoResolucao =
  | "ACEITAR_SUGESTAO"
  | "SELECIONAR_CANDIDATO"
  | "EDITAR_MANUAL"
  | "SEM_OFERTA"
  | "ADICIONAR_A_LISTA"
  | "ASSOCIAR_A_ITEM"
  | "ADICIONAR_CANDIDATO_A_LISTA";

// Exatamente um entre itemBaseId/textoOriginalExtra deve vir preenchido: itemBaseId
// para resolver um item com correspondência na lista base; textoOriginalExtra
// (casado por CandidatoResposta.textoOriginal, mesmo padrão de SELECIONAR_CANDIDATO)
// para um item extra do fornecedor (itemBaseId null no ItemConferenciaResponse).
// ASSOCIAR_A_ITEM exige também associarAItemBaseId (o item base existente escolhido).
// Exceção: ADICIONAR_CANDIDATO_A_LISTA exige itemBaseId (item MULTIPLE_OPTIONS de
// origem) E textoOriginalExtra (candidato específico dentro dele) ao mesmo tempo —
// textoOriginalSelecionado/precoInformado carregam o texto/preço finais, editáveis.
export interface ResolucaoItemRequest {
  itemBaseId?: string | null;
  textoOriginalExtra?: string | null;
  tipo: TipoResolucao;
  textoOriginalSelecionado?: string | null;
  precoInformado?: number | null;
  embalagemQtd?: number | null;
  semEstoque?: boolean | null;
  associarAItemBaseId?: string | null;
}

export interface ConfirmarRespostaRequest {
  texto: string;
  resolucoes: ResolucaoItemRequest[];
}

// Resoluções item a item da Conferência em andamento de um fornecedor — vive no
// rascunho por fornecedor em /entrada/page.tsx (não como estado local do modal), pra
// sobreviver à troca de fornecedor e ao fechar/reabrir do modal (Fase 4.1).
export interface EstadoResolucao {
  resolucoes: Record<string, ResolucaoItemRequest>;
  spinOffs: Record<string, ResolucaoItemRequest[]>;
  excluidos: Record<string, Set<string>>;
}

export interface ConferenciaPatch extends Partial<EstadoResolucao> {
  preview?: PreviewRespostaResponse | null;
}

// Corpo de erro do backend (Spring ProblemDetail) — ver GlobalExceptionHandler.
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
}

// Histórico de Preços — ver GET /historico-precos.
export interface PontoReferenciaPreco {
  cotacaoId: string;
  finalizadaEm: string;
  preco: number;
  fornecedorId: string;
  nomeFornecedor: string;
}

export interface HistoricoPrecoProduto {
  produtoId: string;
  nomeProduto: string;
  quantidadeReferencia: number | null;
  unidadeReferencia: string | null;
  pontos: PontoReferenciaPreco[];
}

// Única exceção ao padrão Page<T> cru (ver HistoricoPrecoPageResponse no backend) — os
// 3 contadores são agregados de todo o catálogo do tenant, não da página exibida.
export interface HistoricoPrecoPageResponse {
  pagina: Page<HistoricoPrecoProduto>;
  produtosComHistorico: number;
  acimaDaUltimaReferencia: number;
  oportunidades: number;
}

// ── Admin PRX — /admin/tenants, /admin/tenants/{id}/usuarios ────────────────

export type TenantStatus = "ATIVO" | "SUSPENSO" | "TRIAL";

export interface Tenant {
  id: string;
  nomeFantasia: string;
  razaoSocial: string | null;
  cnpj: string | null;
  status: TenantStatus;
  plano: string | null;
  criadoEm: string;
}

export interface TenantRequest {
  nomeFantasia: string;
  razaoSocial?: string | null;
  cnpj?: string | null;
  status?: TenantStatus | null;
  plano?: string | null;
}

export interface UsuarioAdmin {
  id: string;
  tenantId: string | null;
  email: string;
  papel: Papel;
  ativo: boolean;
  criadoEm: string;
  // Só vem preenchido na resposta de criação/reset quando a senha foi gerada
  // automaticamente (admin não informou uma própria) — visível uma única vez.
  senhaGerada: string | null;
}

export interface UsuarioAdminRequest {
  email: string;
  senha?: string | null;
  ativo?: boolean | null;
}

export interface ResetSenhaResponse {
  senha: string;
}

export type TipoAcaoCliente = "INSERIR_PRODUTOS" | "REGISTRAR_RESPOSTA" | "NAO_IDENTIFICADO";
export type ResultadoAcaoCliente = "SUCESSO" | "ERRO" | null;

// Catálogo global de ações do cliente — vem do backend (GET /admin/acoes-cliente),
// seedado no boot a partir do enum AcaoCliente. NAO_IDENTIFICADO (resultado null) é o
// fallback universal, usado quando não há template específico configurado e sempre
// usado para mensagens de formato não reconhecido.
export interface AcaoCliente {
  id: string;
  acao: TipoAcaoCliente;
  resultado: ResultadoAcaoCliente;
  descricao: string;
}

export interface TemplateMensagem {
  id: string;
  tenantId: string;
  acaoClienteId: string;
  // Campos legados do envio por Meta Message Template (Prompt 18/19) — desde o
  // Prompt 20 (Service Message em texto livre) nenhum código de produção os usa pra
  // montar o envio real, mantidos opcionais só como referência histórica.
  nomeTemplateMeta: string | null;
  idioma: string | null;
  conteudo: string | null;
  descricaoParametros: string | null;
  ativo: boolean;
  criadoEm: string;
}

export interface TemplateMensagemRequest {
  acaoClienteId: string;
  nomeTemplateMeta?: string | null;
  idioma?: string | null;
  conteudo?: string | null;
  descricaoParametros?: string | null;
  ativo?: boolean | null;
}

export interface ItemCatalogoParametro {
  identificador: string;
  rotulo: string;
}

// Uma "vaga" é o par (AcaoCliente, template já configurado para ela ou null). O grid de
// Templates de Mensagens (Prompt 21) agrupa vagas por evento: o Fallback fica isolado
// (tipo "fallback"), as demais viram uma linha por `acao` com Sucesso e Erro juntos
// (tipo "evento") — mesma origem de dados de sempre, só reorganizada na apresentação.
export interface VagaTemplate {
  acaoCliente: AcaoCliente;
  existente: TemplateMensagem | null;
}

export type VagaTemplateLinha =
  | { tipo: "fallback"; vaga: VagaTemplate }
  | { tipo: "evento"; acao: TipoAcaoCliente; label: string; sucesso: VagaTemplate; erro: VagaTemplate };

export interface UsuarioTelefone {
  id: string;
  numeroWhatsapp: string;
  nomeContato: string | null;
  ativo: boolean;
  criadoEm: string;
}

export interface UsuarioTelefoneRequest {
  numeroWhatsapp: string;
  nomeContato?: string | null;
}

// ── Landing "Entrada de Dados" (/entrada) — ver CotacaoAtualResponse/
// CotacaoAnteriorCursorResponse/FornecedorHistoricoResponse no backend (Fase A do
// refactor, 2026-08-20).

// Um fornecedor dentro do card "Cotação atual" (avatar strip + Hall modo ativa).
export interface FornecedorRespostaResumo {
  fornecedorId: string;
  nome: string;
  status: CotacaoFornecedorStatus;
  // Aproximação: atualizadoEm de CotacaoFornecedor quando o status já saiu de
  // PENDENTE — não existe coluna dedicada de "respondido em" no schema.
  respondeuEm: string | null;
  itensCotados: number;
}

// Card "Cotação atual" da landing — a cotação RASCUNHO/EM_ANDAMENTO mais recente do
// tenant (tenant-wide, não filtrada por usuário criador). GET /cotacoes/atual, 204
// quando não há nenhuma.
export interface CotacaoAtualResponse {
  id: string;
  titulo: string;
  canalOrigem: CanalOrigem;
  ultimaAtividadeEm: string;
  criadoEm: string;
  itensListaBase: number;
  itensCotados: number;
  fornecedores: FornecedorRespostaResumo[];
}

// Um mini card do carrossel "Cotações anteriores" — só cotações FINALIZADA.
export interface CotacaoAnteriorCursorResponse {
  id: string;
  finalizadaEm: string;
  itensListaBase: number;
  itensCotados: number;
  fornecedoresCount: number;
}

export interface CotacaoAnteriorCursorPage {
  items: CotacaoAnteriorCursorResponse[];
  nextCursor: string | null;
  hasMore: boolean;
  totalElements: number;
}

// Selo de confiabilidade do Hall dos Fornecedores (modo histórico) — thresholds
// portados do rel() do mockup: <60min AGIL, <180min REGULAR, senão ATRASA.
export type SeloConfiabilidade = "AGIL" | "REGULAR" | "ATRASA";

// Card do Hall dos Fornecedores em modo histórico (sem cotação em andamento) —
// médias consolidadas de todas as cotações FINALIZADA em que o fornecedor participou.
export interface FornecedorHistoricoResponse {
  fornecedorId: string;
  nome: string;
  cotacoesParticipadas: number;
  coberturaMediaPct: number;
  tempoRespostaMedioMinutos: number;
  selo: SeloConfiabilidade;
}
