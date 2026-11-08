package com.reconcileai.infrastructure.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wraps the application's pooled DataSource in {@link TenantAwareDataSource}. Flyway uses its own owner
 * credentials (spring.flyway.user) and is deliberately NOT wrapped: migrations are not tenant work.
 */
@Configuration
public class RowLevelSecurityConfig {

    @Bean
    static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource hikari && "dataSource".equals(beanName)) {
                    return new TenantAwareDataSource(hikari);
                }
                return bean;
            }
        };
    }
}
