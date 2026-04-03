package io.github.nickm980.smallville.llm;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.nickm980.smallville.Settings;
import io.github.nickm980.smallville.config.SmallvilleConfig;
import io.github.nickm980.smallville.events.EventBus;
import io.github.nickm980.smallville.events.llm.PromptReceievedEvent;
import io.github.nickm980.smallville.exceptions.SmallvilleException;
import io.github.nickm980.smallville.prompts.PromptRequest;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ChatGPT implements LLM {
    private final static Logger LOG = LoggerFactory.getLogger(ChatGPT.class);
    private final static ObjectMapper MAPPER = new ObjectMapper();
    private final EventBus events = EventBus.getEventBus();

	private static final String OLLAMA_URL = "http://localhost:11434/v1/chat/completions";
	private static final String OLLAMA_EMBEDDINGS_URL = "http://localhost:11434/v1/embeddings";
	private static final String OLLAMA_AUTH = "Bearer ollama";
	private static final String OLLAMA_MODEL = "llama3.1:8b-instruct-q4_K_M";
	private static final String GOOGLE_OPENAI_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
	private static final String GOOGLE_MODEL = "gemini-2.0-flash";
    
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
    public float[] getTokenEmbeddings(String text) {
	if (isGoogleProvider()) {
	    LOG.warn("Embeddings are not configured for google_ai provider; returning empty embedding vector.");
	    return new float[0];
	}

	OkHttpClient client = new OkHttpClient();
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

	OkHttpClient client = new OkHttpClient.Builder()
	    .connectTimeout(10, TimeUnit.SECONDS)
	    .writeTimeout(3, TimeUnit.MINUTES)
	    .readTimeout(5, TimeUnit.MINUTES)
	    .build();

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
//	    json = json
//		.replace("%functions", MAPPER.writeValueAsString(SmallvilleConfig.getFunctions().get("functions")));

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
//	Analytics.addPrompt(prompt.getContent());
//	Analytics.addPrompt(result);
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