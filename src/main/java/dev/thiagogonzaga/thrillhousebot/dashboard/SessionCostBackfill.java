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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig.AiPricingConfig.ModelPricing;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recomputes the persisted cost of historical sessions that were stored with cost 0 because their
 * model had no pricing entry when the review ran. The dashboard sums the stored {@code cost}
 * column, so adding pricing only affects future reviews — this backfills the existing rows from
 * their stored token counts and the current pricing table.
 *
 * <p>Runs once on startup. Only rows with recorded tokens, a zero cost, and a now-known model price
 * are touched, so it is idempotent and never clobbers a cost that was already computed.
 */
@ApplicationScoped
public class SessionCostBackfill {

  private static final Logger log = LoggerFactory.getLogger(SessionCostBackfill.class);

  private final ReviewSessionRepository repository;
  private final ThrillhouseConfig config;

  @Inject
  public SessionCostBackfill(ReviewSessionRepository repository, ThrillhouseConfig config) {
    this.repository = repository;
    this.config = config;
  }

  void onStart(@Observes StartupEvent event) {
    try {
      var updated = backfill();
      if (updated > 0) {
        log.info(
            "Backfilled cost for {} session(s) from stored tokens and the pricing table", updated);
      }
    } catch (RuntimeException e) {
      log.warn("Session cost backfill failed; historical dashboard costs may stay at 0", e);
    }
  }

  @Transactional
  public int backfill() {
    Map<String, ? extends ModelPricing> pricing = config.ai().pricing();
    if (pricing.isEmpty()) {
      return 0;
    }

    List<ReviewSession> candidates =
        repository.find("cost <= 0 and (inputTokens > 0 or outputTokens > 0)").list();
    var updated = 0;
    for (ReviewSession session : candidates) {
      var modelPricing = pricing.get(session.getModel());
      if (modelPricing == null) {
        if (!session.isPricingMissing()) {
          session.setPricingMissing(true);
          updated++;
        }
        continue;
      }
      var cost =
          ModelPricing.cost(
              modelPricing.inputPer1k(),
              modelPricing.outputPer1k(),
              session.getInputTokens(),
              session.getOutputTokens());
      if (cost > 0) {
        session.setCost(cost);
        // The model has a price now, so the missing-pricing flag no longer applies.
        session.setPricingMissing(false);
        updated++;
      }
    }
    return updated;
  }
}
