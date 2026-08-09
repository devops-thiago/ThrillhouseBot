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
package dev.thiagogonzaga.thrillhousebot.github;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ArtifactZipFetcher} — the unauthenticated second hop of a GitHub Actions
 * artifact download (#115). The successful download itself needs a live TLS endpoint and is not
 * exercised here; what is pinned are the guards that decide whether a request is made at all and
 * the bound on how much of a response is read.
 */
class ArtifactZipFetcherTest {

  private final ArtifactZipFetcher fetcher = new ArtifactZipFetcher();

  @Test
  void refusesAMissingOrPlaintextLocation() {
    assertEquals(0, fetcher.fetch(null).length, "no location, no download");
    assertEquals(
        0,
        fetcher.fetch(URI.create("http://blob.example/artifact.zip")).length,
        "a plaintext redirect target is never followed");
    assertEquals(
        0,
        fetcher.fetch(URI.create("file:///etc/passwd")).length,
        "only https redirect targets are followed");
  }

  @Test
  void degradesToNullWhenTheDownloadFails() throws IOException {
    int closedPort;
    try (var socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }

    assertEquals(
        0,
        fetcher.fetch(URI.create("https://127.0.0.1:" + closedPort + "/artifact.zip")).length,
        "a failed download must degrade to no coverage context, never propagate");
  }

  @Test
  void rejectsABodyLargerThanTheCapInsteadOfTruncatingIt() throws IOException {
    var atCap = new byte[ArtifactZipFetcher.MAX_BYTES];
    var overCap = new byte[ArtifactZipFetcher.MAX_BYTES + 1];

    assertEquals(
        ArtifactZipFetcher.MAX_BYTES,
        ArtifactZipFetcher.readBounded(new ByteArrayInputStream(atCap)).length,
        "a body exactly at the cap is still read whole");
    assertEquals(
        0,
        ArtifactZipFetcher.readBounded(new ByteArrayInputStream(overCap)).length,
        "half a zip is not a coverage report; an over-long body is dropped, not truncated");
  }
}
