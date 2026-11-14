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

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The head-commit files one review's presence checks read (#1321), when a finding's anchor is not
 * in its file's patch: a patch carries three context lines around each change, and an anchor that
 * runs past them is still in the file.
 *
 * <p>Files are read the way {@link CitedLocationResolver} reads them ({@link
 * CitedLocationResolver#readLines}), each at most once per review and at most {@link #MAX_FILES}
 * files, counted apart from the verifier's evidence reads so neither can use up the other's. {@link
 * DiffLineResolver} only asks for a file the pull request changes, by the path GitHub lists it
 * under, never a path a model wrote. A file past the cap or unreadable answers empty, and presence
 * is then what the patch shows.
 */
final class HeadFileLines implements DiffLineResolver.HeadFiles {

  /** Distinct files read per review for presence. */
  static final int MAX_FILES = 16;

  private final GitHubPullRequestClient prClient;
  private final String auth;
  private final String owner;
  private final String repo;
  private final String ref;
  private final Map<String, Optional<List<String>>> contents = new ConcurrentHashMap<>();
  private final AtomicInteger reads = new AtomicInteger();

  HeadFileLines(
      GitHubPullRequestClient prClient, String auth, String owner, String repo, String ref) {
    this.prClient = prClient;
    this.auth = auth;
    this.owner = owner;
    this.repo = repo;
    this.ref = ref;
  }

  @Override
  public Optional<List<String>> lines(String path) {
    return contents.computeIfAbsent(
        path,
        p ->
            reads.incrementAndGet() > MAX_FILES
                ? Optional.empty()
                : CitedLocationResolver.readLines(prClient, auth, owner, repo, p, ref));
  }
}
