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

import dev.langchain4j.service.Result;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@link FindingVerifier} that answers what the wrapped one answers, with each verdict that names
 * no key given the key of the candidate its id names, as a verifier following the prompt writes it
 * (#1322). Tests that stub verdict text by id alone stay about what they test; a verdict that
 * already carries a key, and an id that names no candidate, are left as they are.
 */
public final class KeyEchoingVerifier implements FindingVerifier {

  private static final Pattern CANDIDATE =
      Pattern.compile("\"id\"\\s*:\\s*(\\d+)\\s*,\\s*\"key\"\\s*:\\s*\"([0-9a-f]{8})\"");
  private static final Pattern VERDICT_ID =
      Pattern.compile("\"id\"\\s*:\\s*(\\d+)(?!\\d)(?!\\s*,\\s*\"key\")");

  private final FindingVerifier delegate;

  private KeyEchoingVerifier(FindingVerifier delegate) {
    this.delegate = delegate;
  }

  /** {@code delegate}'s answers with the candidates' keys echoed. */
  public static FindingVerifier around(FindingVerifier delegate) {
    return new KeyEchoingVerifier(delegate);
  }

  @Override
  public Result<String> verify(
      String findings,
      String prContext,
      String diff,
      String projectStack,
      String previousFindings,
      String verifierSystemPrompt) {
    var result =
        delegate.verify(
            findings, prContext, diff, projectStack, previousFindings, verifierSystemPrompt);
    if (result == null || result.content() == null) {
      return result;
    }
    return Result.<String>builder()
        .content(echoed(result.content(), findings))
        .finishReason(result.finishReason())
        .tokenUsage(result.tokenUsage())
        .build();
  }

  /** {@code answer} with each keyless verdict id followed by its candidate's key. */
  static String echoed(String answer, String candidates) {
    Map<String, String> keys = new HashMap<>();
    var candidate = CANDIDATE.matcher(candidates);
    while (candidate.find()) {
      keys.put(candidate.group(1), candidate.group(2));
    }
    return VERDICT_ID
        .matcher(answer)
        .replaceAll(
            match -> {
              var key = keys.get(match.group(1));
              var id = Matcher.quoteReplacement(match.group());
              return key == null ? id : id + ", \"key\": \"" + key + "\"";
            });
  }
}
