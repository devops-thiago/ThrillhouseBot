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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/** The default {@code service.instance.id} (#1373). */
class ServiceInstanceResourceTest {

  private final ServiceInstanceResource producer = new ServiceInstanceResource();

  @Test
  void theHostnameIsTheInstanceId() {
    var resource = producer.serviceInstance(config(Optional.of(" pod-7 ")));

    assertEquals("pod-7", resource.getAttribute(ServiceInstanceResource.SERVICE_INSTANCE_ID));
  }

  @Test
  void withoutAHostnameEachInstanceGetsARandomId() {
    var first = instanceIdWithout();
    var second = instanceIdWithout();

    assertDoesNotThrow(() -> UUID.fromString(first));
    assertNotEquals(first, second);
  }

  @Test
  void aBlankHostnameCountsAsUnset() {
    var id = ServiceInstanceResource.instanceId("   ");

    assertDoesNotThrow(() -> UUID.fromString(id));
  }

  private String instanceIdWithout() {
    return producer
        .serviceInstance(config(Optional.empty()))
        .getAttribute(ServiceInstanceResource.SERVICE_INSTANCE_ID);
  }

  private static Config config(Optional<String> hostname) {
    var config = mock(Config.class);
    when(config.getOptionalValue(ServiceInstanceResource.HOSTNAME, String.class))
        .thenReturn(hostname);
    return config;
  }
}
