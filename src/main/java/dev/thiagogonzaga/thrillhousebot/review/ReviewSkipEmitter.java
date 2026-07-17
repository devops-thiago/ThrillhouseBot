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
package dev.thiagogonzaga.thrillhousebot.review;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single emit point for automatic-review skips. Each skip produces a structured log line with a
 * {@link ReviewSkipReason} code, increments the {@code thrillhouse.review.skips} OpenTelemetry
 * counter (attributes: {@code reason}, {@code repository}), and bumps an in-memory per-reason count
 * exposed on the dashboard summary endpoint. Counts are per replica and reset on restart.
 */
@ApplicationScoped
public class ReviewSkipEmitter {

  private static final Logger log = LoggerFactory.getLogger(ReviewSkipEmitter.class);

  private static final AttributeKey<String> ATTR_REASON = stringKey("reason");
  private static final AttributeKey<String> ATTR_REPOSITORY = stringKey("repository");

  private final LongCounter skipCounter;
  private final ConcurrentHashMap<ReviewSkipReason, LongAdder> countsByReason =
      new ConcurrentHashMap<>();

  @Inject
  public ReviewSkipEmitter(OpenTelemetry otel) {
    this.skipCounter =
        otel.getMeter("thrillhousebot")
            .counterBuilder("thrillhouse.review.skips")
            .setDescription("Automatic reviews skipped, by structured reason code")
            .build();
  }

  /**
   * Records one skipped automatic review.
   *
   * @param reason structured reason code
   * @param owner repository owner login
   * @param repo repository name
   * @param prNumber pull request number
   * @param detail free-text context for the log line (never used as a metric attribute)
   */
  public void recordSkip(
      ReviewSkipReason reason, String owner, String repo, int prNumber, String detail) {
    log.info(
        "Automatic review skipped [reason={}] for {}/{} #{}: {}",
        reason,
        owner,
        repo,
        prNumber,
        detail);
    skipCounter.add(
        1, Attributes.of(ATTR_REASON, reason.name(), ATTR_REPOSITORY, owner + "/" + repo));
    countsByReason.computeIfAbsent(reason, ignored -> new LongAdder()).increment();
  }

  /** Skip counts by reason code since this replica started, in enum declaration order. */
  public Map<String, Long> countsByReason() {
    var snapshot = new EnumMap<ReviewSkipReason, Long>(ReviewSkipReason.class);
    countsByReason.forEach((reason, count) -> snapshot.put(reason, count.sum()));
    var result = new LinkedHashMap<String, Long>();
    snapshot.forEach((reason, count) -> result.put(reason.name(), count));
    return result;
  }
}
