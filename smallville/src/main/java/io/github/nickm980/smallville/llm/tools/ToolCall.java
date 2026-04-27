package io.github.nickm980.smallville.llm.tools;

/**
 * A single tool call emitted by the LLM in a sendChatWithTools response.
 * id may be null when using Ollama text-mode fallback.
 */
public record ToolCall(
    String id,
    String name,
    String argsJson
) {}
