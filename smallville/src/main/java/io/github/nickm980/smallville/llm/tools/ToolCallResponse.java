package io.github.nickm980.smallville.llm.tools;

import java.util.List;

/**
 * The result of a sendChatWithTools call.
 *
 * If isToolMode is true, toolCalls is non-empty and textContent is null.
 * If isToolMode is false, the model responded in prose (textContent is non-null, toolCalls is empty).
 */
public record ToolCallResponse(
    String textContent,
    List<ToolCall> toolCalls,
    boolean isToolMode
) {
    public static ToolCallResponse ofTools(List<ToolCall> calls) {
        return new ToolCallResponse(null, calls, true);
    }

    public static ToolCallResponse ofText(String text) {
        return new ToolCallResponse(text, List.of(), false);
    }
}
