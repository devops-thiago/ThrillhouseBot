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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.review.OutdatedOutputHider;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pins the command notices to what {@link OutdatedOutputHider} recognizes (#1441). */
class OutdatedNoticeTest {

  @Test
  void recognizesTheCommandNoticesButNotTheHelpText() {
    for (var notice :
        List.of(
            CommentCommandService.PAUSED_NOTICE,
            CommentCommandService.CLOSED_NOTICE,
            CommentCommandService.SUMMARY_ALREADY_PRESENT,
            CommentCommandService.RESOLVE_NOT_PERMITTED_NOTICE)) {
      assertTrue(OutdatedOutputHider.isNotice(notice), notice);
    }
    assertFalse(OutdatedOutputHider.isNotice(CommentCommandService.HELP_TEXT));
  }
}
