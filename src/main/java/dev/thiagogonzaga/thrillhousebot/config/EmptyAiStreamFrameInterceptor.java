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

import jakarta.annotation.Priority;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Drops a payload-less server-sent event from the AI provider's streaming response instead of
 * letting it reach the JSON mapper.
 *
 * <p>During long reasoning pauses (high/xhigh/max effort) providers emit filler frames — a
 * keep-alive comment ({@code : ping}), a bare {@code data:} with nothing after it, or a stray blank
 * line. RESTEasy Reactive's {@code SseParser} dispatches those as an {@code InboundSseEvent} whose
 * data is the empty string, and quarkus-langchain4j's {@code @SseEventFilter(DoneFilter)} only
 * drops the terminal {@code [DONE]} sentinel, so the empty frame goes on to {@code
 * InboundSseEventImpl.readData(ChatCompletionResponse.class)} and Jackson fails it with {@code
 * MismatchedInputException: No content to map due to end-of-input}. That throw happens inside the
 * SSE event handler on a Vert.x event-loop thread, outside every retry and truncation guard the
 * review pipeline has, so it surfaces only as {@code VertxCoreRecorder: Uncaught exception received
 * by Vert.x} at ERROR — the exact log line an operator scans for when a review really did fail
 * (#552). Nothing is actually lost: the frame carried no payload and the stream survives.
 *
 * <p>Neither throwing layer is ours: the interceptor lives in quarkus-langchain4j's {@code
 * OpenAiRestApi} and the parser in RESTEasy Reactive, and the SSE event filter that could have
 * dropped the frame is fixed by annotation on that third-party interface. What is reachable is the
 * reader-interceptor chain: {@code InboundSseEventImpl} builds it from the REST client's
 * configuration, and Quarkus auto-registers every {@code @Provider} as a global REST client
 * provider, so this interceptor is inserted into the AI client's chain. Its priority puts it ahead
 * of {@code OpenAiRestApi.OpenAiRestApiReaderInterceptor} ({@link Priorities#USER}), so it sees the
 * frame first. Returning {@code null} rather than calling {@code proceed()} skips the frame the way
 * {@code MultiInvoker} already expects — it emits an item only when the read returns non-null — so
 * the stream continues untouched.
 *
 * <p>The guard is deliberately narrow, because the point of #552 is to stop masking real failures,
 * not to add masking of our own. All three conditions must hold:
 *
 * <ul>
 *   <li>the target type is one of the OpenAI wire types ({@value #OPENAI_WIRE_TYPE_PACKAGE}), so
 *       reads for the GitHub REST clients and every other client are untouched;
 *   <li>the reader context carries no headers, which is only true of the per-event SSE read path
 *       ({@code InboundSseEventImpl} passes an empty map, while a whole-response read always passes
 *       the response headers) — an empty body on a non-streaming AI call therefore still fails
 *       loudly instead of turning into a silent null;
 *   <li>the frame body is blank. A frame with any JSON in it is handed on unread-from, and a
 *       malformed non-blank frame still raises the mapping error it should.
 * </ul>
 */
@Provider
@ConstrainedTo(RuntimeType.CLIENT)
@Priority(EmptyAiStreamFrameInterceptor.PRIORITY)
public class EmptyAiStreamFrameInterceptor implements ReaderInterceptor {

  /**
   * Ahead of {@code OpenAiRestApi.OpenAiRestApiReaderInterceptor}, which is registered without a
   * {@link Priority} and so sorts at {@link Priorities#USER}; the chain runs in ascending priority.
   */
  static final int PRIORITY = Priorities.USER - 100;

  /**
   * Package holding the OpenAI-compatible response types the AI client deserializes frames into.
   */
  static final String OPENAI_WIRE_TYPE_PACKAGE = "dev.langchain4j.model.openai.internal.";

  @Override
  public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
    if (!isAiStreamFrame(context)) {
      return context.proceed();
    }
    byte[] frame = context.getInputStream().readAllBytes();
    if (new String(frame, StandardCharsets.UTF_8).isBlank()) {
      return null;
    }
    context.setInputStream(new ByteArrayInputStream(frame));
    return context.proceed();
  }

  /** True only for a single server-sent event read into an AI response type. */
  private static boolean isAiStreamFrame(ReaderInterceptorContext context) {
    Class<?> type = context.getType();
    return type != null
        && type.getName().startsWith(OPENAI_WIRE_TYPE_PACKAGE)
        && context.getHeaders().isEmpty();
  }
}
