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

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * Runs the shipped migrations the way production does (#22): on an empty database, and on a
 * database that Hibernate created before Flyway was adopted, which has the v0.8.0 tables but no
 * history table and must be baselined instead of having V1 run against it. Later migrations then
 * run on top of the baseline and keep its rows (#1220).
 */
class SchemaMigrationTest {

  private static final String V1 = "db/migration/V1__baseline.sql";

  @Test
  void anEmptyDatabaseIsMigratedFromV1() {
    var flyway = flyway(freshDatabase());

    var result = flyway.migrate();

    assertEquals(2, result.migrationsExecuted);
    assertEquals(MigrationState.SUCCESS, flyway.info().current().getState());
  }

  @Test
  void aDatabaseFromBeforeFlywayIsBaselinedThenScopedAndV1IsSkipped() throws Exception {
    var url = freshDatabase();
    try (var connection = DriverManager.getConnection(url, "sa", "");
        var statement = connection.createStatement()) {
      statement.execute(v1Script());
      statement.execute(
          "INSERT INTO pausedpr (id, prnumber, repository) VALUES (1, 7, 'acme/app')");
    }
    var flyway = flyway(url);

    var result = flyway.migrate();

    assertEquals(1, result.migrationsExecuted, "only V2 runs on a baselined database");
    var baseline = flyway.info().applied()[0];
    assertEquals(MigrationVersion.fromVersion("1"), baseline.getVersion());
    assertEquals(MigrationState.BASELINE, baseline.getState());
    var current = flyway.info().current();
    assertEquals(MigrationVersion.fromVersion("2"), current.getVersion());
    assertEquals(MigrationState.SUCCESS, current.getState());
    try (var connection = DriverManager.getConnection(url, "sa", "");
        var rows =
            connection
                .createStatement()
                .executeQuery(
                    "SELECT count(*), count(installationid), count(repositoryid) FROM pausedpr")) {
      assertTrue(rows.next());
      assertEquals(1, rows.getInt(1), "baselining must keep the existing rows");
      assertEquals(0, rows.getInt(2), "an existing row is left for the backfill to scope");
      assertEquals(0, rows.getInt(3));
    }
  }

  private static Flyway flyway(String url) {
    return Flyway.configure()
        .dataSource(url, "sa", "")
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion("1")
        .load();
  }

  private static String freshDatabase() {
    return "jdbc:h2:mem:migration-"
        + UUID.randomUUID()
        + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
  }

  private static String v1Script() throws Exception {
    try (var in = SchemaMigrationTest.class.getClassLoader().getResourceAsStream(V1)) {
      return new String(Objects.requireNonNull(in, V1).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
