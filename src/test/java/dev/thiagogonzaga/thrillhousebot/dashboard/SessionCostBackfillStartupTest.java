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
package dev.thiagogonzaga.thrillhousebot.dashboard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig.AiPricingConfig;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig.AiPricingConfig.ModelPricing;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.runtime.StartupEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionCostBackfillStartupTest {

  @Test
  void shouldReturnZeroWhenPricingTableIsEmpty() {
    ReviewSessionRepository repository = mock(ReviewSessionRepository.class);
    ThrillhouseConfig config = mock(ThrillhouseConfig.class);
    AiPricingConfig aiConfig = mock(AiPricingConfig.class);
    when(config.ai()).thenReturn(aiConfig);
    when(aiConfig.pricing()).thenReturn(Map.of());

    var backfill = new SessionCostBackfill(repository, config);

    assertEquals(0, backfill.backfill());
    verify(repository, never()).find(anyString());
  }

  @Test
  void shouldSkipSessionsWhoseComputedCostIsZero() {
    var session = new ReviewSession();
    session.setModel("free-model");
    session.setInputTokens(100);
    session.setOutputTokens(50);
    session.setCost(0.0);

    ReviewSessionRepository repository = mock(ReviewSessionRepository.class);
    @SuppressWarnings("unchecked")
    var query = mock(PanacheQuery.class);
    when(repository.find(anyString())).thenReturn(query);
    when(query.list()).thenReturn(List.of(session));

    ThrillhouseConfig config = mock(ThrillhouseConfig.class);
    AiPricingConfig aiConfig = mock(AiPricingConfig.class);
    ModelPricing pricing = mock(ModelPricing.class);
    when(pricing.inputPer1k()).thenReturn(0.0);
    when(pricing.outputPer1k()).thenReturn(0.0);
    when(config.ai()).thenReturn(aiConfig);
    when(aiConfig.pricing()).thenReturn(Map.of("free-model", pricing));

    var backfill = new SessionCostBackfill(repository, config);

    assertEquals(0, backfill.backfill());
    assertEquals(0.0, session.getCost(), 1e-9);
  }

  @Test
  void onStartShouldSwallowBackfillFailures() {
    ReviewSessionRepository repository = mock(ReviewSessionRepository.class);
    @SuppressWarnings("unchecked")
    var query = mock(PanacheQuery.class);
    when(repository.find(anyString())).thenReturn(query);
    when(query.list()).thenThrow(new RuntimeException("db down"));

    ThrillhouseConfig config = mock(ThrillhouseConfig.class);
    AiPricingConfig aiConfig = mock(AiPricingConfig.class);
    ModelPricing pricing = mock(ModelPricing.class);
    when(config.ai()).thenReturn(aiConfig);
    when(aiConfig.pricing()).thenReturn(Map.of("deepseek-v4-pro", pricing));

    var backfill = new SessionCostBackfill(repository, config);

    assertDoesNotThrow(() -> backfill.onStart(mock(StartupEvent.class)));
  }
}
