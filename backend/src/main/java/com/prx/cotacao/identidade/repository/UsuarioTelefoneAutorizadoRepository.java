package com.prx.cotacao.identidade.repository;

import com.prx.cotacao.identidade.entity.UsuarioTelefoneAutorizado;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UsuarioTelefoneAutorizadoRepository extends JpaRepository<UsuarioTelefoneAutorizado, UUID> {

    List<UsuarioTelefoneAutorizado> findByUsuarioIdOrderByCriadoEmDesc(UUID usuarioId);

    // NOTA (Fase 3): a identificação do remetente do webhook WhatsApp NÃO usa um
    // método aqui — usa JDBC puro direto em IdentificacaoWhatsappService. Motivo: essa
    // busca roda em modo admin (TenantContext.setAdmin(true)) porque o tenant do
    // remetente ainda não é conhecido, mas com spring.jpa.open-in-view=true o
    // EntityManager fica vinculado à requisição inteira pelo OSIV — um método de
    // repository aqui reutilizaria essa MESMA conexão (com o TenantContext de ANTES da
    // identificação), nunca vendo o modo admin de fato aplicado na sessão Postgres.
    // Ver javadoc de IdentificacaoWhatsappService antes de adicionar um método
    // findByNumeroWhatsapp... aqui de novo.

    // numero_whatsapp é único no sistema inteiro, não por tenant — mas esta checagem
    // só enxerga o tenant atual: RLS do Postgres restringe o que a conexão consegue
    // SELECT no nível da sessão (session var app.current_tenant_id), não só o
    // Hibernate @Filter — nem query nativa contorna isso (RLS se aplica à conexão,
    // não à forma como a query foi montada pelo cliente). Serve só pra dar a
    // mensagem amigável no caso comum (duplicata dentro do próprio tenant); o caso
    // cross-tenant é pego pela constraint UNIQUE do banco em
    // UsuarioTelefoneService.criar (RLS não filtra constraint de unicidade — ela roda
    // no nível do índice, indiferente a quais linhas a sessão pode ver).
    boolean existsByNumeroWhatsapp(String numeroWhatsapp);
}
