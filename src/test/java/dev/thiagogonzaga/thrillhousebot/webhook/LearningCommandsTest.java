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
package dev.thiagogonzaga.thrillhousebot.webhook;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import dev.thiagogonzaga.thrillhousebot.review.ReviewLearning;
import dev.thiagogonzaga.thrillhousebot.review.ReviewLearningService.LearningView;
import dev.thiagogonzaga.thrillhousebot.review.ReviewLearningService.RecordOutcome;
import dev.thiagogonzaga.thrillhousebot.review.ReviewLearningService.RetractOutcome;
import dev.thiagogonzaga.thrillhousebot.review.ReviewLearnings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LearningCommandsTest {

  private final ReviewLearnings learnings = mock(ReviewLearnings.class);
  private final LearningCommands commands = new LearningCommands(learnings);

  private static CommentCommandService.CommandContext ctx(CommentCommand command, String body) {
    return new CommentCommandService.CommandContext(
        command, "o", "r", 7, "main", 1L, "Maintainer", "OWNER", 555L, body);
  }

  private static LearningView view(long id, String kind, String title, String path, String text) {
    return new LearningView(
        id,
        kind,
        title,
        "medium",
        path,
        text,
        159,
        "https://github.com/o/r/pull/159#discussion_r" + id,
        "maintainer",
        Instant.EPOCH,
        true,
        null,
        null);
  }

  @Test
  void enabledFollowsTheStore() {
    when(learnings.enabled()).thenReturn(true);
    assertTrue(commands.enabled());
  }

  @Test
  void anEmptyRepositorySaysSo() {
    when(learnings.list(1L, "o", "r")).thenReturn(List.of());

    assertEquals(
        "🧠 ThrillhouseBot has no active learnings for this repository.",
        commands.reply(ctx(CommentCommand.LEARNINGS, "/learnings")));
  }

  @Test
  void theListShowsIdKindSubjectTextAndSourceWithEchoedProseNeutralized() {
    when(learnings.maxPerRepo()).thenReturn(100);
    when(learnings.list(1L, "o", "r"))
        .thenReturn(
            List.of(
                view(
                    4,
                    ReviewLearning.KIND_DECLINE,
                    "renderThread | misses `nested` replies",
                    "src/A.java",
                    "Threads are flat, ping @someone"),
                view(5, ReviewLearning.KIND_CONVENTION, null, null, "x".repeat(300)),
                view(6, ReviewLearning.KIND_CONVENTION, null, "docs/", "docs rule"),
                view(7, ReviewLearning.KIND_DECLINE, "t", null, " ")));

    var reply = commands.reply(ctx(CommentCommand.LEARNINGS, "/learnings"));

    assertTrue(reply.startsWith("## 🧠 ThrillhouseBot learnings for this repository"), reply);
    assertTrue(reply.contains("4 of at most 100 active."), reply);
    assertTrue(
        reply.contains(
            "| 4 | declined finding | `renderThread \\| misses nested replies` in `src/A.java` |"
                + " `Threads are flat, ping @someone` | `maintainer` ([PR"
                + " #159](https://github.com/o/r/pull/159#discussion_r4)) |"),
        reply);
    assertTrue(reply.contains("| 5 | convention | whole repository | `" + "x".repeat(199) + "…`"));
    assertTrue(reply.contains("| 6 | convention | `docs/` |"), reply);
    assertTrue(reply.contains("| 7 | declined finding | `t` | - |"), reply);
    assertFalse(reply.contains("older learning"), reply);
  }

  @Test
  void aLongListIsCutWithAPointerToTheDashboard() {
    var many = new ArrayList<LearningView>();
    for (long i = 1; i <= LearningCommands.MAX_LISTED + 2; i++) {
      many.add(view(i, ReviewLearning.KIND_CONVENTION, null, null, "rule " + i));
    }
    when(learnings.list(1L, "o", "r")).thenReturn(many);

    var reply = commands.reply(ctx(CommentCommand.LEARNINGS, "/learnings"));

    assertTrue(reply.contains("| 30 | convention"), reply);
    assertFalse(reply.contains("| 31 | convention"), reply);
    assertTrue(reply.endsWith("2 older learning(s) not shown; the dashboard lists every one."));
  }

  @Test
  void forgetAcceptsTheIdSpellingsTheListUses() {
    when(learnings.forget(1L, "o", "r", 12L, "Maintainer")).thenReturn(RetractOutcome.RETRACTED);

    for (var body :
        List.of(
            "/forget 12",
            "/forget L12",
            "/forget #12",
            "/forget [L12] it is stale",
            "@thrillhousebot forget 12")) {
      assertEquals(
          "🧹 Learning 12 is retracted; later reviews of this repository no longer see it.",
          commands.reply(ctx(CommentCommand.FORGET, body)),
          body);
    }
  }

  @Test
  void forgetReportsTheOtherOutcomesAndTheUsage() {
    when(learnings.forget(1L, "o", "r", 3L, "Maintainer"))
        .thenReturn(RetractOutcome.ALREADY_RETRACTED);
    when(learnings.forget(1L, "o", "r", 4L, "Maintainer")).thenReturn(RetractOutcome.NOT_FOUND);

    assertEquals(
        "Learning 3 was already retracted.",
        commands.reply(ctx(CommentCommand.FORGET, "/forget 3")));
    assertEquals(
        "This repository has no learning 4. `/learnings` lists the ids.",
        commands.reply(ctx(CommentCommand.FORGET, "/forget 4")));
    assertEquals(
        LearningCommands.FORGET_USAGE, commands.reply(ctx(CommentCommand.FORGET, "/forget")));
    assertEquals(
        LearningCommands.FORGET_USAGE,
        commands.reply(ctx(CommentCommand.FORGET, "/forget `12` in code")));
    assertEquals(LearningCommands.FORGET_USAGE, commands.reply(ctx(CommentCommand.FORGET, null)));
    verify(learnings, never()).forget(anyLong(), anyString(), anyString(), eq(12L), anyString());
  }

  @Test
  void rememberStoresTheTextAfterTheCommandWord() {
    when(learnings.rememberConvention(1L, "o", "r", 7, 555L, "Maintainer", "Prefer `records`."))
        .thenReturn(RecordOutcome.STORED);
    when(learnings.rememberConvention(1L, "o", "r", 7, 555L, "Maintainer", "Use UTC."))
        .thenReturn(RecordOutcome.DUPLICATE);

    assertTrue(
        commands
            .reply(ctx(CommentCommand.REMEMBER, "/remember Prefer `records`."))
            .startsWith("🧠 Remembered for this repository."));
    assertEquals(
        "That convention is already remembered.",
        commands.reply(ctx(CommentCommand.REMEMBER, "@thrillhousebot remember Use UTC.")));
  }

  @Test
  void aQuotedOrDocumentedRememberNeverSuppliesTheText() {
    when(learnings.rememberConvention(1L, "o", "r", 7, 555L, "Maintainer", "Use `UTC` times."))
        .thenReturn(RecordOutcome.STORED);

    var reply =
        commands.reply(
            ctx(
                CommentCommand.REMEMBER,
                "> /remember quoted\n```\n/remember fenced\n```\nsee `/remember x`\n"
                    + "/remember Use `UTC` times."));

    assertTrue(reply.startsWith("🧠 Remembered"), reply);
    assertEquals(
        LearningCommands.REMEMBER_USAGE,
        commands.reply(ctx(CommentCommand.REMEMBER, "> /remember only quoted")));
  }

  @Test
  void rememberExplainsEveryRefusal() {
    when(learnings.maxPerRepo()).thenReturn(100);
    when(learnings.rememberConvention(1L, "o", "r", 7, 555L, "Maintainer", "secret"))
        .thenReturn(RecordOutcome.REFUSED_SECRET);
    when(learnings.rememberConvention(1L, "o", "r", 7, 555L, "Maintainer", "full"))
        .thenReturn(RecordOutcome.REFUSED_CAP);
    when(learnings.rememberConvention(1L, "o", "r", 7, 555L, "Maintainer", "> quoted"))
        .thenReturn(RecordOutcome.REFUSED_EMPTY);

    assertTrue(
        commands
            .reply(ctx(CommentCommand.REMEMBER, "/remember secret"))
            .contains("shaped like a credential"));
    assertTrue(
        commands
            .reply(ctx(CommentCommand.REMEMBER, "/remember full"))
            .contains("already holds 100 active learnings"));
    assertEquals(
        LearningCommands.REMEMBER_USAGE,
        commands.reply(ctx(CommentCommand.REMEMBER, "/remember > quoted")));
    assertEquals(
        LearningCommands.REMEMBER_USAGE,
        commands.reply(ctx(CommentCommand.REMEMBER, "/remember   ")));
    assertEquals(
        LearningCommands.REMEMBER_USAGE,
        commands.reply(ctx(CommentCommand.REMEMBER, "nothing here")));
  }
}
