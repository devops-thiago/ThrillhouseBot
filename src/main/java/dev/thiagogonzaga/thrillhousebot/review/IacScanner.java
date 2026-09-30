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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The risky infrastructure-as-code rules of the deterministic security scan (#60). Each rule fires
 * on a line the pull request adds, in a file of the type the rule applies to, and a rule that needs
 * surrounding context (the direction and port of a firewall rule, the resource of an IAM statement)
 * reads it from the new-side lines of the same hunk. A rule whose context is not in the patch does
 * not fire: precision is the point of a deterministic pass, and the model review still covers what
 * a pattern cannot settle.
 */
final class IacScanner {

  /**
   * One risky line.
   *
   * @param rule the rule that matched
   * @param index the added line's position in {@link PatchLines#lines()}
   * @param line the added line's right-side number
   * @param text the added line's text (infrastructure configuration, not a secret)
   */
  record Hit(SecurityRule rule, int index, int line, String text) {}

  /** The administration ports whose exposure to any address the open-port rule reports. */
  static final Set<Integer> ADMIN_PORTS = Set.of(22, 23, 3389, 5985, 5986);

  /** Lines before and after a matched line that a context-reading rule looks at. */
  private static final int WINDOW = 15;

  private static final Pattern OPEN_CIDR =
      Pattern.compile("(?<![\\d.])0\\.0\\.0\\.0/0(?!\\d)|(?<![\\dA-Fa-f:])::/0(?!\\d)");

  private static final Pattern INGRESS =
      Pattern.compile(
          "(?i)\\bingress\\b|SecurityGroupIngress|\\bsource_ranges\\b|\\bsourceRanges\\b"
              + "|direction\\W+INGRESS");

  private static final Pattern EGRESS =
      Pattern.compile(
          "(?i)\\begress\\b|SecurityGroupEgress|\\bdestination_ranges\\b|\\bdestinationRanges\\b"
              + "|direction\\W+EGRESS");

  private static final Pattern FROM_PORT =
      Pattern.compile("(?i)\\b\"?from_?port\"?\\s*[=:]\\s*\"?(-?\\d{1,5})");

  private static final Pattern TO_PORT =
      Pattern.compile("(?i)\\b\"?to_?port\"?\\s*[=:]\\s*\"?(-?\\d{1,5})");

  private static final Pattern PORT_LIST =
      Pattern.compile("(?i)\\b\"?(?:ports?|port_range|destination_port_range)\"?\\s*[=:]\\s*(.+)");

  private static final Pattern PORT_SPEC = Pattern.compile("(\\d{1,5})(?:\\s*-\\s*(\\d{1,5}))?");

  private static final Pattern ALL_PROTOCOLS =
      Pattern.compile("(?i)\\b\"?(?:ip_?)?protocol\"?\\s*[=:]\\s*[\"']?(?:-1|all)[\"']?,?$");

  /** The spellings of a public bucket, one expression per setting to keep each one simple. */
  private static final List<Pattern> PUBLIC_BUCKET =
      List.of(
          Pattern.compile("\\bacl\\s*=\\s*\"public-read(?:-write)?\""),
          Pattern.compile("\"?AccessControl\"?\\s*:\\s*[\"']?PublicRead(?:Write)?\\b"),
          Pattern.compile(
              "\\b(?:block_public_acls|block_public_policy|ignore_public_acls"
                  + "|restrict_public_buckets)\\s*=\\s*false\\b"),
          Pattern.compile(
              "\"?(?:BlockPublicAcls|BlockPublicPolicy|IgnorePublicAcls|RestrictPublicBuckets)"
                  + "\"?\\s*:\\s*[\"']?false\\b"));

  private static final Pattern WILDCARD_ACTION =
      Pattern.compile("(?i)(?<![a-z])\"?actions?\"?\\s*+[=:]\\s*+\\[?\\s*+[\"']\\*[\"']]?,?$");

  private static final Pattern WILDCARD_RESOURCE =
      Pattern.compile("(?i)(?<![a-z])\"?resources?\"?\\s*+[=:]\\s*+\\[?\\s*+[\"']\\*[\"']]?,?$");

  private static final Pattern DENY_EFFECT =
      Pattern.compile("(?i)\"?effect\"?\\s*[=:]\\s*[\"']?deny");

  private static final Pattern PRIVILEGED =
      Pattern.compile("^\\s*(?:-\\s*)?\"?privileged\"?\\s*:\\s*true\\b");

  private static final Pattern HOST_NAMESPACE =
      Pattern.compile("^\\s*(?:-\\s*)?\"?(?:hostNetwork|hostPID|hostIPC)\"?\\s*:\\s*true\\b");

  private static final Pattern ROOT_USER =
      Pattern.compile("(?i)^\\s*USER\\s+(?:root|0)(?::(?:root|0))?\\s*$");

  private static final Pattern STAGE_OR_USER = Pattern.compile("(?i)^\\s*(?:FROM|USER)\\s+\\S");

  private static final Pattern UNENCRYPTED =
      Pattern.compile("^\\s*(?:encrypted|storage_encrypted)\\s*=\\s*false\\b");

  /** File types a rule can apply to. */
  private enum FileKind {
    TERRAFORM,
    YAML,
    JSON,
    DOCKERFILE,
    OTHER
  }

  /**
   * Extensions that make a {@code Dockerfile}-named file documentation or source about the artifact
   * rather than the artifact ({@code Dockerfile.md}); the same exclusion {@link SeverityCalibrator}
   * applies when it recognizes a container file.
   */
  private static final Set<String> DOCUMENTARY_EXTENSIONS =
      Set.of("md", "markdown", "txt", "rst", "adoc", "html");

  private IacScanner() {}

  /**
   * The risky lines a file's patch adds. {@code newFile} says the patch is the whole file (a file
   * the pull request adds), which is what lets the final-stage root rule know nothing follows.
   */
  static List<Hit> scan(String filename, PatchLines patch, boolean newFile) {
    var kind = kindOf(filename);
    var hits = new ArrayList<Hit>();
    if (kind == FileKind.OTHER) {
      return hits;
    }
    var lines = patch.lines();
    for (int i = 0; i < lines.size(); i++) {
      var line = lines.get(i);
      if (!line.added() || isComment(line.text())) {
        continue;
      }
      var rule = match(kind, lines, i, patch, newFile);
      if (rule != null) {
        hits.add(new Hit(rule, i, line.number(), line.text()));
      }
    }
    return hits;
  }

  private static SecurityRule match(
      FileKind kind, List<PatchLines.Line> lines, int index, PatchLines patch, boolean newFile) {
    var text = lines.get(index).text();
    if (kind == FileKind.DOCKERFILE) {
      return ROOT_USER.matcher(text).matches() && isFinalUser(lines, index, patch, newFile)
          ? SecurityRule.ROOT_USER
          : null;
    }
    if (kind == FileKind.TERRAFORM && UNENCRYPTED.matcher(text).find()) {
      return SecurityRule.UNENCRYPTED_STORAGE;
    }
    if (kind != FileKind.TERRAFORM && (PRIVILEGED.matcher(text).find())) {
      return SecurityRule.PRIVILEGED_CONTAINER;
    }
    if (kind != FileKind.TERRAFORM && HOST_NAMESPACE.matcher(text).find()) {
      return SecurityRule.HOST_NAMESPACE;
    }
    if (PUBLIC_BUCKET.stream().anyMatch(p -> p.matcher(text).find())) {
      return SecurityRule.PUBLIC_BUCKET;
    }
    if (WILDCARD_ACTION.matcher(text.stripTrailing()).find()
        && isAllowAllStatement(window(lines, index))) {
      return SecurityRule.IAM_WILDCARD;
    }
    if (OPEN_CIDR.matcher(text).find() && isOpenAdminIngress(window(lines, index))) {
      return SecurityRule.OPEN_ADMIN_PORT;
    }
    return null;
  }

  /**
   * The lines a context-reading rule may look at around one added line, and that line's position
   * among them.
   */
  private record Window(List<PatchLines.Line> lines, int index) {
    PatchLines.Line line(int i) {
      return lines.get(i);
    }

    PatchLines.Line self() {
      return lines.get(index);
    }
  }

  /**
   * The new-side lines of {@code index}'s hunk within {@link #WINDOW} lines of it: context never
   * crosses a hunk boundary, because the lines between two hunks are not in the patch.
   */
  private static Window window(List<PatchLines.Line> lines, int index) {
    int hunk = lines.get(index).hunk();
    int start = index;
    while (start > 0 && index - start < WINDOW && lines.get(start - 1).hunk() == hunk) {
      start--;
    }
    int end = index + 1;
    while (end < lines.size() && end - index <= WINDOW && lines.get(end).hunk() == hunk) {
      end++;
    }
    return new Window(lines.subList(start, end), index - start);
  }

  /**
   * Whether a {@code USER root} line is the final stage's last {@code USER}: no later {@code FROM}
   * or {@code USER} in the patch, and the patch provably reaches the end of the file — the file is
   * new, or the line is in the last hunk and that hunk ran out of file before its trailing context.
   * An instruction outside the patch could otherwise drop privilege after it.
   */
  private static boolean isFinalUser(
      List<PatchLines.Line> lines, int index, PatchLines patch, boolean newFile) {
    for (int i = index + 1; i < lines.size(); i++) {
      if (STAGE_OR_USER.matcher(lines.get(i).text()).find()) {
        return false;
      }
    }
    return newFile
        || (lines.get(index).hunk() == patch.lastHunk() && patch.lastHunkReachesEndOfFile());
  }

  /**
   * Whether a wildcard {@code Action} line belongs to an allow statement on every resource: a
   * wildcard {@code Resource} in the same statement ({@link #block}) and no {@code Effect: Deny}
   * there. A statement spread wider than the hunk shows does not fire.
   */
  private static boolean isAllowAllStatement(Window window) {
    boolean wildcardResource = false;
    for (var line : block(window)) {
      if (DENY_EFFECT.matcher(line.text()).find()) {
        return false;
      }
      wildcardResource |= WILDCARD_RESOURCE.matcher(line.text().stripTrailing()).find();
    }
    return wildcardResource;
  }

  /**
   * Whether a line opening {@code 0.0.0.0/0} or {@code ::/0} is an ingress rule reaching an
   * administration port. The direction is the nearest ingress or egress marker above the line
   * (where the enclosing block or list is named), or, when none is above, inside the rule's own
   * block below it (a {@code type = "ingress"} written after the CIDR). The ports come from the
   * rule's own block ({@link #block}), or failing that from a port list in its resource ({@link
   * #portListReachesAdminPort}): the nearest {@code from_port}/{@code to_port} pair or port list,
   * and an all-protocols rule reaches every port. A rule whose direction or ports are not in the
   * hunk does not fire.
   */
  private static boolean isOpenAdminIngress(Window window) {
    if (!isIngress(window)) {
      return false;
    }
    return reachesAdminPort(block(window))
        .or(() -> portListReachesAdminPort(resource(window)))
        .orElse(false);
  }

  /**
   * Whether the port specification among {@code scope} reaches an administration port, or empty
   * when the scope carries none.
   */
  private static Optional<Boolean> reachesAdminPort(List<PatchLines.Line> scope) {
    for (var line : scope) {
      if (ALL_PROTOCOLS.matcher(line.text().stripTrailing()).find()) {
        return Optional.of(true);
      }
    }
    Integer from = nearestPort(scope, FROM_PORT);
    Integer to = nearestPort(scope, TO_PORT);
    if (from != null || to != null) {
      int low = from != null ? from : to;
      int high = to != null ? to : from;
      return Optional.of(coversAdminPort(low, high));
    }
    for (var line : scope) {
      Matcher list = PORT_LIST.matcher(line.text());
      if (list.find()) {
        return Optional.of(listCoversAdminPort(list.group(1)));
      }
    }
    return Optional.empty();
  }

  /**
   * The fallback for a rule whose own block names no ports: a port list elsewhere in the resource
   * (a GCP firewall's {@code allow { ports }}). Only a port list is read there. A protocol or a
   * {@code from_port}/{@code to_port} pair in the resource belongs to a sibling rule, such as an
   * all-protocols egress next to the ingress, and would otherwise be read as this rule's ports.
   */
  private static Optional<Boolean> portListReachesAdminPort(List<PatchLines.Line> scope) {
    for (var line : scope) {
      Matcher list = PORT_LIST.matcher(line.text());
      if (list.find()) {
        return Optional.of(listCoversAdminPort(list.group(1)));
      }
    }
    return Optional.empty();
  }

  /**
   * The top-level resource around {@code index}, nearest first, for a rule whose ports sit in a
   * sibling block rather than its own ({@code source_ranges} next to a GCP firewall's {@code allow
   * { ports }}): upward to the unindented line that opens it, downward to the unindented brace that
   * closes it, within the {@link #window}.
   */
  private static List<PatchLines.Line> resource(Window window) {
    var result = new ArrayList<PatchLines.Line>();
    result.add(window.self());
    for (int i = window.index() - 1; i >= 0; i--) {
      result.add(window.line(i));
      if (isTopLevel(window.line(i).text())) {
        break;
      }
    }
    for (int i = window.index() + 1; i < window.lines().size(); i++) {
      if (isTopLevel(window.line(i).text())) {
        break;
      }
      result.add(window.line(i));
    }
    return result;
  }

  private static boolean isTopLevel(String text) {
    return !text.isEmpty() && !Character.isWhitespace(text.charAt(0));
  }

  private static boolean isIngress(Window window) {
    for (int i = window.index(); i >= 0; i--) {
      var text = window.line(i).text();
      if (i < window.index() && closesBlock(text)) {
        break;
      }
      var direction = direction(text);
      if (direction != Direction.NONE) {
        return direction == Direction.INGRESS;
      }
    }
    for (var line : block(window)) {
      var direction = direction(line.text());
      if (direction != Direction.NONE) {
        return direction == Direction.INGRESS;
      }
    }
    return false;
  }

  /** The traffic direction a line names; a line naming both or neither says nothing. */
  private enum Direction {
    INGRESS,
    EGRESS,
    NONE
  }

  private static Direction direction(String text) {
    boolean ingress = INGRESS.matcher(text).find();
    boolean egress = EGRESS.matcher(text).find();
    if (ingress == egress) {
      return Direction.NONE;
    }
    return ingress ? Direction.INGRESS : Direction.EGRESS;
  }

  /**
   * The lines of the rule {@code index} sits in, nearest first (the line above before the line
   * below at equal distance): upward up to and including the line that opens the block (a line
   * ending in an opening brace) or YAML list item ({@code - }), unless the line itself opens it;
   * downward up to the line that closes the block or opens the next one; all within the {@link
   * #window}. Brackets do not bound a rule: {@code cidr_blocks = [} is a value inside it. Reading
   * ports from a whole window instead would take the neighbouring rule's port whenever it happened
   * to be closer.
   */
  private static List<PatchLines.Line> block(Window window) {
    var above = new ArrayList<PatchLines.Line>();
    for (int i = window.index() - 1;
        !opensBlock(window.self().text()) && i >= 0 && !closesBlock(window.line(i).text());
        i--) {
      var line = window.line(i);
      above.add(line);
      if (opensBlock(line.text())) {
        break;
      }
    }
    var below = new ArrayList<PatchLines.Line>();
    for (int i = window.index() + 1; i < window.lines().size(); i++) {
      var line = window.line(i);
      if (closesBlock(line.text()) || opensBlock(line.text())) {
        break;
      }
      below.add(line);
    }
    var result = new ArrayList<PatchLines.Line>();
    result.add(window.self());
    for (int d = 0; d < Math.max(above.size(), below.size()); d++) {
      if (d < above.size()) {
        result.add(above.get(d));
      }
      if (d < below.size()) {
        result.add(below.get(d));
      }
    }
    return result;
  }

  private static boolean opensBlock(String text) {
    var stripped = text.strip();
    return stripped.endsWith("{") || stripped.startsWith("- ");
  }

  private static boolean closesBlock(String text) {
    var stripped = text.strip();
    return stripped.startsWith("}");
  }

  private static Integer nearestPort(List<PatchLines.Line> nearby, Pattern pattern) {
    for (var line : nearby) {
      Matcher matcher = pattern.matcher(line.text());
      if (matcher.find()) {
        return Integer.parseInt(matcher.group(1));
      }
    }
    return null;
  }

  private static boolean listCoversAdminPort(String spec) {
    Matcher port = PORT_SPEC.matcher(spec);
    while (port.find()) {
      int low = Integer.parseInt(port.group(1));
      int high = port.group(2) != null ? Integer.parseInt(port.group(2)) : low;
      if (coversAdminPort(low, high)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a port range contains an administration port. {@code -1} and {@code 0..65535} are the
   * spellings of "every port" in the security-group APIs.
   */
  private static boolean coversAdminPort(int low, int high) {
    if (Math.min(low, high) < 0) {
      return true;
    }
    for (int port : ADMIN_PORTS) {
      if (low <= port && port <= high) {
        return true;
      }
    }
    return false;
  }

  private static boolean isComment(String text) {
    var stripped = text.strip();
    return stripped.startsWith("#") || stripped.startsWith("//");
  }

  private static FileKind kindOf(String filename) {
    var name = filename.substring(filename.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    if (name.endsWith(".tf")) {
      return FileKind.TERRAFORM;
    }
    if (name.endsWith(".yml") || name.endsWith(".yaml")) {
      return FileKind.YAML;
    }
    if (name.endsWith(".json")) {
      return FileKind.JSON;
    }
    return isContainerFile(name) ? FileKind.DOCKERFILE : FileKind.OTHER;
  }

  private static boolean isContainerFile(String name) {
    var segments = List.of(name.split("\\."));
    if (!segments.contains("dockerfile") && !segments.contains("containerfile")) {
      return false;
    }
    var extension = segments.get(segments.size() - 1);
    return segments.size() == 1 || !DOCUMENTARY_EXTENSIONS.contains(extension);
  }
}
