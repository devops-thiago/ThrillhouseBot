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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.impl.VertxInternal;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * Every outbound call the bot makes — the langchain4j OpenAI client and the GitHub REST clients are
 * all Quarkus REST clients over the Vert.x HTTP client — resolves its hostname through the Vert.x
 * address resolver. Quarkus leaves {@code quarkus.vertx.use-async-dns} false by default, which sets
 * {@code vertx.disableDnsResolver=true} and hands Netty the JDK-backed {@link
 * DefaultAddressResolverGroup}, whose {@code DefaultNameResolver} calls {@code
 * InetAddress.getByName} on the event-loop thread opening the connection. That is what parked event
 * loops for seconds under concurrent reviews. These tests assert the resolved runtime rather than
 * the property text, so the {@code quarkus.vertx.*} wiring in {@code application.properties} is
 * covered too.
 */
@QuarkusTest
class AsyncDnsResolverTest {

  @Inject Vertx vertx;

  @Test
  void hostnamesResolveThroughTheAsyncResolverNotTheBlockingJdkOne() {
    var group = ((VertxInternal) vertx).nettyAddressResolverGroup();

    assertNotSame(DefaultAddressResolverGroup.INSTANCE, group);
    assertInstanceOf(DnsAddressResolverGroup.class, group);
  }

  @Test
  void positiveCacheIsFlooredAtTheJvmDefaultDnsTtl() {
    assertEquals(
        30,
        ConfigProvider.getConfig()
            .getValue("quarkus.vertx.resolver.cache-min-time-to-live", Integer.class));
  }
}
