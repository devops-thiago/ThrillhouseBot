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

import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Prometheus scrape endpoint on the main HTTP port. Serves whatever the {@link
 * PrometheusMetricsRegistrar} collected — token usage, latency, cost, and review-outcome metrics —
 * in Prometheus text format; answers 404 while no reader is registered (endpoint disabled via
 * {@code PROMETHEUS_METRICS_ENABLED=false} or OTel SDK disabled).
 */
@Path("/metrics")
public class PrometheusMetricsResource {

  private static final PrometheusTextFormatWriter WRITER = PrometheusTextFormatWriter.create();

  private final PrometheusMetricsRegistrar registrar;

  @Inject
  public PrometheusMetricsResource(PrometheusMetricsRegistrar registrar) {
    this.registrar = registrar;
  }

  @GET
  public Response scrape() throws IOException {
    var reader = registrar.reader();
    if (reader.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND)
          .type(MediaType.TEXT_PLAIN)
          .entity("Prometheus metrics are disabled (PROMETHEUS_METRICS_ENABLED=false).")
          .build();
    }
    var buffer = new ByteArrayOutputStream();
    WRITER.write(buffer, reader.get().collect());
    return Response.ok(buffer.toByteArray(), WRITER.getContentType()).build();
  }
}
