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

import java.util.Locale;
import java.util.Set;

/**
 * The fixed rule list of the deterministic security scan (#60): what each rule looks for, the grade
 * it publishes at, the title it publishes under, and the words a model finding's title uses when it
 * reports the same defect. The list is deliberately short and every rule is documented in the
 * README; a rule is added only when a line-level pattern identifies the defect with a low false
 * positive rate.
 *
 * <p>Grades are part of the rule, not judged per review. A credential in a well-known provider
 * format, or a private key, is {@link RiskLevel#CRITICAL}: whoever reads the pull request can use
 * it. A JSON Web Token and a generic {@code password = "..."} assignment are {@link
 * RiskLevel#HIGH}: the format alone does not prove a live credential. Confidence is {@link
 * Confidence#HIGH} for every rule (#921): it answers whether the pattern is on the line, which a
 * match settles, so a scan finding never carries the "verify before acting" hedge.
 *
 * <p>{@link #ROOT_USER} is {@link RiskLevel#MEDIUM}, the risk {@link SeverityCalibrator} gives the
 * model's missing-privilege-drop class (#773): a container that starts as root breaks nothing by
 * itself, but a compromise of it starts with root. Medium never blocks a merge under any {@link
 * BlockingStrictness}, so the finding informs the verdict without deciding it. The calibrator pins
 * the model's finding at medium confidence because the model's claim reaches past the line; the
 * scan's claim is the line, and a model finding about the same defect collapses into the scan's
 * ({@link #defectWords}), so the two grades never post side by side.
 */
enum SecurityRule {
  AWS_ACCESS_KEY(Category.SECRET, "AWS access key ID", RiskLevel.CRITICAL),
  GITHUB_TOKEN(Category.SECRET, "GitHub token", RiskLevel.CRITICAL),
  SLACK_TOKEN(Category.SECRET, "Slack token", RiskLevel.CRITICAL),
  GOOGLE_API_KEY(Category.SECRET, "Google API key", RiskLevel.CRITICAL),
  STRIPE_LIVE_KEY(Category.SECRET, "Stripe live key", RiskLevel.CRITICAL),
  PRIVATE_KEY(Category.SECRET, "private key", RiskLevel.CRITICAL),
  JWT(Category.SECRET, "JSON Web Token", RiskLevel.HIGH),
  GENERIC_SECRET(Category.SECRET, "credential", RiskLevel.HIGH),

  OPEN_ADMIN_PORT(
      Category.IAC,
      "admin port open to the internet",
      RiskLevel.HIGH,
      "An ingress rule on this line admits `0.0.0.0/0` or `::/0` to an administration port (SSH"
          + " 22, Telnet 23, RDP 3389 or WinRM 5985/5986), or to every port, so the service is"
          + " reachable from any address on the internet and exposed to credential stuffing and"
          + " exploit scanning. Restrict the source to the addresses that need it (a VPN or"
          + " bastion range), or reach the host through a session manager instead of an open"
          + " port.",
      Set.of(
          "ingress",
          "cidr",
          "ssh",
          "rdp",
          "port",
          "ports",
          "firewall",
          "security",
          "internet",
          "world",
          "open",
          "exposed",
          Topics.PUBLIC,
          "winrm",
          "telnet"),
      Set.of("ssh", "rdp", "winrm", "telnet")),
  PUBLIC_BUCKET(
      Category.IAC,
      "S3 bucket made public",
      RiskLevel.HIGH,
      "This line grants public access to an S3 bucket: a `public-read`/`public-read-write` ACL, or"
          + " an S3 Block Public Access setting switched off. Every object in the bucket becomes"
          + " readable (or writable) by anyone. Keep Block Public Access on and serve public"
          + " content through a CDN origin access identity or pre-signed URLs instead.",
      Set.of("s3", "bucket", Topics.PUBLIC, "acl", "access", "block"),
      Set.of(Topics.PUBLIC)),
  IAM_WILDCARD(
      Category.IAC,
      "IAM policy allows every action on every resource",
      RiskLevel.HIGH,
      "This statement allows `Action: \"*\"` on `Resource: \"*\"`, which is full administrator"
          + " access: whatever holds the policy can read every secret, change every permission"
          + " and delete every resource in the account. Grant the specific actions on the specific"
          + " resources the workload uses.",
      Set.of(
          "iam",
          "wildcard",
          "policy",
          "permission",
          "permissions",
          Topics.PRIVILEGE,
          Topics.PRIVILEGES,
          "admin",
          "administrator",
          "action",
          "actions",
          "resource",
          "resources",
          "least"),
      Set.of("wildcard", "administrator")),
  PRIVILEGED_CONTAINER(
      Category.IAC,
      "privileged container",
      RiskLevel.HIGH,
      "`privileged: true` gives the container every capability and access to the host's devices,"
          + " so a compromise of the container is a compromise of the node. Drop it and add only"
          + " the specific capabilities the workload needs.",
      Set.of(
          "privileged", Topics.PRIVILEGE, Topics.PRIVILEGES, "container", "capabilities", "root"),
      Set.of("privileged")),
  HOST_NAMESPACE(
      Category.IAC,
      "pod shares a host namespace",
      RiskLevel.HIGH,
      "`hostNetwork`, `hostPID` or `hostIPC` set to `true` puts the pod in the node's own"
          + " network, process or IPC namespace: it can reach services bound to the node's"
          + " loopback, see and signal every process on the node, or read other workloads' shared"
          + " memory. Remove it unless the workload is a node agent that genuinely needs it.",
      Set.of(
          "hostnetwork",
          "hostpid",
          "hostipc",
          "host",
          "namespace",
          "namespaces",
          "network",
          "pid",
          "ipc",
          "isolation"),
      Set.of("hostnetwork", "hostpid", "hostipc")),
  ROOT_USER(
      Category.IAC,
      "final image stage runs as root",
      RiskLevel.MEDIUM,
      "The last `USER` instruction of the image's final stage is `root`, so the container starts"
          + " every process as root and a compromise of it starts with root privileges. Switch to"
          + " an unprivileged user (`USER 10001` or a named user created in the image) after the"
          + " steps that need root.",
      Set.of(
          "root",
          "user",
          Topics.PRIVILEGE,
          Topics.PRIVILEGES,
          "drop",
          "nonroot",
          "non",
          "unprivileged"),
      Set.of("root")),
  UNENCRYPTED_STORAGE(
      Category.IAC,
      "storage encryption turned off",
      RiskLevel.MEDIUM,
      "This line sets `encrypted = false` (or `storage_encrypted = false`), so the volume, snapshot"
          + " or database is stored unencrypted at rest. Leave encryption on; it is free on every"
          + " AWS storage service that exposes the flag and cannot be turned on later without"
          + " recreating the resource.",
      Set.of("encryption", "encrypted", "unencrypted", "rest", "kms", "storage"),
      Set.of("encryption", "encrypted", "unencrypted"));

  /** Which half of the scan a rule belongs to; each half has its own switch. */
  enum Category {
    SECRET,
    IAC
  }

  /**
   * The prefix every title the scan publishes starts with. It tells a reader the finding came from
   * a pattern match, and it lets a later round recognize its own findings in the persisted response
   * ({@link #fromTitle}).
   */
  static final String TITLE_PREFIX = "Security scan: ";

  private final Category category;
  private final String label;
  private final RiskLevel risk;
  private final String explanation;
  private final Set<String> topic;
  private final Set<String> defectWords;

  SecurityRule(Category category, String label, RiskLevel risk) {
    this(category, label, risk, "", Topics.SECRET, Set.of());
  }

  SecurityRule(
      Category category,
      String label,
      RiskLevel risk,
      String explanation,
      Set<String> topic,
      Set<String> defectWords) {
    this.category = category;
    this.label = label;
    this.risk = risk;
    this.explanation = explanation;
    this.topic = topic;
    this.defectWords = defectWords;
  }

  /**
   * Title words of a model finding that reports a leaked credential. A holder class, because an
   * enum constant's constructor arguments cannot read a static field of the enum itself.
   */
  private static final class Topics {
    static final String PRIVILEGE = "privilege";
    static final String PRIVILEGES = "privileges";
    static final String PUBLIC = "public";

    static final Set<String> SECRET =
        Set.of(
            "secret",
            "secrets",
            "credential",
            "credentials",
            "token",
            "tokens",
            "password",
            "passwords",
            "apikey",
            "key",
            "keys",
            "hardcoded",
            "leaked",
            "leak",
            "jwt",
            "private",
            "pem");

    private Topics() {}
  }

  Category category() {
    return category;
  }

  String label() {
    return label;
  }

  String risk() {
    return risk.name().toLowerCase(Locale.ROOT);
  }

  /** Every rule's confidence: a match settles whether the pattern is on the line (#921). */
  String confidence() {
    return Confidence.HIGH.name().toLowerCase(Locale.ROOT);
  }

  String explanation() {
    return explanation;
  }

  /** The words a model finding's title uses when it reports this rule's defect. */
  Set<String> topic() {
    return topic;
  }

  /**
   * The words of {@link #topic} that name an IaC rule's defect and nothing else nearby, so one of
   * them in a model title near a detection makes the two the same defect (#921, #952): {@code root}
   * beside a final-stage {@code USER root}, {@code privileged} beside {@code privileged: true}. A
   * broad topic word ({@code network}, {@code port}, {@code user}) is not one. Empty for the secret
   * rules, whose duplicates are found on the matched line instead.
   */
  Set<String> defectWords() {
    return defectWords;
  }

  /**
   * The title an IaC finding publishes under. A secret finding's title also names the redacted
   * value and is built by the scanner.
   */
  String iacTitle() {
    return TITLE_PREFIX + label;
  }

  /** The secret title head, which the redacted value and the key name follow. */
  String secretTitleHead() {
    return TITLE_PREFIX + "hardcoded " + label;
  }

  /**
   * The rule a persisted finding was raised by, recognized by its exact title shape, or {@code
   * null} for a finding the scan did not raise.
   */
  static SecurityRule fromTitle(String title) {
    if (title == null || !title.startsWith(TITLE_PREFIX)) {
      return null;
    }
    for (SecurityRule rule : values()) {
      if (rule.category == Category.IAC) {
        if (title.equals(rule.iacTitle())) {
          return rule;
        }
      } else if (title.startsWith(rule.secretTitleHead() + " (")
          || title.startsWith(rule.secretTitleHead() + " in ")) {
        return rule;
      }
    }
    return null;
  }
}
