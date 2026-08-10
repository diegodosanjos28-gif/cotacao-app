package com.prx.cotacao.shared.tenant;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;

import jakarta.persistence.EntityManagerFactory;

@Configuration
@EnableJpaRepositories(
    basePackages = "com.prx.cotacao",
    repositoryBaseClass = TenantAwareRepositoryImpl.class
)
public class JpaConfig {

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new TenantAwareTransactionManager(emf);
    }
}
