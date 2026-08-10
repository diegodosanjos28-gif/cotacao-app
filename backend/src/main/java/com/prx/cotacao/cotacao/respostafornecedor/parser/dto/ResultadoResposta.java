package com.prx.cotacao.cotacao.respostafornecedor.parser.dto;

import java.util.List;

public record ResultadoResposta(
        String nomeFornecedor,
        List<LinhaFornecedor> linhas
) {}
