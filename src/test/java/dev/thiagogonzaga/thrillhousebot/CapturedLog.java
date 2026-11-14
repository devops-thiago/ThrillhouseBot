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
package dev.thiagogonzaga.thrillhousebot;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Records what one class logs, at every level, until closed. */
public final class CapturedLog implements AutoCloseable {

  private final Logger logger;
  private final Level originalLevel;
  private final List<LogRecord> records = new CopyOnWriteArrayList<>();
  private final Handler handler =
      new Handler() {
        @Override
        public void publish(LogRecord entry) {
          records.add(entry);
        }

        @Override
        public void flush() {
          // Nothing is buffered.
        }

        @Override
        public void close() {
          // Nothing to release.
        }
      };

  private CapturedLog(Class<?> source) {
    logger = Logger.getLogger(source.getName());
    originalLevel = logger.getLevel();
    logger.setLevel(Level.ALL);
    handler.setLevel(Level.ALL);
    logger.addHandler(handler);
  }

  /** Starts recording what {@code source} logs. */
  public static CapturedLog of(Class<?> source) {
    return new CapturedLog(source);
  }

  /** The messages logged at {@code level}, each followed by its parameters. */
  public List<String> at(Level level) {
    return records.stream()
        .filter(entry -> entry.getLevel().equals(level))
        .map(CapturedLog::text)
        .toList();
  }

  /** The message with its parameters appended, whatever placeholder style the logger used. */
  private static String text(LogRecord entry) {
    var parameters = entry.getParameters();
    return parameters == null
        ? entry.getMessage()
        : entry.getMessage() + " " + java.util.Arrays.toString(parameters);
  }

  @Override
  public void close() {
    logger.removeHandler(handler);
    logger.setLevel(originalLevel);
  }
}
