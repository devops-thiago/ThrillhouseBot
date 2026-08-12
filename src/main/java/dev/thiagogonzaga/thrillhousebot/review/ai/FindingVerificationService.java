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
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.service.Result;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.review.Confidence;
import dev.thiagogonzaga.thrillhousebot.review.PromptTemplateEscaper;
import dev.thiagogonzaga.thrillhousebot.review.RiskLevel;
import io.quarkus.logging.Log;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Second-pass audit of review findings: a skeptical verifier call re-evaluates each candidate
 * against the diff and project stack, dropping rejected findings and lowering inflated ones.
 *
 * <p>Fails open by design — any verifier error keeps the original findings, so a broken or slow
 * verification call can degrade quality but never lose a review.
 *
 * <p>The verifier is a billed review-path call, so it participates in the {@code
 * REVIEW_MAX_TOKENS_PER_REVIEW} spend ceiling like every other call the review makes: its {@link
 * Result#tokenUsage() provider-reported usage} is recorded straight into the {@link
 * ReviewTokenLedger} (the blocking call never passes through the streaming path's {@link
 * ReviewSessionContext} bind, so the observability listener cannot correlate it), and once the
 * ceiling is reached the call is skipped fail-open — the unverified findings are kept, which is
 * exactly this service's error contract.
 */
@ApplicationScoped
public class FindingVerificationService {

  private final FindingVerifier verifier;
  private final ThrillhouseConfig config;
  private final ObjectMapper mapper;
  private final ReviewTokenLedger tokenLedger;
  private final TruncatedResponseSalvager salvager;

  @Inject
  public FindingVerificationService(
      FindingVerifier verifier,
      ThrillhouseConfig config,
      ObjectMapper mapper,
      ReviewTokenLedger tokenLedger,
      TruncatedResponseSalvager salvager) {
    this.verifier = verifier;
    this.config = config;
    this.mapper = mapper;
    this.tokenLedger = tokenLedger;
    this.salvager = salvager;
  }

  private static final Pattern HEDGING =
      Pattern.compile("\\b(may|might|could|potentially|possibly)\\b", Pattern.CASE_INSENSITIVE);

  /**
   * Sentence/clause boundaries the hedging scan splits on, so a hedge is judged against the clause
   * it actually qualifies rather than against the whole finding body.
   */
  private static final Pattern CLAUSE_BOUNDARY = Pattern.compile("(?<=[.;!?])\\s+|\\R");

  /**
   * A clause that asks the reader to check something rather than hedging the defect itself. The
   * review prompt REQUIRES this clause on a finding whose sink and tainted value are both in the
   * diff but whose mitigation might live in a layer that was not shown: "lower the confidence, name
   * in the description the exact layer to verify". Counting the hedge word inside that mandated
   * clause as a hedge of the claim charges the same uncertainty twice, on a security class where
   * the prompt guarantees the clause is present.
   */
  private static final Pattern VERIFICATION_CUE =
      Pattern.compile(
          "\\b(verify|verifying|verification|confirm\\w*|unverifiable|not shown|not visible"
              + "|outside the diff|not in the diff)\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * Injection sinks and vulnerability classes named explicitly, per the review prompt's dimension-2
   * list. Matching one of these is only the first half of the floor's test.
   */
  private static final Pattern INJECTION_SINK =
      Pattern.compile(
          "\\b(xss|cross[- ]site scripting|sql injection|command injection|shell injection"
              + "|path traversal|directory traversal|(unsafe|insecure) deserializ\\w*"
              + "|dangerouslysetinnerhtml|bypasssecuritytrusthtml|v-html|inner_?html|outerhtml"
              + "|insertadjacenthtml|document\\.write)\\b",
          Pattern.CASE_INSENSITIVE);

  /** String-built SQL, which findings usually name by its shape rather than as "SQL injection". */
  private static final Pattern SQL = Pattern.compile("\\bsql\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern STRING_BUILT =
      Pattern.compile(
          "\\b(concatenat\\w*|interpolat\\w*|string[- ]?built|built from)\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * The finding's own statement that nothing neutralizes the tainted value. Deliberately keyed on
   * an ABSENCE claim: it is what turns "this code uses innerHTML" into "user data reaches an
   * injection sink unmitigated", which is the class the prompt floors at "high".
   */
  private static final Pattern NO_MITIGATION =
      Pattern.compile(
          "\\b(unsanitiz\\w*|unescaped|unvalidated|unparameteriz\\w*|non-parameteriz\\w*"
              + "|(no|not|without|missing|never|lacks?|absent)\\s+(\\w+[\\s-]+){0,3}"
              + "(sanitiz|escap|validat|parameteriz|encod)\\w*)",
          Pattern.CASE_INSENSITIVE);

  /**
   * A finding that RULES THE SINK OUT rather than reporting it ("so there is no SQL injection",
   * "not vulnerable to XSS"). Both floor signals are token matches, so such a sentence satisfies
   * them out of its own negation — the sink name sits inside the denial, and a phrase like "not
   * concatenated but parameterized" matches the absence group on the very mitigation it asserts.
   * The gap is one word, so an assertion of the defect that merely contains a negation ("no
   * protection against SQL injection") is not mistaken for a denial.
   */
  private static final Pattern SINK_DENIED =
      Pattern.compile(
          "\\b(no|not|never)\\s+(\\w+[\\s-]+){0,1}"
              + "(xss|cross[- ]site scripting|sql injection|command injection|shell injection"
              + "|path traversal|directory traversal|vulnerab\\w*|exploitab\\w*)\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * A finding that states the mitigation IS present ("it is escaped on render", "React escapes
   * them"). An absence claim about one layer must not floor the class when the same text says
   * another layer neutralizes the value. Negated forms are excluded, so "is not escaped" and "was
   * never sanitized" stay absence claims instead of defeating themselves.
   */
  private static final Pattern MITIGATION_ASSERTED =
      Pattern.compile(
          "\\b(is|are|was|were|gets|been|already)\\s+(?!(no|not|never)\\b)(\\w+[\\s-]+){0,1}"
              + "(sanitiz|escap|validat|parameteriz|encod)(ed|es|ing)\\b"
              + "|\\b(?!(no|not|never|nothing)\\b)\\w+\\s+"
              + "(sanitizes|escapes|validates|parameterizes|encodes)\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * A conditional clause — a hypothesis the finding raises, not a fact it states. Matches from the
   * conditional marker to the end of that clause, so only the hypothetical span is removed and
   * whatever the finding asserts around it survives.
   *
   * <p>Both floor defeaters are assertion tests, and neither regex can carry mood: "If the feedback
   * API sanitizes body on write, the exploit is neutralized" satisfies {@link #MITIGATION_ASSERTED}
   * on the token pair "API sanitizes" even though the sentence goes on to reject the hypothesis
   * ("but a sanitizer you cannot see is not a sanitizer"). That phrasing is not incidental — #575's
   * review prompt REQUIRES a demonstrated-sink finding whose mitigating layer was not shown to name
   * the exact layer to verify, so the instruction manufactures the wording the defeater then reads
   * as a mitigation (#608). Scoping to the clause is the same narrowing the hedging scan already
   * needed for the same reason.
   *
   * <p>The clause ends at a real clause boundary — strong punctuation, or a coordinator that opens
   * the consequent ("but", "so", "then"...) — and deliberately NOT at a comma. A protasis carries
   * its own commas ("If the feedback API, per its own contract, always sanitizes the body on write,
   * ..."), and stopping at the first one left the mitigation verb sitting in text read as asserted,
   * which is the very under-firing this pattern exists to remove. The coordinator boundary is what
   * keeps the consequent: "If it were stored as plain text this would be moot, but React escapes it
   * at render" still asserts its mitigation, and that assertion must still defeat the floor, since
   * over-firing the floor is the dangerous direction (#594).
   *
   * <p>Residual trade-off, chosen deliberately: a consequent separated by a bare comma and nothing
   * else ("If you follow the render path, React escapes the value") is swallowed with the protasis,
   * so its mitigation no longer defeats the floor. Commas cannot serve both roles, and this is the
   * safer half — the finding must ALSO name a sink and ALSO claim nothing sanitizes it before the
   * floor can fire at all, and the floor only lifts risk, leaving confidence and every other signal
   * where the model put them.
   *
   * <p>Plain "should" is NOT a marker. Only inverted "Should the API sanitize ..." is a hypothesis,
   * and its bare infinitive matches none of {@link #MITIGATION_ASSERTED}'s verb forms, so listing
   * it bought nothing — while "It should be noted that the API sanitizes body on write" is an
   * assertion, and treating it as a hypothesis hid a real mitigation from the defeater.
   */
  private static final Pattern CONDITIONAL_CLAUSE =
      Pattern.compile(
          "\\b(if|unless|whether|in case|assuming|provided that)\\b"
              + "(?:(?!\\b(but|so|then|however|therefore|otherwise)\\b)"
              + "[^;:.!?\\n\\r\\u2013\\u2014])*",
          Pattern.CASE_INSENSITIVE);

  /** The floor an unmitigated-injection-sink finding may never publish below (#570). */
  private static final RiskLevel INJECTION_SINK_FLOOR = RiskLevel.HIGH;

  private static final String HIGH_LABEL = "high";

  private static final String MEDIUM_LABEL = "medium";

  /** The verifier response's only field, and the array salvage keys on when the body is cut. */
  private static final String VERDICTS = "verdicts";

  /**
   * Audits the response's findings; {@code diff}, {@code projectStack} and {@code previousFindings}
   * must already be escaped for prompt templating, the same values handed to the review call.
   * Previous findings let the verifier reject re-raises of answered findings. {@code
   * ledgerSessionId} is the review's {@link ReviewTokenLedger} key ({@link
   * ReviewTokenLedger#keyFor}): the call's usage is recorded against it, and once the review's
   * spend ceiling is reached the call is skipped fail-open, keeping the unverified findings.
   */
  public ReviewResponse verify(
      long ledgerSessionId,
      ReviewResponse response,
      String diff,
      String projectStack,
      String previousFindings) {
    return floorInjectionSinkRisk(
        audit(ledgerSessionId, response, diff, projectStack, previousFindings));
  }

  /** The audit itself; {@link #verify} applies the deterministic severity floor to its result. */
  private ReviewResponse audit(
      long ledgerSessionId,
      ReviewResponse response,
      String diff,
      String projectStack,
      String previousFindings) {
    ReviewResponse screened = demoteHedgedBlockingFindings(response);
    if (!config.review().verifierEnabled() || screened.findings().isEmpty()) {
      return screened;
    }
    if (tokenLedger.ceilingReached(ledgerSessionId)) {
      // The verifier is a fresh billed call; once the ceiling is reached it is not made. Skipping
      // fail-open keeps the unverified findings — degraded quality, never a lost review.
      Log.warnf(
          "Finding verification for review session %d skipped at the review's token spend ceiling"
              + " (%d tokens spent, ceiling %d — REVIEW_MAX_TOKENS_PER_REVIEW); keeping the %d"
              + " unverified finding(s)",
          ledgerSessionId,
          tokenLedger.tokensSpent(ledgerSessionId),
          tokenLedger.ceiling(),
          screened.findings().size());
      return screened;
    }
    try {
      var result =
          verifier.verify(
              PromptTemplateEscaper.escape(renderCandidates(screened.findings())),
              diff,
              projectStack,
              previousFindings == null ? "" : previousFindings);
      // Meter before unwrapping: a truncated response was still billed, so its spend counts.
      recordVerifierUsage(ledgerSessionId, result);
      var raw =
          AiResponses.textOrThrowOnTruncation(
              result, "Finding verification", AiResponses.ModelLane.CONCISE);
      if (raw == null || raw.isBlank()) {
        // The unwrap helper's documented "no response" soft failure, which this caller has to
        // honour like every other one: a reasoning model can spend the whole output budget on
        // reasoning tokens and return an empty body with no length stop. Keeping the unverified
        // findings is this service's error contract; handing the absent body to the JSON
        // extraction instead reported an expected outcome as a verification fault.
        Log.warnf(
            "Finding verification returned no response body — keeping the %d unverified finding(s)",
            screened.findings().size());
        return screened;
      }
      return apply(screened, parseOrSalvage(raw, screened.findings().size()));
    } catch (AiResponseTruncatedException e) {
      return salvageTruncatedVerdicts(screened, e);
    } catch (IOException | RuntimeException e) {
      Log.warnf(
          e,
          "Finding verification failed — keeping the %d unverified finding(s)",
          screened.findings().size());
      return screened;
    }
  }

  /**
   * Parses the verifier body, salvaging the verdicts that completed when it arrives cut mid-JSON
   * (#546). Production has seen the body cut with no {@code finish_reason=length} reported, so it
   * never reaches the truncation lane above: it landed in the generic catch and every verdict was
   * thrown away, leaving a response that carried nine complete verdicts and a tenth cut off
   * contributing nothing. The cut body is well-formed up to the cut, so {@link
   * TruncatedResponseSalvager} recovers the verdicts that closed — the same machinery the review
   * lane already uses, not a second parser.
   *
   * <p>Salvage is strictly additive to the fail-open contract: a candidate whose verdict fell on
   * the far side of the cut simply has no verdict, and {@link #apply} keeps such a finding exactly
   * as it stands — a missing verdict never rejects or downgrades anything. When nothing at all is
   * recoverable the parse failure is rethrown, so the caller logs and fails open as before.
   */
  private VerificationResponse parseOrSalvage(String raw, int candidates) throws IOException {
    try {
      return mapper.readValue(ReviewResponseParser.extractJson(raw), VerificationResponse.class);
    } catch (IOException | RuntimeException e) {
      var salvaged = salvager.salvageArray(raw, VERDICTS, VerificationResponse.Verdict.class);
      if (salvaged.isEmpty()) {
        throw e;
      }
      var verified = candidatesCovered(salvaged, candidates);
      Log.warnf(
          "Finding verification response was cut mid-JSON (%s); salvaged %d verdict(s) covering %d"
              + " of %d candidate finding(s) — the remaining %d stay unverified",
          e.getMessage(), salvaged.size(), verified, candidates, candidates - verified);
      return new VerificationResponse(salvaged);
    }
  }

  /**
   * Applies the verdicts that closed before the model's response-length cap cut the body (#599).
   * Same salvage as {@link #parseOrSalvage}, on the other lane that reaches a cut body: this one
   * arrives as a reported {@code finish_reason=length} rather than as a parse failure, so it never
   * passed through the parse path at all.
   *
   * <p>The cut text only became reachable here when {@link AiResponses#textOrThrowOnTruncation} was
   * given {@link Result#content()} to carry on the failure (#592/#580) — before that the blocking
   * lanes passed {@code null}, so a verification call cut mid-JSON discarded every verdict it had
   * already paid for, including the complete ones. Nothing else about the lane changes: the
   * truncation is still not retried, and a body with nothing recoverable in it (no partial body at
   * all, or a cut before the first verdict closed) fails open exactly as before, keeping every
   * unverified finding.
   */
  private ReviewResponse salvageTruncatedVerdicts(
      ReviewResponse screened, AiResponseTruncatedException e) {
    var candidates = screened.findings().size();
    var salvaged =
        salvager.salvageArray(e.partialBody(), VERDICTS, VerificationResponse.Verdict.class);
    if (salvaged.isEmpty()) {
      // The verifier fails open by design; say why so a cap is not mistaken for a provider fault.
      Log.warnf("Finding verification — keeping unverified findings. %s", e.getMessage());
      return screened;
    }
    var verified = candidatesCovered(salvaged, candidates);
    Log.warnf(
        "Finding verification was cut at the model's response-length cap; salvaged %d verdict(s)"
            + " covering %d of %d candidate finding(s) — the remaining %d stay unverified. %s",
        salvaged.size(), verified, candidates, candidates - verified, e.getMessage());
    return apply(screened, new VerificationResponse(salvaged));
  }

  /**
   * How many distinct candidates the salvaged verdicts actually cover. Salvage is best-effort over
   * model output, so a verdict can carry an id outside the 1-based candidate range; counting only
   * the ids in range keeps the log honest about what stayed unverified.
   */
  private static long candidatesCovered(
      List<VerificationResponse.Verdict> salvaged, int candidates) {
    return salvaged.stream()
        .mapToInt(VerificationResponse.Verdict::id)
        .filter(id -> id >= 1 && id <= candidates)
        .distinct()
        .count();
  }

  /**
   * Records the verifier call's provider-reported usage in the review's ledger. The blocking AI
   * service returns usage on its {@link Result}, so it is recorded here directly — the
   * observability listener only correlates calls made under the streaming path's session bind,
   * which this call never is. A missing result or usage (a provider that omits it) records nothing;
   * the ledger itself tolerates a null side of the count.
   */
  private void recordVerifierUsage(long ledgerSessionId, Result<String> result) {
    var usage = result == null ? null : result.tokenUsage();
    if (usage == null) {
      return;
    }
    tokenLedger.recordUsage(ledgerSessionId, usage.inputTokenCount(), usage.outputTokenCount());
  }

  /**
   * Deterministic guard that runs even when the AI verifier is disabled or fails: a
   * blocking-eligible finding whose own wording hedges ("may", "might", "could"...) is by
   * definition not a demonstrated failure, so its confidence drops to medium — it still posts, but
   * can no longer request changes on its own.
   *
   * <p>The hedge is read clause by clause: a hedge word inside a clause that names something to
   * verify is the verification request the prompt mandates next to a demonstrated defect, not a
   * hedge of the defect. Scanning the whole body made that mandated clause self-defeating on the
   * security class it was written for — a demonstrated injection whose description named the
   * unshown sanitizing layer lost its blocking confidence for saying so (#570).
   */
  static ReviewResponse demoteHedgedBlockingFindings(ReviewResponse response) {
    if (response.findings().isEmpty()) {
      return response;
    }
    var adjusted = new ArrayList<ReviewResponse.Finding>(response.findings().size());
    var changed = false;
    for (ReviewResponse.Finding finding : response.findings()) {
      if (isBlockingEligible(finding) && containsHedging(finding)) {
        Log.infof(
            "Demoting hedged %s finding '%s' to medium confidence",
            finding.risk(), finding.title());
        adjusted.add(
            new ReviewResponse.Finding(
                finding.risk(),
                MEDIUM_LABEL,
                finding.file(),
                finding.line(),
                finding.title(),
                finding.description(),
                finding.suggestionOld(),
                finding.suggestionNew()));
        changed = true;
      } else {
        adjusted.add(finding);
      }
    }
    if (!changed) {
      return response;
    }
    return new ReviewResponse(adjusted, response.previousFindingsStatus(), response.summary());
  }

  /**
   * Deterministic severity floor for the one class the review prompt already pins (#570): a finding
   * that names an injection sink AND states that nothing sanitizes, escapes, validates, encodes or
   * parameterizes the value reaching it publishes at no less than "high" risk.
   *
   * <p>Applied to the audit's OUTPUT, so it holds however the level was arrived at — the review
   * call rating the class low in one framework and high in another, or the verifier taking it down
   * because a rendering or query-dialect semantic "is not verifiable here". Both were observed on
   * the same planted defect in two frameworks, published as CRITICAL inline against MEDIUM
   * collapsed; the prompt-side floor alone did not hold, because nothing downstream enforced it.
   *
   * <p>Only risk moves, and only upward: severity is a property of the defect class and its blast
   * radius, while "some layer I was not shown might neutralize this" is a statement about
   * confidence. Confidence is therefore left exactly where the model or the verifier put it — the
   * hedge survives, the misclassification does not. Placement follows: {@code Finding.postsInline}
   * keeps a high-risk finding on an inline thread however low its confidence, so the same defect
   * class stops landing in the collapsed "Things to double-check" block in one framework and on the
   * diff in another.
   *
   * <p>The trigger needs BOTH halves stated in the finding's own words, so it does not fire on a
   * mention of a sink in passing (a sink rendering a literal template) or on an unrelated finding
   * that happens to say "unvalidated". Both halves are token matches, though, so a sentence that
   * RULES THE SINK OUT can satisfy them out of its own negation ("not concatenated but
   * parameterized, so no SQL injection is possible"); two defeaters — a denied sink, and a
   * mitigation the finding says IS present — suppress the floor for exactly those. Both are read on
   * what the finding ASSERTS, with {@linkplain #CONDITIONAL_CLAUSE conditional clauses} removed
   * first: a hypothesis the finding raises and rejects in the same breath is not a statement that
   * the sink is safe, and the review prompt requires that hypothesis on this very class (#608). A
   * critical keeps its level: the floor lifts, never lowers.
   */
  static ReviewResponse floorInjectionSinkRisk(ReviewResponse response) {
    if (response.findings().isEmpty()) {
      return response;
    }
    var adjusted = new ArrayList<ReviewResponse.Finding>(response.findings().size());
    var changed = false;
    for (ReviewResponse.Finding finding : response.findings()) {
      if (!belowInjectionSinkFloor(finding)) {
        adjusted.add(finding);
        continue;
      }
      Log.infof(
          "Raising unmitigated-injection-sink finding '%s' from %s to %s risk; confidence stays %s",
          finding.title(), finding.risk(), HIGH_LABEL, finding.confidence());
      adjusted.add(
          new ReviewResponse.Finding(
              HIGH_LABEL,
              finding.confidence(),
              finding.file(),
              finding.line(),
              finding.title(),
              finding.description(),
              finding.suggestionOld(),
              finding.suggestionNew()));
      changed = true;
    }
    if (!changed) {
      return response;
    }
    return new ReviewResponse(
        adjusted, response.previousFindingsStatus(), recount(response.summary(), adjusted));
  }

  private static boolean belowInjectionSinkFloor(ReviewResponse.Finding finding) {
    // Both enums declare constants most- to least-severe, so a higher ordinal is a lower rating.
    if (RiskLevel.fromString(finding.risk()).ordinal() <= INJECTION_SINK_FLOOR.ordinal()) {
      return false;
    }
    String text =
        (finding.title() == null ? "" : finding.title())
            + "\n"
            + (finding.description() == null ? "" : finding.description());
    // The trigger is read on the whole finding; the defeaters only on what it ASSERTS. A denial or
    // a mitigation raised as a hypothesis the finding then rejects is not a statement that the sink
    // is safe, and the review prompt mandates exactly that hypothesis on this class (#608).
    String asserted = CONDITIONAL_CLAUSE.matcher(text).replaceAll(" ");
    return namesInjectionSink(text)
        && NO_MITIGATION.matcher(text).find()
        // Either defeater anywhere in the finding's assertions suppresses the floor. Under-firing
        // costs a finding the lift it should have had, which is where this class already stood;
        // over-firing escalates a non-defect and, at high confidence, blocks the merge on it.
        && !SINK_DENIED.matcher(asserted).find()
        && !MITIGATION_ASSERTED.matcher(asserted).find();
  }

  private static boolean namesInjectionSink(String text) {
    return INJECTION_SINK.matcher(text).find()
        || (SQL.matcher(text).find() && STRING_BUILT.matcher(text).find());
  }

  private static boolean isBlockingEligible(ReviewResponse.Finding finding) {
    RiskLevel risk = RiskLevel.fromString(finding.risk());
    return (risk == RiskLevel.CRITICAL || risk == RiskLevel.HIGH)
        && Confidence.fromString(finding.confidence()) == Confidence.HIGH;
  }

  private static boolean containsHedging(ReviewResponse.Finding finding) {
    return hedgesTheClaim(finding.title()) || hedgesTheClaim(finding.description());
  }

  /**
   * Whether the finding's own wording hedges the DEFECT, judged clause by clause. A hedge word
   * inside a clause that names something to verify does not hedge the claim — it is the
   * verification request the prompt asks for alongside a defect the material demonstrates, so on
   * its own it must not cost the finding its blocking confidence (#570).
   */
  private static boolean hedgesTheClaim(String text) {
    if (text == null) {
      return false;
    }
    for (String clause : CLAUSE_BOUNDARY.split(text)) {
      if (HEDGING.matcher(clause).find() && !VERIFICATION_CUE.matcher(clause).find()) {
        return true;
      }
    }
    return false;
  }

  /** The shape each candidate is presented in; ids are 1-based positions in the findings list. */
  @RegisterForReflection
  record Candidate(
      int id,
      String risk,
      String confidence,
      String file,
      int line,
      String title,
      String description,
      @JsonProperty("suggestion_old") String suggestionOld,
      @JsonProperty("suggestion_new") String suggestionNew) {

    static Candidate of(int id, ReviewResponse.Finding f) {
      return new Candidate(
          id,
          f.risk(),
          f.confidence(),
          f.file(),
          f.line(),
          f.title(),
          f.description(),
          f.suggestionOld(),
          f.suggestionNew());
    }
  }

  String renderCandidates(List<ReviewResponse.Finding> findings) throws IOException {
    var candidates = new ArrayList<Candidate>(findings.size());
    for (var i = 0; i < findings.size(); i++) {
      candidates.add(Candidate.of(i + 1, findings.get(i)));
    }
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(candidates);
  }

  ReviewResponse apply(ReviewResponse response, VerificationResponse verification) {
    var byId = new HashMap<Integer, VerificationResponse.Verdict>();
    for (VerificationResponse.Verdict verdict : verification.verdicts()) {
      byId.putIfAbsent(verdict.id(), verdict);
    }

    var kept = new ArrayList<ReviewResponse.Finding>();
    var rejected = 0;
    var downgraded = 0;
    for (var i = 0; i < response.findings().size(); i++) {
      var finding = response.findings().get(i);
      var verdict = byId.get(i + 1);
      String decision = verdict != null && verdict.verdict() != null ? verdict.verdict() : "";
      switch (decision.toLowerCase(Locale.ROOT)) {
        case "rejected" -> {
          rejected++;
          Log.infof(
              "Verifier rejected finding '%s' (%s:%d): %s",
              finding.title(), finding.file(), finding.line(), verdict.reason());
        }
        case "downgraded" -> {
          downgraded++;
          kept.add(downgrade(finding, verdict));
        }
        // confirmed, unknown decision, or no verdict at all — fail open and keep the finding
        default -> kept.add(finding);
      }
    }

    if (rejected == 0 && downgraded == 0) {
      return response;
    }
    Log.infof(
        "Finding verification: %d kept, %d downgraded, %d rejected",
        kept.size() - downgraded, downgraded, rejected);
    return new ReviewResponse(
        kept, response.previousFindingsStatus(), recount(response.summary(), kept));
  }

  /** Applies the verdict's risk/confidence, but only ever in the lowering direction. */
  static ReviewResponse.Finding downgrade(
      ReviewResponse.Finding finding, VerificationResponse.Verdict verdict) {
    RiskLevel risk = lowered(RiskLevel.fromString(finding.risk()), verdict.risk());
    Confidence confidence =
        loweredConfidence(Confidence.fromString(finding.confidence()), verdict.confidence());
    return new ReviewResponse.Finding(
        risk.name().toLowerCase(Locale.ROOT),
        confidence.name().toLowerCase(Locale.ROOT),
        finding.file(),
        finding.line(),
        finding.title(),
        finding.description(),
        finding.suggestionOld(),
        finding.suggestionNew());
  }

  /**
   * Both enums declare constants from most to least severe, so higher ordinal = lower rating.
   * Proposed ratings are parsed strictly — the lenient {@code fromString} defaults would turn a
   * garbled verifier label into the lowest rating, silently collapsing a critical finding.
   */
  private static RiskLevel lowered(RiskLevel original, String proposed) {
    RiskLevel candidate = strictRisk(proposed);
    return candidate != null && candidate.ordinal() > original.ordinal() ? candidate : original;
  }

  private static Confidence loweredConfidence(Confidence original, String proposed) {
    Confidence candidate = strictConfidence(proposed);
    return candidate != null && candidate.ordinal() > original.ordinal() ? candidate : original;
  }

  /** Exact known labels only; null for anything else so the caller keeps the original rating. */
  private static RiskLevel strictRisk(String value) {
    if (value == null) {
      return null;
    }
    return switch (value.strip().toLowerCase(Locale.ROOT)) {
      case "critical" -> RiskLevel.CRITICAL;
      case HIGH_LABEL -> RiskLevel.HIGH;
      case MEDIUM_LABEL -> RiskLevel.MEDIUM;
      case "low" -> RiskLevel.LOW;
      default -> null;
    };
  }

  private static Confidence strictConfidence(String value) {
    if (value == null) {
      return null;
    }
    return switch (value.strip().toLowerCase(Locale.ROOT)) {
      case HIGH_LABEL -> Confidence.HIGH;
      case MEDIUM_LABEL -> Confidence.MEDIUM;
      case "low" -> Confidence.LOW;
      default -> null;
    };
  }

  /** Recomputes the summary counts after filtering, preserving the prose fields. */
  public static ReviewResponse.Summary recount(
      ReviewResponse.Summary original, List<ReviewResponse.Finding> findings) {
    if (original == null) {
      return null;
    }
    return new ReviewResponse.Summary(
        findings.size(),
        countRisk(findings, RiskLevel.CRITICAL),
        countRisk(findings, RiskLevel.HIGH),
        countRisk(findings, RiskLevel.MEDIUM),
        countRisk(findings, RiskLevel.LOW),
        original.overallAssessment(),
        original.prPurpose(),
        original.descriptionGaps(),
        original.suggestedLabels(),
        original.fileSummaries(),
        original.walkthroughDiagram());
  }

  private static int countRisk(List<ReviewResponse.Finding> findings, RiskLevel risk) {
    return (int) findings.stream().filter(f -> RiskLevel.fromString(f.risk()) == risk).count();
  }
}
