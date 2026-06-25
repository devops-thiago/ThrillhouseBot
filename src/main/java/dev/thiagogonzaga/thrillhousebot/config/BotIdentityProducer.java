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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Exposes the bot's {@link BotIdentity} — resolved once from {@code GITHUB_BOT_LOGINS} — as a
 * single application-scoped bean, so the review-pipeline collaborators inject it instead of each
 * re-deriving it from {@link ThrillhouseConfig} (one config read, no per-class drift within the
 * review package). {@code TriggerDetector} (webhook) deliberately keeps its own {@link
 * BotIdentity#from} derivation so its no-arg and bot-logins constructors stay usable as test and
 * fallback wiring.
 */
@ApplicationScoped
public class BotIdentityProducer {

  // @Singleton (not @ApplicationScoped): BotIdentity is a record (final), so it can't be proxied by
  // a normal scope; a pseudo-scope still gives one shared instance for the immutable value.
  @Produces
  @Singleton
  public BotIdentity botIdentity(ThrillhouseConfig config) {
    return BotIdentity.from(config.github().botLogins());
  }
}
