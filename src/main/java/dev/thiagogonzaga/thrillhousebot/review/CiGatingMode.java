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
package dev.thiagogonzaga.thrillhousebot.review;

import java.util.List;
import java.util.Locale;

/**
 * How strictly CI status factors into the APPROVE decision. Fail-closed {@link #STRICT} is the safe
 * default; softer modes exist for teams with flaky CI or incomplete required-context resolution.
 */
public enum CiGatingMode {
  /** Hold APPROVE while required CI is pending, failing, or unreadable (fail-closed). */
  STRICT,
  /** APPROVE is allowed, but the summary and check run still note CI uncertainty. */
  WARN,
  /** Ignore CI for the APPROVE decision (findings-only gate); CI is not fetched. */
  OFF;

  /** Values accepted by {@code thrillhousebot.review.ci-gating} / {@link #parse}. */
  public static final List<String> ALLOWED = List.of("strict", "warn", "off");

  /** Wire value normalized to the lowercase form operators and docs use. */
  public static String normalize(String raw) {
    return raw.strip().toLowerCase(Locale.ROOT);
  }

  /**
   * Parses a configured value into a mode. Callers must validate against {@link #ALLOWED} first
   * (startup) — this throws on an unrecognized token so a typo never silently falls through to
   * {@link #STRICT}.
   */
  public static CiGatingMode parse(String raw) {
    return valueOf(normalize(raw).toUpperCase(Locale.ROOT));
  }

  /** True when non-green / unreadable CI must downgrade APPROVE to COMMENT. */
  public boolean holdsApproval() {
    return this == STRICT;
  }

  /** True when CI checks are fetched and evaluated for the verdict path. */
  public boolean evaluatesCi() {
    return this != OFF;
  }
}
