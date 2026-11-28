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
package dev.thiagogonzaga.thrillhousebot.github;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHCommitPointer;
import org.kohsuke.github.GHException;
import org.kohsuke.github.GHRepository;

/**
 * {@link GitHubPullRequestClient} over hub4j, with the client and failure contract of {@link
 * GitHubTokenReads}.
 */
@ApplicationScoped
public class GitHubPullRequestReads implements GitHubPullRequestClient {

  private final GitHubTokenReads reads;

  @Inject
  public GitHubPullRequestReads(GitHubTokenReads reads) {
    this.reads = reads;
  }

  @FunctionalInterface
  private interface Read<T> {
    T from(GHRepository repository) throws IOException;
  }

  @Override
  public PullRequestDetails getPullRequestOnce(
      String auth, String accept, String owner, String repo, int pullNumber) {
    return read(
        auth,
        owner,
        repo,
        repository -> {
          var pr = repository.getPullRequest(pullNumber);
          return new PullRequestDetails(
              pr.getTitle(),
              pr.getBody(),
              ref(pr.getHead()),
              ref(pr.getBase()),
              pr.getChangedFiles(),
              pr.getAdditions(),
              pr.getDeletions(),
              pr.getState().name().toLowerCase(Locale.ROOT));
        });
  }

  @Override
  public List<FileDiff> getPullRequestFilesOnce(
      String auth, String accept, String owner, String repo, int pullNumber) {
    return read(
        auth,
        owner,
        repo,
        repository -> {
          var pages =
              repository
                  .getPullRequest(pullNumber)
                  .listFiles()
                  .withPageSize(FILES_PER_PAGE)
                  .iterator();
          var files = new ArrayList<FileDiff>();
          for (var page = 0; page < MAX_FILE_PAGES && pages.hasNext(); page++) {
            for (var file : pages.nextPage()) {
              files.add(
                  new FileDiff(
                      file.getFilename(),
                      file.getStatus(),
                      file.getAdditions(),
                      file.getDeletions(),
                      file.getChanges(),
                      file.getPatch(),
                      file.getPreviousFilename(),
                      file.getSha()));
            }
          }
          return files;
        });
  }

  @Override
  public CompareResponse compareCommitsOnce(
      String auth, String accept, String owner, String repo, String base, String head) {
    return read(
        auth,
        owner,
        repo,
        repository -> {
          var comparison = repository.getCompare(base, head);
          var status = comparison.getStatus();
          return new CompareResponse(
              comparison.getTotalCommits(),
              Arrays.stream(comparison.getFiles()).map(GitHubPullRequestReads::file).toList(),
              status == null ? null : status.name());
        });
  }

  @Override
  public FileContent getFileContentOnce(
      String auth, String accept, String owner, String repo, String path, String ref) {
    return read(
        auth,
        owner,
        repo,
        repository -> {
          var file = repository.getFileContent(path, ref);
          return new FileContent(
              file.getName(),
              file.getPath(),
              file.getEncodedContent(),
              file.getEncoding(),
              file.getSize());
        });
  }

  @Override
  public TreeResponse getTreeOnce(
      String auth, String accept, String owner, String repo, String treeSha, String recursive) {
    return read(
        auth,
        owner,
        repo,
        repository -> {
          var tree =
              recursive == null
                  ? repository.getTree(treeSha)
                  : repository.getTreeRecursive(treeSha, 1);
          return new TreeResponse(
              tree.getSha(),
              tree.getTree().stream()
                  .map(entry -> new TreeEntry(entry.getPath(), entry.getType(), entry.getSize()))
                  .toList(),
              tree.isTruncated());
        });
  }

  private <T> T read(String auth, String owner, String repo, Read<T> read) {
    try {
      return read.from(reads.repository(auth, owner + "/" + repo));
    } catch (IOException | GHException e) {
      throw GitHubTokenReads.unreadable(e);
    }
  }

  private static Ref ref(GHCommitPointer pointer) {
    return new Ref(pointer.getSha(), pointer.getRef());
  }

  private static FileDiff file(GHCommit.File file) {
    return new FileDiff(
        file.getFileName(),
        file.getStatus(),
        file.getLinesAdded(),
        file.getLinesDeleted(),
        file.getLinesChanged(),
        file.getPatch(),
        file.getPreviousFilename(),
        file.getSha());
  }
}
