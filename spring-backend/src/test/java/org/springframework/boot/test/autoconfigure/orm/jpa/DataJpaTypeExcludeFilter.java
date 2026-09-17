package org.springframework.boot.test.autoconfigure.orm.jpa;

import org.springframework.boot.test.context.filter.annotation.StandardAnnotationCustomizableTypeExcludeFilter;

/**
 * {@link StandardAnnotationCustomizableTypeExcludeFilter} for {@link DataJpaTest}.
 *
 * <p>Compatibility shim for Spring Boot 4.x. Filters the application context
 * for a JPA slice test, keeping only JPA-related and explicitly imported beans.
 *
 * <p>Overrides {@link #getAnnotationType()} to return {@code DataJpaTest.class}
 * directly, bypassing the {@code ResolvableType} generic-resolution path that
 * can fail when the annotation class lives in the test source tree rather than
 * a proper classpath artifact (preventing {@code Assert.java:254} failures).
 *
 * <p>Lives in the test source tree only — no production classpath impact.
 */
class DataJpaTypeExcludeFilter
        extends StandardAnnotationCustomizableTypeExcludeFilter<DataJpaTest> {

    DataJpaTypeExcludeFilter(Class<?> testClass) {
        super(testClass);
    }

    @Override
    protected Class<DataJpaTest> getAnnotationType() {
        return DataJpaTest.class;
    }
}
