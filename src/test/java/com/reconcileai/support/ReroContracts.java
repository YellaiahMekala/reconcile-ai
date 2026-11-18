package com.reconcileai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contract C JSON Schemas (draft 2020-12), copied unchanged from {@code platform/contracts/rero/} into
 * {@code src/test/resources/contracts/rero/}. Formats ({@code uuid}, {@code date-time}) are asserted.
 */
public final class ReroContracts {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Schema> SCHEMAS = new ConcurrentHashMap<>();

    private ReroContracts() {}

    /** @param name schema file without extension, e.g. {@code run.v1} */
    public static java.util.List<Error> validate(String name, JsonNode body) {
        return SCHEMAS.computeIfAbsent(name, ReroContracts::load).validate(body);
    }

    public static java.util.List<Error> validate(String name, String body) {
        try {
            return validate(name, JSON.readTree(body));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String resource(String path) {
        try (InputStream in = ReroContracts.class.getResourceAsStream("/contracts/rero/" + path)) {
            if (in == null) {
                throw new IllegalStateException("missing contract resource " + path);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Schema load(String name) {
        try {
            SchemaRegistryConfig config = SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build();
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                    b -> b.schemaRegistryConfig(config))
                .getSchema(JSON.readTree(resource(name + ".json")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
