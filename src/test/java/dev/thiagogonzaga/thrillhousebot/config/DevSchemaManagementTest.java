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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * Schema management per profile, bound straight from {@code
 * src/main/resources/application.properties} — the dev profile is never exercised by the test suite
 * (tests run under {@code %test}, whose own {@code src/test/resources/application.properties} sets
 * the strategy), so nothing else here notices when the shipped file leaves {@code quarkus:dev}
 * without one.
 *
 * <p>#565: the strategy was set only under {@code %prod}, and dev mode points at an explicit H2 URL
 * so no Dev Services database creates the schema for it. Hibernate's default is {@code none}, so a
 * fresh clone booted against an empty database — the app started and {@code /q/health} answered 200
 * while post-boot validation logged {@code missing table [finding_feedback]} and every DB-backed
 * request failed with {@code Table "REVIEWSESSION" not found}.
 */
class DevSchemaManagementTest {

  private static final String STRATEGY = "quarkus.hibernate-orm.schema-management.strategy";
  private static final String JDBC_URL = "quarkus.datasource.jdbc.url";

  @Test
  void devModeCreatesTheSchemaItStartsAgainst() throws Exception {
    assertEquals("drop-and-create", shipped("dev").getValue(STRATEGY, String.class));
  }

  @Test
  void devModeRecreatesOnlyAnInMemoryDatabase() throws Exception {
    // drop-and-create is safe precisely because the dev URL is a throwaway in-memory H2 instance:
    // if this ever becomes a file or a server URL, the strategy above has to be revisited first.
    assertTrue(
        shipped("dev").getValue(JDBC_URL, String.class).startsWith("jdbc:h2:mem:"),
        "dev must stay on in-memory H2 while it recreates the schema on every boot");
  }

  @Test
  void prodStillMigratesRatherThanRecreating() throws Exception {
    // Guards the blast radius of the dev line: production data must never be dropped at boot.
    assertEquals("update", shipped("prod").getValue(STRATEGY, String.class));
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
