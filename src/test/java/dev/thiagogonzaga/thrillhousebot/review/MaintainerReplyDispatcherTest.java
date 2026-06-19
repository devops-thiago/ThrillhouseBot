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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class MaintainerReplyDispatcherTest {

  @Mock private ExecutorService executor;
  @Mock private MaintainerReplyService replyService;

  private MaintainerReplyDispatcher dispatcher;

  private static final MaintainerReplyService.ReplyTask TASK =
      new MaintainerReplyService.ReplyTask(
          "owner", "repo", 42, 1L, "octocat", "OWNER", "q", "t", "b", true, 99L, 1000L, false, "h");

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    dispatcher = new MaintainerReplyDispatcher(executor, replyService);
  }

  @Test
  void dispatchSubmitsTaskToExecutorAndReturnsTrue() {
    // Run the submitted task inline so we can assert the service was invoked with the task.
    doAnswer(
            inv -> {
              inv.getArgument(0, Runnable.class).run();
              return null;
            })
        .when(executor)
        .execute(any(Runnable.class));

    assertTrue(dispatcher.dispatch(TASK));
    verify(replyService).handle(TASK);
  }

  @Test
  void dispatchReturnsFalseWhenExecutorRejects() {
    doThrow(new RejectedExecutionException("saturated"))
        .when(executor)
        .execute(any(Runnable.class));

    assertFalse(dispatcher.dispatch(TASK));
    verify(replyService, never()).handle(any());
  }
}
