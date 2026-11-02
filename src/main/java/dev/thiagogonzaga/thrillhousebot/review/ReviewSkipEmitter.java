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
import java.util.Collection;
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
 * counter (attributes: {@code reason}, {@code repository}), and bumps an in-memory count per
 * installation and reason exposed on the dashboard summary endpoint. The dashboard shows a login
 * only the counts of installations whose installation-wide views it may see (#1221). Counts are per
 * replica and reset on restart.
 */
@ApplicationScoped
public class ReviewSkipEmitter {

  private static final Logger log = LoggerFactory.getLogger(ReviewSkipEmitter.class);

  private static final AttributeKey<String> ATTR_REASON = stringKey("reason");
  private static final AttributeKey<String> ATTR_REPOSITORY = stringKey("repository");

  private final LongCounter skipCounter;

  /**
   * Counts by installation id, then reason. A skip recorded before the installation is known (a
   * redelivered webhook) is kept under {@link #UNKNOWN_INSTALLATION}, which no login is shown.
   */
  private final ConcurrentHashMap<Long, ConcurrentHashMap<ReviewSkipReason, LongAdder>> counts =
      new ConcurrentHashMap<>();

  /** Installation key of a skip whose payload carried no installation. */
  public static final long UNKNOWN_INSTALLATION = 0L;

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
   * @param installationId installation the event came from, or {@link #UNKNOWN_INSTALLATION}
   * @param owner repository owner login
   * @param repo repository name
   * @param prNumber pull request number
   * @param detail free-text context for the log line (never used as a metric attribute)
   */
  public void recordSkip(
      ReviewSkipReason reason,
      long installationId,
      String owner,
      String repo,
      int prNumber,
      String detail) {
    log.info(
        "Automatic review skipped [reason={}] for {}/{} #{}: {}",
        reason,
        owner,
        repo,
        prNumber,
        detail);
    skipCounter.add(
        1, Attributes.of(ATTR_REASON, reason.name(), ATTR_REPOSITORY, owner + "/" + repo));
    counts
        .computeIfAbsent(installationId, ignored -> new ConcurrentHashMap<>())
        .computeIfAbsent(reason, ignored -> new LongAdder())
        .increment();
  }

  /**
   * Skip counts by reason code since this replica started, summed over {@code installationIds}, in
   * enum declaration order; a reason none of them recorded is absent.
   */
  public Map<String, Long> countsByReason(Collection<Long> installationIds) {
    var snapshot = new EnumMap<ReviewSkipReason, Long>(ReviewSkipReason.class);
    for (var installationId : installationIds) {
      var byReason = counts.get(installationId);
      if (byReason != null) {
        byReason.forEach((reason, count) -> snapshot.merge(reason, count.sum(), Long::sum));
      }
    }
    var result = new LinkedHashMap<String, Long>();
    snapshot.forEach((reason, count) -> result.put(reason.name(), count));
    return result;
  }
}
