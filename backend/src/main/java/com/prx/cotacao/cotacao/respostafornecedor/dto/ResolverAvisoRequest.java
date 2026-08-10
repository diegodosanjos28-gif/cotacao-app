package com.prx.cotacao.cotacao.respostafornecedor.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ResolverAvisoRequest(@NotNull @Min(1) Integer embalagemQtd) {}
