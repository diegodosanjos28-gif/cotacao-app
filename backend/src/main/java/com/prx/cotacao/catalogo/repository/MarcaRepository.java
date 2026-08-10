package com.prx.cotacao.catalogo.repository;

import com.prx.cotacao.catalogo.entity.Marca;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface MarcaRepository extends JpaRepository<Marca, UUID> {

    /**
     * Nomes de marca visíveis ao tenant da conexão atual (globais + do próprio tenant,
     * filtrados pela RLS de {@code marca} — não há filtro explícito de tenant_id aqui de
     * propósito), ordenados da mais específica (nome mais longo) para a mais genérica.
     * A ordem original da lista hardcoded que esta tabela substitui era incidental, não
     * uma regra de negócio documentada — ordenar por tamanho evita que uma marca curta
     * "vença" por aparecer antes na tabela quando na verdade é substring de outra mais
     * específica.
     */
    @Query("SELECT m.nome FROM Marca m ORDER BY LENGTH(m.nome) DESC")
    List<String> findNomesOrdenadosPorEspecificidade();
}
