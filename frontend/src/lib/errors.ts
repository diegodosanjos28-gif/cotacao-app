import { ApiError } from "./api";

export function getErrorMessage(err: unknown, fallback: string): string {
  // `res.statusText` vem vazio em respostas HTTP/2 (não existe reason phrase no
  // protocolo), então erros sem corpo JSON com `detail` viram ApiError com message
  // "" — sem o fallback aqui, a tela mostra nenhum erro visível.
  return err instanceof ApiError && err.message ? err.message : fallback;
}
