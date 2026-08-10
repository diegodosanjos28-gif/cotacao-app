package com.prx.cotacao.cotacao.respostafornecedor.dto;

import com.prx.cotacao.cotacao.respostafornecedor.entity.ItemConferencia;

import java.util.List;

public record ContadoresConferencia(int total, int ok, int atencao, int revisar) {

    public static ContadoresConferencia from(List<ItemConferencia> itens) {
        int ok = 0, atencao = 0, revisar = 0;
        for (ItemConferencia item : itens) {
            switch (item.status()) {
                case OK -> ok++;
                case ATENCAO -> atencao++;
                case REVISAR -> revisar++;
            }
        }
        return new ContadoresConferencia(itens.size(), ok, atencao, revisar);
    }
}
