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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Verdicts returned by the finding verifier, keyed by the candidate finding's 1-based id. */
@RegisterForReflection
public record VerificationResponse(List<Verdict> verdicts) {
  public VerificationResponse {
    verdicts = List.copyOf(verdicts != null ? verdicts : List.of());
  }

  /**
   * One candidate's verdict. Besides the decision and the corrected grade, it judges the finding's
   * suggested fix (#1080): {@code suggestion} is {@code keep}, {@code replace} (with the corrected
   * code in {@code suggestion_new}) or {@code drop}; and {@code cut} lists the sentences of the
   * description that state a side claim the material contradicts. All three are optional, and each
   * is read leniently: a value of a shape the schema does not name (lines as an array, one cut
   * sentence as a string, an object) is read as far as it holds text, or as absent, so a stray
   * shape there never costs the call the verdicts themselves.
   */
  @RegisterForReflection
  public record Verdict(
      int id,
      String verdict, // confirmed, downgraded, rejected
      String risk, // corrected risk for downgrades
      String confidence, // corrected confidence for downgrades
      String reason,
      @JsonDeserialize(using = LenientText.class) String suggestion, // keep, replace, drop
      // The corrected code, for replace.
      @JsonProperty("suggestion_new") @JsonDeserialize(using = LenientText.class)
          String suggestionNew,
      @JsonDeserialize(using = LenientTextList.class) List<String> cut) {

    public Verdict {
      // The model may emit null elements; copyOf stays inline so SpotBugs sees the defensive copy.
      cut = List.copyOf(cut == null ? List.of() : cut.stream().filter(Objects::nonNull).toList());
    }

    /** A verdict with no judgment of the suggestion and nothing to cut. */
    public Verdict(int id, String verdict, String risk, String confidence, String reason) {
      this(id, verdict, risk, confidence, reason, null, null, List.of());
    }
  }

  /**
   * Reads a string field leniently: a string as it is, an array as its string elements one per line
   * (code sent as a list of lines), anything else as absent.
   */
  @RegisterForReflection
  public static final class LenientText extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      var lines = texts(parser.readValueAsTree());
      return lines.isEmpty() ? null : String.join("\n", lines);
    }
  }

  /** Reads a list of strings leniently: one string as a list of one, any non-string skipped. */
  @RegisterForReflection
  public static final class LenientTextList extends JsonDeserializer<List<String>> {

    @Override
    public List<String> deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      return texts(parser.readValueAsTree());
    }
  }

  /** The string {@code node} is, or the string elements of the array it is, in order. */
  static List<String> texts(JsonNode node) {
    var texts = new ArrayList<String>();
    if (node.isTextual()) {
      texts.add(node.asText());
    } else if (node.isArray()) {
      for (var element : node) {
        if (element.isTextual()) {
          texts.add(element.asText());
        }
      }
    }
    return List.copyOf(texts);
  }
}
