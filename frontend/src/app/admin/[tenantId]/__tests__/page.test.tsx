// Cobre a tela /admin/{tenantId} (detalhe do tenant + tabela de usuários) migrada
// para o DataGrid compartilhado: colunas Email/Status/Criado em/Ações, o botão
// "Editar" abrindo o modal de edição, e "Resetar senha" desabilitando e mostrando
// "Resetando..." enquanto a chamada está em voo. Segue o mesmo padrão de
// `entrada/__tests__/page.test.tsx` para páginas com `params: Promise<...>` (React `use`).

import { Suspense } from "react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import TenantDetalhePage from "@/app/admin/[tenantId]/page";
import { ApiError } from "@/lib/api";
import { AcaoCliente, TemplateMensagem, Tenant, UsuarioAdmin } from "@/lib/types";

const {
  buscarTenantMock,
  listarUsuariosDoTenantMock,
  resetarSenhaUsuarioMock,
  listarAcoesClienteMock,
  listarTemplatesMensagemMock,
  criarTemplateMensagemMock,
  atualizarTemplateMensagemMock,
  listarParametrosDisponiveisMock,
} = vi.hoisted(() => ({
  buscarTenantMock: vi.fn(),
  listarUsuariosDoTenantMock: vi.fn(),
  resetarSenhaUsuarioMock: vi.fn(),
  listarAcoesClienteMock: vi.fn(),
  listarTemplatesMensagemMock: vi.fn(),
  criarTemplateMensagemMock: vi.fn(),
  atualizarTemplateMensagemMock: vi.fn(),
  listarParametrosDisponiveisMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/admin/t-1",
}));

vi.mock("@/components/AuthProvider", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/components/AuthProvider")>();
  return {
    ...actual,
    useAuth: () => ({ ready: true, authenticated: true, papel: "ADMIN_PRX", tenantId: null }),
  };
});

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    buscarTenant: buscarTenantMock,
    listarUsuariosDoTenant: listarUsuariosDoTenantMock,
    resetarSenhaUsuario: resetarSenhaUsuarioMock,
    listarAcoesCliente: listarAcoesClienteMock,
    listarTemplatesMensagem: listarTemplatesMensagemMock,
    criarTemplateMensagem: criarTemplateMensagemMock,
    atualizarTemplateMensagem: atualizarTemplateMensagemMock,
    listarParametrosDisponiveis: listarParametrosDisponiveisMock,
  };
});

function makeTenant(overrides: Partial<Tenant> = {}): Tenant {
  return {
    id: "t-1",
    nomeFantasia: "Mercado Alfa",
    razaoSocial: "Alfa Comércio Ltda",
    cnpj: "111",
    status: "ATIVO",
    plano: "PRO",
    criadoEm: "2026-07-01T10:00:00Z",
    ...overrides,
  };
}

function makeUsuario(overrides: Partial<UsuarioAdmin> = {}): UsuarioAdmin {
  return {
    id: "u-1",
    tenantId: "t-1",
    email: "operador@alfa.com",
    papel: "OPERADOR_CLIENTE",
    ativo: true,
    criadoEm: "2026-07-02T10:00:00Z",
    senhaGerada: null,
    ...overrides,
  };
}

function makeAcaoCliente(overrides: Partial<AcaoCliente> = {}): AcaoCliente {
  return {
    id: "ac-1",
    acao: "NAO_IDENTIFICADO",
    resultado: null,
    descricao: "Mensagem não identificada",
    ...overrides,
  };
}

function makeTemplateMensagem(overrides: Partial<TemplateMensagem> = {}): TemplateMensagem {
  return {
    id: "tm-1",
    tenantId: "t-1",
    acaoClienteId: "ac-1",
    nomeTemplateMeta: null,
    idioma: null,
    conteudo: "Recebemos sua mensagem.",
    descricaoParametros: null,
    ativo: true,
    criadoEm: "2026-07-01T10:00:00Z",
    ...overrides,
  };
}

// Catálogo fixo de 5 AcaoCliente usado nos testes do grid de Templates de Mensagens
// (Prompt 21): NAO_IDENTIFICADO (fallback, resultado null) + INSERIR_PRODUTOS e
// REGISTRAR_RESPOSTA, cada um com o par Sucesso/Erro — mesmo shape do seed real.
const ACAO_FALLBACK = makeAcaoCliente({
  id: "ac-fallback",
  acao: "NAO_IDENTIFICADO",
  resultado: null,
  descricao: "Mensagem não identificada",
});
const ACAO_INSERIR_SUCESSO = makeAcaoCliente({
  id: "ac-inserir-sucesso",
  acao: "INSERIR_PRODUTOS",
  resultado: "SUCESSO",
  descricao: "Lista de produtos processada com sucesso",
});
const ACAO_INSERIR_ERRO = makeAcaoCliente({
  id: "ac-inserir-erro",
  acao: "INSERIR_PRODUTOS",
  resultado: "ERRO",
  descricao: "Erro ao processar lista de produtos",
});
const ACAO_RESPOSTA_SUCESSO = makeAcaoCliente({
  id: "ac-resposta-sucesso",
  acao: "REGISTRAR_RESPOSTA",
  resultado: "SUCESSO",
  descricao: "Resposta do fornecedor registrada",
});
const ACAO_RESPOSTA_ERRO = makeAcaoCliente({
  id: "ac-resposta-erro",
  acao: "REGISTRAR_RESPOSTA",
  resultado: "ERRO",
  descricao: "Erro ao registrar resposta do fornecedor",
});

function acoesClientePadrao(): AcaoCliente[] {
  return [ACAO_FALLBACK, ACAO_INSERIR_SUCESSO, ACAO_INSERIR_ERRO, ACAO_RESPOSTA_SUCESSO, ACAO_RESPOSTA_ERRO];
}

const PLACEHOLDER_CONTEUDO = "Texto que será enviado ao cliente — use os botões abaixo para inserir parâmetros";

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

async function renderPage(tenantId = "t-1") {
  await act(async () => {
    render(
      <Suspense fallback={null}>
        <TenantDetalhePage params={Promise.resolve({ tenantId })} />
      </Suspense>,
    );
  });
}

// Troca para a aba "Templates de Mensagens" e espera o grid sair do estado de loading
// (a aba de Usuários some da tela, então não há ambiguidade de "Carregando...").
async function abrirAbaTemplates() {
  const botaoAba = screen.getByRole("button", { name: "Templates de Mensagens" });
  act(() => {
    botaoAba.click();
  });
  await waitFor(() => expect(screen.queryAllByText("Carregando...")).toHaveLength(0));
}

beforeEach(() => {
  buscarTenantMock.mockReset();
  listarUsuariosDoTenantMock.mockReset();
  resetarSenhaUsuarioMock.mockReset();
  listarAcoesClienteMock.mockReset();
  listarTemplatesMensagemMock.mockReset();
  criarTemplateMensagemMock.mockReset();
  atualizarTemplateMensagemMock.mockReset();
  listarParametrosDisponiveisMock.mockReset();

  buscarTenantMock.mockResolvedValue(makeTenant());
  listarUsuariosDoTenantMock.mockResolvedValue([]);
  listarTemplatesMensagemMock.mockResolvedValue([]);
  listarAcoesClienteMock.mockResolvedValue(acoesClientePadrao());
  listarParametrosDisponiveisMock.mockResolvedValue([]);
});

describe("TenantDetalhePage — tabela de usuários", () => {
  it("renderiza email, status (ATIVO/INATIVO) e data de criação de cada usuário", async () => {
    listarUsuariosDoTenantMock.mockResolvedValue([
      makeUsuario({ id: "u-1", email: "ativo@alfa.com", ativo: true }),
      makeUsuario({ id: "u-2", email: "inativo@alfa.com", ativo: false }),
    ]);

    await renderPage();

    await waitFor(() => expect(screen.getByText("ativo@alfa.com")).toBeTruthy());
    expect(screen.getByText("inativo@alfa.com")).toBeTruthy();
    // StatusBadge mapeia ATIVO/INATIVO para os labels "Ativo"/"Inativo" (ver StatusBadge.tsx).
    // Escopado à linha do usuário: o card do tenant no topo também mostra um badge "Ativo".
    const linhaAtivo = screen.getByText("ativo@alfa.com").closest("tr")!;
    const linhaInativo = screen.getByText("inativo@alfa.com").closest("tr")!;
    expect(linhaAtivo.textContent).toContain("Ativo");
    expect(linhaInativo.textContent).toContain("Inativo");
  });

  it("mostra 'Nenhum usuário cadastrado neste tenant ainda.' quando a lista está vazia", async () => {
    listarUsuariosDoTenantMock.mockResolvedValue([]);

    await renderPage();

    await waitFor(() =>
      expect(screen.getByText("Nenhum usuário cadastrado neste tenant ainda.")).toBeTruthy(),
    );
  });

  // Nota: o estado "Carregando..." (loading=true no DataGrid) não é re-testado aqui
  // com uma promise pendente para sempre — combinado com o `use(params)` desta página
  // (Suspense), isso trava `act`/`waitFor` indefinidamente (interação conhecida entre
  // React 19 `use()` e o scheduler em jsdom, não um bug da tela). O mecanismo genérico
  // de `loading` do DataGrid já é coberto em components/grid/__tests__/DataGrid.test.tsx,
  // e o mesmo estado desta tela é coberto nas páginas irmãs sem `use(params)`
  // (AdminPage, AdministradoresPage, SelecionarTenantPage, MeusTelefonesPage).
});

describe("TenantDetalhePage — ação Editar", () => {
  it("clicar em 'Editar' na linha do usuário abre o modal de edição", async () => {
    listarUsuariosDoTenantMock.mockResolvedValue([makeUsuario({ email: "operador@alfa.com" })]);

    await renderPage();
    await waitFor(() => expect(screen.getByText("operador@alfa.com")).toBeTruthy());

    const linha = screen.getByText("operador@alfa.com").closest("tr")!;
    const botaoEditar = Array.from(linha.querySelectorAll("button")).find((b) => b.textContent === "Editar")!;
    act(() => {
      botaoEditar.click();
    });

    expect(screen.getByText("Editar usuário")).toBeTruthy();
  });
});

describe("TenantDetalhePage — ação Resetar senha", () => {
  it("mostra 'Resetando...' e desabilita o botão enquanto a chamada está em voo, depois abre o modal com a senha", async () => {
    listarUsuariosDoTenantMock.mockResolvedValue([makeUsuario({ id: "u-1", email: "operador@alfa.com" })]);
    const { promise, resolve } = deferred<{ senha: string }>();
    resetarSenhaUsuarioMock.mockReturnValue(promise);

    await renderPage();
    await waitFor(() => expect(screen.getByText("operador@alfa.com")).toBeTruthy());

    const linha = screen.getByText("operador@alfa.com").closest("tr")!;
    const botaoResetar = Array.from(linha.querySelectorAll("button")).find(
      (b) => b.textContent === "Resetar senha",
    )! as HTMLButtonElement;

    act(() => {
      botaoResetar.click();
    });

    await waitFor(() => expect(screen.getByText("Resetando...")).toBeTruthy());
    expect((screen.getByText("Resetando...") as HTMLButtonElement).disabled).toBe(true);
    expect(resetarSenhaUsuarioMock).toHaveBeenCalledWith("t-1", "u-1");

    await act(async () => {
      resolve({ senha: "abc12345" });
    });

    await waitFor(() => expect(screen.getByText("abc12345")).toBeTruthy());
  });

  it("mostra mensagem de erro quando o reset falha", async () => {
    listarUsuariosDoTenantMock.mockResolvedValue([makeUsuario({ id: "u-1", email: "operador@alfa.com" })]);
    resetarSenhaUsuarioMock.mockRejectedValue(new Error("boom"));

    await renderPage();
    await waitFor(() => expect(screen.getByText("operador@alfa.com")).toBeTruthy());

    const linha = screen.getByText("operador@alfa.com").closest("tr")!;
    const botaoResetar = Array.from(linha.querySelectorAll("button")).find(
      (b) => b.textContent === "Resetar senha",
    )!;

    await act(async () => {
      botaoResetar.click();
    });

    await waitFor(() =>
      expect(screen.getByText("Não foi possível resetar a senha.")).toBeTruthy(),
    );
  });
});

// ── Prompt 21: grid de Templates de Mensagens reorganizado (3 linhas: Fallback +
// Lista de Produtos + Resposta de Fornecedor, cada evento agrupando Sucesso/Erro) ──

describe("TenantDetalhePage — grid de Templates de Mensagens", () => {
  it("agrupa as 5 AcaoCliente do catálogo em exatamente 3 linhas", async () => {
    await renderPage();
    await abrirAbaTemplates();

    await waitFor(() => expect(screen.getByText("Mensagem não identificada")).toBeTruthy());
    expect(screen.getByText("Lista de Produtos")).toBeTruthy();
    expect(screen.getByText("Resposta de Fornecedor")).toBeTruthy();

    const corpo = screen.getByText("Lista de Produtos").closest("table")!.querySelector("tbody")!;
    expect(corpo.querySelectorAll("tr")).toHaveLength(3);
  });
});

describe("TenantDetalhePage — modal de evento (Sucesso/Erro)", () => {
  it("busca os parâmetros de Sucesso e Erro com acaoClienteId diferentes, e preserva o texto digitado ao trocar de aba", async () => {
    listarParametrosDisponiveisMock.mockImplementation((_tenantId: string, acaoClienteId: string) =>
      Promise.resolve(
        acaoClienteId === ACAO_INSERIR_SUCESSO.id
          ? [{ identificador: "nome_cliente", rotulo: "Nome do cliente" }]
          : [{ identificador: "motivo_erro", rotulo: "Motivo do erro" }],
      ),
    );

    await renderPage();
    await abrirAbaTemplates();
    await waitFor(() => expect(screen.getByText("Lista de Produtos")).toBeTruthy());

    const linha = screen.getByText("Lista de Produtos").closest("tr")!;
    act(() => {
      Array.from(linha.querySelectorAll("button")).find((b) => b.textContent === "Configurar")!.click();
    });

    await waitFor(() =>
      expect(listarParametrosDisponiveisMock).toHaveBeenCalledWith("t-1", ACAO_INSERIR_SUCESSO.id),
    );
    expect(listarParametrosDisponiveisMock).toHaveBeenCalledWith("t-1", ACAO_INSERIR_ERRO.id);

    // As duas seções (Sucesso/Erro) ficam montadas ao mesmo tempo — os dois catálogos
    // de parâmetros aparecem no DOM independente de qual aba está ativa.
    await waitFor(() => expect(screen.getByRole("button", { name: "+ Nome do cliente" })).toBeTruthy());
    expect(screen.getByRole("button", { name: "+ Motivo do erro" })).toBeTruthy();

    // Sucesso é a aba ativa por padrão.
    expect(screen.getByRole("button", { name: "Sucesso" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Erro" })).toBeTruthy();

    const textareas = screen.getAllByPlaceholderText(PLACEHOLDER_CONTEUDO) as HTMLTextAreaElement[];
    expect(textareas).toHaveLength(2);
    const [sucessoTextarea] = textareas;

    fireEvent.change(sucessoTextarea, { target: { value: "Recebemos sua lista!" } });
    expect(sucessoTextarea.value).toBe("Recebemos sua lista!");

    act(() => {
      screen.getByRole("button", { name: "Erro" }).click();
    });
    act(() => {
      screen.getByRole("button", { name: "Sucesso" }).click();
    });

    // A seção Sucesso nunca desmontou — o texto digitado continua lá, não voltou a
    // buscar o catálogo de novo nem resetou o textarea.
    expect(sucessoTextarea.value).toBe("Recebemos sua lista!");
    expect(listarParametrosDisponiveisMock).toHaveBeenCalledTimes(2);
  });
});

describe("TenantDetalhePage — Salvar do modal de evento (caminho feliz)", () => {
  it("um único Salvar persiste as duas seções e atualiza a prévia no grid", async () => {
    criarTemplateMensagemMock.mockImplementation((_tenantId: string, dados: { acaoClienteId: string; conteudo: string | null; ativo: boolean | null }) =>
      Promise.resolve(
        makeTemplateMensagem({
          id: dados.acaoClienteId === ACAO_INSERIR_SUCESSO.id ? "tm-sucesso" : "tm-erro",
          acaoClienteId: dados.acaoClienteId,
          conteudo: dados.conteudo,
          ativo: dados.ativo ?? true,
        }),
      ),
    );

    await renderPage();
    await abrirAbaTemplates();
    await waitFor(() => expect(screen.getByText("Lista de Produtos")).toBeTruthy());

    const linha = screen.getByText("Lista de Produtos").closest("tr")!;
    act(() => {
      Array.from(linha.querySelectorAll("button")).find((b) => b.textContent === "Configurar")!.click();
    });

    const [sucessoTextarea, erroTextarea] = screen.getAllByPlaceholderText(
      PLACEHOLDER_CONTEUDO,
    ) as HTMLTextAreaElement[];
    fireEvent.change(sucessoTextarea, { target: { value: "Lista recebida com sucesso!" } });
    fireEvent.change(erroTextarea, { target: { value: "Não conseguimos ler sua lista." } });

    await act(async () => {
      screen.getByRole("button", { name: "Salvar" }).click();
    });

    expect(criarTemplateMensagemMock).toHaveBeenCalledWith(
      "t-1",
      expect.objectContaining({ acaoClienteId: ACAO_INSERIR_SUCESSO.id, conteudo: "Lista recebida com sucesso!" }),
    );
    expect(criarTemplateMensagemMock).toHaveBeenCalledWith(
      "t-1",
      expect.objectContaining({ acaoClienteId: ACAO_INSERIR_ERRO.id, conteudo: "Não conseguimos ler sua lista." }),
    );

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    await waitFor(() => expect(screen.getByText("Lista recebida com sucesso!")).toBeTruthy());
    expect(screen.getByText("Não conseguimos ler sua lista.")).toBeTruthy();
  });
});

describe("TenantDetalhePage — Salvar do modal de evento (falha parcial)", () => {
  it("mantém o modal aberto na seção com erro e, no retry, não repete o create já bem-sucedido", async () => {
    let chamadasErro = 0;
    criarTemplateMensagemMock.mockImplementation((_tenantId: string, dados: { acaoClienteId: string; conteudo: string | null; ativo: boolean | null }) => {
      if (dados.acaoClienteId === ACAO_INSERIR_SUCESSO.id) {
        return Promise.resolve(
          makeTemplateMensagem({
            id: "tm-sucesso",
            acaoClienteId: dados.acaoClienteId,
            conteudo: dados.conteudo,
            ativo: dados.ativo ?? true,
          }),
        );
      }
      chamadasErro += 1;
      if (chamadasErro === 1) {
        return Promise.reject(new ApiError("Falha ao salvar o template de erro.", 500));
      }
      return Promise.resolve(
        makeTemplateMensagem({
          id: "tm-erro",
          acaoClienteId: dados.acaoClienteId,
          conteudo: dados.conteudo,
          ativo: dados.ativo ?? true,
        }),
      );
    });
    atualizarTemplateMensagemMock.mockImplementation(
      (_tenantId: string, templateId: string, dados: { acaoClienteId: string; conteudo: string | null; ativo: boolean | null }) =>
        Promise.resolve(
          makeTemplateMensagem({
            id: templateId,
            acaoClienteId: dados.acaoClienteId,
            conteudo: dados.conteudo,
            ativo: dados.ativo ?? true,
          }),
        ),
    );

    await renderPage();
    await abrirAbaTemplates();
    await waitFor(() => expect(screen.getByText("Lista de Produtos")).toBeTruthy());

    const linha = screen.getByText("Lista de Produtos").closest("tr")!;
    act(() => {
      Array.from(linha.querySelectorAll("button")).find((b) => b.textContent === "Configurar")!.click();
    });

    await act(async () => {
      screen.getByRole("button", { name: "Salvar" }).click();
    });

    // Falha parcial: modal continua aberto, mostrando o erro da seção Erro.
    expect(screen.getByRole("dialog")).toBeTruthy();
    await waitFor(() => expect(screen.getByText("Falha ao salvar o template de erro.")).toBeTruthy());
    expect(criarTemplateMensagemMock).toHaveBeenCalledTimes(2);
    expect(atualizarTemplateMensagemMock).not.toHaveBeenCalled();

    await act(async () => {
      screen.getByRole("button", { name: "Salvar" }).click();
    });

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    // A seção Sucesso já tinha sido salva com sucesso no primeiro clique — o retry usa
    // atualizarTemplateMensagem (update), não repete o create (evitaria um 409).
    const chamadasCriarSucesso = criarTemplateMensagemMock.mock.calls.filter(
      (chamada) => (chamada[1] as { acaoClienteId: string }).acaoClienteId === ACAO_INSERIR_SUCESSO.id,
    );
    expect(chamadasCriarSucesso).toHaveLength(1);
    expect(atualizarTemplateMensagemMock).toHaveBeenCalledWith(
      "t-1",
      "tm-sucesso",
      expect.objectContaining({ acaoClienteId: ACAO_INSERIR_SUCESSO.id }),
    );

    // A seção Erro nunca tinha sido salva — o retry chama criarTemplateMensagem de novo.
    const chamadasCriarErro = criarTemplateMensagemMock.mock.calls.filter(
      (chamada) => (chamada[1] as { acaoClienteId: string }).acaoClienteId === ACAO_INSERIR_ERRO.id,
    );
    expect(chamadasCriarErro).toHaveLength(2);
  });
});

describe("TenantDetalhePage — modal Fallback (sem campos legados de Meta Template)", () => {
  it("abre sem os campos legados e salva conteúdo/ativo", async () => {
    criarTemplateMensagemMock.mockResolvedValue(
      makeTemplateMensagem({
        id: "tm-fallback",
        acaoClienteId: ACAO_FALLBACK.id,
        conteudo: "Não entendi sua mensagem.",
        ativo: true,
      }),
    );

    await renderPage();
    await abrirAbaTemplates();
    await waitFor(() => expect(screen.getByText("Mensagem não identificada")).toBeTruthy());

    const linha = screen.getByText("Mensagem não identificada").closest("tr")!;
    act(() => {
      Array.from(linha.querySelectorAll("button")).find((b) => b.textContent === "Configurar")!.click();
    });

    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText(`Configurar template — ${ACAO_FALLBACK.descricao}`)).toBeTruthy();
    // Prompt 21 removeu os campos legados de Meta Message Template (nomeTemplateMeta/idioma).
    expect(dialog.querySelector("details")).toBeNull();
    expect(within(dialog).queryByText(/idioma/i)).toBeNull();
    expect(within(dialog).queryByText(/nome do template/i)).toBeNull();

    const textarea = within(dialog).getByPlaceholderText(PLACEHOLDER_CONTEUDO) as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: "Não entendi sua mensagem." } });

    await act(async () => {
      screen.getByRole("button", { name: "Salvar" }).click();
    });

    expect(criarTemplateMensagemMock).toHaveBeenCalledWith("t-1", {
      acaoClienteId: ACAO_FALLBACK.id,
      conteudo: "Não entendi sua mensagem.",
      ativo: true,
    });

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    await waitFor(() => expect(screen.getByText("Não entendi sua mensagem.")).toBeTruthy());
  });
});
