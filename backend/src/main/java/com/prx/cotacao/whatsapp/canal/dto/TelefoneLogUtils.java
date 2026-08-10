package com.prx.cotacao.whatsapp.canal.dto;

/** Mascara número de telefone para log — só os últimos 4 dígitos ficam visíveis. */
public final class TelefoneLogUtils {

    private TelefoneLogUtils() {}

    public static String mascarar(String numero) {
        if (numero == null || numero.length() <= 4) {
            return "****";
        }
        return "****" + numero.substring(numero.length() - 4);
    }
}
