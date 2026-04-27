package io.github.nickm980.smallville.llm;

import io.github.nickm980.smallville.llm.tools.ToolCallResponse;
import io.github.nickm980.smallville.llm.tools.ToolDefinition;
import io.github.nickm980.smallville.prompts.PromptRequest;

import java.util.List;
import java.util.Map;

public interface LLM {

    /**
     * Sends a prompt to the Chat Completions API
     *
     * @param prompt
     * @param temperature Creativity level. Higher values are more creative but less
     *                    coherent
     * @return chat response String
     */
    String sendChat(PromptRequest prompt, double temperature);

    /**
     * Sends a multi-turn conversation with a tool schema.
     * The LLM may respond with tool calls or prose.
     *
     * @param messages     Full conversation history (role/content maps, plus tool result entries)
     * @param tools        Tool definitions the LLM may call
     * @param temperature  Creativity level
     * @return ToolCallResponse — isToolMode true when the LLM issued tool calls
     */
    ToolCallResponse sendChatWithTools(List<Map<String, Object>> messages,
                                       List<ToolDefinition> tools,
                                       double temperature);

    /** Human-readable model identifier shown in the debug UI. */
    String getModelName();

    /**
     * Pricing is $0.0004 / thousand tokens for embeddings.
     *
     * @param input
     * @return
     */
    float[] getTokenEmbeddings(String input);
}