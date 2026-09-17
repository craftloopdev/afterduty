package org.springframework.boot.test.autoconfigure.orm.jpa;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.PersistenceExceptionTranslationAutoConfiguration;
import org.springframework.boot.test.autoconfigure.OverrideAutoConfiguration;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.BootstrapWith;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Spring Boot 4.x-compatible replacement for the removed
 * {@code @DataJpaTest} annotation from {@code spring-boot-test-autoconfigure}.
 *
 * <p>Bootstraps a Spring application context that contains only JPA-related
 * infrastructure (DataSource, EntityManagerFactory, Spring Data JPA repositories)
 * and transaction management. All other auto-configurations are disabled.
 *
 * <p>Tests annotated with {@code @DataJpaTest} are wrapped in a transaction that
 * is rolled back after each test method by default (via {@link Transactional}).
 *
 * <p>Additional beans can be registered via {@code @Import} on the test class,
 * exactly as in the Spring Boot 3.x version of this annotation.
 *
 * <p><strong>Implementation note:</strong> This is a test-source-only shim for
 * Spring Boot 4.0.x, which moved the JPA test slice out of
 * {@code spring-boot-test-autoconfigure}. Uses {@code @OverrideAutoConfiguration(enabled=false)}
 * so only the JPA auto-configurations listed in {@code @ImportAutoConfiguration} are loaded.
 * The {@link DataJpaTypeExcludeFilter} narrows component scanning to JPA repositories only;
 * test classes explicitly {@code @Import} any additional service beans they need.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@BootstrapWith(DataJpaTestContextBootstrapper.class)
@ExtendWith(SpringExtension.class)
@OverrideAutoConfiguration(enabled = false)
@TypeExcludeFilters(DataJpaTypeExcludeFilter.class)
@Transactional
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class,
        PersistenceExceptionTranslationAutoConfiguration.class,
        TransactionAutoConfiguration.class
})
public @interface DataJpaTest {

    /**
     * Additional properties in key=value format that should be added to the
     * Spring {@link org.springframework.core.env.Environment} before the test runs.
     */
    String[] properties() default {};

    /**
     * Whether to use default component scanning filters. Set to {@code false} to
     * disable the type exclude filter that narrows scanning to JPA components.
     */
    boolean useDefaultFilters() default true;

    /**
     * A set of include filters which can be used to add otherwise filtered beans.
     */
    Filter[] includeFilters() default {};

    /**
     * A set of exclude filters which can be used to filter beans that would
     * otherwise be added to the application context.
     */
    Filter[] excludeFilters() default {};

    /**
     * Auto-configuration exclusions that should be applied for this test.
     */
    @AliasFor(annotation = ImportAutoConfiguration.class, attribute = "exclude")
    Class<?>[] excludeAutoConfiguration() default {};
}
