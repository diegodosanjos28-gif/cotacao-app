package com.prx.cotacao.cotacao.hall.dto;

// Selo de confiabilidade do Hall dos Fornecedores (modo histórico) — thresholds
// portados literalmente da função rel() do mockup_entrada_2.html (regra 3 do
// CLAUDE.md: porte de protótipo preserva comportamento exato, não é oportunidade de
// redesenhar). Ver HallFornecedoresService pra como tempoRespostaMedioMinutos (a
// métrica usada aqui) é calculado.
public enum SeloConfiabilidade {
    AGIL, REGULAR, ATRASA;

    public static SeloConfiabilidade deTempoRespostaMinutos(double minutos) {
        if (minutos < 60) return AGIL;
        if (minutos < 180) return REGULAR;
        return ATRASA;
    }
}
