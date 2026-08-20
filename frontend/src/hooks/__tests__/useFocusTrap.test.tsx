import { useRef, useState } from "react";
import { describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { useFocusTrap } from "../useFocusTrap";

// Harness mínimo: um botão "Abrir" fora do trap (pra testar devolução de foco) e um
// container condicionalmente montado (como um modal). "Fechar-outer" fica FORA do
// containerRef de propósito — fecha o harness sem contar como elemento focável do
// trap, pra poder testar o caso "sem nenhum elemento focável dentro" isoladamente.
function Harness({ semFocavel = false }: { semFocavel?: boolean }) {
  const [aberto, setAberto] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  useFocusTrap(containerRef, aberto);

  return (
    <div>
      <button onClick={() => setAberto(true)}>Abrir</button>
      {aberto && (
        <>
          <div ref={containerRef} data-testid="container">
            {!semFocavel && (
              <>
                <button>Primeiro</button>
                <button>Segundo</button>
                <button onClick={() => setAberto(false)}>Fechar</button>
              </>
            )}
          </div>
          <button onClick={() => setAberto(false)}>Fechar-outer</button>
        </>
      )}
    </div>
  );
}

describe("useFocusTrap", () => {
  it("move o foco pro primeiro elemento focável do container ao ativar", async () => {
    render(<Harness />);
    fireEvent.click(screen.getByText("Abrir"));

    expect(await screen.findByText("Primeiro")).toBe(document.activeElement);
  });

  it("Tab no último elemento cicla de volta pro primeiro (trap)", async () => {
    render(<Harness />);
    fireEvent.click(screen.getByText("Abrir"));
    await screen.findByText("Primeiro");

    screen.getByText("Fechar").focus();
    fireEvent.keyDown(document.activeElement!, { key: "Tab" });

    expect(document.activeElement).toBe(screen.getByText("Primeiro"));
  });

  it("Shift+Tab no primeiro elemento cicla pro último (trap)", async () => {
    render(<Harness />);
    fireEvent.click(screen.getByText("Abrir"));
    await screen.findByText("Primeiro");

    screen.getByText("Primeiro").focus();
    fireEvent.keyDown(document.activeElement!, { key: "Tab", shiftKey: true });

    expect(document.activeElement).toBe(screen.getByText("Fechar"));
  });

  it("devolve o foco ao elemento anterior quando desativa (fecha)", async () => {
    render(<Harness />);
    const abrir = screen.getByText("Abrir");
    abrir.focus();
    fireEvent.click(abrir);
    await screen.findByText("Primeiro");

    fireEvent.click(screen.getByText("Fechar"));

    expect(document.activeElement).toBe(abrir);
  });

  it("sem nenhum elemento focável, o próprio container recebe foco (nunca fica solto)", async () => {
    render(<Harness semFocavel />);
    fireEvent.click(screen.getByText("Abrir"));

    const container = await screen.findByTestId("container");
    expect(document.activeElement).toBe(container);
  });
});
