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

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import java.time.Duration;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * Every signal's OTLP export timeout defaults above the collector's 60-second HTTP/2 keep-alive
 * (#1268). Quarkus uses the timeout as the connection's read idle timeout, and at its own 10-second
 * default an export written after a quiet spell is sent twice (quarkusio/quarkus#57107). Asserted
 * through the resolved configuration, so the three keys in {@code application.properties} are
 * covered, not only the documentation.
 */
@QuarkusTest
class OtlpExportTimeoutDefaultsTest {

  @Test
  void everySignalWaitsLongerThanTheCollectorsKeepAlive() {
    var config = ConfigProvider.getConfig();
    for (var signal : new String[] {"traces", "metrics", "logs"}) {
      var timeout =
          config.getValue("quarkus.otel.exporter.otlp." + signal + ".timeout", Duration.class);
      assertEquals(Duration.ofSeconds(65), timeout, signal);
    }
  }
}
