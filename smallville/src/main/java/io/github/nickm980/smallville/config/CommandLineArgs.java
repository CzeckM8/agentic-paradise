package io.github.nickm980.smallville.config;

import com.beust.jcommander.Parameter;

public class CommandLineArgs {

    @Parameter(names = "--port", description = "Port to run server on", required = false)
    private int port = 8080;

    @Parameter(names = "--api-key", description = "Open AI private key for chat completions (optional for local Ollama dev)", required = false)
    private String apiKey = "dev-fake-key";

    @Parameter(names = "--python-server-port", description = "Python server port", required = false)
    private String pythonServerPort;

    public String getPythonServerPort() {
	return pythonServerPort;
    }

    public int getPort() {
	return port;
    }

    public String getApiKey() {
	return apiKey;
    }
}
