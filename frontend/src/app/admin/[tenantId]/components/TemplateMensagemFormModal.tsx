"use client";

import { FormEvent, useEffect, useRef, useState } from "react";
import Modal from "@/components/Modal";
import { atualizarTemplateMensagem, criarTemplateMensagem, listarParametrosDisponiveis } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { AcaoCliente, ItemCatalogoParametro, TemplateMensagem, TemplateMensagemRequest } from "@/lib/types";

interface Props {
  open: boolean;
  onClose: () => void;
  tenantId: string;
  acaoCliente: AcaoCliente;
  template: TemplateMensagem | null;
  onSalvo: (template: TemplateMensagem) => void;
}

// Mesmo padrão de UsuarioFormModal/FornecedorFormModal/TenantFormModal: form isolado,
// remontado via `key` no Modal toda vez que abre. `acaoCliente` não é campo editável —
// vem fixo de qual vaga foi clicada na tela.
function TemplateMensagemForm({
  tenantId,
  acaoCliente,
  template,
  onClose,
  onSalvo,
}: {
  tenantId: string;
  acaoCliente: AcaoCliente;
  template: TemplateMensagem | null;
  onClose: () => void;
  onSalvo: (template: TemplateMensagem) => void;
}) {
  const [conteudo, setConteudo] = useState(template?.conteudo ?? "");
  const [ativo, setAtivo] = useState(template?.ativo ?? true);
  const [salvando, setSalvando] = useState(false);
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

  // Insere {{identificador}} na posição do cursor — sem posição a rastrear (Prompt 20):
  // a substituição no envio real é por nome, cada ocorrência do token vira o valor real,
  // não existe mais {{1}}/{{2}} posicional.
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

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setSalvando(true);
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
      onSalvo(salvo);
      onClose();
    } catch (err) {
      setErro(getErrorMessage(err, "Não foi possível salvar o template."));
    } finally {
      setSalvando(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-3">
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
      <div className="flex justify-end gap-2 pt-2">
        <button
          type="button"
          onClick={onClose}
          className="rounded-md border border-bdr px-4 py-2 text-sm font-medium hover:bg-hov"
        >
          Cancelar
        </button>
        <button
          type="submit"
          disabled={salvando}
          className="rounded-md bg-prx px-4 py-2 text-sm font-medium text-white hover:bg-prx-l disabled:opacity-50"
        >
          {salvando ? "Salvando..." : "Salvar"}
        </button>
      </div>
    </form>
  );
}

export default function TemplateMensagemFormModal({ open, onClose, tenantId, acaoCliente, template, onSalvo }: Props) {
  return (
    <Modal open={open} onClose={onClose} title={`Configurar template — ${acaoCliente.descricao}`}>
      {open && (
        <TemplateMensagemForm
          key={template?.id ?? `novo-${acaoCliente.id}`}
          tenantId={tenantId}
          acaoCliente={acaoCliente}
          template={template}
          onClose={onClose}
          onSalvo={onSalvo}
        />
      )}
    </Modal>
  );
}
