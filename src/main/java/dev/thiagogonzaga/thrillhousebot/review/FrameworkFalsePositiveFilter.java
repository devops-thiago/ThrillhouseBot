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

import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Deterministic guard against recurring framework false positives the model produces from
 * remembered — and wrong — framework rules. The first guarded pattern is the CDI/Spring
 * constructor-injection claim: a finding that a class is "missing a no-arg/default constructor"
 * when the diff itself shows a constructor annotated {@code @Inject} (or {@code @Autowired}) on
 * that class. Constructor injection is the documented idiom in CDI (Quarkus/Jakarta) and Spring; no
 * no-arg constructor is required, so the diff refutes the claim outright and the finding is
 * dropped.
 *
 * <p>The check is conservative in the same direction as {@link FindingQuoteValidator}: a finding is
 * dropped only when the refuting evidence — an injection-annotated constructor of the flagged
 * file's class — is visible on the kept (context/added) side of that file's own diff section. When
 * the constructor sits outside the diff window nothing is provable, and the finding passes through
 * unchanged.
 */
@ApplicationScoped
public class FrameworkFalsePositiveFilter {

  /** A "default constructor" claim; the no-arg/zero-arg variants pair the two tokens below. */
  private static final Pattern DEFAULT_CONSTRUCTOR_CLAIM =
      Pattern.compile("\\bdefault\\s+(?:public\\s+)?constructor\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern NO_ARG_TOKEN =
      Pattern.compile("\\b(?:no|zero)[\\s-]?arg(?:ument)?s?\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern CONSTRUCTOR_TOKEN =
      Pattern.compile("\\bconstructor\\b", Pattern.CASE_INSENSITIVE);

  /** How far apart (in characters) the no-arg and constructor tokens may sit and still pair up. */
  private static final int TOKEN_PROXIMITY = 60;

  /** An injection annotation, bare or fully qualified, possibly inlined with the declaration. */
  private static final Pattern INJECT_ANNOTATION =
      Pattern.compile("@(?:(?:jakarta|javax)\\.inject\\.)?Inject\\b|@(?:[\\w.]*\\.)?Autowired\\b");

  /** How many kept diff lines after an injection annotation may hold the constructor. */
  private static final int ANNOTATION_LOOKAHEAD = 5;

  /** Drops findings the diff deterministically refutes; leaves everything else untouched. */
  public ReviewResponse filter(ReviewResponse response, String diff) {
    if (response.findings().isEmpty() || diff == null || diff.isBlank()) {
      return response;
    }
    FindingQuoteValidator.DiffIndex index = FindingQuoteValidator.indexDiff(diff);
    var kept = new ArrayList<ReviewResponse.Finding>(response.findings().size());
    var changed = false;
    for (ReviewResponse.Finding finding : response.findings()) {
      if (isRefutedNoArgConstructorClaim(finding, index)) {
        Log.infof(
            "Dropping finding '%s' (%s:%d) — it claims a missing no-arg constructor but the diff"
                + " shows an injection-annotated constructor; constructor injection needs no no-arg"
                + " constructor in CDI/Spring",
            finding.title(), finding.file(), finding.line());
        changed = true;
        continue;
      }
      kept.add(finding);
    }
    if (!changed) {
      return response;
    }
    return new ReviewResponse(
        kept,
        response.previousFindingsStatus(),
        FindingVerificationService.recount(response.summary(), kept));
  }

  private static boolean isRefutedNoArgConstructorClaim(
      ReviewResponse.Finding finding, FindingQuoteValidator.DiffIndex index) {
    if (!claimsMissingNoArgConstructor(finding)) {
      return false;
    }
    String className = classNameOf(finding.file());
    if (className.isEmpty()) {
      return false;
    }
    return hasInjectedConstructor(index.diffLinesFor(finding.file()), className);
  }

  private static boolean claimsMissingNoArgConstructor(ReviewResponse.Finding finding) {
    return matchesClaim(finding.title()) || matchesClaim(finding.description());
  }

  private static boolean matchesClaim(String text) {
    if (text == null) {
      return false;
    }
    return DEFAULT_CONSTRUCTOR_CLAIM.matcher(text).find()
        || tokensAppearNearby(text, NO_ARG_TOKEN, CONSTRUCTOR_TOKEN);
  }

  /** Whether both tokens occur in {@code text} within {@link #TOKEN_PROXIMITY} characters. */
  private static boolean tokensAppearNearby(String text, Pattern first, Pattern second) {
    var firstMatcher = first.matcher(text);
    while (firstMatcher.find()) {
      var secondMatcher = second.matcher(text);
      while (secondMatcher.find()) {
        int gap =
            Math.max(
                secondMatcher.start() - firstMatcher.end(),
                firstMatcher.start() - secondMatcher.end());
        if (gap <= TOKEN_PROXIMITY) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Whether the kept (context/added) lines of the file's diff section show a constructor of {@code
   * className} annotated for injection: the annotation either on the declaration line itself or on
   * one of the few preceding lines, with only further annotation lines allowed in between. Deleted
   * lines never count — an {@code @Inject} the PR removes is not evidence the bean still has it.
   */
  private static boolean hasInjectedConstructor(
      List<FindingQuoteValidator.DiffLine> diffLines, String className) {
    var keptLines = new ArrayList<String>();
    for (var line : diffLines) {
      if (line.marker() != '-') {
        keptLines.add(line.normalizedText());
      }
    }
    Pattern constructorDecl = Pattern.compile("(?<![\\w.])" + Pattern.quote(className) + "\\s*\\(");
    for (int i = 0; i < keptLines.size(); i++) {
      if (INJECT_ANNOTATION.matcher(keptLines.get(i)).find()
          && constructorAtOrBelow(keptLines, i, className, constructorDecl)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the annotation line at {@code annotationIndex} — or one of the next few lines, with
   * only further annotation lines allowed in between — declares a constructor of {@code className}.
   */
  private static boolean constructorAtOrBelow(
      List<String> keptLines, int annotationIndex, String className, Pattern constructorDecl) {
    if (declaresConstructor(keptLines.get(annotationIndex), className, constructorDecl)) {
      return true;
    }
    int last = Math.min(keptLines.size() - 1, annotationIndex + ANNOTATION_LOOKAHEAD);
    for (int j = annotationIndex + 1; j <= last; j++) {
      String candidate = keptLines.get(j);
      if (declaresConstructor(candidate, className, constructorDecl)) {
        return true;
      }
      if (!candidate.startsWith("@")) {
        return false;
      }
    }
    return false;
  }

  private static boolean declaresConstructor(String line, String className, Pattern decl) {
    if (line.contains("new " + className)) {
      return false;
    }
    return decl.matcher(line).find();
  }

  /** The simple class name implied by the finding's file path, or empty when not a Java file. */
  private static String classNameOf(String file) {
    if (file == null || file.isBlank()) {
      return "";
    }
    String name = file.substring(file.lastIndexOf('/') + 1);
    int dot = name.lastIndexOf('.');
    if (dot <= 0 || !name.endsWith(".java")) {
      return "";
    }
    return name.substring(0, dot);
  }
}
