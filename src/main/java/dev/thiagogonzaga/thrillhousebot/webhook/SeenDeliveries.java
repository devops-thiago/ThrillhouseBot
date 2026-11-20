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
package dev.thiagogonzaga.thrillhousebot.webhook;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.sql.SQLException;
import java.time.Instant;

/**
 * The stored webhook delivery ids (#1374), shared by every instance on the database. The first
 * instance to insert an id handles its delivery; the others drop it as a redelivery.
 */
@ApplicationScoped
public class SeenDeliveries implements PanacheRepositoryBase<SeenDelivery, String> {

  /** The id column's width. A longer id is deduplicated in memory only. */
  static final int ID_LENGTH = 255;

  /** The SQL state of a unique-key violation, in PostgreSQL and H2 alike. */
  private static final String UNIQUE_VIOLATION = "23505";

  /** How deep {@link #isUniqueViolation} follows a cause chain. */
  private static final int MAX_CAUSE_DEPTH = 16;

  /**
   * Records {@code deliveryId} as seen until {@code expiresAt}, unless an unexpired row already
   * holds it. An expired row is replaced. The insert skips a conflicting row instead of failing, so
   * of two instances inserting the same id at once exactly one gets the row.
   *
   * @return {@code true} when this call recorded the id (the delivery is new); {@code false} when
   *     an unexpired row already held it (a redelivery)
   */
  @Transactional
  public boolean claim(String deliveryId, Instant now, Instant expiresAt) {
    delete("deliveryId = ?1 and expiresAt <= ?2", deliveryId, now);
    var inserted =
        getEntityManager()
            .createNativeQuery(
                "INSERT INTO webhook_delivery (deliveryid, seenat, expiresat)"
                    + " VALUES (?1, ?2, ?3) ON CONFLICT DO NOTHING")
            .setParameter(1, deliveryId)
            .setParameter(2, now)
            .setParameter(3, expiresAt)
            .executeUpdate();
    return inserted == 1;
  }

  /** Deletes the id's row, so a redelivery of it is handled. */
  @Transactional
  public void release(String deliveryId) {
    deleteById(deliveryId);
  }

  /**
   * Deletes up to {@code limit} rows expired at {@code now}, oldest first.
   *
   * @return how many rows it deleted
   */
  @Transactional
  public int purgeExpired(Instant now, int limit) {
    var ids =
        getEntityManager()
            .createQuery(
                "select d.deliveryId from SeenDelivery d where d.expiresAt <= ?1"
                    + " order by d.expiresAt",
                String.class)
            .setParameter(1, now)
            .setMaxResults(limit)
            .getResultList();
    if (ids.isEmpty()) {
      return 0;
    }
    return (int) delete("deliveryId in ?1", ids);
  }

  /**
   * Whether {@code failure} was caused by a unique-key violation: another instance inserted the
   * same id between this one's checks, so the delivery is a redelivery, not a database failure.
   */
  static boolean isUniqueViolation(Throwable failure) {
    var cause = failure;
    // Bounded, so a cause chain that loops back on itself cannot hang the webhook thread.
    for (var depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }
}
