import { describe, expect, it } from "vitest";
import { labelMes, primeiroDiaMes, somarMeses, ultimoDiaMes } from "../competencia";

// mesAtual() não é testado aqui — depende de new Date() (relógio real), sem valor
// de asserção estável. somarMeses/labelMes são puras (recebem "AAAA-MM"), cobrem o
// caso que motivou evitar Date: virada de ano e formatação sem passar por
// new Date("AAAA-MM-01"), que desloca de fuso (ver comentário em competencia.ts).

describe("somarMeses", () => {
  it("soma dentro do mesmo ano", () => {
    expect(somarMeses("2026-08", 1)).toBe("2026-09");
    expect(somarMeses("2026-08", -1)).toBe("2026-07");
  });

  it("vira o ano ao somar além de dezembro", () => {
    expect(somarMeses("2026-12", 1)).toBe("2027-01");
  });

  it("vira o ano ao subtrair antes de janeiro", () => {
    expect(somarMeses("2026-01", -1)).toBe("2025-12");
  });

  it("delta 0 retorna o mesmo mês", () => {
    expect(somarMeses("2026-08", 0)).toBe("2026-08");
  });
});

describe("labelMes", () => {
  it("formata mês e ano por extenso, em português, com inicial maiúscula", () => {
    expect(labelMes("2026-08")).toBe("Agosto de 2026");
    expect(labelMes("2026-01")).toBe("Janeiro de 2026");
    expect(labelMes("2026-12")).toBe("Dezembro de 2026");
  });
});

describe("primeiroDiaMes", () => {
  it("sempre dia 01", () => {
    expect(primeiroDiaMes("2026-08")).toBe("2026-08-01");
    expect(primeiroDiaMes("2026-02")).toBe("2026-02-01");
  });
});

describe("ultimoDiaMes", () => {
  it("mês de 31 dias", () => {
    expect(ultimoDiaMes("2026-08")).toBe("2026-08-31");
  });

  it("mês de 30 dias", () => {
    expect(ultimoDiaMes("2026-09")).toBe("2026-09-30");
  });

  it("fevereiro em ano comum (28 dias)", () => {
    expect(ultimoDiaMes("2026-02")).toBe("2026-02-28");
  });

  it("fevereiro em ano bissexto (29 dias)", () => {
    expect(ultimoDiaMes("2028-02")).toBe("2028-02-29");
  });

  it("dezembro não vira janeiro do ano seguinte", () => {
    expect(ultimoDiaMes("2026-12")).toBe("2026-12-31");
  });
});
