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

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One webhook delivery id seen within the dedup window (#1374). {@link SeenDeliveries} writes and
 * reads the rows; {@link WebhookDeduplicator} decides with them.
 *
 * <p>The row holds no repository or installation data, only the id GitHub generated and two times,
 * so it is not scoped by installation. It is deleted once it expires ({@link DeliveryPurge}), or at
 * once when the delivery's dispatch failed, so that a redelivery is handled.
 */
@Entity
@Table(
    name = "webhook_delivery",
    indexes = @Index(name = "idx_webhookdelivery_expires", columnList = "expiresAt"))
@RegisterForReflection
public class SeenDelivery extends PanacheEntityBase {

  /** The {@code X-GitHub-Delivery} header. */
  @Id
  @Column(length = SeenDeliveries.ID_LENGTH)
  String deliveryId;

  @Column(nullable = false)
  Instant seenAt;

  /** When the redelivery window ends; the row no longer counts from then on. */
  @Column(nullable = false)
  Instant expiresAt;
}
