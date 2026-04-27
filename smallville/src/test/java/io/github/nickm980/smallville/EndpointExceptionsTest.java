package io.github.nickm980.smallville;

import static org.junit.Assert.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.github.nickm980.smallville.analytics.Analytics;
import io.github.nickm980.smallville.api.SmallvilleServer;
import io.github.nickm980.smallville.llm.ChatGPT;
import io.github.nickm980.smallville.llm.tools.ToolCallResponse;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;

public class EndpointExceptionsTest {
    private Javalin app;

    @BeforeEach
    public void setUp() {
	ChatGPT llm = Mockito.mock(ChatGPT.class);
	Mockito.when(llm.sendChat(Mockito.any(), Mockito.anyInt())).thenReturn("result");
	Mockito.when(llm.sendChatWithTools(Mockito.any(), Mockito.any(), Mockito.anyDouble()))
	    .thenReturn(ToolCallResponse.ofText(""));
	app = new SmallvilleServer(new Analytics(), llm, new World()).server();
    }

    @Test
    public void GET_endpoint_not_found() {
	JavalinTest.test(app, (server, client) -> {
	    assertEquals(client.get("/thisendpointdoesnotexist").code(), 404);
	});
    }
}
