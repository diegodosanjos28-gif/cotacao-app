package com.prx.cotacao.shared.error;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Erro de conflito de estado de negócio (ex: cotação já finalizada, embalagem já
 * confirmada) — distinto de {@link IllegalArgumentException}, que continua mapeado
 * para 400 e reservado a argumento malformado.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
