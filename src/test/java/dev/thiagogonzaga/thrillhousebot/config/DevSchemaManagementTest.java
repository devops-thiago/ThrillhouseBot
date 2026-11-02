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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Schema management per profile, bound straight from {@code
 * src/main/resources/application.properties}. The dev and prod profiles are never booted by the
 * test suite (tests run under {@code %test}, whose own properties file sets the schema settings),
 * so nothing else here notices when the shipped file changes them.
 *
 * <p>#565: dev mode once booted against an empty database because no strategy created its schema.
 * #22: Flyway now creates and migrates the schema at boot in every profile, and Hibernate only
 * validates the entities against it; production must never recreate or patch the schema itself.
 */
class DevSchemaManagementTest {

  private static final String STRATEGY = "quarkus.hibernate-orm.schema-management.strategy";
  private static final String MIGRATE_AT_START = "quarkus.flyway.migrate-at-start";
  private static final String CLEAN_AT_START = "quarkus.flyway.clean-at-start";

  @ParameterizedTest
  @ValueSource(strings = {"dev", "prod"})
  void flywayBuildsTheSchemaTheProfileStartsAgainst(String profile) throws Exception {
    var config = shipped(profile);
    assertTrue(config.getValue(MIGRATE_AT_START, Boolean.class));
    assertEquals("validate", config.getValue(STRATEGY, String.class));
  }

  @Test
  void prodNeverCleansItsDatabase() throws Exception {
    // Guards production data: only the test profile wipes its in-memory schema at boot.
    assertFalse(
        shipped("prod").getOptionalValue(CLEAN_AT_START, Boolean.class).orElse(false),
        "prod must never clean the schema at boot");
  }

  @Test
  void aDatabaseFromBeforeFlywayIsBaselinedAtTheV1Schema() throws Exception {
    var config = shipped("prod");
    assertTrue(config.getValue("quarkus.flyway.baseline-on-migrate", Boolean.class));
    assertEquals("1", config.getValue("quarkus.flyway.baseline-version", String.class));
  }

  private static SmallRyeConfig shipped(String profile) throws Exception {
    return new SmallRyeConfigBuilder()
        .addDefaultInterceptors() // profile selection and ${VAR:default} expansion, as at runtime
        .withValidateUnknown(false)
        .withProfile(profile)
        .withSources(
            new PropertiesConfigSource(
                Paths.get("src/main/resources/application.properties").toUri().toURL()))
        .build();
  }
}
