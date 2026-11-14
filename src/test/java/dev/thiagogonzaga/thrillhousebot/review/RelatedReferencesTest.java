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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RelatedReferencesTest {

  private static FileDiff changed(String path, String... lines) {
    return new FileDiff(
        path,
        "modified",
        lines.length,
        0,
        lines.length,
        "@@ -1,3 +1,9 @@\n" + String.join("\n", lines));
  }

  private static List<RelatedReferences.Candidate> extract(
      Set<String> repository, FileDiff... files) {
    var changedPaths = new java.util.HashSet<String>();
    for (var file : files) {
      changedPaths.add(file.filename());
    }
    return RelatedReferences.extract(List.of(files), changedPaths, repository, path -> false);
  }

  private static List<String> paths(List<RelatedReferences.Candidate> candidates) {
    return candidates.stream().map(RelatedReferences.Candidate::path).toList();
  }

  @Test
  void aJavaImportResolvesToTheFileOfThatPackage() {
    var candidates =
        extract(
            Set.of(
                "src/main/java/app/web/Handler.java",
                "src/main/java/app/util/InputSanitizer.java",
                "src/main/java/other/InputSanitizer.java"),
            changed(
                "src/main/java/app/web/Handler.java",
                "+import app.util.InputSanitizer;",
                "+    var clean = sanitizer.clean(raw);"));

    assertEquals(List.of("src/main/java/app/util/InputSanitizer.java"), paths(candidates));
    var candidate = candidates.get(0);
    assertEquals(RelatedReferences.Via.IMPORT, candidate.via());
    assertEquals(List.of("src/main/java/app/web/Handler.java"), candidate.referencedBy());
    assertEquals(List.of("InputSanitizer"), candidate.symbols());
    assertEquals(List.of("clean"), candidate.members());
  }

  @Test
  void aStaticImportAndAWildcardImportAreReadForWhatTheyName() {
    var repository = Set.of("src/app/Limits.java", "src/app/Handler.java");

    assertEquals(
        List.of("src/app/Limits.java"),
        paths(
            extract(
                repository, changed("src/app/Handler.java", " import static app.Limits.MAX;"))));
    assertEquals(
        List.of(), paths(extract(repository, changed("src/app/Handler.java", "+import app.*;"))));
  }

  @Test
  void aTypeNamedOnAnAddedLineResolvesToItsOnlyFileOfThatLanguage() {
    var candidates =
        extract(
            Set.of("src/app/Handler.java", "src/app/RateLimiter.java", "docs/RateLimiter.md"),
            changed(
                "src/app/Handler.java",
                "+    RateLimiter.acquire(key);",
                "-    OldLimiter.acquire(key);",
                "+    // Handler mentions RateLimiter in a comment"));

    assertEquals(List.of("src/app/RateLimiter.java"), paths(candidates));
    assertEquals(RelatedReferences.Via.SYMBOL, candidates.get(0).via());
    assertEquals(List.of("acquire"), candidates.get(0).members());
  }

  @Test
  void aTypeWithSeveralFilesGoesToTheNearestAndToNoneOnATie() {
    var near =
        extract(
            Set.of("a/b/Handler.java", "a/b/Config.java", "x/y/Config.java"),
            changed("a/b/Handler.java", "+    Config.load();"));
    var tied =
        extract(
            Set.of("a/Handler.java", "b/Config.java", "c/Config.java"),
            changed("a/Handler.java", "+    Config.load();"));

    assertEquals(List.of("a/b/Config.java"), paths(near));
    assertEquals(List.of(), paths(tied));
  }

  @Test
  void aChangedFileAnExcludedFileAndARemovedFilesReferencesAreNotCandidates() {
    var repository = Set.of("src/TAx.java", "src/TBx.java", "src/TCx.java", "src/TDx.java");
    var a = changed("src/TAx.java", "+    TBx.run(); TCx.run(); TDx.run();");
    var b = changed("src/TBx.java", "+    int x;");
    var removed =
        new FileDiff("src/Gone.java", "removed", 0, 1, 1, "@@ -1 +0,0 @@\n+    TDx.run();");

    var candidates =
        RelatedReferences.extract(
            List.of(a, b, removed),
            Set.of("src/TAx.java", "src/TBx.java", "src/Gone.java"),
            repository,
            "src/TCx.java"::equals);

    assertEquals(List.of("src/TDx.java"), paths(candidates));
    assertEquals(List.of("src/TAx.java"), candidates.get(0).referencedBy());
  }

  @Test
  void pythonImportsResolveAbsoluteAndRelativeModules() {
    var repository =
        Set.of(
            "app/api/views.py",
            "app/api/schemas.py",
            "app/core/security.py",
            "app/core/__init__.py",
            "app/models/__init__.py");
    var candidates =
        extract(
            repository,
            changed(
                "app/api/views.py",
                "+from app.core.security import verify_token",
                "+from . import schemas",
                "+from .. import models",
                "+import app.core"));

    assertEquals(
        Set.of(
            "app/core/security.py",
            "app/api/schemas.py",
            "app/models/__init__.py",
            "app/core/__init__.py"),
        Set.copyOf(paths(candidates)));
  }

  @Test
  void scriptImportsResolveRelativePathsExtensionsAndIndexFiles() {
    var repository =
        Set.of(
            "web/src/pages/Cart.tsx",
            "web/src/lib/money.ts",
            "web/src/components/index.ts",
            "web/src/pages/total.js");
    var candidates =
        extract(
            repository,
            changed(
                "web/src/pages/Cart.tsx",
                "+import { format } from '../lib/money.js';",
                "+import { Button } from \"../components\";",
                "+const total = require('./total');",
                "+import React from 'react';",
                "+import x from '../../../../outside';"));

    assertEquals(
        Set.of("web/src/lib/money.ts", "web/src/components/index.ts", "web/src/pages/total.js"),
        Set.copyOf(paths(candidates)));
  }

  @Test
  void aCommentedOutImportRefersToNothing() {
    var repository =
        Set.of("web/src/pages/Cart.tsx", "web/src/lib/money.ts", "app/a.py", "app/b.py");

    assertEquals(
        List.of(),
        paths(
            extract(
                repository,
                changed(
                    "web/src/pages/Cart.tsx",
                    "+// import { format } from '../lib/money.js';",
                    " * see the helper imported from '../lib/money'"))));
    assertEquals(List.of(), paths(extract(repository, changed("app/a.py", "+# from . import b"))));
    assertEquals(
        List.of("src/net/Buffer.hpp"),
        paths(
            extract(
                Set.of("src/net/socket.cpp", "src/net/Buffer.hpp"),
                changed("src/net/socket.cpp", "+*out = Buffer::wrap(raw);", "+ */", "+*"))),
        "a line that starts with a dereference is code");
  }

  @Test
  void goRustRubyCAndPhpImportsResolve() {
    assertEquals(
        List.of("internal/auth/token.go"),
        paths(
            extract(
                Set.of("cmd/main.go", "internal/auth/token.go", "internal/auth/token_test.go"),
                changed("cmd/main.go", "+\t\"example.com/app/internal/auth\""))));
    assertEquals(
        List.of("core/src/limits/mod.rs"),
        paths(
            extract(
                Set.of("core/src/api.rs", "core/src/limits/mod.rs"),
                changed("core/src/api.rs", "+use crate::limits::Quota;"))));
    assertEquals(
        List.of("lib/billing/tax.rb"),
        paths(
            extract(
                Set.of("lib/billing/invoice.rb", "lib/billing/tax.rb"),
                changed("lib/billing/invoice.rb", "+require_relative 'tax'"))));
    assertEquals(
        List.of("src/net/buffer.h"),
        paths(
            extract(
                Set.of("src/net/socket.c", "src/net/buffer.h"),
                changed("src/net/socket.c", "+#include \"buffer.h\""))));
    assertEquals(
        List.of("src/Billing/Tax.php"),
        paths(
            extract(
                Set.of("src/Http/Checkout.php", "src/Billing/Tax.php"),
                changed(
                    "src/Http/Checkout.php", "+use App\\Billing\\Tax;", "+use function strlen;"))));
  }

  @Test
  void aSnakeCaseFileIsFoundForACamelCaseType() {
    assertEquals("rate_limiter", RelatedReferences.snakeCase("RateLimiter"));
    assertEquals("api_client", RelatedReferences.snakeCase("APIClient"));
    assertEquals("http2_server", RelatedReferences.snakeCase("HTTP2Server"));
    assertEquals(
        List.of("app/rate_limiter.py"),
        paths(
            extract(
                Set.of("app/views.py", "app/rate_limiter.py"),
                changed("app/views.py", "+    limiter = RateLimiter(key)"))));
  }

  @Test
  void anImportOnAnAddedLineOutranksOneOnAContextLineAndTheListIsCapped() {
    var repository = new java.util.HashSet<String>();
    repository.add("src/app/Handler.java");
    repository.add("src/app/Old.java");
    repository.add("src/app/Fresh.java");
    var lines = new java.util.ArrayList<String>();
    lines.add(" import app.Old;");
    lines.add("+import app.Fresh;");
    for (var i = 0; i < RelatedReferences.MAX_CANDIDATES + 5; i++) {
      repository.add("src/app/Type" + i + "x.java");
      lines.add("+    Type" + i + "x.run();");
    }

    var candidates =
        extract(repository, changed("src/app/Handler.java", lines.toArray(String[]::new)));

    assertEquals(RelatedReferences.MAX_CANDIDATES, candidates.size());
    assertEquals("src/app/Fresh.java", candidates.get(0).path());
    assertEquals("src/app/Old.java", candidates.get(1).path());
  }

  @Test
  void nothingIsFoundWithoutAListingAPatchOrAKnownLanguage() {
    var file = changed("src/A.java", "+    B.run();");

    assertEquals(List.of(), extract(Set.of(), file));
    assertEquals(
        List.of(), RelatedReferences.extract(null, Set.of(), Set.of("src/B.java"), p -> false));
    assertEquals(
        List.of(),
        extract(Set.of("src/B.java"), new FileDiff("src/A.java", "modified", 1, 0, 1, null)));
    assertEquals(
        List.of(), extract(Set.of("docs/B.java"), changed("docs/notes.md", "+See B.run() here")));
    assertEquals(
        List.of(),
        extract(Set.of("src/B.java"), changed("src/A.java", "+" + "B.run(); ".repeat(100))));
  }

  @Test
  void hunkHeadersAreListedClippedAndCapped() {
    var patch = "@@ -1 +1 @@ class A\n+x\n@@ -9 +9 @@ " + "y".repeat(50) + "\n+z\n@@ -20 +20 @@";

    assertEquals(
        List.of("@@ -1 +1 @@ class A", "@@ -9 +9 @@ yyyyyyyy…"),
        RelatedReferences.hunkHeaders(patch, 2, 20));
    assertTrue(RelatedReferences.hunkHeaders(null, 2, 20).isEmpty());
    assertEquals("a/b", RelatedReferences.normalize("a/c", "../b"));
  }
}
