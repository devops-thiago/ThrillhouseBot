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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;

/** Parses the {@link DocGenerator}'s raw JSON output into a {@link DocGenerationResponse}. */
@ApplicationScoped
public class DocGenerationParser {

  private final ObjectMapper mapper;

  @Inject
  public DocGenerationParser(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public DocGenerationResponse parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("Model returned an empty response");
    }
    try {
      // Models sometimes wrap the JSON in ```json fences or leading prose.
      return mapper.readValue(ReviewResponseParser.extractJson(raw), DocGenerationResponse.class);
    } catch (IOException e) {
      throw new IllegalArgumentException("Model response is not valid add-docs JSON", e);
    }
  }
}
