import { CanalOrigem } from "./types";

export function canalLabel(canal: CanalOrigem): string {
  return canal === "WHATSAPP" ? "WhatsApp AI" : "Web";
}
