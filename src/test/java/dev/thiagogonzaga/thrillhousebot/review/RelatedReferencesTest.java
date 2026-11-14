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
  void pythonImportsSpanningLinesOrNamingSeveralModulesResolveEachName() {
    var repository =
        Set.of(
            "app/api/views.py",
            "app/api/schemas.py",
            "app/api/forms.py",
            "app/core/security.py",
            "app/core/tokens.py",
            "app/core/audit.py",
            "app/core/__init__.py");

    assertEquals(
        Set.of(
            "app/api/schemas.py",
            "app/api/forms.py",
            "app/core/security.py",
            "app/core/tokens.py",
            "app/core/audit.py"),
        Set.copyOf(
            paths(
                extract(
                    repository,
                    changed(
                        "app/api/views.py",
                        "+from . import (",
                        "+    schemas,  # see render(page)",
                        "+    forms as f,",
                        "+)",
                        "+from app.core import (security,",
                        "+    tokens)",
                        "+import app.core.audit as audit, os")))));
    var unclosed =
        new FileDiff(
            "app/api/views.py",
            "modified",
            2,
            0,
            2,
            "@@ -1 +1 @@\n+from app.core import (\n@@ -9 +9 @@\n+    security,\n+x = (1)");
    assertEquals(List.of(), paths(extract(repository, unclosed)), "a hunk ends an open import");
  }

  @Test
  void aFromImportOpenedBeforeATrailingCommentStillReadsItsNames() {
    var candidates =
        extract(
            Set.of("app/api/views.py", "app/core/keys.py", "app/core/__init__.py"),
            changed(
                "app/api/views.py",
                "+from app.core import (  # the validators (all of them)",
                "+    keys,",
                "+)"));

    assertEquals(List.of("app/core/keys.py"), paths(candidates));
  }

  @Test
  void aMemberCallBelongsOnlyToTheCandidateItWasMadeOn() {
    var candidates =
        extract(
            Set.of(
                "src/app/web/Handler.java",
                "src/app/util/InputSanitizer.java",
                "src/app/util/Limits.java"),
            changed(
                "src/app/web/Handler.java",
                "+import app.util.InputSanitizer;",
                "+    var id = sanitizer.clean(raw).strip();",
                "+    if (id.length() > Limits.MAX_ID || db.find(id) == null) return;",
                "+    Limits.check(id).orElseThrow();",
                "+    limitsHolder.reset();"));

    assertEquals(
        List.of("clean"),
        candidates.stream()
            .filter(c -> c.path().endsWith("InputSanitizer.java"))
            .findFirst()
            .orElseThrow()
            .members());
    assertEquals(
        List.of("check", "orElseThrow", "reset"),
        candidates.stream()
            .filter(c -> c.path().endsWith("Limits.java"))
            .findFirst()
            .orElseThrow()
            .members());
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
  void anImportOfAFileThatIsNotSourceOfTheSameLanguageIsNeverACandidate() {
    assertEquals(
        List.of(),
        paths(
            extract(
                Set.of("web/app.js", "web/infra/secrets.json", "web/keys.env", "web/tool.py"),
                changed(
                    "web/app.js",
                    "+import settings from './infra/secrets.json';",
                    "+const keys = require('./keys.env');",
                    "+import tool from './tool.py';"))));
    assertEquals(
        List.of(),
        paths(
            extract(
                Set.of("src/main.c", "src/config/credentials.env"),
                changed("src/main.c", "+#include \"config/credentials.env\""))));
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

  @Test
  void linesThatAreNotImportsOrThatResolveNothingAddNoCandidate() {
    var repository =
        Set.of(
            "cmd/main.go",
            "root.go",
            "core/src/api.rs",
            "lib/billing/invoice.rb",
            "src/Http/Checkout.php",
            "src/app/Handler.java",
            "a.py",
            "Makefile");

    assertEquals(
        List.of(),
        paths(
            extract(
                repository,
                new FileDiff(
                    "cmd/main.go",
                    "modified",
                    2,
                    0,
                    2,
                    "+ignored before the first hunk\n@@ -1 +1 @@\n+x := 1\n\n+\t\"fmt/unknown\""),
                changed("core/src/api.rs", "+let x = 1;", "+use crate::nothing::Here;"),
                changed("lib/billing/invoice.rb", "+x = 1", "+require_relative '../../../gone'"),
                changed("src/Http/Checkout.php", "+$x = 1;", "+use const App\\Limits\\MAX;"),
                changed(
                    "src/app/Handler.java",
                    "+import java.util.List;",
                    "+import a.b.c.d.Missing;",
                    "+/* Handler in a block comment */",
                    "+    Ab x; int y;"),
                changed(
                    "a.py",
                    "+from ... import x",
                    "+from . import *",
                    "+from missing import *",
                    "+from missing import a, , b"),
                new FileDiff(null, "modified", 1, 0, 1, "@@ -1 +1 @@\n+x"))));
    assertEquals(
        List.of(), RelatedReferences.extract(List.of(), Set.of(), repository, path -> false));
    assertEquals(
        List.of(),
        RelatedReferences.extract(
            List.of(changed("a.py", "+import b")), Set.of(), null, path -> false));
  }

  @Test
  void rootFilesRelativePathsAndSuffixIncludesResolve() {
    assertEquals(
        List.of("b.py"),
        paths(extract(Set.of("a.py", "b.py"), changed("a.py", "+from . import b"))));
    assertEquals(
        List.of("util.js"),
        paths(extract(Set.of("app.js", "util.js"), changed("app.js", "+import u from './util';"))));
    assertEquals(
        List.of("lib/billing/tax.rb"),
        paths(
            extract(
                Set.of("lib/billing/invoice.rb", "lib/billing/tax.rb"),
                changed("lib/billing/invoice.rb", "+require_relative 'tax.rb'"))));
    assertEquals(
        List.of("include/net/buffer.h"),
        paths(
            extract(
                Set.of("src/socket.c", "include/net/buffer.h"),
                changed("src/socket.c", "+#include \"net/buffer.h\""))));
    assertEquals(
        List.of(),
        paths(
            extract(
                Set.of("src/socket.c", "a/net/buffer.h", "b/net/buffer.h"),
                changed("src/socket.c", "+#include \"../../net/buffer.h\""))));
    assertEquals(
        List.of("core/src/limits.rs"),
        paths(
            extract(
                Set.of("core/src/api.rs", "core/src/limits.rs"),
                changed("core/src/api.rs", "+#[derive(Debug)]", "+use crate::limits::Quota;"))));
    assertEquals("a/b", RelatedReferences.normalize("", "./a//b"));
    assertEquals(null, RelatedReferences.normalize("", "."));
  }

  @Test
  void goImportsPickTheDeepestPackageDirectory() {
    var repository =
        Set.of(
            "main.go",
            "auth/a.go",
            "internal/auth/b.go",
            "internal/auth/c.go",
            "pkg/auth/d.go",
            "x/y/z.go");

    assertEquals(
        List.of("internal/auth/b.go", "internal/auth/c.go"),
        paths(extract(repository, changed("x/y/z.go", "+\t\"example.com/internal/auth\""))));
    assertEquals(
        List.of("internal/auth/b.go", "internal/auth/c.go"),
        paths(extract(repository, changed("x/y/z.go", "+import a \"internal/auth\""))));
  }

  @Test
  void aPhpNameAloneMatchesOnlyWhenOneFileHasIt() {
    assertEquals(
        List.of("src/Billing/Tax.php"),
        paths(
            extract(
                Set.of("src/Http/Checkout.php", "src/Billing/Tax.php"),
                changed("src/Http/Checkout.php", "+use Vendor\\Tax;"))));
    assertEquals(
        List.of(),
        paths(
            extract(
                Set.of("src/Http/Checkout.php", "src/Billing/Tax.php", "src/Legacy/Tax.php"),
                changed("src/Http/Checkout.php", "+use Vendor\\Tax;"))));
  }

  @Test
  void typeNamesReadOnlyTheirOwnLanguageAndEveryLanguageFamily() {
    assertEquals(
        List.of(),
        paths(
            extract(
                Set.of("src/Handler.java", "tools/RateLimiter.py"),
                changed(
                    "src/Handler.java", "+    RateLimiter.acquire(); RateLimiter.release();"))));
    assertEquals(
        List.of("Api/RateLimiter.cs"),
        paths(
            extract(
                Set.of("Api/Handler.cs", "Api/RateLimiter.cs"),
                changed("Api/Handler.cs", "+using Api.Limits;", "+    RateLimiter.Acquire();"))));
    for (var extension : List.of("rb", "rs", "go", "dart")) {
      assertEquals(
          List.of("lib/rate_limiter." + extension),
          paths(
              extract(
                  Set.of("lib/handler." + extension, "lib/rate_limiter." + extension),
                  changed("lib/handler." + extension, "+    RateLimiter.acquire()"))),
          extension);
    }
  }

  @Test
  void theNearestOfSeveralFilesIsMeasuredByLeadingDirectories() {
    assertEquals(
        List.of("a/Config.java"),
        paths(
            extract(
                Set.of("a/b/c/Handler.java", "a/Config.java", "x/Config.java"),
                changed("a/b/c/Handler.java", "+    Config.load();"))));
    assertEquals(
        List.of("a/b/c/Config.java"),
        paths(
            extract(
                Set.of("a/Handler.java", "a/b/c/Config.java", "x/Config.java"),
                changed("a/Handler.java", "+    Config.load();"))));
    assertEquals(
        List.of(),
        paths(
            extract(
                Set.of("Handler.java", "Config.java", "x/Config.java"),
                changed("Handler.java", "+    Config.load();"))));
  }

  @Test
  void theCalledMembersKeptPerCandidateAreCapped() {
    var calls = new StringBuilder("+    Limits.check()");
    for (var i = 0; i < RelatedReferences.MAX_MEMBERS + 5; i++) {
      calls.append(".step").append(i).append("()");
    }

    var candidates =
        extract(Set.of("src/A.java", "src/Limits.java"), changed("src/A.java", calls.toString()));

    assertEquals(RelatedReferences.MAX_MEMBERS, candidates.get(0).members().size());
  }
}
