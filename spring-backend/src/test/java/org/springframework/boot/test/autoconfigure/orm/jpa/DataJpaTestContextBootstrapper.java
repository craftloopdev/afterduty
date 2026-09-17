package org.springframework.boot.test.autoconfigure.orm.jpa;

import org.springframework.boot.test.autoconfigure.TestSliceTestContextBootstrapper;

/**
 * {@link TestSliceTestContextBootstrapper} for {@link DataJpaTest}.
 *
 * <p>Compatibility shim for Spring Boot 4.x. Extends the generic
 * {@code TestSliceTestContextBootstrapper} so the Spring test context machinery
 * recognises the {@link DataJpaTest} annotation and applies its composed
 * meta-annotations ({@code @ImportAutoConfiguration}, {@code @OverrideAutoConfiguration},
 * etc.) when bootstrapping the application context for JPA slice tests.
 *
 * <p>Lives in the test source tree only — no production classpath impact.
 */
class DataJpaTestContextBootstrapper extends TestSliceTestContextBootstrapper<DataJpaTest> {
}
