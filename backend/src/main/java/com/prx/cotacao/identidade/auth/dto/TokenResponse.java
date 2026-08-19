package com.prx.cotacao.identidade.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

// refreshToken nunca é serializado na resposta HTTP — vai só no Set-Cookie httpOnly
// montado em AuthResource. O campo continua aqui pro service ter como construir esse
// cookie sem precisar de um segundo tipo de retorno.
public record TokenResponse(String accessToken, @JsonIgnore String refreshToken) {}
