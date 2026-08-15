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
import dev.thiagogonzaga.thrillhousebot.review.VerificationCoverage;
import io.quarkus.logging.Log;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
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
   * Vulnerability classes named explicitly, per the review prompt's dimension-2 list. Matching one
   * of these is only the first half of the floor's test.
   */
  private static final Pattern INJECTION_SINK =
      Pattern.compile(
          "\\b(xss|cross[- ]site scripting|sql injection|command injection|shell injection"
              + "|path traversal|directory traversal|(unsafe|insecure) deserializ\\w*)\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * The raw-HTML sinks from the same list, named as the API rather than as a class. Read as a union
   * with {@link #INJECTION_SINK} — a finding naming either names a sink — and kept a second pattern
   * only because one alternation of both lists is more than the regex complexity budget allows.
   */
  private static final Pattern HTML_SINK_API =
      Pattern.compile(
          "\\b(dangerouslysetinnerhtml|bypasssecuritytrusthtml|v-html|inner_?html|outerhtml"
              + "|insertadjacenthtml|document\\.write)\\b",
          Pattern.CASE_INSENSITIVE);

  /** String-built SQL, which findings usually name by its shape rather than as "SQL injection". */
  private static final Pattern SQL = Pattern.compile("\\bsql\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern STRING_BUILT =
      Pattern.compile(
          "\\b(concatenat\\w*|interpolat\\w*|string[- ]?built|built from)\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * The finding's own statement that nothing neutralizes the tainted value, worded as an adjective
   * on the value itself. Deliberately keyed on an ABSENCE claim: it is what turns "this code uses
   * innerHTML" into "user data reaches an injection sink unmitigated", which is the class the
   * prompt floors at "high".
   */
  private static final Pattern UNMITIGATED_ADJECTIVE =
      Pattern.compile(
          "\\b(unsanitiz\\w*|unescaped|unvalidated|unparameteriz\\w*|non-parameteriz\\w*)",
          Pattern.CASE_INSENSITIVE);

  /**
   * The same absence claim worded as a missing step ("no sanitization", "never escaped"), with room
   * for a few words between the negator and the neutralizing verb. Read as a union with {@link
   * #UNMITIGATED_ADJECTIVE} and the pronoun-subject wording {@link #claimsAbsenceAsSubject
   * recognizes} in its finite and modal forms; the wordings are one claim recognized separately
   * only because one alternation of every wording is more than the regex complexity budget allows.
   */
  private static final Pattern MITIGATION_ABSENT =
      Pattern.compile(
          "\\b(no|not|without|missing|never|lacks?|absent)\\s+(\\w+[\\s-]+){0,3}"
              + "(sanitiz|escap|validat|parameteriz|encod)\\w*",
          Pattern.CASE_INSENSITIVE);

  /**
   * A neutralizing verb in its finite or participle forms — the verb of the pronoun-subject absence
   * claim {@link #claimsAbsenceAsSubject} recognizes ("Nothing sanitizes the value", "nobody
   * escapes it before render"). The verb is held to these forms because a defense noun in the same
   * stems must never re-match as the verb through the subject gap. One token of a three-pattern
   * read: {@link #NEGATING_SUBJECT_BEFORE_VERB} supplies the subject, {@link
   * #DEFENSE_OBJECT_AFTER_VERB} the direct-object exclusion — one wording held in three patterns
   * only because the single pattern that spelled all three out (subject, gap, verb and a trailing
   * lookahead) was more than the regex complexity budget allows.
   */
  private static final Pattern ABSENCE_SUBJECT_VERB =
      Pattern.compile(
          "\\b(sanitiz|escap|validat|parameteriz|encod)(es|ed|ing)\\b", Pattern.CASE_INSENSITIVE);

  /**
   * The same verb token with a modal ("Nothing <i>can sanitize</i> the value", "nobody <i>could
   * escape</i> it in time"): {@link #ABSENCE_SUBJECT_VERB}'s finite-only suffix group never sees
   * the bare infinitive a modal takes, so this common wording read as no absence claim at all
   * (#696). Under-fire is the safe direction, but not a reason to keep a hole this common. {@link
   * #claimsAbsenceAsSubject} holds it to the same subject prefix and the same defense-noun-object
   * rejection, for the same reason: "nothing can escape validation" says every value IS validated.
   * One non-negator gap word is admitted between the modal and the verb, so an adverb ("Nothing can
   * ever sanitize") does not hide the claim — mirroring {@link #MITIGATION_DO_SUPPORTED}'s adverb
   * gap.
   */
  private static final Pattern ABSENCE_MODAL_VERB =
      Pattern.compile(
          "\\b(can|could|will|would|may|might|must)\\s+(?!(no|not|never)\\b)(\\w+\\s+)?"
              + "(sanitiz|escap|validat|parameteriz|encod)e\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * The negating pronoun subject within three words of the text BEFORE a {@link
   * #ABSENCE_SUBJECT_VERB} match — anchored to that boundary with {@code \z}, it is the verbatim
   * subject-and-gap prefix of the one-pattern form. {@link #MITIGATION_ASSERTED_SUBJECT} excludes
   * the same pronouns from its subject slot, and {@link #assertsMitigation} drops an
   * auxiliary-order match they precede ("Nothing is sanitized"), so the two never read one sentence
   * both ways.
   */
  private static final Pattern NEGATING_SUBJECT_BEFORE_VERB =
      Pattern.compile("\\b(nothing|nobody)\\s+(\\w+[\\s-]+){0,3}\\z", Pattern.CASE_INSENSITIVE);

  /**
   * A defense-stemmed word within two words AFTER the verb — the verbatim body of the one-pattern
   * form's trailing negative lookahead, applied by {@link #claimsAbsenceAsSubject} at the match end
   * via {@code lookingAt}. A defense noun as the verb's direct object flips the sentence's meaning
   * — "Nothing escapes validation" says every value IS validated — so a modified or tool-named
   * object ("nothing escapes heavy validation", "nothing escapes the sanitizer") is rejected too,
   * keeping the over-fire direction closed (#594) while "nothing escapes the value" stays an
   * absence claim — a defense noun further than two words out ("nothing escapes the value before
   * validation") no longer flips the reading. The defense stems match the verb group plus "filter"
   * — "parameteriz" included, so "nothing escapes parameterization" reads as the mitigation it
   * asserts (#696) — and the separator admits clause-break punctuation (semicolon, colon, dashes)
   * besides whitespace, so a defense noun across a clause break ("Nothing escapes; the sanitizer
   * runs on render") still flips the reading. The comma and sentence-ending punctuation are
   * deliberately excluded: a comma carries coordination ("Nothing escapes, sanitizes, or validates
   * the value") and appositive denials ("Nothing escapes, but the sanitizer is disabled"), both of
   * which must stay absence claims, and a defense noun in the NEXT sentence must not defuse this
   * sentence's absence claim. A comma-coordinated ASSERTED mitigation ("Nothing escapes, but the
   * sanitizer runs on render") keeps its absence claim registered here by the same exclusion —
   * {@link #hasUndeniedDefenseAction} reads the follow-up clause and defeats the floor instead
   * (#696). Accepted residual, the safe under-fire direction: a DENIED defense across the clause
   * break ("Nothing escapes; the sanitizer is disabled") still flips the reading, because a regex
   * cannot carry the denial back over the punctuation.
   */
  private static final Pattern DEFENSE_OBJECT_AFTER_VERB =
      Pattern.compile(
          "[\\s;:\\u2013\\u2014-]+(\\w+[\\s;:\\u2013\\u2014-]+){0,2}"
              + "(sanitiz|escap|validat|parameteriz|encod|filter)",
          Pattern.CASE_INSENSITIVE);

  /**
   * A finding that RULES THE SINK OUT rather than reporting it ("so there is no SQL injection",
   * "not vulnerable to XSS"). Both floor signals are token matches, so such a sentence satisfies
   * them out of its own negation — the sink name sits inside the denial, and a phrase like "not
   * concatenated but parameterized" matches the absence group on the very mitigation it asserts.
   * The gap is one word, so an assertion of the defect that merely contains a negation ("no
   * protection against SQL injection") is not mistaken for a denial — and the one word it does
   * admit must not be a bridge verb ("does not prevent SQL injection", "never blocks XSS"), which
   * asserts the defect rather than denying the sink. That exclusion lives in {@link
   * #deniesTheSink}, which drops a match whose gap word is a {@link #BRIDGE_VERB_GAP} — folding it
   * into this pattern as a lookahead puts it over the regex complexity budget — while "no SQL
   * injection here" stays a denial.
   */
  private static final Pattern SINK_DENIED =
      Pattern.compile(
          "\\b(no|not|never)\\s+(\\w+[\\s-]+)?"
              + "(xss|cross[- ]site scripting|sql injection|command injection|shell injection"
              + "|path traversal|directory traversal)\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * A bridge verb filling {@link #SINK_DENIED}'s one-word gap ("does not <i>prevent</i> SQL
   * injection"): the negation then targets the missing defense, not the sink, so the sentence
   * asserts the defect and must not read as a denial. The stems are the warding-off family
   * (prevent, stop, block, guard, protect, defend, prohibit, forbid, disallow, preclude, thwart,
   * hinder, impede); an adjective or quantifier gap ("no possible SQL injection") stays a denial.
   * Matched against the gap group whole, so a bridge verb elsewhere in the finding changes nothing.
   * Read as a union with {@link #NEUTRALIZING_VERB_GAP}; one family per pattern only because one
   * alternation of both is more than the regex complexity budget allows.
   */
  private static final Pattern BRIDGE_VERB_GAP =
      Pattern.compile(
          "(prevent|stop|block|guard|protect|defend|prohibit|forbid|disallow|preclude|thwart"
              + "|hinder|impede)\\w*[\\s-]+",
          Pattern.CASE_INSENSITIVE);

  /**
   * The bridge verbs that remove the defect (mitigate, eliminate, avoid, fix, address, handle) or
   * neutralize the value (sanitize, escape, validate, filter, neutralize); the second half of
   * {@link #BRIDGE_VERB_GAP}'s union.
   */
  private static final Pattern NEUTRALIZING_VERB_GAP =
      Pattern.compile(
          "(mitigat|eliminat|avoid|fix|address|handle|sanitiz|escap|validat|filter|neutraliz)"
              + "\\w*[\\s-]+",
          Pattern.CASE_INSENSITIVE);

  /**
   * The same denial worded about the exposure rather than the class ("not exploitable", "no
   * vulnerability here"). Read as a union with {@link #SINK_DENIED}, from which it is separated
   * only because one alternation of both wordings is more than the regex complexity budget allows.
   */
  private static final Pattern EXPLOITABILITY_DENIED =
      Pattern.compile(
          "\\b(no|not|never)\\s+(\\w+[\\s-]+)?(vulnerab|exploitab)\\w*\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * A finding that states the mitigation IS present ("it is escaped on render"), worded on a
   * be-copula. An absence claim about one layer must not floor the class when the same text says
   * another layer neutralizes the value. Negated forms are excluded, so "is not escaped" and "was
   * never sanitized" stay absence claims instead of defeating themselves. This auxiliary-order
   * wording cannot see its own subject, so a pronoun-negated subject ("Nothing is sanitized") is
   * excluded in {@link #assertsMitigation}, which drops a match directly preceded by a {@link
   * #NEGATING_SUBJECT} — folding that in as lookbehinds would push the pattern over the regex
   * complexity budget.
   *
   * <p>Read as a union with {@link #MITIGATION_ASSERTED_GET} and {@link
   * #MITIGATION_ASSERTED_SUBJECT}: one assertion split across three patterns only because one
   * alternation of every auxiliary and the subject-slot wording is more than the regex complexity
   * budget allows — and none of the copulas, verb forms or the one-word gap may be dropped to buy
   * it back, since each narrows what it matches (#594, #608).
   */
  private static final Pattern MITIGATION_ASSERTED_BE =
      Pattern.compile(
          "\\b(is|are|was|were)\\s+(?!(no|not|never)\\b)(\\w+[\\s-]+)?"
              + "(sanitiz|escap|validat|parameteriz|encod)(ed|es|ing)\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * The same auxiliary-order assertion on its remaining auxiliaries — the get-passive ("gets
   * escaped"), the bare perfect ("been sanitized") and the copula-elided adverb ("already
   * validated"); the second leg of {@link #MITIGATION_ASSERTED_BE}'s union.
   */
  private static final Pattern MITIGATION_ASSERTED_GET =
      Pattern.compile(
          "\\b(gets|been|already)\\s+(?!(no|not|never)\\b)(\\w+[\\s-]+)?"
              + "(sanitiz|escap|validat|parameteriz|encod)(ed|es|ing)\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * The same assertion worded with the mitigating layer in the subject slot ("React escapes them");
   * the third leg of {@link #MITIGATION_ASSERTED_BE}'s union. The negating pronouns are excluded
   * from the subject slot so a pronoun-worded absence claim never reads as a mitigation. The
   * coordinators stay IN the subject slot — a conjoined verb pair ("the framework renders the
   * output and escapes it") has no other subject-verb match when its first verb is not
   * defense-listed, and dropping "and escapes" would over-fire the floor on an asserted mitigation
   * (#696). A coordinator continuing a pronoun-negated verb chain instead ("Nothing escapes,
   * sanitizes, or validates the value") is dropped in {@link #hasUnnegatedAssertedMatch} by the
   * {@link #continuesAbsenceChain} check, which reads the chain back to its negated subject.
   */
  private static final Pattern MITIGATION_ASSERTED_SUBJECT =
      Pattern.compile(
          "\\b(?!(no|not|never|nothing|nobody)\\b)\\w+\\s+"
              + "(sanitizes|escapes|validates|parameterizes|encodes)\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * One link of the pronoun-negated defense-verb chain closing the text before a
   * coordinator-subject {@link #MITIGATION_ASSERTED_SUBJECT} match: in "Nothing escapes, sanitizes,
   * <i>or validates</i> the value", the "or validates" pair continues the ONE absence claim the
   * chain opened, so it must not read as a mitigation — while "the framework renders the output
   * <i>and escapes</i> it" has no such chain before its coordinator and stays the asserted
   * mitigation it is. {@link #continuesAbsenceChain} walks these links back from the coordinator
   * until {@link #NEGATING_SUBJECT_BEFORE_VERB} confirms the negated subject; one link per pattern,
   * end-anchored like {@link #NEGATING_SUBJECT}, only because the single pattern that spelled the
   * whole chain out was more than the regex complexity budget allows. Consulted only for a match
   * whose subject slot holds a {@link #COORDINATOR_SUBJECT}, so a real subject earlier in the
   * sentence is never chained away: a clause with its own subject ("Nothing escapes, the framework
   * sanitizes, or validates ...") breaks the link run — the non-defense word before its verb ends
   * the walk, and the subject-verb pair itself already defeats the floor as a mitigation. Accepted
   * residual, the safe under-fire direction: a chain verb carrying its own object ("Nothing
   * escapes, sanitizes the value, or validates ...") also breaks the run, so its trailing
   * coordinator pair reads as a mitigation and the floor stays silent — carrying objects through
   * the links is an open-ended grammar problem, and the absence claim was already registered by the
   * chain's earlier verbs.
   */
  private static final Pattern ABSENCE_CHAIN_LINK =
      Pattern.compile(
          "\\b(sanitiz|escap|validat|parameteriz|encod)\\w*[,\\s]+\\z", Pattern.CASE_INSENSITIVE);

  /**
   * The coordinator subject that hands a {@link #MITIGATION_ASSERTED_SUBJECT} match to {@link
   * #continuesAbsenceChain}. Matched against the match's own span, anchored at its start.
   */
  private static final Pattern COORDINATOR_SUBJECT =
      Pattern.compile("^(and|or|nor)\\b", Pattern.CASE_INSENSITIVE);

  /**
   * A pronoun subject that negates the clause it opens: a {@link #MITIGATION_ASSERTED_BE} or {@link
   * #MITIGATION_ASSERTED_GET} match starting right after one ("Nothing <i>is sanitized</i> before
   * render") is the absence claim in auxiliary order, not a mitigation. Anchored to the end of the
   * text before the match, so a pronoun elsewhere in the finding changes nothing.
   */
  private static final Pattern NEGATING_SUBJECT =
      Pattern.compile("\\b(nothing|nobody)\\s*$", Pattern.CASE_INSENSITIVE);

  /**
   * The mitigation asserted with do-support ("the framework does escape the value", "React did
   * sanitize it"): emphatic, but still a statement of fact, and invisible to the copula and
   * subject-slot patterns {@link #MITIGATION_ASSERTED_BE}, {@link #MITIGATION_ASSERTED_GET} and
   * {@link #MITIGATION_ASSERTED_SUBJECT}. One gap word is admitted between the auxiliary and the
   * verb, so an emphatic adverb ("does always escape") does not hide the assertion (#696); the gap
   * word must not be a negator, so "does not escape" and "did never sanitize" stay absence claims.
   * Modals are deliberately absent — "should escape" is a recommendation, not an assertion. Kept a
   * separate pattern because folding it into the others would put them back over the regex
   * complexity budget.
   */
  private static final Pattern MITIGATION_DO_SUPPORTED =
      Pattern.compile(
          "\\b(do|does|did)\\s+(?!(no|not|never)\\b)(\\w+\\s+)?"
              + "(sanitiz|escap|validat|parameteriz|encod)es?\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * The mitigation asserted as the defense itself operating ("the sanitizer runs on render", "the
   * validation applies to every request"): a defense-stemmed subject with an operate-family verb
   * after it. Invisible to every other mitigation pattern — the clause has no auxiliary and its
   * verb is not in the subject-slot list — and exactly the wording a comma-coordinated follow-up
   * takes ("Nothing escapes, but the sanitizer runs on render"), where {@link
   * #DEFENSE_OBJECT_AFTER_VERB}'s comma exclusion keeps the absence claim registered, so without
   * this pattern the floor over-fired on a sentence that asserts the defense runs (#696). One gap
   * word is admitted between the subject and the verb, mirroring {@link #MITIGATION_DO_SUPPORTED}'s
   * adverb gap, so "the sanitizer always runs" is not hidden by its adverb; the gap word must not
   * be a negator, so "the sanitizer never runs" stays an absence statement, and {@link
   * #hasUndeniedDefenseAction} drops a match preceded by a {@link #NEGATING_DETERMINER}, so "no
   * sanitizer runs" stays one too. Held in two patterns — {@link #DEFENSE_ACTION_SUBJECT} is the
   * subject-and-gap prefix, end-anchored before this verb token, with the gap word captured so the
   * negator exclusion moves into {@link #hasUndeniedDefenseAction} — only because the single
   * pattern that spelled both out was more than the regex complexity budget allows.
   */
  private static final Pattern DEFENSE_ACTION_VERB =
      Pattern.compile(
          "\\b(runs?|ran|running|executes?|executed|applies|applied|fires?|fired)\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * The defense-stemmed subject and its one-word gap before a {@link #DEFENSE_ACTION_VERB} token,
   * end-anchored like {@link #NEGATING_SUBJECT_BEFORE_VERB}; the gap word is captured for the
   * negator exclusion in {@link #hasUndeniedDefenseAction}.
   */
  private static final Pattern DEFENSE_ACTION_SUBJECT =
      Pattern.compile(
          "\\b(sanitiz|escap|validat|parameteriz|encod|filter)\\w*\\s+((\\w+)\\s+)?\\z",
          Pattern.CASE_INSENSITIVE);

  /** The gap word that turns a defense-action pair into its own denial ("sanitizer never runs"). */
  private static final Pattern DEFENSE_ACTION_NEGATOR_GAP =
      Pattern.compile("no|not|never", Pattern.CASE_INSENSITIVE);

  /**
   * A negating determiner or preposition closing the text before a {@link #DEFENSE_ACTION_SUBJECT}
   * match ("<i>no</i> sanitizer runs", "<i>without</i> escaping applied"), with room for two
   * modifiers ("no working sanitizer runs", "not a single sanitizer runs"): the clause then states
   * the defense does NOT operate and must not read as a mitigation. Anchored to the end of the text
   * before the match, like {@link #NEGATING_SUBJECT}. Accepted residual, matching pre-#696
   * behavior: an idiomatic negator ("no doubt the sanitizer runs", "not to mention") also satisfies
   * this pattern and denies a genuinely asserted mitigation — exempting idioms is an open-ended
   * lexicon problem a bounded regex cannot close.
   */
  private static final Pattern NEGATING_DETERMINER =
      Pattern.compile(
          "\\b(no|not|never|without|nor)\\s+(\\w+\\s+){0,2}\\z", Pattern.CASE_INSENSITIVE);

  /**
   * A conditional clause — a hypothesis the finding raises, not a fact it states. Matches from the
   * conditional marker to the end of that clause, so only the hypothetical span is removed and
   * whatever the finding asserts around it survives.
   *
   * <p>Both floor defeaters are assertion tests, and neither regex can carry mood: "If the feedback
   * API sanitizes body on write, the exploit is neutralized" satisfies {@link
   * #MITIGATION_ASSERTED_SUBJECT} on the token pair "API sanitizes" even though the sentence goes
   * on to reject the hypothesis ("but a sanitizer you cannot see is not a sanitizer"). That
   * phrasing is not incidental — #575's review prompt REQUIRES a demonstrated-sink finding whose
   * mitigating layer was not shown to name the exact layer to verify, so the instruction
   * manufactures the wording the defeater then reads as a mitigation (#608). Scoping to the clause
   * is the same narrowing the hedging scan already needed for the same reason.
   *
   * <p>The clause ends at a real clause boundary — strong punctuation, or a coordinator that opens
   * the consequent ("but", "so", "then"...) — and deliberately NOT at a comma. A coordinator
   * immediately followed by a comma is a parenthetical still inside the protasis ("If, however, the
   * API always sanitizes the body on write, ...") and does not end the span; stopping there left
   * the mitigation verb in text read as asserted, the under-firing this pattern exists to remove. A
   * protasis carries its own commas ("If the feedback API, per its own contract, always sanitizes
   * the body on write, ..."), and stopping at the first one left the mitigation verb sitting in
   * text read as asserted, which is the very under-firing this pattern exists to remove. The
   * coordinator boundary is what keeps the consequent: "If it were stored as plain text this would
   * be moot, but React escapes it at render" still asserts its mitigation, and that assertion must
   * still defeat the floor, since over-firing the floor is the dangerous direction (#594).
   *
   * <p>Residual trade-off, chosen deliberately: a consequent separated by a bare comma and nothing
   * else ("If you follow the render path, React escapes the value") is swallowed with the protasis,
   * so its mitigation no longer defeats the floor — and so is a comma-spliced consequent whose
   * coordinator itself trails a comma ("this would be moot, however, React escapes it"), since a
   * regex cannot tell that parenthetical from the one inside the protasis. In the other direction,
   * a coordinator word used adverbially without a following comma ("If, however unlikely, ..." or
   * "If, but only if, ...") still ends the span early and can leave a protasis verb read as
   * asserted: recognizing comma-delimited parentheticals in general needs pairing the delimiters,
   * which a bounded regex cannot do, and that phrasing is rare in review findings — accepted as
   * residual under-fire, the safe direction. Commas cannot serve both roles, and this is the safer
   * half — the finding must ALSO name a sink and ALSO claim nothing sanitizes it before the floor
   * can fire at all, and the floor only lifts risk, leaving confidence and every other signal where
   * the model put them.
   *
   * <p>Plain "should" is NOT a marker. Only inverted "Should the API sanitize ..." is a hypothesis,
   * and its bare infinitive matches none of the mitigation-asserted patterns' verb forms, so
   * listing it bought nothing — while "It should be noted that the API sanitizes body on write" is
   * an assertion, and treating it as a hypothesis hid a real mitigation from the defeater.
   */
  private static final Pattern CONDITIONAL_CLAUSE =
      Pattern.compile(
          "\\b(if|unless|whether|in case|assuming|provided that)\\b"
              + "(?:(?!\\b(but|so|then|however|therefore|otherwise)\\b(?!,))"
              + "[^;:.!?\\n\\r\\u2013\\u2014])*",
          Pattern.CASE_INSENSITIVE);

  /** The floor an unmitigated-injection-sink finding may never publish below (#570). */
  private static final RiskLevel INJECTION_SINK_FLOOR = RiskLevel.HIGH;

  private static final String HIGH_LABEL = "high";

  private static final String MEDIUM_LABEL = "medium";

  /** The verifier response's only field, and the array salvage keys on when the body is cut. */
  private static final String VERDICTS = "verdicts";

  /**
   * The decision labels the audit acts on, and so the only ones {@link #candidatesCovered} counts
   * as a screened candidate. Read through {@link #decisionOf} by both that count and {@link
   * #apply}'s switch, so what the review DOES with a verdict and what it CLAIMS it verified cannot
   * drift (#710): every other label — absent, blank, or a word this service does not recognize —
   * lands in {@code apply}'s fail-open default, where the finding posts exactly as the reviewer
   * raised it. The strictness matches {@link #strictRisk}/{@link #strictConfidence}: an
   * uninterpretable label from model output is treated as absent rather than guessed at.
   */
  private static final Set<String> ACTED_ON_DECISIONS =
      Set.of("confirmed", "downgraded", "rejected");

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
    return verify(ledgerSessionId, response, diff, projectStack, previousFindings, coverage -> {});
  }

  /**
   * The {@link #verify(long, ReviewResponse, String, String, String)} variant that also reports how
   * much of the candidate set the verifier actually covered, so the posted review can disclose an
   * unverified or partially verified finding set instead of leaving that state log-only (#623). The
   * sink receives exactly one {@link VerificationCoverage} per attempted verification — nothing is
   * reported when the verifier is disabled or there is nothing to verify — and the fail-open
   * contract is untouched: coverage changes what the review says, never which findings it keeps.
   */
  public ReviewResponse verify(
      long ledgerSessionId,
      ReviewResponse response,
      String diff,
      String projectStack,
      String previousFindings,
      Consumer<VerificationCoverage> coverageSink) {
    return floorInjectionSinkRisk(
        audit(ledgerSessionId, response, diff, projectStack, previousFindings, coverageSink));
  }

  /** The audit itself; {@link #verify} applies the deterministic severity floor to its result. */
  private ReviewResponse audit(
      long ledgerSessionId,
      ReviewResponse response,
      String diff,
      String projectStack,
      String previousFindings,
      Consumer<VerificationCoverage> coverageSink) {
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
      coverageSink.accept(new VerificationCoverage(screened.findings().size(), 0));
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
        coverageSink.accept(new VerificationCoverage(screened.findings().size(), 0));
        return screened;
      }
      var verification = parseOrSalvage(raw, screened.findings().size());
      var applied = apply(screened, verification);
      // Accepted only after apply() succeeded: the fail-open catch below records (N, 0) for any
      // failure, so recording before it could double this attempt's candidates if apply threw.
      coverageSink.accept(
          new VerificationCoverage(
              screened.findings().size(),
              (int) candidatesCovered(verification.verdicts(), screened.findings().size())));
      return applied;
    } catch (AiResponseTruncatedException e) {
      return salvageTruncatedVerdicts(screened, e, coverageSink);
    } catch (IOException | RuntimeException e) {
      Log.warnf(
          e,
          "Finding verification failed — keeping the %d unverified finding(s)",
          screened.findings().size());
      coverageSink.accept(new VerificationCoverage(screened.findings().size(), 0));
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
      ReviewResponse screened,
      AiResponseTruncatedException e,
      Consumer<VerificationCoverage> coverageSink) {
    var candidates = screened.findings().size();
    var salvaged =
        salvager.salvageArray(e.partialBody(), VERDICTS, VerificationResponse.Verdict.class);
    if (salvaged.isEmpty()) {
      // The verifier fails open by design; say why so a cap is not mistaken for a provider fault.
      Log.warnf("Finding verification — keeping unverified findings. %s", e.getMessage());
      coverageSink.accept(new VerificationCoverage(candidates, 0));
      return screened;
    }
    var verified = candidatesCovered(salvaged, candidates);
    Log.warnf(
        "Finding verification was cut at the model's response-length cap; salvaged %d verdict(s)"
            + " covering %d of %d candidate finding(s) — the remaining %d stay unverified. %s",
        salvaged.size(), verified, candidates, candidates - verified, e.getMessage());
    var applied = apply(screened, new VerificationResponse(salvaged));
    coverageSink.accept(new VerificationCoverage(candidates, (int) verified));
    return applied;
  }

  /**
   * How many distinct candidates the verdicts actually screened — the {@code verified} half of the
   * {@link VerificationCoverage} every lane reports, and so what the published review claims a
   * second stage ruled on (#623). Salvage is best-effort over model output, so a verdict can carry
   * an id outside the 1-based candidate range; counting only the ids in range keeps the log honest
   * about what stayed unverified.
   *
   * <p>An id alone is not coverage. A verdict whose decision label is absent, blank or not one of
   * {@link #ACTED_ON_DECISIONS} falls into {@link #apply}'s fail-open default, where the candidate
   * posts exactly as the reviewer raised it — the same state a candidate with no verdict at all
   * ends in — so counting it screened published an unverified finding inside a set the review
   * called fully verified (#710). That is the harm #623 exists to prevent, and it is the dangerous
   * direction: the empty-body path keeps every finding unverified and says so, while a set that
   * mixes verified and unverified findings and reads as fully screened tells the reader nothing is
   * outstanding. Undercounting instead only costs an over-cautious clause on a finding the audit
   * did rule on in words this service cannot read.
   *
   * <p>Both cut lanes (#546/#617) measure through here, which settles the two edges a cut can land
   * on. Nothing decidable salvaged reports {@code (N, 0)} — {@link
   * VerificationCoverage.Outcome#NONE}, the same disclosure the empty-body path renders, because
   * the findings are in the same state: the verifier ruled on none of them. A cut whose salvage
   * covers every candidate reports {@code (N, N)} — genuinely {@link
   * VerificationCoverage.Outcome#FULL}, and deliberately silent. That is not a hypothetical shape:
   * a body cut after the verdicts array closed raises Jackson's "expected close marker for Object"
   * against the ROOT object, and every verdict in it is complete. What the cut destroyed there is
   * the body's tail — the closing punctuation, or elements for ids that do not exist — so every
   * finding did receive a decision the audit applied, and disclosing a gap would tell the reader a
   * fully screened set was not screened. The cut is logged either way, so an operator chasing the
   * response-length cap keeps the signal the reader does not need.
   */
  private static long candidatesCovered(
      List<VerificationResponse.Verdict> salvaged, int candidates) {
    return salvaged.stream()
        .filter(verdict -> ACTED_ON_DECISIONS.contains(decisionOf(verdict)))
        .mapToInt(VerificationResponse.Verdict::id)
        .filter(id -> id >= 1 && id <= candidates)
        .distinct()
        .count();
  }

  /**
   * The verdict's decision, normalized the one way both {@link #apply} and {@link
   * #candidatesCovered} read it; the empty string for a candidate with no verdict and for a verdict
   * carrying no label. One reader so the two cannot disagree about what counts as a decision — the
   * drift #710 was filed on.
   */
  private static String decisionOf(VerificationResponse.Verdict verdict) {
    return verdict == null || verdict.verdict() == null
        ? ""
        : verdict.verdict().toLowerCase(Locale.ROOT);
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
        && claimsNothingNeutralizesIt(text)
        // Either defeater anywhere in the finding's assertions suppresses the floor. Under-firing
        // only costs a finding the lift it should have had (which is where this class already
        // stood), while over-firing escalates a non-defect and, at high confidence, blocks the
        // merge on it.
        && !deniesTheSink(asserted)
        && !assertsMitigation(asserted);
  }

  /**
   * A hit from any of the mitigation-asserted patterns or {@link #MITIGATION_DO_SUPPORTED}, unless
   * the match opens with a negating pronoun subject: "Nothing is sanitized" is the absence claim in
   * auxiliary order, and {@link #MITIGATION_ASSERTED_BE} and {@link #MITIGATION_ASSERTED_GET} start
   * at the auxiliary so they never see the subject.
   */
  private static boolean assertsMitigation(String asserted) {
    return hasUnnegatedAssertedMatch(asserted)
        || hasUnnegatedMatch(MITIGATION_DO_SUPPORTED, asserted)
        || hasUndeniedDefenseAction(asserted);
  }

  /**
   * A defense-action pair — a {@link #DEFENSE_ACTION_VERB} token whose text up to the token
   * satisfies {@link #DEFENSE_ACTION_SUBJECT} — unless its gap word is a {@link
   * #DEFENSE_ACTION_NEGATOR_GAP} ("the sanitizer never runs") or a {@link #NEGATING_DETERMINER}
   * closes the text before the subject ("no sanitizer runs"): "the sanitizer runs on render"
   * asserts the defense operates, while both negated forms state it does not. Regions instead of
   * substrings, like every other before-the-match check here.
   */
  private static boolean hasUndeniedDefenseAction(String asserted) {
    Matcher verb = DEFENSE_ACTION_VERB.matcher(asserted);
    Matcher subject = DEFENSE_ACTION_SUBJECT.matcher(asserted);
    Matcher denial = NEGATING_DETERMINER.matcher(asserted);
    while (verb.find()) {
      if (subject.region(0, verb.start()).find()) {
        String gap = subject.group(3);
        if ((gap == null || !DEFENSE_ACTION_NEGATOR_GAP.matcher(gap).matches())
            && !denial.region(0, subject.start()).find()) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * {@link #hasUnnegatedMatch} over the three mitigation-asserted patterns walked as ONE
   * alternation: at each step the leftmost match across the three wins — a position tie in the
   * patterns' declared order — and scanning resumes after it, exactly where the single pattern's
   * own scan resumed. Checking the patterns one whole pass at a time instead would also surface
   * matches the one-alternation scan stepped over, and a defeater must not fire on text its
   * one-pattern form never read as a mitigation.
   */
  private static boolean hasUnnegatedAssertedMatch(String asserted) {
    Matcher[] wordings = {
      MITIGATION_ASSERTED_BE.matcher(asserted),
      MITIGATION_ASSERTED_GET.matcher(asserted),
      MITIGATION_ASSERTED_SUBJECT.matcher(asserted)
    };
    Matcher negation = NEGATING_SUBJECT.matcher(asserted);
    var starts = new int[] {-1, -1, -1};
    var ends = new int[wordings.length];
    var from = 0;
    while (true) {
      var leftmost = leftmostWording(wordings, starts, ends, from);
      if (leftmost == -1) {
        return false;
      }
      // A region instead of a substring, so dropping a negated match never copies the text; the
      // pattern's $ honors the region end under the matcher's default anchoring bounds.
      if (!negation.region(0, starts[leftmost]).find()
          && !continuesAbsenceChain(asserted, starts[leftmost], ends[leftmost])) {
        return true;
      }
      // No wording matches an empty string, so the scan always advances.
      from = ends[leftmost];
      starts[leftmost] = -1;
    }
  }

  /**
   * Whether a mitigation-asserted match is really the tail of a coordinated absence claim: its
   * subject slot holds a {@link #COORDINATOR_SUBJECT} and an unbroken run of {@link
   * #ABSENCE_CHAIN_LINK}s walks back from it to a {@link #NEGATING_SUBJECT_BEFORE_VERB}, as in
   * "Nothing escapes, sanitizes, <i>or validates</i> the value". Each link starts strictly before
   * the previous one, so the walk always terminates.
   */
  private static boolean continuesAbsenceChain(String asserted, int start, int end) {
    if (!COORDINATOR_SUBJECT.matcher(asserted).region(start, end).lookingAt()) {
      return false;
    }
    Matcher link = ABSENCE_CHAIN_LINK.matcher(asserted);
    Matcher negated = NEGATING_SUBJECT_BEFORE_VERB.matcher(asserted);
    var at = start;
    while (link.region(0, at).find()) {
      at = link.start();
      if (negated.region(0, at).find()) {
        return true;
      }
    }
    return false;
  }

  /** A wording with no further matches; loses every comparison for the leftmost slot. */
  private static final int NO_MORE_MATCHES = Integer.MAX_VALUE;

  /**
   * The index of the wording holding the leftmost live candidate at or past {@code from}, or -1
   * when every wording is out of matches; a position tie keeps the lowest index, the patterns'
   * declared order. Only a consumed or overtaken candidate is re-found — one at or past {@code
   * from} is still that wording's next match, since the find that produced it proved nothing of
   * that wording starts before it — so the walk stays a single pass per wording instead of
   * rescanning all three from every resume point.
   */
  private static int leftmostWording(Matcher[] wordings, int[] starts, int[] ends, int from) {
    var leftmost = -1;
    var best = NO_MORE_MATCHES;
    for (var i = 0; i < wordings.length; i++) {
      if (starts[i] < from) {
        if (wordings[i].find(from)) {
          starts[i] = wordings[i].start();
          ends[i] = wordings[i].end();
        } else {
          starts[i] = NO_MORE_MATCHES;
        }
      }
      if (starts[i] < best) {
        best = starts[i];
        leftmost = i;
      }
    }
    return leftmost;
  }

  private static boolean hasUnnegatedMatch(Pattern mitigation, String asserted) {
    Matcher asserts = mitigation.matcher(asserted);
    Matcher negation = NEGATING_SUBJECT.matcher(asserted);
    while (asserts.find()) {
      if (!negation.region(0, asserts.start()).find()) {
        return true;
      }
    }
    return false;
  }

  private static boolean namesInjectionSink(String text) {
    return INJECTION_SINK.matcher(text).find()
        || HTML_SINK_API.matcher(text).find()
        || (SQL.matcher(text).find() && STRING_BUILT.matcher(text).find());
  }

  /** The absence claim in any of its four wordings; one claim, three recognizers. */
  private static boolean claimsNothingNeutralizesIt(String text) {
    return UNMITIGATED_ADJECTIVE.matcher(text).find()
        || MITIGATION_ABSENT.matcher(text).find()
        || claimsAbsenceAsSubject(text);
  }

  /**
   * The absence claim worded with a pronoun subject, finite ("Nothing sanitizes the value") or
   * modal ("Nothing can sanitize the value", #696): an {@link #ABSENCE_SUBJECT_VERB} or {@link
   * #ABSENCE_MODAL_VERB} token whose text up to the token satisfies {@link
   * #NEGATING_SUBJECT_BEFORE_VERB} and whose text from the token's end does not open on a {@link
   * #DEFENSE_OBJECT_AFTER_VERB}. Checking every verb token against an anchored prefix and an
   * anchored trailer decides exactly the parses the one-pattern form decided through backtracking
   * and its trailing lookahead — including the parse where a defense noun flips an earlier verb's
   * reading while a later verb in the same subject gap stays clean — so the split changes what the
   * analyzer counts, not what the recognizer accepts.
   */
  private static boolean claimsAbsenceAsSubject(String text) {
    return claimsAbsenceOnVerbToken(ABSENCE_SUBJECT_VERB, text)
        || claimsAbsenceOnVerbToken(ABSENCE_MODAL_VERB, text);
  }

  private static boolean claimsAbsenceOnVerbToken(Pattern verbToken, String text) {
    Matcher verb = verbToken.matcher(text);
    // Regions instead of substrings, so stepping through the verb tokens never copies the text;
    // with the matchers' default opaque and anchoring bounds a region IS the whole input to the
    // pattern, so {@code \z} stops at the region end and the semantics stay those of a substring.
    Matcher subject = NEGATING_SUBJECT_BEFORE_VERB.matcher(text);
    Matcher object = DEFENSE_OBJECT_AFTER_VERB.matcher(text);
    while (verb.find()) {
      if (subject.region(0, verb.start()).find()
          && !object.region(verb.end(), text.length()).lookingAt()) {
        return true;
      }
    }
    return false;
  }

  /**
   * The sink denied by class ("no SQL injection") or by exposure ("not exploitable"). A class match
   * whose one-word gap is a bridge verb ("does not prevent SQL injection") asserts the defect
   * rather than denying the sink, so it does not count as a denial.
   */
  private static boolean deniesTheSink(String asserted) {
    Matcher denial = SINK_DENIED.matcher(asserted);
    while (denial.find()) {
      String gap = denial.group(2);
      if (gap == null
          || !(BRIDGE_VERB_GAP.matcher(gap).matches()
              || NEUTRALIZING_VERB_GAP.matcher(gap).matches())) {
        return true;
      }
    }
    return EXPLOITABILITY_DENIED.matcher(asserted).find();
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
      switch (decisionOf(verdict)) {
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
