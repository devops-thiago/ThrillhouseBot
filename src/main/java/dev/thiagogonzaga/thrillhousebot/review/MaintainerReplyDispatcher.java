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

import dev.thiagogonzaga.thrillhousebot.config.ReviewExecutor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands a conversational reply to the shared review executor so the slow AI call and GitHub round
 * trips run off the webhook ACK path. Unlike {@link ReviewDispatcher} there is no per-PR
 * coalescing: each maintainer message gets its own answer.
 */
@ApplicationScoped
public class MaintainerReplyDispatcher {

  private static final Logger log = LoggerFactory.getLogger(MaintainerReplyDispatcher.class);

  private final ExecutorService reviewExecutor;
  private final MaintainerReplyService replyService;

  @Inject
  public MaintainerReplyDispatcher(
      @ReviewExecutor ExecutorService reviewExecutor, MaintainerReplyService replyService) {
    this.reviewExecutor = reviewExecutor;
    this.replyService = replyService;
  }

  /**
   * Queues the reply task on the review executor.
   *
   * @return {@code true} if the task was queued; {@code false} if the executor rejected it (e.g. it
   *     is shutting down) so nothing will run — the caller rolls back webhook dedup so a redelivery
   *     can retry.
   */
  public boolean dispatch(MaintainerReplyService.ReplyTask task) {
    try {
      reviewExecutor.execute(() -> replyService.handle(task));
      return true;
    } catch (RejectedExecutionException e) {
      log.warn(
          "Failed to queue conversational reply for {}/{} #{} — executor rejected task",
          task.owner(),
          task.repo(),
          task.prNumber(),
          e);
      return false;
    }
  }
}
