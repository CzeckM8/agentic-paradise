package io.github.nickm980.smallville.llm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import okhttp3.Protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.nickm980.smallville.Settings;
import io.github.nickm980.smallville.config.SmallvilleConfig;
import io.github.nickm980.smallville.events.EventBus;
import io.github.nickm980.smallville.events.llm.PromptReceievedEvent;
import io.github.nickm980.smallville.exceptions.SmallvilleException;
import io.github.nickm980.smallville.llm.tools.ToolCall;
import io.github.nickm980.smallville.llm.tools.ToolCallResponse;
import io.github.nickm980.smallville.llm.tools.ToolDefinition;
import io.github.nickm980.smallville.prompts.PromptRequest;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ChatGPT implements LLM {
    private final static Logger LOG = LoggerFactory.getLogger(ChatGPT.class);
    private final static ObjectMapper MAPPER = new ObjectMapper();
    private final EventBus events = EventBus.getEventBus();

    /** Single shared HTTP client — reuses connections across all LLM calls (HTTP/2 multiplexed). */
    private static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .build();

	private static final String OLLAMA_URL = "http://localhost:11434/v1/chat/completions";
	private static final String OLLAMA_EMBEDDINGS_URL = "http://localhost:11434/v1/embeddings";
	private static final String OLLAMA_AUTH = "Bearer ollama";
	private static final String OLLAMA_MODEL = "llama3.1:8b-instruct-q4_K_M";
	private static final String GOOGLE_OPENAI_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
	private static final String GOOGLE_MODEL = "gemini-2.0-flash";

    @Override
    public String getModelName() {
        return getChatModel();
    }

    @Override
    public String sendChat(PromptRequest prompt, double temperature) {
	int maxRetries = SmallvilleConfig.getConfig().getMaxRetries();
	int retryCount = 0;
	String result = null;

	ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	Semaphore semaphore = new Semaphore(0);

	while (retryCount < maxRetries) {
	    try {
		result = attemptRequest(prompt, temperature);
		break;
	    } catch (IOException | SmallvilleException e) {
		retryCount++;
		LOG.error("Request failed. Retrying... (Attempt " + retryCount + ")");

		executor.schedule(() -> semaphore.release(), 2, TimeUnit.SECONDS);

		try {
		    semaphore.acquire();
		} catch (InterruptedException ex) {
		    Thread.currentThread().interrupt();
		}
	    }
	}

	executor.shutdownNow();

	if (result == null) {
	    LOG.error("Failed to get a successful response after " + maxRetries + " attempts.");
	    throw new SmallvilleException("Failed to get a successful response.");
	}

	return result;
    }

    @Override
    public ToolCallResponse sendChatWithTools(List<Map<String, Object>> messages,
                                               List<ToolDefinition> tools,
                                               double temperature) {
        int maxRetries = SmallvilleConfig.getConfig().getMaxRetries();
        int retryCount = 0;
        ToolCallResponse result = null;

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        Semaphore semaphore = new Semaphore(0);

        while (retryCount < maxRetries) {
            try {
                result = attemptRequestWithTools(messages, tools, temperature);
                break;
            } catch (IOException | SmallvilleException e) {
                retryCount++;
                LOG.error("[ToolCall] Request failed. Retrying... (Attempt {})", retryCount);
                executor.schedule(() -> semaphore.release(), 2, TimeUnit.SECONDS);
                try {
                    semaphore.acquire();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        executor.shutdownNow();

        if (result == null) {
            LOG.error("[ToolCall] Failed after {} attempts. Returning empty text response.", maxRetries);
            return ToolCallResponse.ofText("");
        }

        return result;
    }

    private ToolCallResponse attemptRequestWithTools(List<Map<String, Object>> messages,
                                                      List<ToolDefinition> tools,
                                                      double temperature) throws IOException {
        long start = System.currentTimeMillis();

        OkHttpClient client = SHARED_HTTP_CLIENT;

        // Build the tools JSON array in OpenAI format
        ArrayNode toolsArray = MAPPER.createArrayNode();
        for (ToolDefinition tool : tools) {
            ObjectNode funcDef = MAPPER.createObjectNode();
            funcDef.put("name", tool.name());
            funcDef.put("description", tool.description());
            if (tool.parametersSchema() != null) {
                funcDef.set("parameters", MAPPER.valueToTree(tool.parametersSchema()));
            }
            ObjectNode toolNode = MAPPER.createObjectNode();
            toolNode.put("type", "function");
            toolNode.set("function", funcDef);
            toolsArray.add(toolNode);
        }

        // For Ollama, append text-mode fallback instructions to the last system message
        List<Map<String, Object>> enrichedMessages = messages;
        if (!isGoogleProvider()) {
            enrichedMessages = appendTextModeFallbackInstructions(messages, tools);
        }

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", getChatModel());
        body.set("messages", MAPPER.valueToTree(enrichedMessages));
        body.put("temperature", temperature);
        body.put("max_tokens", 2000);
        body.set("tools", toolsArray);
        body.put("tool_choice", "auto");

        String json = MAPPER.writeValueAsString(body);
        LOG.debug("[ToolCall Request] {}", json.length() > 500 ? json.substring(0, 500) + "..." : json);

        String apiUrl = getChatCompletionsUrl();
        String authHeader = getAuthorizationHeader();

        RequestBody requestBody = RequestBody.create(json.getBytes(), okhttp3.MediaType.parse("application/json"));
        Request request = new Request.Builder()
            .url(apiUrl)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", authHeader)
            .post(requestBody)
            .build();

        Response response = client.newCall(request).execute();
        String responseBody = response.body().string();
        LOG.info("[ToolCall] Raw response: {}", responseBody.length() > 600 ? responseBody.substring(0, 600) + "..." : responseBody);

        if (!response.isSuccessful()) {
            throw new SmallvilleException("[ToolCall] LLM request failed with status " + response.code() + ": " + responseBody);
        }

        JsonNode node = MAPPER.readTree(responseBody);
        if (node.get("choices") == null) {
            throw new SmallvilleException("[ToolCall] Invalid response — no 'choices' field");
        }

        JsonNode message = node.get("choices").get(0).get("message");

        // --- Native tool call detection ---
        JsonNode toolCallsNode = message.get("tool_calls");
        if (toolCallsNode != null && toolCallsNode.isArray() && toolCallsNode.size() > 0) {
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode tc : toolCallsNode) {
                String id = tc.path("id").asText(null);
                String name = tc.path("function").path("name").asText("");
                String argsJson = tc.path("function").path("arguments").asText("{}");
                calls.add(new ToolCall(id, name, argsJson));
                LOG.info("[ToolCall] Native call: {}({})", name, argsJson.length() > 100 ? argsJson.substring(0, 100) + "..." : argsJson);
            }
            long elapsed = System.currentTimeMillis() - start;
            LOG.debug("[ToolCall] Response took {}ms — {} native tool calls", elapsed, calls.size());
            return ToolCallResponse.ofTools(calls);
        }

        // --- Text-mode fallback: parse JSON lines from content ---
        String content = message.path("content").asText("");
        List<ToolCall> textModeCalls = parseTextModeToolCalls(content);
        if (!textModeCalls.isEmpty()) {
            LOG.info("[ToolCall] Text-mode fallback parsed {} calls", textModeCalls.size());
            return ToolCallResponse.ofTools(textModeCalls);
        }

        // --- Pure prose response ---
        long elapsed = System.currentTimeMillis() - start;
        LOG.debug("[ToolCall] Prose response in {}ms", elapsed);
        return ToolCallResponse.ofText(content);
    }

    /**
     * For Ollama: append JSON-output instructions to the system message so the model
     * knows how to emit tool calls as structured text if native tool_calls are not supported.
     */
    private List<Map<String, Object>> appendTextModeFallbackInstructions(
            List<Map<String, Object>> messages, List<ToolDefinition> tools) {

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n--- TOOL USE INSTRUCTIONS ---\n");
        sb.append("If you want to call a tool, output a JSON object on its own line with NO surrounding text:\n");
        sb.append("{\"tool\":\"<name>\",\"args\":{...}}\n\n");
        sb.append("Available tools:\n");
        for (ToolDefinition t : tools) {
            sb.append("- ").append(t.name()).append(": ").append(t.description()).append("\n");
        }
        sb.append("Call commit_action exactly once to end your turn.");

        String instructions = sb.toString();

        List<Map<String, Object>> result = new ArrayList<>(messages.size());
        boolean injected = false;
        for (Map<String, Object> msg : messages) {
            if (!injected && "system".equals(msg.get("role"))) {
                Map<String, Object> patched = new HashMap<>(msg);
                patched.put("content", msg.get("content") + instructions);
                result.add(patched);
                injected = true;
            } else {
                result.add(msg);
            }
        }
        if (!injected) {
            // No system message found — prepend one
            Map<String, Object> sys = new HashMap<>();
            sys.put("role", "system");
            sys.put("content", instructions.trim());
            result.add(0, sys);
        }
        return result;
    }

    /**
     * Scans LLM text output line-by-line for JSON tool-call objects.
     * Matches lines of the form: {"tool":"name","args":{...}}
     */
    private List<ToolCall> parseTextModeToolCalls(String text) {
        List<ToolCall> calls = new ArrayList<>();
        if (text == null || text.isBlank()) return calls;

        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) continue;
            try {
                JsonNode node = MAPPER.readTree(trimmed);
                if (!node.has("tool")) continue;
                String name = node.get("tool").asText("");
                String argsJson = node.has("args") ? MAPPER.writeValueAsString(node.get("args")) : "{}";
                calls.add(new ToolCall(null, name, argsJson));
            } catch (Exception ignored) {
                // Not a valid JSON line — skip
            }
        }
        return calls;
    }

    @Override
    public float[] getTokenEmbeddings(String text) {
	if (isGoogleProvider()) {
	    LOG.warn("Embeddings are not configured for google_ai provider; returning empty embedding vector.");
	    return new float[0];
	}

	OkHttpClient client = SHARED_HTTP_CLIENT;
	ObjectMapper mapper = new ObjectMapper();
	float[] result = new float[0];

	try {
	    // Create the request body
	    JsonNode requestBody = mapper.createObjectNode().put("model", "nomic-embed-text").put("input", text);

	    Request request = new Request.Builder()
		.url(OLLAMA_EMBEDDINGS_URL)
		.post(RequestBody.create(mapper.writeValueAsString(requestBody), okhttp3.MediaType.parse("application/json")))
		.addHeader("Authorization", OLLAMA_AUTH)
		.build();

	    Response response = client.newCall(request).execute();
	    String responseBody = response.body().string();
	    JsonNode responseJson = mapper.readTree(responseBody);

	    result = mapper.convertValue(responseJson.get("data").get(0).get("embedding"), float[].class);
	} catch (IOException e) {
	    e.printStackTrace();
	}

	return result;
    }

    private String attemptRequest(PromptRequest prompt, double temperature) throws IOException, SmallvilleException {
	long start = System.currentTimeMillis();

	OkHttpClient client = SHARED_HTTP_CLIENT;

	String json = """
		{
			"model": "%model",
			"messages": [%messages],
			"temperature": %temperature, "max_tokens": 2000

		""";

	if (prompt.isFunctional()) {
	    json += """
	    	,
	    	"functions": %functions,
	    	"function_call": {"name": "%function_name"}
	    	""";
	}

	json += "}";
	json = json.replaceAll("\t", "");
	json = json.strip();
	if (prompt.isFunctional()) {
	    json = json.replace("%function_name", prompt.getFunction());
	}

	json = json.replace("%messages", MAPPER.writeValueAsString(prompt.build()));
	json = json.replace("%temperature", String.valueOf(temperature));
	json = json.replace("%model", getChatModel());

	LOG.debug("[Chat Request Original]" + json);
	LOG.debug("[Chat Request]" + prompt.getContent());

	String apiUrl = getChatCompletionsUrl();
	String authHeader = getAuthorizationHeader();

	RequestBody body = RequestBody.create(json.getBytes(), okhttp3.MediaType.parse("application/json"));
	Request request = new Request.Builder()
	    .url(apiUrl)
	    .addHeader("Content-Type", "application/json")
	    .addHeader("Authorization", authHeader)
	    .post(body)
	    .build();

	String result = "";

	Response response = client.newCall(request).execute();
	String responseBody = response.body().string();
	LOG.info("Raw LLM Response: " + responseBody);
	if (!response.isSuccessful()) {
	    throw new SmallvilleException("LLM request failed with status " + response.code() + ": " + responseBody);
	}

	ObjectMapper objectMapper = new ObjectMapper();
	JsonNode node = objectMapper.readTree(responseBody);
	if (node.get("choices") == null) {
		LOG.error("No 'choices' in response: " + node.toString());
		throw new SmallvilleException("Invalid response structure.");
	}

	result = node.get("choices").get(0).get("message").get("content").asText();

	try {
	    Object res = node.get("choices").get(0).get("message").get("function_call").get("arguments");
	    LOG.info(res.toString());
	} catch (Exception e) {

	}

	LOG.debug("[Chat Response]" + node.get("choices").toPrettyString());

	long end = System.currentTimeMillis();
	LOG.debug("[Chat] Response took " + String.valueOf(start - end) + "ms");
	PromptReceievedEvent promptReceievedEvent = new PromptReceievedEvent(prompt.getContent(), result, start-end);
	events.postEvent(promptReceievedEvent);

	return promptReceievedEvent.getResult();
    }

    private boolean isGoogleProvider() {
	String provider = System.getProperty("llm.provider", "");
	return "google_ai".equalsIgnoreCase(provider);
    }

    private String getChatCompletionsUrl() {
	return isGoogleProvider() ? GOOGLE_OPENAI_URL : OLLAMA_URL;
    }

    private String getChatModel() {
	String overrideModel = System.getProperty("llm.model");
	if (overrideModel != null && !overrideModel.isBlank()) {
	    return overrideModel;
	}
	return isGoogleProvider() ? GOOGLE_MODEL : OLLAMA_MODEL;
    }

    private String getAuthorizationHeader() {
	if (isGoogleProvider()) {
	    String key = System.getProperty("googleai.api.key", "");
	    if (key.isBlank()) {
		throw new SmallvilleException("google_ai provider requires -Dgoogleai.api.key");
	    }
	    return "Bearer " + key;
	}
	return OLLAMA_AUTH;
    }
}
