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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.service.UserMessage;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code @UserMessage} to the method (never a parameter) on every AI service. On a parameter,
 * quarkus-langchain4j uses that parameter's raw value as the whole user message and skips template
 * rendering, dropping every other {@code @V} variable — the bug this guards against. A cheap, fast
 * structural check that complements the end-to-end {@link AiServicePromptRenderingTest}.
 */
class AiServiceUserMessagePlacementTest {

  @Test
  void replyAssistantPutsUserMessageOnTheMethod() {
    assertUserMessageOnMethodNotParameter(ReplyAssistant.class);
  }

  @Test
  void prReviewerPutsUserMessageOnTheMethod() {
    assertUserMessageOnMethodNotParameter(PrReviewer.class);
  }

  @Test
  void findingVerifierPutsUserMessageOnTheMethod() {
    assertUserMessageOnMethodNotParameter(FindingVerifier.class);
  }

  private static void assertUserMessageOnMethodNotParameter(Class<?> aiService) {
    for (Method method : aiService.getDeclaredMethods()) {
      assertTrue(
          method.isAnnotationPresent(UserMessage.class),
          aiService.getSimpleName()
              + "."
              + method.getName()
              + " must declare @UserMessage on the method so the template is rendered");
      for (Parameter parameter : method.getParameters()) {
        assertFalse(
            parameter.isAnnotationPresent(UserMessage.class),
            aiService.getSimpleName()
                + "."
                + method.getName()
                + " must not put @UserMessage on parameter '"
                + parameter.getName()
                + "' — that drops every other @V variable from the prompt");
      }
    }
  }
}
