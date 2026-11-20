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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.UUID;
import org.eclipse.microprofile.config.Config;

/**
 * Gives the exported telemetry a {@code service.instance.id} (#1373), so instances exporting to one
 * backend keep separate series: without it their resource attributes are identical, and cumulative
 * counters from different instances overwrite each other.
 *
 * <p>The id is the {@code HOSTNAME} environment variable (a Kubernetes pod name or a container
 * hostname) when it is set, and a random UUID generated at startup otherwise. Quarkus merges every
 * CDI {@link Resource} bean into the SDK resource and applies {@code
 * quarkus.otel.resource.attributes} (fed by {@code OTEL_RESOURCE_ATTRIBUTES}) over them, so {@code
 * OTEL_RESOURCE_ATTRIBUTES=service.instance.id=...} overrides this default.
 */
@ApplicationScoped
public class ServiceInstanceResource {

  /** The OpenTelemetry semantic-convention key for the instance id. */
  static final AttributeKey<String> SERVICE_INSTANCE_ID =
      AttributeKey.stringKey("service.instance.id");

  /** The environment variable read for the default id. */
  static final String HOSTNAME = "HOSTNAME";

  @Produces
  @Singleton
  Resource serviceInstance(Config config) {
    var hostname = config.getOptionalValue(HOSTNAME, String.class).orElse(null);
    return Resource.create(Attributes.of(SERVICE_INSTANCE_ID, instanceId(hostname)));
  }

  /** The hostname when it has text, otherwise a fresh random id. */
  static String instanceId(String hostname) {
    if (hostname != null && !hostname.isBlank()) {
      return hostname.strip();
    }
    return UUID.randomUUID().toString();
  }
}
