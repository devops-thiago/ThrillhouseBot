---
slug: 0.3.1/review-eval
title: Review-quality evaluation
description: Probe and score review quality against collected failure cases.
---

A repeatable check of the bot's review quality against the failure cases
collected while dogfooding the v0.1.0 release PRs. Run it after every deploy of
the bot, before trusting its reviews again.

The probe files live in `scripts/review-probe/`. They embed the exact patterns
that produced false findings: CI expression syntax (`${{ }}`), generic type
parameters, `|}` and diff-marker strings, string escapes, and two
similar-but-differently-guarded workflow jobs. One deliberate bug (SQL
injection built by string concatenation) is the true-positive control.

## Round 1: fresh review

1. Create a branch on the test repository (`devops-thiago/ThrillhouseBot-test`)
   and copy both probe files into it, as new files.
2. Open a PR and let the bot review it (or comment `/review`).
3. Score the review:

| Check | Pass condition |
|---|---|
| True positive | The SQL injection in `Probe.findUser` is reported |
| Quote fidelity | Every quoted snippet appears verbatim in the probe files |
| Expression syntax | No finding claims `${{ }}` is written as `${ }` or is invalid |
| Generics | No finding quotes `new ArrayList<>()` where the file has a type argument |
| String escapes | No finding claims `"\n"` is a literal backslash and n |
| Cross-scope | No finding compares the `gated` and `report-only` jobs as if one were a broken copy of the other |
| Self-reference | No finding claims `Probe.neutralize` is a no-op or injection risk (the rendering neutralizes the markers, making it look like one) |
| Single report | The SQL injection is reported once, not as several findings |

## Round 2: follow-up behavior

1. Reply to the SQL injection finding's thread with a justification, e.g.
   "Intentional: this probe file is test data, not shipped code."
2. Push any trivial commit to the PR (whitespace works) so the bot re-reviews.
3. Score the follow-up:

| Check | Pass condition |
|---|---|
| Status honored | The previous finding is marked `justified`, not `unresolved` |
| No re-raise | The SQL injection is not posted again as a new finding |
| No escalation | It is also not posted again at a higher severity |

## Round 3: memory across rounds

1. Push one more trivial commit (a third review round).
2. The justified finding from round 1 must still not return. This exercises
   the all-rounds memory: the finding is absent from the latest response, so
   only the accumulated history can suppress it.

Record the outcome (date, deployed version, pass/fail per check) in the PR
description on the test repository, then close the PR. A failed check means
the deploy regressed review quality; compare the bot's session log with the
matching check above before shipping anything else.
