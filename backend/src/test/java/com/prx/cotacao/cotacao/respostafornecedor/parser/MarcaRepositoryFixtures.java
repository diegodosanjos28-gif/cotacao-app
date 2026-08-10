package com.prx.cotacao.cotacao.respostafornecedor.parser;

import com.prx.cotacao.catalogo.repository.MarcaRepository;

import java.util.Comparator;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MarcaRepository} de teste unitário — evita subir contexto Spring/JPA só para
 * testar {@code MatchingProdutoService.extrairMarca} e os serviços que dependem dele.
 * Stuba a mesma lista de marcas seedada como default global em V16__create_marca.sql,
 * ordenada como o repositório real ordena em produção (mais específica primeiro).
 */
public final class MarcaRepositoryFixtures {

    public static final List<String> MARCAS_DEFAULT = List.of(
            "heinz", "hellmanns", "hellmann", "uniao", "união", "liza", "marata", "amafil",
            "docesucar", "coca-cola", "coca cola", "pepsi", "guarana", "guaraná", "antarctica",
            "skol", "brahma", "sadia", "perdigao", "perdigão", "seara", "friboi", "swift",
            "camil", "tio joao", "tio joão", "kicaldo", "yoki", "vitarella", "adria",
            "renata", "barilla", "nissin", "maggi", "knorr", "arisco", "fugini", "sakura",
            "predilecta", "quero", "cica", "etti", "pomarola", "cirio", "elefante",
            "tang", "clight", "royal", "dr.oetker", "fleischmann", "dona benta",
            "sol", "bunge", "soya", "salada", "cocinero", "primor", "abc", "neve",
            "scott", "personal", "mili", "sulani", "qualita", "omo", "brilhante",
            "tixan", "ype", "minuano", "limpol", "bombril", "assolan"
    );

    private MarcaRepositoryFixtures() {
    }

    public static MarcaRepository comMarcasDefault() {
        MarcaRepository repo = mock(MarcaRepository.class);
        List<String> ordenadas = MARCAS_DEFAULT.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        when(repo.findNomesOrdenadosPorEspecificidade()).thenReturn(ordenadas);
        return repo;
    }
}
