package com.reconcileai.rero;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform Q3.2 / TDD section 8: every handler under {@code /internal/**} is a GET. A POST, PUT, PATCH or DELETE
 * mapping there (or a mapping with no method, which accepts all) fails the build.
 */
class InternalApiReadOnlyTest {

    @Test
    void everyInternalMappingIsGetOnly() throws Exception {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<String> internalHandlers = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        for (var candidate : scan.findCandidateComponents("com.reconcileai")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            RequestMapping onClass = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            List<String> prefixes = onClass == null || paths(onClass).isEmpty() ? List.of("") : paths(onClass);
            for (Method m : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                List<String> own = paths(mapping).isEmpty() ? List.of("") : paths(mapping);
                boolean internal = prefixes.stream().flatMap(p -> own.stream().map(o -> p + o))
                    .anyMatch(full -> full.startsWith("/internal"));
                if (!internal) {
                    continue;
                }
                String name = type.getSimpleName() + "." + m.getName();
                internalHandlers.add(name);
                boolean getOnly = Arrays.equals(mapping.method(), new RequestMethod[] {RequestMethod.GET})
                    && AnnotatedElementUtils.hasAnnotation(m, GetMapping.class);
                if (!getOnly) {
                    violations.add(name);
                }
            }
        }
        assertThat(internalHandlers).as("the four RERO tools are found").hasSize(4);
        assertThat(violations).as("non-GET handlers under /internal").isEmpty();
    }

    private static List<String> paths(RequestMapping m) {
        return Stream.concat(Arrays.stream(m.path()), Arrays.stream(m.value())).distinct().toList();
    }
}
