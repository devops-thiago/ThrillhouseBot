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
package dev.thiagogonzaga.thrillhousebot.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Produces a dedicated executor for blocking review work so webhook handling does not pin {@link
 * java.util.concurrent.ForkJoinPool#commonPool()} threads.
 */
@ApplicationScoped
public class ReviewExecutorProducer {

  private static final Logger log = LoggerFactory.getLogger(ReviewExecutorProducer.class);

  @Produces
  @ApplicationScoped
  @ReviewExecutor
  ExecutorService reviewExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  void shutdown(@Disposes @ReviewExecutor ExecutorService executor) {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        log.warn("Review executor did not terminate within 30s — forcing shutdown");
        executor.shutdownNow();
      }
    } catch (InterruptedException _) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
