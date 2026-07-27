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
package dev.thiagogonzaga.thrillhousebot.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Counts finished PR reviews by outcome. Recorded once per review at its terminal transition (the
 * orchestrator's completed/failed paths), never per AI call — retried calls inside one review must
 * not inflate the count.
 */
@ApplicationScoped
public class ReviewOutcomeMetrics {

  private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");

  private final LongCounter reviews;

  @Inject
  public ReviewOutcomeMetrics(OpenTelemetry otel) {
    this.reviews =
        otel.getMeter("thrillhousebot")
            .counterBuilder("thrillhouse.reviews.total")
            .setDescription("Finished PR reviews by outcome")
            .build();
  }

  public void recordCompleted() {
    reviews.add(1, Attributes.of(OUTCOME, "completed"));
  }

  public void recordFailed() {
    reviews.add(1, Attributes.of(OUTCOME, "failed"));
  }
}
