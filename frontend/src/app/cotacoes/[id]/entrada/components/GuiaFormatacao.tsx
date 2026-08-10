import Card from "@/components/Card";
import { UNIDADES } from "@/lib/unidades";

export default function GuiaFormatacao() {
  return (
    <Card>
      <h2 className="font-semibold text-t1">Guia de formatação</h2>
      <ol className="mt-3 space-y-2 text-sm text-t2">
        <li>1. Uma linha por item.</li>
        <li>2. Formato: quantidade + unidade + nome do produto.</li>
        <li>3. Sem unidade explícita, assume-se &quot;un&quot; e quantidade 1.</li>
      </ol>
      <pre className="mt-3 rounded-md bg-surf p-3 font-mono text-xs text-t1">
        15un sazon legumes 60g{"\n"}2cx leite integral 1l
      </pre>
      <div className="mt-3 flex flex-wrap gap-1.5">
        {UNIDADES.map((u) => (
          <span
            key={u}
            className="rounded-full bg-hov px-2 py-0.5 text-xs font-medium text-t2"
          >
            {u}
          </span>
        ))}
      </div>
    </Card>
  );
}
