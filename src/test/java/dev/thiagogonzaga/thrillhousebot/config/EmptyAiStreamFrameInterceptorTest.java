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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.quarkiverse.langchain4j.openai.common.OpenAiRestApi;
import io.quarkus.arc.Arc;
import io.quarkus.rest.client.reactive.runtime.AnnotationRegisteredProviders;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import java.io.IOException;
import org.jboss.resteasy.reactive.client.impl.ClientSerialisers;
import org.jboss.resteasy.reactive.client.impl.InboundSseEventImpl;
import org.jboss.resteasy.reactive.common.jaxrs.ConfigurationImpl;
import org.jboss.resteasy.reactive.common.util.CaseInsensitiveMap;
import org.junit.jupiter.api.Test;

/**
 * Drives the real read path of #552: an {@link InboundSseEventImpl} carrying an empty {@code data:}
 * payload, read through the same reader-interceptor chain the AI client uses ({@code
 * OpenAiRestApi.OpenAiRestApiReaderInterceptor} over {@code
 * OpenAiRestApi.OpenAiRestApiJacksonReader}). Without {@link EmptyAiStreamFrameInterceptor} in that
 * chain the read fails with {@code MismatchedInputException: No content to map due to end-of-input}
 * — and in production that throw happens on a Vert.x event loop, where it can only surface as an
 * uncaught ERROR.
 */
@QuarkusTest
class EmptyAiStreamFrameInterceptorTest {

  /** A frame body that maps cleanly; {@code id} is what the AI client's own validation requires. */
  private static final String REAL_FRAME = "{\"id\":\"chatcmpl-552\"}";

  /** Sentinel returned by a mocked {@code proceed()}, so "passed through" is observable. */
  private static final Object PROCEEDED = new Object();

  @Test
  void anEmptyFrameIsSkippedInsteadOfFailingTheRead() {
    assertNull(readFrame("", true));
  }

  @Test
  void aWhitespaceOnlyFrameIsSkippedToo() {
    assertNull(readFrame("   ", true));
  }

  @Test
  void withoutTheInterceptorTheSameFrameFailsToMap() {
    var failure = assertThrows(RuntimeException.class, () -> readFrame("", false));

    var cause = rootCause(failure);
    assertInstanceOf(MismatchedInputException.class, cause, failure.toString());
    assertTrue(cause.getMessage().contains("No content to map"), cause.getMessage());
  }

  @Test
  void aFrameCarryingContentIsStillDeserialized() {
    var response = readFrame(REAL_FRAME, true);

    assertNotNull(response);
    assertEquals("chatcmpl-552", response.id());
  }

  @Test
  void aMalformedNonBlankFrameStillFailsLoudly() {
    assertThrows(RuntimeException.class, () -> readFrame("{", true));
  }

  @Test
  void aReadForAnotherClientsTypeIsLeftAlone() throws IOException {
    var context = contextFor(String.class, new CaseInsensitiveMap<>());

    assertSame(PROCEEDED, new EmptyAiStreamFrameInterceptor().aroundReadFrom(context));
    verify(context, never()).getInputStream();
  }

  @Test
  void aReadWithoutAKnownTypeIsLeftAlone() throws IOException {
    var context = contextFor(null, new CaseInsensitiveMap<>());

    assertSame(PROCEEDED, new EmptyAiStreamFrameInterceptor().aroundReadFrom(context));
    verify(context, never()).getInputStream();
  }

  @Test
  void aWholeResponseReadWithHeadersIsLeftAlone() throws IOException {
    var headers = new CaseInsensitiveMap<String>();
    headers.putSingle("content-type", MediaType.APPLICATION_JSON);
    var context = contextFor(ChatCompletionResponse.class, headers);

    assertSame(PROCEEDED, new EmptyAiStreamFrameInterceptor().aroundReadFrom(context));
    verify(context, never()).getInputStream();
  }

  @Test
  void theInterceptorIsRegisteredOnTheAiRestClient() {
    var providers =
        Arc.container()
            .instance(AnnotationRegisteredProviders.class)
            .get()
            .getProviders(OpenAiRestApi.class);

    assertEquals(
        EmptyAiStreamFrameInterceptor.PRIORITY,
        providers.get(EmptyAiStreamFrameInterceptor.class),
        () -> "not registered on the AI client: " + providers.keySet());
  }

  private static ReaderInterceptorContext contextFor(
      Class<?> type, CaseInsensitiveMap<String> headers) throws IOException {
    var context = mock(ReaderInterceptorContext.class);
    doReturn(type).when(context).getType();
    when(context.getHeaders()).thenReturn(headers);
    when(context.proceed()).thenReturn(PROCEEDED);
    return context;
  }

  private static ChatCompletionResponse readFrame(String data, boolean withInterceptor) {
    return sseFrame(data, withInterceptor).readData(ChatCompletionResponse.class);
  }

  /**
   * The AI client's reader chain as {@code QuarkusOpenAiClient} builds it, reproduced around a
   * single dispatched server-sent event.
   */
  private static InboundSseEventImpl sseFrame(String data, boolean withInterceptor) {
    var configuration = new ConfigurationImpl(RuntimeType.CLIENT);
    configuration.register(new OpenAiRestApi.OpenAiRestApiJacksonReader());
    configuration.register(new OpenAiRestApi.OpenAiRestApiReaderInterceptor());
    if (withInterceptor) {
      configuration.register(new EmptyAiStreamFrameInterceptor());
    }
    var event = new InboundSseEventImpl(configuration, new ClientSerialisers());
    event.setData(data);
    event.setMediaType(MediaType.APPLICATION_JSON_TYPE);
    return event;
  }

  private static Throwable rootCause(Throwable throwable) {
    var cause = throwable;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }
}
