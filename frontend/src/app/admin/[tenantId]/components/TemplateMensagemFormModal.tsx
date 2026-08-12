"use client";

import { FormEvent, useState } from "react";
import Modal from "@/components/Modal";
import { atualizarTemplateMensagem, criarTemplateMensagem } from "@/lib/api";
import { getErrorMessage } from "@/lib/errors";
import { ResultadoTemplateMensagem, TemplateMensagem, TemplateMensagemRequest } from "@/lib/types";

interface Props {
  open: boolean;
  onClose: () => void;
  tenantId: string;
  resultado: ResultadoTemplateMensagem;
  template: TemplateMensagem | null;
  onSalvo: (template: TemplateMensagem) => void;
}

const LABEL_RESULTADO: Record<ResultadoTemplateMensagem, string> = {
  SUCESSO: "Sucesso",
  ERRO: "Erro",
};

// Mesmo padrão de UsuarioFormModal/FornecedorFormModal/TenantFormModal: form isolado,
// remontado via `key` no Modal toda vez que abre. `resultado` não é campo editável —
// vem fixo de qual das 2 vagas (Sucesso/Erro) foi clicada na tela.
function TemplateMensagemForm({
  tenantId,
  resultado,
  template,
  onClose,
  onSalvo,
}: {
  tenantId: string;
  resultado: ResultadoTemplateMensagem;
  template: TemplateMensagem | null;
  onClose: () => void;
  onSalvo: (template: TemplateMensagem) => void;
}) {
  const [nomeTemplateMeta, setNomeTemplateMeta] = useState(template?.nomeTemplateMeta ?? "");
  const [idioma, setIdioma] = useState(template?.idioma ?? "pt_BR");
  const [conteudo, setConteudo] = useState(template?.conteudo ?? "");
  const [descricaoParametros, setDescricaoParametros] = useState(template?.descricaoParametros ?? "");
  const [ativo, setAtivo] = useState(template?.ativo ?? true);
  const [salvando, setSalvando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!nomeTemplateMeta.trim() || !idioma.trim()) return;
    setSalvando(true);
    setErro(null);
    try {
      const dados: TemplateMensagemRequest = {
        resultado,
        nomeTemplateMeta,
        idioma,
        conteudo: conteudo || null,
        descricaoParametros: descricaoParametros || null,
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
        <label className="text-xs font-medium text-t2">Nome do template (Meta)</label>
        <input
          type="text"
          value={nomeTemplateMeta}
          onChange={(e) => setNomeTemplateMeta(e.target.value)}
          required
          placeholder="Nome exato do template aprovado no Business Manager"
          className="mt-1 w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
        />
      </div>
      <div>
        <label className="text-xs font-medium text-t2">Idioma</label>
        <input
          type="text"
          value={idioma}
          onChange={(e) => setIdioma(e.target.value)}
          required
          className="mt-1 w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
        />
      </div>
      <div>
        <label className="text-xs font-medium text-t2">Conteúdo (preview)</label>
        <textarea
          value={conteudo}
          onChange={(e) => setConteudo(e.target.value)}
          rows={3}
          placeholder="Texto aprovado na Meta, só como referência — não é enviado pelo sistema"
          className="mt-1 w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
        />
      </div>
      <div>
        <label className="text-xs font-medium text-t2">Parâmetros</label>
        <p className="mt-1 text-xs text-t3">
          A ordem real é sempre <code>{"{{1}} = tipo da mensagem"}</code>,{" "}
          <code>{"{{2}} = detalhe"}</code> — definida em código. O campo abaixo é só anotação livre sua.
        </p>
        <textarea
          value={descricaoParametros}
          onChange={(e) => setDescricaoParametros(e.target.value)}
          rows={2}
          className="mt-1 w-full rounded-md border border-bdr px-3 py-2 text-sm outline-none focus:border-prx"
        />
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

export default function TemplateMensagemFormModal({ open, onClose, tenantId, resultado, template, onSalvo }: Props) {
  return (
    <Modal open={open} onClose={onClose} title={`Configurar template — ${LABEL_RESULTADO[resultado]}`}>
      {open && (
        <TemplateMensagemForm
          key={template?.id ?? `novo-${resultado}`}
          tenantId={tenantId}
          resultado={resultado}
          template={template}
          onClose={onClose}
          onSalvo={onSalvo}
        />
      )}
    </Modal>
  );
}
