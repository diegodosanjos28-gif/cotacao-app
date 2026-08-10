package com.prx.cotacao.shared.error;

import com.prx.cotacao.identidade.auth.dto.AuthException;
import com.prx.cotacao.identidade.auth.dto.RateLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    // Lock otimista (@Version) perdido — outra requisição concorrente já gravou o
    // mesmo registro primeiro (ex: dois resolver() de aviso pro mesmo cpfId).
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLocking(OptimisticLockingFailureException ex) {
        log.warn("Conflito de lock otimista: {}", ex.getClass().getSimpleName());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Este item já foi atualizado por outra requisição. Recarregue e tente novamente.");
    }

    // Constraint de unicidade violada (ex: duas confirmações concorrentes do mesmo
    // fornecedor+cotação disputando o UNIQUE de cotacao_produto_fornecedor) — sem isso
    // cairia no catch-all como 500 genérico, quando semanticamente é um conflito.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Violação de integridade de dado: {}", ex.getMostSpecificCause().getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Conflito ao salvar — outra requisição concorrente já alterou este recurso. Tente novamente.");
    }

    @ExceptionHandler(AuthException.class)
    public ProblemDetail handleAuth(AuthException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(RateLimitException.class)
    public ProblemDetail handleRateLimit(RateLimitException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    // Catch-all: qualquer exceção não mapeada explicitamente não deve vazar mensagem
    // interna/stack trace pro cliente — loga o detalhe e devolve um 500 genérico.
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Erro não tratado", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Erro interno. Tente novamente.");
    }
}
