import {
  AdicionarFornecedorCotacaoRequest,
  AdicionarItemCotacaoRequest,
  ComparativoItemResponse,
  ConfirmarRespostaRequest,
  Cotacao,
  CotacaoFornecedorResponse,
  CotacaoStatus,
  EconomiaResumoResponse,
  EditarItemCotacaoRequest,
  Fornecedor,
  FornecedorRequest,
  HistoricoPrecoPageResponse,
  ImportarTextoItemResponse,
  ItemListaResponse,
  ItemRespostaResponse,
  MapaCompraResponse,
  MensagemResponse,
  Page,
  PreviewRespostaResponse,
  ProblemDetail,
  Produto,
  ResetSenhaResponse,
  ResolverAvisoResponse,
  Tenant,
  TenantRequest,
  TenantStatus,
  TokenResponse,
  AcaoCliente,
  CenarioSelecionado,
  ItemCatalogoParametro,
  TemplateMensagem,
  TemplateMensagemRequest,
  UsuarioAdmin,
  UsuarioAdminRequest,
  UsuarioTelefone,
  UsuarioTelefoneRequest,
} from "./types";
import { getAccessToken, isAccessTokenExpiringSoon, setAccessToken } from "./auth";

const API_URL = `${process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080"}/api`;

// Exigido pelo CsrfHeaderFilter do backend em /auth/refresh e /auth/logout — os dois
// endpoints que autenticam via cookie (refresh_token), não via Authorization header.
// SameSite=Strict já barra a maior parte do CSRF; este header é defesa em
// profundidade, e só um fetch/XHR do próprio frontend consegue setá-lo.
const CSRF_HEADER = { "X-Requested-With": "XMLHttpRequest" } as const;

export class ApiError extends Error {
  status: number;
  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}

let refreshInFlight: Promise<boolean> | null = null;

// Exportado: é também o que o AuthProvider chama no bootstrap da aplicação pra fazer
// o silent refresh (o cookie refresh_token, se ainda válido, vai junto sozinho via
// credentials: 'include' — não há refresh token legível em JS pra checar antes).
export async function refreshSession(): Promise<boolean> {
  // Evita disparar múltiplos /auth/refresh em paralelo quando várias chamadas
  // batem em 401 ao mesmo tempo — todas esperam a mesma promise.
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      try {
        const res = await fetch(`${API_URL}/auth/refresh`, {
          method: "POST",
          credentials: "include",
          headers: { ...CSRF_HEADER },
        });
        if (!res.ok) return false;
        const tokens: TokenResponse = await res.json();
        setAccessToken(tokens.accessToken);
        return true;
      } catch {
        return false;
      } finally {
        refreshInFlight = null;
      }
    })();
  }
  return refreshInFlight;
}

// Único ponto de saída: avisa o backend (revoga o refresh token e limpa o cookie),
// limpa o access token em memória e força um reload completo pra /login. Usado tanto
// pelo botão "Sair" (NavBar) quanto pelo fetch wrapper abaixo quando o refresh falha.
// window.location.href em vez de router.replace porque garante que nenhum estado de
// componente sobrevive ao logout.
export async function logout(): Promise<void> {
  try {
    await fetch(`${API_URL}/auth/logout`, {
      method: "POST",
      credentials: "include",
      headers: { ...CSRF_HEADER },
    });
  } catch {
    // Best-effort: se a rede falhar, ainda assim limpa o estado local e manda pro
    // login — não faz sentido travar o logout do usuário por causa disso.
  }
  setAccessToken(null);
  if (typeof window !== "undefined") window.location.href = "/login";
}

async function request<T>(path: string, options: RequestInit = {}, retry = true): Promise<T> {
  // Renovação proativa: se o access token está perto de expirar, renova antes de
  // mandar a requisição em vez de esperar bater 401 — reduz a janela em que uma
  // chamada legítima falha por expiração. `retry=false` marca uma chamada que já é
  // o retry pós-refresh (ou o próprio /auth/refresh), então não reavalia de novo.
  if (retry && isAccessTokenExpiringSoon()) {
    await refreshSession();
  }

  const token = getAccessToken();
  const headers = new Headers(options.headers);
  if (!(options.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const res = await fetch(`${API_URL}${path}`, { ...options, headers, credentials: "include" });

  if (res.status === 401 && retry) {
    const refreshed = await refreshSession();
    if (refreshed) return request<T>(path, options, false);
    await logout();
    throw new ApiError("Sessão expirada", 401);
  }

  if (!res.ok) {
    const problem: ProblemDetail | null = await res.json().catch(() => null);
    throw new ApiError(problem?.detail ?? res.statusText, res.status);
  }

  if (res.status === 204) return undefined as T;
  return res.json();
}

// ── Auth ──────────────────────────────────────────────────────────────────

export function login(email: string, senha: string): Promise<TokenResponse> {
  return request<TokenResponse>("/auth/login", {
    method: "POST",
    body: JSON.stringify({ email, senha }),
  });
}

// tenantId null = sair do modo navegação, voltar ao painel admin puro. Só ADMIN_PRX
// pode chamar (backend valida via @PreAuthorize).
export function selecionarTenant(tenantId: string | null): Promise<TokenResponse> {
  return request<TokenResponse>("/auth/selecionar-tenant", {
    method: "POST",
    body: JSON.stringify({ tenantId }),
  });
}

// ── Cotações ──────────────────────────────────────────────────────────────

interface ListarCotacoesOpcoes {
  page?: number;
  size?: number;
  status?: CotacaoStatus;
  q?: string;
  sort?: string;
}

export function listarCotacoes(opcoes: ListarCotacoesOpcoes = {}): Promise<Page<Cotacao>> {
  const { page = 0, size = 20, status, q, sort = "criadoEm,desc" } = opcoes;
  const params = new URLSearchParams({ page: String(page), size: String(size), sort });
  if (status) params.set("status", status);
  if (q) params.set("q", q);
  return request<Page<Cotacao>>(`/cotacoes?${params.toString()}`);
}

export function buscarCotacao(id: string): Promise<Cotacao> {
  return request<Cotacao>(`/cotacoes/${id}`);
}

export function criarCotacao(titulo: string): Promise<Cotacao> {
  return request<Cotacao>("/cotacoes", {
    method: "POST",
    body: JSON.stringify({ titulo }),
  });
}

export function finalizarCotacao(id: string, cenario: CenarioSelecionado): Promise<Cotacao> {
  return request<Cotacao>(`/cotacoes/${id}/finalizar?cenario=${cenario}`, { method: "POST" });
}

export function enviarLista(cotacaoId: string, texto: string): Promise<ItemListaResponse[]> {
  return request<ItemListaResponse[]>(`/cotacoes/${cotacaoId}/lista`, {
    method: "POST",
    body: JSON.stringify({ texto }),
  });
}

export function buscarLista(cotacaoId: string): Promise<ItemListaResponse[]> {
  return request<ItemListaResponse[]>(`/cotacoes/${cotacaoId}/lista`);
}

// Botão "Concluir ajuste e seguir para conferência" da tela Ajuste de Lista (Fase 3
// WhatsApp) — vira lista_revisada=TRUE sem tocar status/canal_origem.
export function concluirAjusteLista(cotacaoId: string): Promise<Cotacao> {
  return request<Cotacao>(`/cotacoes/${cotacaoId}/lista/concluir-ajuste`, { method: "POST" });
}

// Preview: roda parser + matching + classificação, NÃO persiste nada — só marca
// cotacao_fornecedor como PROCESSADO. Persistência de fato é confirmarResposta.
export function enviarResposta(
  cotacaoId: string,
  fornecedorId: string,
  texto: string,
): Promise<PreviewRespostaResponse> {
  return request<PreviewRespostaResponse>(`/cotacoes/${cotacaoId}/fornecedores/${fornecedorId}/resposta`, {
    method: "POST",
    body: JSON.stringify({ texto }),
  });
}

// Reconstrói o texto já persistido de uma resposta que nunca passou pelo preview
// (hoje, só o caminho WhatsApp — WhatsappRespostaFornecedorService persiste direto).
// Alimentar esse texto de volta em enviarResposta reabre a Conferência normalmente.
export function buscarRespostaPersistida(
  cotacaoId: string,
  fornecedorId: string,
): Promise<{ texto: string }> {
  return request<{ texto: string }>(`/cotacoes/${cotacaoId}/fornecedores/${fornecedorId}/resposta-persistida`);
}

// "Cancelar Conferência" (achado do usuário, 2026-08-04): apaga a resposta já
// persistida deste fornecedor pra esta cotação e volta cotacao_fornecedor.status pra
// PENDENTE — sem isso, "Conferir resposta do fornecedor" reconstruiria a mesma
// resposta cancelada no próximo clique (ver buscarRespostaPersistida).
export function cancelarRespostaFornecedor(cotacaoId: string, fornecedorId: string): Promise<void> {
  return request<void>(`/cotacoes/${cotacaoId}/fornecedores/${fornecedorId}/resposta`, {
    method: "DELETE",
  });
}

export function confirmarResposta(
  cotacaoId: string,
  fornecedorId: string,
  dados: ConfirmarRespostaRequest,
): Promise<ItemRespostaResponse[]> {
  return request<ItemRespostaResponse[]>(`/cotacoes/${cotacaoId}/fornecedores/${fornecedorId}/confirmar`, {
    method: "POST",
    body: JSON.stringify(dados),
  });
}

// Leitura somente-consulta da conferência já confirmada de um fornecedor (Prompt 26) —
// usada pela aba de Conferência pra navegar por conferências já realizadas sem
// reprocessar a resposta (o que rebaixaria o fornecedor de volta pra PROCESSADO).
export function buscarConferenciaConfirmada(
  cotacaoId: string,
  fornecedorId: string,
): Promise<ItemRespostaResponse[]> {
  return request<ItemRespostaResponse[]>(
    `/cotacoes/${cotacaoId}/fornecedores/${fornecedorId}/conferencia-confirmada`,
  );
}

// ── Fornecedores da cotação (fluxo sequencial) ──────────────────────────────

export function listarFornecedoresDaCotacao(cotacaoId: string): Promise<CotacaoFornecedorResponse[]> {
  return request<CotacaoFornecedorResponse[]>(`/cotacoes/${cotacaoId}/fornecedores`);
}

export function adicionarFornecedorNaCotacao(
  cotacaoId: string,
  dados: AdicionarFornecedorCotacaoRequest,
): Promise<CotacaoFornecedorResponse> {
  return request<CotacaoFornecedorResponse>(`/cotacoes/${cotacaoId}/fornecedores`, {
    method: "POST",
    body: JSON.stringify(dados),
  });
}

export function resolverAviso(
  cotacaoId: string,
  cpfId: string,
  embalagemQtd: number,
): Promise<ResolverAvisoResponse> {
  return request<ResolverAvisoResponse>(`/cotacoes/${cotacaoId}/avisos/${cpfId}/resolver`, {
    method: "POST",
    body: JSON.stringify({ embalagemQtd }),
  });
}

export function comparativo(cotacaoId: string): Promise<ComparativoItemResponse[]> {
  return request<ComparativoItemResponse[]>(`/cotacoes/${cotacaoId}/comparativo`);
}

// Comparativo de várias cotações numa chamada só — usado por telas que renderizam uma
// grid inteira (uma linha por cotação), em vez de disparar 1 request por linha
// visível (achado do usuário 08-20: o Dashboard estourava o rate limit por IP fazendo
// isso em paralelo pra duas grids ao mesmo tempo). Chave do retorno é o cotacaoId.
export function comparativoLote(cotacaoIds: string[]): Promise<Record<string, ComparativoItemResponse[]>> {
  if (cotacaoIds.length === 0) return Promise.resolve({});
  const params = new URLSearchParams({ ids: cotacaoIds.join(",") });
  return request<Record<string, ComparativoItemResponse[]>>(`/cotacoes/comparativo-lote?${params.toString()}`);
}

// KPIs de "Economia de Cotações" (Dashboard) agregados no backend sobre TODAS as
// cotações FINALIZADA do tenant — não depende de paginar/buscar cotações no frontend.
export function economiaResumo(): Promise<EconomiaResumoResponse> {
  return request<EconomiaResumoResponse>("/cotacoes/economia-resumo");
}

export function editarItemCotacao(
  cotacaoId: string,
  cotacaoProdutoId: string,
  dados: EditarItemCotacaoRequest,
): Promise<void> {
  return request<void>(`/cotacoes/${cotacaoId}/produtos/${cotacaoProdutoId}`, {
    method: "PATCH",
    body: JSON.stringify(dados),
  });
}

export function removerItemCotacao(cotacaoId: string, cotacaoProdutoId: string): Promise<void> {
  return request<void>(`/cotacoes/${cotacaoId}/produtos/${cotacaoProdutoId}`, {
    method: "DELETE",
  });
}

// Grid Unificado (Prompt 12) — botão "+ Adicionar Produto".
export function adicionarItemCotacao(
  cotacaoId: string,
  dados: AdicionarItemCotacaoRequest,
): Promise<ItemListaResponse> {
  return request<ItemListaResponse>(`/cotacoes/${cotacaoId}/produtos`, {
    method: "POST",
    body: JSON.stringify(dados),
  });
}

// Modal "Colar do WhatsApp" (Prompt 12) — sempre-append, nunca substitui itens já no
// grid (ver CotacaoListaService.importarTexto no backend).
export function importarTextoCotacao(cotacaoId: string, texto: string): Promise<ImportarTextoItemResponse[]> {
  return request<ImportarTextoItemResponse[]>(`/cotacoes/${cotacaoId}/produtos/importar-texto`, {
    method: "POST",
    body: JSON.stringify({ texto }),
  });
}

export function mapaCompra(cotacaoId: string, cenario: CenarioSelecionado): Promise<MapaCompraResponse> {
  return request<MapaCompraResponse>(`/cotacoes/${cotacaoId}/mapa?cenario=${cenario}`);
}

export function mensagemFornecedor(
  cotacaoId: string,
  fornecedorId: string,
  cenario: CenarioSelecionado,
): Promise<MensagemResponse> {
  return request<MensagemResponse>(`/cotacoes/${cotacaoId}/fornecedores/${fornecedorId}/mensagem?cenario=${cenario}`);
}

// ── Ajuste manual da distribuição do Mapa de Compra ──────────────────────────

export function moverItemMapa(cotacaoId: string, cotacaoProdutoId: string, fornecedorId: string): Promise<void> {
  return request<void>(`/cotacoes/${cotacaoId}/mapa/itens/${cotacaoProdutoId}?fornecedorId=${fornecedorId}`, {
    method: "PUT",
  });
}

export function removerItemMapa(cotacaoId: string, cotacaoProdutoId: string): Promise<void> {
  return request<void>(`/cotacoes/${cotacaoId}/mapa/itens/${cotacaoProdutoId}/remover`, {
    method: "POST",
  });
}

export function restaurarItemMapa(cotacaoId: string, cotacaoProdutoId: string): Promise<void> {
  return request<void>(`/cotacoes/${cotacaoId}/mapa/itens/${cotacaoProdutoId}`, {
    method: "DELETE",
  });
}

export function restaurarCenarioMapa(cotacaoId: string): Promise<void> {
  return request<void>(`/cotacoes/${cotacaoId}/mapa/ajustes`, {
    method: "DELETE",
  });
}

// ── Fornecedores ──────────────────────────────────────────────────────────

export function listarFornecedores(): Promise<Fornecedor[]> {
  return request<Fornecedor[]>("/fornecedores");
}

export function criarFornecedor(dados: FornecedorRequest): Promise<Fornecedor> {
  return request<Fornecedor>("/fornecedores", { method: "POST", body: JSON.stringify(dados) });
}

export function atualizarFornecedor(id: string, dados: FornecedorRequest): Promise<Fornecedor> {
  return request<Fornecedor>(`/fornecedores/${id}`, { method: "PUT", body: JSON.stringify(dados) });
}

export function inativarFornecedor(id: string): Promise<void> {
  return request<void>(`/fornecedores/${id}`, { method: "DELETE" });
}

// ── Produtos ──────────────────────────────────────────────────────────────

interface BuscarProdutosOpcoes {
  q?: string;
  page?: number;
  size?: number;
}

// Listagem/autocomplete paginado — usado pra buscar sugestões, nunca pra resolver nome
// de um produtoId já conhecido (ver buscarProdutosPorIds abaixo, bounded pelo tamanho
// da cotação, não pelo catálogo inteiro do tenant).
export function buscarProdutos(opcoes: BuscarProdutosOpcoes = {}): Promise<Page<Produto>> {
  const { q, page = 0, size = 8 } = opcoes;
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (q) params.set("q", q);
  return request<Page<Produto>>(`/produtos?${params.toString()}`);
}

// Resolução de nome por IDs conhecidos (ex: produtoIdEncontrado de itens já salvos numa
// cotação) — devolve a lista completa desses IDs, sem paginação.
export function buscarProdutosPorIds(ids: string[]): Promise<Produto[]> {
  if (ids.length === 0) return Promise.resolve([]);
  const params = new URLSearchParams({ ids: ids.join(",") });
  return request<Produto[]>(`/produtos?${params.toString()}`);
}

// ── Histórico de Preços ──────────────────────────────────────────────────

interface HistoricoPrecosOpcoes {
  q?: string;
  page?: number;
  size?: number;
}

export function historicoPrecos(opcoes: HistoricoPrecosOpcoes = {}): Promise<HistoricoPrecoPageResponse> {
  const { q, page = 0, size = 20 } = opcoes;
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (q) params.set("q", q);
  return request<HistoricoPrecoPageResponse>(`/historico-precos?${params.toString()}`);
}

// ── Admin: Tenants ────────────────────────────────────────────────────────

export function listarTenants(q?: string, status?: TenantStatus): Promise<Tenant[]> {
  const params = new URLSearchParams();
  if (q) params.set("q", q);
  if (status) params.set("status", status);
  const query = params.toString() ? `?${params.toString()}` : "";
  return request<Tenant[]>(`/admin/tenants${query}`);
}

export function buscarTenant(id: string): Promise<Tenant> {
  return request<Tenant>(`/admin/tenants/${id}`);
}

export function criarTenant(dados: TenantRequest): Promise<Tenant> {
  return request<Tenant>("/admin/tenants", { method: "POST", body: JSON.stringify(dados) });
}

export function atualizarTenant(id: string, dados: TenantRequest): Promise<Tenant> {
  return request<Tenant>(`/admin/tenants/${id}`, { method: "PUT", body: JSON.stringify(dados) });
}

// ── Admin: Usuários ───────────────────────────────────────────────────────

export function listarUsuariosDoTenant(tenantId: string): Promise<UsuarioAdmin[]> {
  return request<UsuarioAdmin[]>(`/admin/tenants/${tenantId}/usuarios`);
}

export function criarUsuarioDoTenant(tenantId: string, dados: UsuarioAdminRequest): Promise<UsuarioAdmin> {
  return request<UsuarioAdmin>(`/admin/tenants/${tenantId}/usuarios`, {
    method: "POST",
    body: JSON.stringify(dados),
  });
}

export function atualizarUsuarioDoTenant(
  tenantId: string,
  usuarioId: string,
  dados: UsuarioAdminRequest,
): Promise<UsuarioAdmin> {
  return request<UsuarioAdmin>(`/admin/tenants/${tenantId}/usuarios/${usuarioId}`, {
    method: "PUT",
    body: JSON.stringify(dados),
  });
}

export function resetarSenhaUsuario(tenantId: string, usuarioId: string): Promise<ResetSenhaResponse> {
  return request<ResetSenhaResponse>(`/admin/tenants/${tenantId}/usuarios/${usuarioId}/reset-senha`, {
    method: "POST",
  });
}

// ── Admin: Templates de Mensagens ────────────────────────────────────────────

export function listarTemplatesMensagem(tenantId: string): Promise<TemplateMensagem[]> {
  return request<TemplateMensagem[]>(`/admin/tenants/${tenantId}/templates-mensagem`);
}

export function criarTemplateMensagem(tenantId: string, dados: TemplateMensagemRequest): Promise<TemplateMensagem> {
  return request<TemplateMensagem>(`/admin/tenants/${tenantId}/templates-mensagem`, {
    method: "POST",
    body: JSON.stringify(dados),
  });
}

export function atualizarTemplateMensagem(
  tenantId: string,
  templateId: string,
  dados: TemplateMensagemRequest,
): Promise<TemplateMensagem> {
  return request<TemplateMensagem>(`/admin/tenants/${tenantId}/templates-mensagem/${templateId}`, {
    method: "PUT",
    body: JSON.stringify(dados),
  });
}

export function listarParametrosDisponiveis(
  tenantId: string,
  acaoClienteId: string,
): Promise<ItemCatalogoParametro[]> {
  const params = new URLSearchParams({ acaoClienteId });
  return request<ItemCatalogoParametro[]>(
    `/admin/tenants/${tenantId}/templates-mensagem/parametros-disponiveis?${params}`,
  );
}

// ── Admin: Ações do cliente (catálogo global, alimenta o grid de Templates) ─

export function listarAcoesCliente(): Promise<AcaoCliente[]> {
  return request<AcaoCliente[]>(`/admin/acoes-cliente`);
}

// ── Admin: Administradores (ADMIN_PRX, sem tenant) ──────────────────────────

export function listarAdministradores(): Promise<UsuarioAdmin[]> {
  return request<UsuarioAdmin[]>("/admin/administradores");
}

export function criarAdministrador(dados: UsuarioAdminRequest): Promise<UsuarioAdmin> {
  return request<UsuarioAdmin>("/admin/administradores", {
    method: "POST",
    body: JSON.stringify(dados),
  });
}

export function atualizarAdministrador(usuarioId: string, dados: UsuarioAdminRequest): Promise<UsuarioAdmin> {
  return request<UsuarioAdmin>(`/admin/administradores/${usuarioId}`, {
    method: "PUT",
    body: JSON.stringify(dados),
  });
}

export function resetarSenhaAdministrador(usuarioId: string): Promise<ResetSenhaResponse> {
  return request<ResetSenhaResponse>(`/admin/administradores/${usuarioId}/reset-senha`, {
    method: "POST",
  });
}

// ── Meus Telefones (WhatsApp) ────────────────────────────────────────────

export function listarMeusTelefones(): Promise<UsuarioTelefone[]> {
  return request<UsuarioTelefone[]>("/usuarios/me/telefones");
}

export function criarMeuTelefone(dados: UsuarioTelefoneRequest): Promise<UsuarioTelefone> {
  return request<UsuarioTelefone>("/usuarios/me/telefones", { method: "POST", body: JSON.stringify(dados) });
}

export function removerMeuTelefone(id: string): Promise<void> {
  return request<void>(`/usuarios/me/telefones/${id}`, { method: "DELETE" });
}
