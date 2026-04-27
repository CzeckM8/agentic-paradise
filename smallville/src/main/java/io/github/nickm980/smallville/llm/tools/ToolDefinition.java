package io.github.nickm980.smallville.llm.tools;

import java.util.Map;

/**
 * Describes a tool the LLM may call.
 * Serialised into the OpenAI-compatible "tools" array on every sendChatWithTools request.
 */
public record ToolDefinition(
    String name,
    String description,
    Map<String, Object> parametersSchema
) {}
