/*
 * Copyright 2026 Thiago Gonzaga
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.thiagogonzaga.thrillhousebot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.quarkiverse.langchain4j.openai.common.OpenAiRestApi;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof of #237, through the real AI REST client against a loopback provider.
 *
 * <p>{@link EmptyAiStreamFrameInterceptorTest} pins the reader chain around one dispatched event.
 * What only a real stream can show is the two things #237 asks for: that a payload-less event
 * arriving mid-stream is skipped rather than ending the stream, and that nothing escapes to the
 * Vert.x uncaught-exception handler while it is skipped. The second is the production signature the
 * report quotes ({@code VertxCoreRecorder: Uncaught exception received by Vert.x}), and it is
 * observable only from outside the {@code Multi}: {@code MultiInvoker} reads each event inside the
 * response handler, so a throw there is reported to the Vert.x context and never reaches the
 * subscriber, which is why the review kept completing while the ERROR was logged.
 *
 * <p>The provider is the real {@link OpenAiRestApi} built the way {@code QuarkusOpenAiClient}
 * builds it, so the interceptor is on the path through the same global-provider registration that
 * puts it on the production client, and the stream carries every filler shape the interceptor
 * names: a keep-alive comment, a bare {@code data:} and a stray blank line, each flushed on its own
 * the way a provider sends them during a reasoning pause. quarkus-langchain4j 1.13.1 still hands
 * each of those to Jackson unchanged from 1.11.2, so the in-app interceptor is what this test
 * relies on.
 */
@QuarkusTest
class EmptyAiStreamFrameEndToEndTest {

  private static final String FIRST_CHUNK =
      "data: {\"id\":\"chatcmpl-237\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"}}]}";

  private static final String SECOND_CHUNK =
      "data: {\"id\":\"chatcmpl-237\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" world\"}}]}";

  /** Each entry is one SSE block; the blank line that ends it is appended by the stub. */
  private static final List<String> STREAM =
      List.of(FIRST_CHUNK, ": ping", "data:", "", SECOND_CHUNK, "data: [DONE]");

  @Inject Vertx vertx;

  private HttpServer server;
  private Handler<Throwable> quarkusHandler;
  private final List<Throwable> uncaught = new CopyOnWriteArrayList<>();

  @BeforeEach
  void captureUncaughtExceptions() {
    quarkusHandler = vertx.exceptionHandler();
    vertx.exceptionHandler(uncaught::add);
  }

  @AfterEach
  void restore() {
    vertx.exceptionHandler(quarkusHandler);
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  @Test
  void aPayloadLessEventMidStreamIsSkippedWithoutReachingTheVertxUncaughtHandler()
      throws IOException {
    var client = startProvider();

    var chunks =
        client
            .streamingChatCompletion(
                ChatCompletionRequest.builder().model("stub").addUserMessage("review").build(),
                OpenAiRestApi.ApiMetadata.builder().openAiApiKey("dummy").build())
            .collect()
            .asList()
            .await()
            .atMost(Duration.ofSeconds(30));

    assertEquals(List.of("Hello", " world"), chunks.stream().map(this::content).toList());
    assertEquals(List.of(), uncaught, () -> "reached the Vert.x uncaught handler: " + uncaught);
  }

  private String content(ChatCompletionResponse chunk) {
    return chunk.choices().get(0).delta().content();
  }

  private OpenAiRestApi startProvider() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/chat/completions", this::streamEvents);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    return QuarkusRestClientBuilder.newBuilder()
        .baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
        .build(OpenAiRestApi.class);
  }

  private void streamEvents(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    try (OutputStream out = exchange.getResponseBody()) {
      for (String block : STREAM) {
        out.write((block + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
      }
    }
  }
}
