"use client";

import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from "react";
import Modal from "@/components/Modal";
import { atualizarTemplateMensagem, criarTemplateMensagem, listarParametrosDisponiveis } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { AcaoCliente, ItemCatalogoParametro, TemplateMensagem, TemplateMensagemRequest, VagaTemplate } from "@/lib/types";

interface Props {
  open: boolean;
  onClose: () => void;
  tenantId: string;
  label: string;
  sucesso: VagaTemplate;
  erro: VagaTemplate;
  onSalvos: (templates: TemplateMensagem[]) => void;
}

interface ResultadoSalvar {
  ok: boolean;
  template?: TemplateMensagem;
  erro?: string;
}

interface SecaoFormHandle {
  salvar: () => Promise<ResultadoSalvar>;
}

// Uma seção por resultado (Sucesso/Erro) — lógica de catálogo/parâmetro copiada de
// TemplateMensagemFormModal.tsx (não extraída/compartilhada, pra não arriscar aquele
// modal). Sem os campos legados de Meta Template (Prompt 21: removidos da UI).
const TemplateMensagemSecaoForm = forwardRef<SecaoFormHandle, {
  tenantId: string;
  acaoCliente: AcaoCliente;
  template: TemplateMensagem | null;
  visivel: boolean;
}>(function TemplateMensagemSecaoForm({ tenantId, acaoCliente, template, visivel }, ref) {
  const [conteudo, setConteudo] = useState(template?.conteudo ?? "");
  const [ativo, setAtivo] = useState(template?.ativo ?? true);
  const [erro, setErro] = useState<string | null>(null);
  const [catalogo, setCatalogo] = useState<ItemCatalogoParametro[]>([]);
  const conteudoRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    let cancelado = false;
    listarParametrosDisponiveis(tenantId, acaoCliente.id)
      .then((itens) => {
        if (!cancelado) setCatalogo(itens);
      })
      .catch(() => {
        if (!cancelado) setCatalogo([]);
      });
    return () => {
      cancelado = true;
    };
  }, [tenantId, acaoCliente.id]);

  function inserirParametro(identificador: string) {
    const token = `{{${identificador}}}`;
    const textarea = conteudoRef.current;
    if (textarea) {
      const start = textarea.selectionStart ?? conteudo.length;
      const end = textarea.selectionEnd ?? conteudo.length;
      const novoConteudo = conteudo.slice(0, start) + token + conteudo.slice(end);
      setConteudo(novoConteudo);
      requestAnimationFrame(() => {
        textarea.focus();
        textarea.selectionStart = textarea.selectionEnd = start + token.length;
      });
    } else {
      setConteudo(conteudo + token);
    }
  }

  useImperativeHandle(
    ref,
    () => ({
      async salvar(): Promise<ResultadoSalvar> {
        setErro(null);
        try {
          const dados: TemplateMensagemRequest = {
            acaoClienteId: acaoCliente.id,
            conteudo: conteudo || null,
            ativo,
          };
          const salvo = template
            ? await atualizarTemplateMensagem(tenantId, template.id, dados)
            : await criarTemplateMensagem(tenantId, dados);
          return { ok: true, template: salvo };
        } catch (err) {
          const msg = getErrorMessage(err, "Não foi possível salvar o template.");
          setErro(msg);
          return { ok: false, erro: msg };
        }
      },
    }),
    [tenantId, acaoCliente, template, conteudo, ativo],
  );

  return (
    <div className={visivel ? "space-y-3" : "hidden"}>
      {erro && <p className="text-sm text-er">{erro}</p>}
      <div>
        <label className="text-xs font-medium text-t2">Conteúdo da mensagem</label>
        <textarea
          ref={conteudoRef}
          value={conteudo}
          onChange={(e) => setConteudo(e.target.value)}
          rows={3}
          placeholder="Texto que será enviado ao cliente — use os botões abaixo para inserir parâmetros"
          className="mt-1 w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
        />
      </div>
      <div>
        <label className="text-xs font-medium text-t2">Parâmetros disponíveis</label>
        <p className="mt-1 text-xs text-t3">
          Clique para inserir <code>{"{{identificador}}"}</code> no texto — cada ocorrência é substituída pelo valor
          real no envio.
        </p>
        <div className="mt-2 flex flex-wrap gap-2">
          {catalogo.map((item) => (
            <button
              key={item.identificador}
              type="button"
              onClick={() => inserirParametro(item.identificador)}
              className="rounded-full border border-bdr px-2 py-0.5 text-xs font-medium hover:bg-hov"
            >
              + {item.rotulo}
            </button>
          ))}
          {catalogo.length === 0 && <span className="text-xs text-t3">Nenhum parâmetro disponível para este cenário.</span>}
        </div>
      </div>
      <label className="flex items-center gap-2 text-sm text-t2">
        <input type="checkbox" checked={ativo} onChange={(e) => setAtivo(e.target.checked)} />
        Ativo
      </label>
    </div>
  );
});

// Mesmo padrão de remontagem via `key` do TemplateMensagemFormModal.tsx.
function TemplateMensagemEventoForm({
  tenantId,
  sucesso,
  erro,
  onClose,
  onSalvos,
}: {
  tenantId: string;
  sucesso: VagaTemplate;
  erro: VagaTemplate;
  onClose: () => void;
  onSalvos: (templates: TemplateMensagem[]) => void;
}) {
  const [aba, setAba] = useState<"sucesso" | "erro">("sucesso");
  const [salvando, setSalvando] = useState(false);
  const [templateSucessoAtual, setTemplateSucessoAtual] = useState(sucesso.existente);
  const [templateErroAtual, setTemplateErroAtual] = useState(erro.existente);

  const sucessoRef = useRef<SecaoFormHandle>(null);
  const erroRef = useRef<SecaoFormHandle>(null);

  async function onSalvarTudo() {
    setSalvando(true);
    const [rSucesso, rErro] = await Promise.allSettled([
      sucessoRef.current!.salvar(),
      erroRef.current!.salvar(),
    ]);
    setSalvando(false);

    const okSucesso = rSucesso.status === "fulfilled" && rSucesso.value.ok;
    const okErro = rErro.status === "fulfilled" && rErro.value.ok;

    const salvos: TemplateMensagem[] = [];
    if (okSucesso) {
      const t = (rSucesso as PromiseFulfilledResult<ResultadoSalvar>).value.template!;
      salvos.push(t);
      setTemplateSucessoAtual(t);
    }
    if (okErro) {
      const t = (rErro as PromiseFulfilledResult<ResultadoSalvar>).value.template!;
      salvos.push(t);
      setTemplateErroAtual(t);
    }

    if (salvos.length > 0) onSalvos(salvos);

    if (okSucesso && okErro) {
      onClose();
    } else {
      setAba(!okSucesso ? "sucesso" : "erro");
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex gap-2 border-b border-bdr">
        <button
          type="button"
          onClick={() => setAba("sucesso")}
          className={`px-4 py-2 text-sm font-medium ${
            aba === "sucesso" ? "border-b-2 border-prx text-prx" : "text-t2 hover:text-t1"
          }`}
        >
          Sucesso
        </button>
        <button
          type="button"
          onClick={() => setAba("erro")}
          className={`px-4 py-2 text-sm font-medium ${
            aba === "erro" ? "border-b-2 border-prx text-prx" : "text-t2 hover:text-t1"
          }`}
        >
          Erro
        </button>
      </div>

      <TemplateMensagemSecaoForm
        ref={sucessoRef}
        tenantId={tenantId}
        acaoCliente={sucesso.acaoCliente}
        template={templateSucessoAtual}
        visivel={aba === "sucesso"}
      />
      <TemplateMensagemSecaoForm
        ref={erroRef}
        tenantId={tenantId}
        acaoCliente={erro.acaoCliente}
        template={templateErroAtual}
        visivel={aba === "erro"}
      />

      <div className="flex justify-end gap-2 pt-2">
        <button
          type="button"
          onClick={onClose}
          className="rounded-md border border-bdr px-4 py-2 text-sm font-medium hover:bg-hov"
        >
          Cancelar
        </button>
        <button
          type="button"
          onClick={onSalvarTudo}
          disabled={salvando}
          className="rounded-md bg-prx px-4 py-2 text-sm font-medium text-white hover:bg-prx-l disabled:opacity-50"
        >
          {salvando ? "Salvando..." : "Salvar"}
        </button>
      </div>
    </div>
  );
}

export default function TemplateMensagemEventoFormModal({ open, onClose, tenantId, label, sucesso, erro, onSalvos }: Props) {
  return (
    <Modal open={open} onClose={onClose} title={`Configurar template — ${label}`}>
      {open && (
        <TemplateMensagemEventoForm
          key={`${sucesso.acaoCliente.id}-${erro.acaoCliente.id}`}
          tenantId={tenantId}
          sucesso={sucesso}
          erro={erro}
          onClose={onClose}
          onSalvos={onSalvos}
        />
      )}
    </Modal>
  );
}
