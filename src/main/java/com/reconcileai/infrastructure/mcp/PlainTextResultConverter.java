package com.reconcileai.infrastructure.mcp;

import org.springframework.ai.tool.execution.ToolCallResultConverter;

import java.lang.reflect.Type;

/** Returns tool text as is. Spring AI's default would JSON-encode a String (quotes and escaped newlines). */
public class PlainTextResultConverter implements ToolCallResultConverter {

    @Override
    public String convert(Object result, Type returnType) {
        return result == null ? "" : result.toString();
    }
}
