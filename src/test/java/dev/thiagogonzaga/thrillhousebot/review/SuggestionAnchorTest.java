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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #924: a suggestion is posted only on the lines its {@code suggestion_old} quotes. Each case is
 * one the dogfood rounds posted on the wrong line, so that applying it deleted a row or a line
 * nobody flagged.
 */
class SuggestionAnchorTest {

  private static final String EXPIRY_TRACKER = "java/src/main/java/bloodbank/ExpiryTracker.java";
  private static final String REACT_DOC = "docs/CONFIG-REACT.md";
  private static final String REFRESH_ROW =
      "| `VITE_REFRESH_INTERVAL` | How often the desk reloads the loan list. |";

  /** ThrillhouseBot-test#104, thread 4145843629: the suggestion sat on the row two lines up. */
  @Test
  void aTableRowSuggestionMovesFromTheCitedRowToTheRowItQuotes() {
    var resolver =
        resolver(
            REACT_DOC,
            "# React configuration",
            "",
            "The desk reads these Vite variables at build time.",
            "",
            "| Key | Meaning |",
            "|---|---|",
            "| `VITE_LOAN_API_URL` | Base URL of the loan API. |",
            "| `VITE_BRANCH_ID` | Branch the desk serves. |",
            REFRESH_ROW);
    var cited =
        finding(
            REACT_DOC,
            7,
            REFRESH_ROW,
            "| `VITE_REFRESH_INTERVAL` | How often the desk reloads the loan list, in"
                + " milliseconds. Defaults to 30000. |");

    var placed = SuggestionAnchor.place(cited, resolver);

    assertEquals(9, placed.line());
    assertEquals(cited.suggestionNew(), placed.suggestionNew());
    assertEquals(REFRESH_ROW, placed.suggestionOld());
  }

  /** ThrillhouseBot-test#109, thread 4145874203: a row added below the row it quotes. */
  @Test
  void aSuggestionThatAddsARowMovesOntoTheRowItKeeps() {
    var maxBookings =
        "| `MAX_BOOKINGS_PER_USER` | Maximum number of active bookings one user may own. |";
    var resolver =
        resolver(
            "docs/CONFIG-NODE.md",
            "# Node configuration",
            "",
            "The service reads its settings from environment variables in `node/src/config.js`.",
            "",
            "| Key | Meaning |",
            "|---|---|",
            "| `PORT` | Port the API listens on. |",
            "| `ROOM_IDS` | Comma-separated rooms that can be booked. |",
            "| `HOLD_TIMEOUT` | Seconds a hold lasts. |",
            maxBookings);
    var cited =
        finding(
            "docs/CONFIG-NODE.md",
            8,
            maxBookings,
            "| `MAX_BOOKINGS_PER_USER` | Maximum number of active bookings one user may own."
                + " Defaults to `5`. |\n"
                + "| `DIRECTORY_URL` | Base URL of the company directory API. |");

    var placed = SuggestionAnchor.place(cited, resolver);

    assertEquals(10, placed.line());
    assertEquals(cited.suggestionNew(), placed.suggestionNew());
  }

  /** ThrillhouseBot-test#126, thread 4147518680: on the COPY, suggesting the RUN line. */
  @Test
  void aDockerfileSuggestionForTheRunLineMovesOffTheCopyItWasPostedOn() {
    var run = "RUN CGO_ENABLED=0 go build -o /out/fuelrecon .";
    var resolver =
        resolver(
            "go/Dockerfile",
            "FROM golang:1.23 AS build",
            "WORKDIR /src",
            "COPY go.mod go.sum ./",
            "RUN go mod download",
            "COPY . .",
            run,
            "",
            "FROM gcr.io/distroless/static-debian12",
            "USER nonroot",
            "COPY --from=build /out/fuelrecond /usr/local/bin/fuelrecond",
            "ENTRYPOINT [\"/usr/local/bin/fuelrecond\"]");
    var cited =
        finding("go/Dockerfile", 10, run, "RUN CGO_ENABLED=0 go build -o /out/fuelrecond .");

    assertEquals(6, SuggestionAnchor.place(cited, resolver).line());
  }

  /** ThrillhouseBot-test#157, thread 4149684751: on {@code USER app}, suggesting the COPY. */
  @Test
  void aDockerfileCopySuggestionMovesOffTheUserLineBelowIt() {
    var copy = "COPY --from=build /src/build/elevator-analyzer /usr/local/bin/elevator-analyser";
    var lines = new ArrayList<String>();
    lines.add("FROM gcc:14 AS build");
    lines.add("WORKDIR /src");
    lines.add("COPY . .");
    lines.add("RUN make");
    lines.add("");
    lines.add("FROM debian:bookworm-slim");
    for (var i = 7; i <= 12; i++) {
      lines.add("RUN echo step " + i);
    }
    lines.add("RUN useradd --system app");
    lines.add(copy);
    lines.add("USER app");
    lines.add("ENTRYPOINT [\"/usr/local/bin/elevator-analyser\"]");
    var resolver = resolver("c/Dockerfile", lines.toArray(String[]::new));
    var cited =
        finding(
            "c/Dockerfile",
            15,
            copy,
            "COPY --from=build /src/build/elevator-analyser /usr/local/bin/elevator-analyser");

    var placed = SuggestionAnchor.place(cited, resolver);

    assertEquals(14, placed.line());
    assertEquals(cited.suggestionNew(), placed.suggestionNew());
  }

  /** ThrillhouseBot-test#126, thread 4147615375: a 3-column table row replaced by two others. */
  @Test
  void aThreeColumnRowSuggestionMovesOntoTheRowItQuotesAndKeepsItsCells() {
    var pageTimeout =
        "| `FUELRECON_PAGE_TIMEOUT` | How long to wait for each provider page request. | |";
    var resolver = goConfigDoc(pageTimeout);
    var cited =
        finding(
            "docs/CONFIG-GO.md",
            9,
            pageTimeout,
            pageTimeout
                + "\n| `FUELRECON_DATABASE_URL` | PostgreSQL connection string. |"
                + " `postgres://localhost/fuelrecon?sslmode=disable` |");

    var placed = SuggestionAnchor.place(cited, resolver);

    assertEquals(10, placed.line());
    assertEquals(cited.suggestionNew(), placed.suggestionNew());
  }

  /**
   * #964 round 16 (c), ThrillhouseBot-test#211 thread 4154600596: the suggestion for a 3-column row
   * has two cells, so applying it drops the Default column. The finding stays, without it.
   */
  @Test
  void aTableRowSuggestionThatDropsACellLosesItsSuggestionButKeepsTheFinding() {
    var apiToken =
        "| `FUELRECON_API_TOKEN` | Bearer token for the fuel-card provider API. | none |";
    var resolver = goConfigDoc("| `FUELRECON_PAGE_TIMEOUT` | Page request timeout. | |");
    var cited =
        finding(
            "docs/CONFIG-GO.md",
            8,
            apiToken,
            "| `FUELRECON_API_TOKEN` | Bearer token for the fuel-card provider API. Unset uses a"
                + " hard-coded fallback credential in go/config.go. |");

    var placed = SuggestionAnchor.place(cited, resolver);

    assertEquals(8, placed.line());
    assertEquals(apiToken, placed.suggestionOld(), "the anchor stays for later rounds");
    assertNull(placed.suggestionNew());
    assertEquals(cited.title(), placed.title());
  }

  /** ThrillhouseBot-test#132, thread 4147549850: the finding sat at line 40, its stub at 63-69. */
  @Test
  void aMockFidelityFindingMovesOntoTheStubItQuotes() {
    var stub =
        List.of(
            "static int stub_send(const char *technician, const char *msg)",
            "{",
            "    (void)technician;",
            "    (void)msg;",
            "    stub_calls++;",
            "    return 0;",
            "}");
    var lines = new ArrayList<String>();
    for (var i = 1; i <= 62; i++) {
      lines.add("/* line " + i + " */");
    }
    lines.addAll(stub);
    lines.add("");
    var resolver = resolver("c/tests/test_analyser.c", lines.toArray(String[]::new));
    var cited = finding("c/tests/test_analyser.c", 40, String.join("\n", stub), null);

    var placed = SuggestionAnchor.place(cited, resolver);

    assertEquals(63, placed.line());
    assertEquals(cited.suggestionOld(), placed.suggestionOld());
  }

  @Test
  void aFindingOnTheLinesItQuotesIsLeftAsIs() {
    var resolver = resolver("a.py", "def f():", "    return 1", "    # done");
    var single = finding("a.py", 2, "return 1", "return 2");
    var withinBlock = finding("a.py", 2, "def f():\n    return 1", "def f():\n    return 2");
    var noQuote = finding("a.py", 3, null, null);

    assertSame(single, SuggestionAnchor.place(single, resolver));
    assertSame(withinBlock, SuggestionAnchor.place(withinBlock, resolver));
    assertSame(noQuote, SuggestionAnchor.place(noQuote, resolver));
  }

  @Test
  void aQuoteThatCannotBePlacedLosesItsSuggestionAndKeepsItsLine() {
    var resolver =
        resolver("a.py", "x = 1", "y = 2", "x = 1", "", "", "", "", "", "", "", "", "x = 1");
    var absent = finding("a.py", 2, "z = 3", "z = 4");
    var ambiguous = finding("a.py", 7, "x = 1", "x = 2");
    var unknownFile = finding("b.py", 1, "x = 1", "x = 2");

    for (var cited : List.of(absent, ambiguous, unknownFile)) {
      var placed = SuggestionAnchor.place(cited, resolver);
      assertEquals(cited.line(), placed.line());
      assertEquals(cited.suggestionOld(), placed.suggestionOld());
      assertNull(placed.suggestionNew());
    }
    var alreadyBare = finding("a.py", 2, "z = 3", null);
    assertSame(alreadyBare, SuggestionAnchor.place(alreadyBare, resolver));
  }

  @Test
  void ofSeveralLocationsTheOneNearTheCitedLineWins() {
    var resolver =
        resolver("a.py", "x = 1", "y = 2", "z = 3", "", "", "", "", "", "", "", "", "x = 1");

    assertEquals(1, SuggestionAnchor.place(finding("a.py", 3, "x = 1", "x = 2"), resolver).line());
    assertEquals(
        12, SuggestionAnchor.place(finding("a.py", 12, "x = 1", "x = 2"), resolver).line());
  }

  @Test
  void tableShapeIsJudgedByCellsOfMarkdownRowsOnly() {
    assertTrue(SuggestionAnchor.changesTableShape("| a | b | c |", "| a | b |"));
    assertTrue(SuggestionAnchor.changesTableShape("| a | b |", "a, b"));
    assertFalse(SuggestionAnchor.changesTableShape("| a | b |", "| a | b"));
    assertFalse(SuggestionAnchor.changesTableShape("| a \\| x | b |", "| a | b |"));
    assertFalse(SuggestionAnchor.changesTableShape("| a | b |\n\n| c | d |", "| e | f |"));
    assertFalse(SuggestionAnchor.changesTableShape("x = 1", "| a | b |"));
    assertFalse(SuggestionAnchor.changesTableShape("| a | b |", null));
    assertFalse(SuggestionAnchor.changesTableShape("| a | b |", " "));
    assertFalse(SuggestionAnchor.changesTableShape(" ", "| a |"));
    assertEquals(3, SuggestionAnchor.cellCount("| a | b | c |"));
    assertEquals(3, SuggestionAnchor.cellCount("| a | b | |"));
    assertEquals(2, SuggestionAnchor.cellCount("| a | b \\|"));
    // An escaped backslash does not escape the pipe after it: that pipe separates cells.
    assertEquals(2, SuggestionAnchor.cellCount("| a \\\\| b |"));
    assertEquals(1, SuggestionAnchor.cellCount("| a \\| b |"));
    assertEquals(1, SuggestionAnchor.cellCount("|"));
  }

  /**
   * #1197, ThrillhouseBot-test#293 discussion_r4336454622: the quote and the suggestion both start
   * at column 0 where ExpiryTracker.java:51 is indented 8 spaces; the later lines keep theirs.
   */
  @Test
  void aJavaSuggestionGetsBackItsFirstLinesIndent() {
    var resolver =
        resolver(
            EXPIRY_TRACKER,
            "        Instant limit = clock.instant().plus(window);",
            "        List<BloodUnit> out = new ArrayList<>();",
            "        for (BloodUnit u : units.values()) {",
            "            if (u.expiresAt().isBefore(limit)) {",
            "                out.add(u);",
            "            }",
            "        }",
            "        return out;");
    var cited =
        finding(
            EXPIRY_TRACKER,
            3,
            """
            for (BloodUnit u : units.values()) {
                        if (u.expiresAt().isBefore(limit)) {
                            out.add(u);
                        }
                    }""",
            """
            for (BloodUnit u : units.values()) {
                        if (!u.expiresAt().isAfter(limit)) {
                            out.add(u);
                        }
                    }""");

    var placed = SuggestionAnchor.place(cited, resolver);

    assertEquals(
        """
                for (BloodUnit u : units.values()) {
                    if (!u.expiresAt().isAfter(limit)) {
                        out.add(u);
                    }
                }\
        """,
        placed.suggestionNew());
    assertEquals(cited.suggestionOld(), placed.suggestionOld());
    assertEquals(3, placed.line());
  }

  /**
   * #1197, ThrillhouseBot-test#292 discussion_r4336431427: go/allocator.go indents with tabs, and
   * the quote and the suggestion use one space per level.
   */
  @Test
  void aGoSuggestionGetsItsTabsBack() {
    var resolver =
        resolver(
            "go/allocator.go",
            "\tvar unassigned []Vehicle // vehicles that could not be given a lane",
            "\tfor _, v := range vs {",
            "\t\tunassigned = append(unassigned, v)",
            "\t\tlane, err := a.Allocate(v)",
            "\t\tif err != nil {",
            "\t\t\tcontinue",
            "\t\t}",
            "\t\tassigned[v.Plate] = lane",
            "\t}");
    var cited =
        finding(
            "go/allocator.go",
            3,
            """
             for _, v := range vs {
              unassigned = append(unassigned, v)
              lane, err := a.Allocate(v)
              if err != nil {
               continue
              }
              assigned[v.Plate] = lane
             }\
            """,
            """
             for _, v := range vs {
              lane, err := a.Allocate(v)
              if err != nil {
               unassigned = append(unassigned, v)
               continue
              }
              assigned[v.Plate] = lane
             }\
            """);

    var placed = SuggestionAnchor.place(cited, resolver);

    assertEquals(
        """
        \tfor _, v := range vs {
        \t\tlane, err := a.Allocate(v)
        \t\tif err != nil {
        \t\t\tunassigned = append(unassigned, v)
        \t\t\tcontinue
        \t\t}
        \t\tassigned[v.Plate] = lane
        \t}""",
        placed.suggestionNew());
    assertEquals(cited.suggestionOld(), placed.suggestionOld());
    assertEquals(3, placed.line());
  }

  /** #1197 controls: indentation already right, one line, and a Python dedent quoted verbatim. */
  @Test
  void aSuggestionIndentedOnPurposeIsLeftAsWritten() {
    var java =
        resolver(
            EXPIRY_TRACKER, "    void f() {", "        int a = 1;", "        int b = 2;", "    }");
    var matching =
        finding(
            EXPIRY_TRACKER,
            2,
            "        int a = 1;\n        int b = 2;",
            "        int a = 2;\n        int b = 3;");
    var oneLine = finding(EXPIRY_TRACKER, 2, "int a = 1;", "int a = 2;\n        int c = 0;");
    var python = resolver("a.py", "class C:", "    def helper(self, x):", "        return x");
    var dedent =
        finding(
            "a.py",
            2,
            "    def helper(self, x):\n        return x",
            "def helper(x):\n    return x");

    assertSame(matching, SuggestionAnchor.place(matching, java));
    assertSame(oneLine, SuggestionAnchor.place(oneLine, java));
    assertSame(dedent, SuggestionAnchor.place(dedent, python));
  }

  private static DiffLineResolver goConfigDoc(String lastRow) {
    return resolver(
        "docs/CONFIG-GO.md",
        "# Go configuration",
        "",
        "The service reads these environment variables at startup.",
        "",
        "| Key | Meaning | Default |",
        "|---|---|---|",
        "| `FUELRECON_ADDR` | Listen address. | `:8080` |",
        "| `FUELRECON_API_TOKEN` | Bearer token for the fuel-card provider API. | none |",
        "| `FUELRECON_PROVIDER_HOSTS` | Comma-separated provider hosts. | |",
        lastRow);
  }

  /** A resolver over one file the pull request adds with {@code lines}, numbered from 1. */
  private static DiffLineResolver resolver(String file, String... lines) {
    var patch = new StringBuilder("@@ -0,0 +1," + lines.length + " @@");
    for (var line : lines) {
      patch.append("\n+").append(line);
    }
    return new DiffLineResolver(Map.of(file, patch.toString()));
  }

  private static ReviewResponse.Finding finding(
      String file, int line, String suggestionOld, String suggestionNew) {
    return new ReviewResponse.Finding(
        "medium", "high", file, line, "Title", "Desc", suggestionOld, suggestionNew);
  }
}
