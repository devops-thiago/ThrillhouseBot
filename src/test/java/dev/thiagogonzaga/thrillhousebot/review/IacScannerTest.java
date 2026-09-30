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

import java.util.List;
import org.junit.jupiter.api.Test;

/** #60: the risky infrastructure-as-code half of the deterministic security scan. */
class IacScannerTest {

  /** A new file's patch: every line added, starting at line 1. */
  private static PatchLines added(String... lines) {
    var sb = new StringBuilder("@@ -0,0 +1," + lines.length + " @@\n");
    for (var line : lines) {
      sb.append('+').append(line).append('\n');
    }
    return PatchLines.parse(sb.toString());
  }

  /** A raw patch, one line per argument. */
  private static PatchLines patch(String... lines) {
    return PatchLines.parse(String.join("\n", lines) + "\n");
  }

  private static List<IacScanner.Hit> scanNew(String filename, String... lines) {
    return IacScanner.scan(filename, added(lines), true);
  }

  private static List<SecurityRule> rules(List<IacScanner.Hit> hits) {
    return hits.stream().map(IacScanner.Hit::rule).toList();
  }

  // --- admin port open to the internet ---

  @Test
  void terraformIngressOpeningSshToTheWorldIsReportedAndItsEgressIsNot() {
    var hits =
        scanNew(
            "infra/sg.tf",
            "resource \"aws_security_group\" \"bastion\" {",
            "  ingress {",
            "    from_port   = 22",
            "    to_port     = 22",
            "    protocol    = \"tcp\"",
            "    cidr_blocks = [\"0.0.0.0/0\"]",
            "  }",
            "  egress {",
            "    from_port   = 0",
            "    to_port     = 0",
            "    protocol    = \"-1\"",
            "    cidr_blocks = [\"0.0.0.0/0\"]",
            "  }",
            "}");
    assertEquals(List.of(SecurityRule.OPEN_ADMIN_PORT), rules(hits));
    assertEquals(6, hits.get(0).line());
    assertEquals("    cidr_blocks = [\"0.0.0.0/0\"]", hits.get(0).text());
  }

  @Test
  void anIngressOnAWebPortIsNotReported() {
    assertTrue(
        scanNew(
                "sg.tf",
                "ingress {",
                "  from_port = 443",
                "  to_port = 443",
                "  protocol = \"tcp\"",
                "  cidr_blocks = [\"0.0.0.0/0\"]",
                "}")
            .isEmpty());
  }

  @Test
  void anIngressOnEveryPortOrProtocolIsReported() {
    assertEquals(
        1,
        scanNew(
                "sg.tf",
                "ingress {",
                "  from_port = 0",
                "  to_port = 65535",
                "  protocol = \"tcp\"",
                "  cidr_blocks = [",
                "    \"0.0.0.0/0\",",
                "  ]",
                "}")
            .size());
    assertEquals(
        1,
        scanNew(
                "sg.tf",
                "ingress {",
                "  from_port = 0",
                "  to_port = 0",
                "  protocol = \"-1\"",
                "  ipv6_cidr_blocks = [\"::/0\"]",
                "}")
            .size());
  }

  @Test
  void aSecurityGroupRuleNamingItsDirectionAfterTheCidrIsReported() {
    var hits =
        scanNew(
            "rules.tf",
            "resource \"aws_security_group_rule\" \"rdp\" {",
            "  cidr_blocks       = [\"0.0.0.0/0\"]",
            "  from_port         = 3389",
            "  to_port           = 3389",
            "  protocol          = \"tcp\"",
            "  type              = \"ingress\"",
            "}");
    assertEquals(List.of(SecurityRule.OPEN_ADMIN_PORT), rules(hits));
  }

  @Test
  void anOpenCidrWithNoDirectionOrNoPortsInTheHunkIsNotReported() {
    assertTrue(
        scanNew(
                "vpc.tf",
                "resource \"aws_route\" \"r\" {",
                "  destination_cidr_block = \"0.0.0.0/0\"",
                "}")
            .isEmpty());
    assertTrue(scanNew("sg.tf", "ingress {", "  cidr_blocks = [\"0.0.0.0/0\"]", "}").isEmpty());
  }

  @Test
  void aCommentedOutRuleIsNotReported() {
    assertTrue(
        scanNew(
                "sg.tf",
                "ingress {",
                "  from_port = 22",
                "  to_port = 22",
                "  # cidr_blocks = [\"0.0.0.0/0\"]",
                "}")
            .isEmpty());
  }

  @Test
  void cloudFormationYamlReportsOnlyTheAdminPortItem() {
    var hits =
        scanNew(
            "template.yaml",
            "      SecurityGroupIngress:",
            "        - IpProtocol: tcp",
            "          FromPort: 22",
            "          ToPort: 22",
            "          CidrIp: 0.0.0.0/0",
            "        - IpProtocol: tcp",
            "          FromPort: 443",
            "          ToPort: 443",
            "          CidrIp: 0.0.0.0/0");
    assertEquals(1, hits.size());
    assertEquals(5, hits.get(0).line());
  }

  @Test
  void cloudFormationJsonOnOneLine() {
    var hits =
        scanNew(
            "stack.json",
            "\"SecurityGroupIngress\": [",
            "  { \"IpProtocol\": \"tcp\", \"FromPort\": 5985, \"ToPort\": 5986, \"CidrIp\": \"0.0.0.0/0\" },",
            "  { \"IpProtocol\": \"tcp\", \"FromPort\": 80, \"ToPort\": 80, \"CidrIp\": \"0.0.0.0/0\" }",
            "]");
    assertEquals(1, hits.size());
    assertEquals(2, hits.get(0).line());
  }

  @Test
  void gcpFirewallPortListsAndRanges() {
    assertEquals(
        1,
        scanNew(
                "fw.tf",
                "resource \"google_compute_firewall\" \"ssh\" {",
                "  allow {",
                "    protocol = \"tcp\"",
                "    ports    = [\"20-25\"]",
                "  }",
                "  source_ranges = [\"0.0.0.0/0\"]",
                "}")
            .size());
    assertTrue(
        scanNew(
                "fw.tf",
                "resource \"google_compute_firewall\" \"web\" {",
                "  source_ranges = [\"0.0.0.0/0\"]",
                "  ports = [\"80\", \"443\"]",
                "}")
            .isEmpty());
  }

  @Test
  void anOverlongPortNumberNeitherFailsTheScanNorReadsAsAnAdminPort() {
    assertTrue(
        scanNew(
                "fw.tf",
                "ingress {",
                "  from_port = 123456789012",
                "  to_port = 123456789012",
                "  cidr_blocks = [\"0.0.0.0/0\"]",
                "}")
            .isEmpty());
    assertTrue(
        scanNew("fw.tf", "  source_ranges = [\"0.0.0.0/0\"]", "  ports = [\"99999999999\"]")
            .isEmpty());
  }

  @Test
  void aLonePortBoundOrMinusOneIsReadAsTheRange() {
    assertEquals(
        1,
        scanNew(
                "t.yaml",
                "SecurityGroupIngress:",
                "  - IpProtocol: tcp",
                "    FromPort: 22",
                "    CidrIp: 0.0.0.0/0")
            .size());
    assertEquals(
        1,
        scanNew(
                "t.yaml",
                "SecurityGroupIngress:",
                "  - IpProtocol: tcp",
                "    ToPort: 3389",
                "    CidrIp: 0.0.0.0/0")
            .size());
    assertEquals(
        1,
        scanNew(
                "t.yaml",
                "SecurityGroupIngress:",
                "  - IpProtocol: tcp",
                "    FromPort: -1",
                "    ToPort: -1",
                "    CidrIp: 0.0.0.0/0")
            .size());
  }

  @Test
  void theCidrOnTheFirstLineOfAListItemReadsTheItemBelowIt() {
    assertEquals(
        1,
        scanNew(
                "t.yaml",
                "SecurityGroupIngress:",
                "  - CidrIp: 0.0.0.0/0",
                "    FromPort: 22",
                "    ToPort: 22",
                "  - CidrIp: 10.0.0.0/8",
                "    FromPort: 443")
            .size());
  }

  @Test
  void aClosedBlockAboveEndsTheSearchForTheDirection() {
    var ingress =
        scanNew(
            "r.tf",
            "resource \"aws_security_group_rule\" \"out\" {",
            "  type = \"egress\"",
            "}",
            "resource \"aws_security_group_rule\" \"ssh\" {",
            "  cidr_blocks = [\"0.0.0.0/0\"]",
            "  from_port   = 22",
            "  to_port     = 22",
            "  type        = \"ingress\"",
            "}");
    assertEquals(1, ingress.size());
    assertEquals(5, ingress.get(0).line());
    assertTrue(
        scanNew(
                "r.tf",
                "resource \"aws_security_group_rule\" \"ssh\" {",
                "  cidr_blocks = [\"0.0.0.0/0\"]",
                "  from_port   = 22",
                "  to_port     = 22",
                "  type        = \"egress\"",
                "}")
            .isEmpty());
  }

  @Test
  void contextNeverCrossesAHunkOrTheWindow() {
    // The ingress block opens in the first hunk; the second hunk alone says nothing of direction.
    var split =
        patch(
            "@@ -1,3 +1,3 @@",
            " ingress {",
            "   from_port = 22",
            "   to_port = 22",
            "@@ -40,1 +40,2 @@",
            "+  cidr_blocks = [\"0.0.0.0/0\"]",
            " }");
    assertTrue(IacScanner.scan("sg.tf", split, false).isEmpty());

    var padding = new java.util.ArrayList<String>();
    padding.add("ingress {");
    padding.add("  from_port = 22");
    padding.add("  to_port = 22");
    for (int i = 0; i < 20; i++) {
      padding.add("  description_" + i + " = \"x\"");
    }
    padding.add("  cidr_blocks = [\"0.0.0.0/0\"]");
    for (int i = 0; i < 20; i++) {
      padding.add("  tag_" + i + " = \"y\"");
    }
    padding.add("}");
    assertTrue(scanNew("sg.tf", padding.toArray(String[]::new)).isEmpty());
  }

  @Test
  void aRuleAtTheEndOfAHunkReadsItsResourceFromThatHunkOnly() {
    var patch =
        patch(
            "@@ -1,3 +1,4 @@",
            "   allow {",
            "     ports = [\"22\"]",
            "   }",
            "+  source_ranges = [\"0.0.0.0/0\"]",
            "@@ -50,1 +51,1 @@",
            "-x",
            "+ports = [\"80\"]");
    assertEquals(1, IacScanner.scan("fw.tf", patch, false).size());
    assertEquals(
        1,
        scanNew(
                "t.yaml",
                "SecurityGroupIngress:",
                "  - IpProtocol: tcp",
                "    FromPort: 0",
                "    ToPort: -1",
                "    CidrIp: 0.0.0.0/0")
            .size());
  }

  @Test
  void aSiblingEgressProtocolIsNotReadAsAnIngressWithoutPorts() {
    assertTrue(
        scanNew(
                "web.tf",
                "resource \"aws_security_group\" \"web\" {",
                "  ingress {",
                "    cidr_blocks = [\"0.0.0.0/0\"]",
                "  }",
                "  egress {",
                "    from_port = 0",
                "    to_port   = 0",
                "    protocol  = \"-1\"",
                "  }",
                "}")
            .isEmpty());
  }

  @Test
  void aBlankLineInsideTheResourceDoesNotEndIt() {
    assertEquals(
        1,
        scanNew(
                "fw.tf",
                "resource \"google_compute_firewall\" \"rdp\" {",
                "  allow {",
                "    ports = [\"3389\"]",
                "  }",
                "",
                "  source_ranges = [\"::/0\"]",
                "",
                "}")
            .size());
  }

  @Test
  void slashCommentsAndContainerfilesAreRecognized() {
    assertTrue(
        scanNew(
                "sg.tf",
                "ingress {",
                "  from_port = 22",
                "  to_port = 22",
                "  // cidr_blocks = [\"0.0.0.0/0\"]",
                "}")
            .isEmpty());
    assertEquals(1, scanNew("Containerfile", "FROM x", "USER root").size());
  }

  // --- public S3 bucket ---

  @Test
  void publicBucketSettings() {
    assertEquals(
        List.of(SecurityRule.PUBLIC_BUCKET), rules(scanNew("s3.tf", "  acl = \"public-read\"")));
    assertEquals(
        List.of(SecurityRule.PUBLIC_BUCKET),
        rules(scanNew("s3.tf", "  block_public_acls = false")));
    assertEquals(
        List.of(SecurityRule.PUBLIC_BUCKET),
        rules(scanNew("stack.yaml", "      AccessControl: PublicReadWrite")));
    assertEquals(
        List.of(SecurityRule.PUBLIC_BUCKET),
        rules(scanNew("stack.json", "  \"RestrictPublicBuckets\": false,")));
    assertTrue(scanNew("s3.tf", "  acl = \"private\"").isEmpty());
    assertTrue(scanNew("s3.tf", "  block_public_acls = true").isEmpty());
  }

  // --- IAM wildcard ---

  @Test
  void anAllowStatementOnEveryActionAndResourceIsReported() {
    var hits =
        scanNew(
            "policy.json",
            "  \"Statement\": [{",
            "    \"Effect\": \"Allow\",",
            "    \"Action\": \"*\",",
            "    \"Resource\": \"*\"",
            "  }]");
    assertEquals(List.of(SecurityRule.IAM_WILDCARD), rules(hits));
    assertEquals(3, hits.get(0).line());
    assertEquals(
        1,
        scanNew("iam.tf", "statement {", "  actions   = [\"*\"]", "  resources = [\"*\"]", "}")
            .size());
  }

  @Test
  void iamNearMisses() {
    assertTrue(
        scanNew(
                "p.json",
                "{",
                "  \"Effect\": \"Deny\",",
                "  \"Action\": \"*\",",
                "  \"Resource\": \"*\"",
                "}")
            .isEmpty());
    assertTrue(
        scanNew(
                "p.json",
                "{",
                "  \"Action\": \"*\",",
                "  \"Resource\": \"arn:aws:s3:::logs/*\"",
                "}")
            .isEmpty());
    assertTrue(
        scanNew("p.json", "{", "  \"NotAction\": \"*\",", "  \"Resource\": \"*\"", "}").isEmpty());
    assertTrue(
        scanNew("p.json", "{", "  \"Action\": \"s3:*\",", "  \"Resource\": \"*\"", "}").isEmpty());
  }

  // --- Kubernetes ---

  @Test
  void privilegedContainersAndHostNamespaces() {
    assertEquals(
        List.of(SecurityRule.PRIVILEGED_CONTAINER),
        rules(scanNew("deploy.yaml", "          privileged: true")));
    assertEquals(
        List.of(SecurityRule.HOST_NAMESPACE, SecurityRule.HOST_NAMESPACE),
        rules(scanNew("pod.yml", "  hostNetwork: true", "  hostPID: true")));
    assertEquals(
        List.of(SecurityRule.PRIVILEGED_CONTAINER, SecurityRule.HOST_NAMESPACE),
        rules(scanNew("pod.yaml", "  privileged: True", "  hostPID: TRUE")));
    assertTrue(scanNew("deploy.yaml", "          privileged: false").isEmpty());
    assertTrue(scanNew("deploy.yaml", "          allowPrivilegeEscalation: false").isEmpty());
    assertTrue(scanNew("main.tf", "  privileged = true").isEmpty());
  }

  // --- Dockerfile USER root ---

  @Test
  void aNewDockerfileEndingAsRootIsReported() {
    var hits = scanNew("Dockerfile", "FROM alpine:3.20", "RUN apk add curl", "USER root");
    assertEquals(List.of(SecurityRule.ROOT_USER), rules(hits));
    assertEquals(3, hits.get(0).line());
    assertEquals(1, scanNew("docker/api.dockerfile", "FROM x", "USER 0:0").size());
  }

  @Test
  void rootFollowedByALaterUserOrStageIsNotReported() {
    assertTrue(scanNew("Dockerfile", "FROM x", "USER root", "RUN make", "USER app").isEmpty());
    assertTrue(scanNew("Dockerfile", "FROM x AS build", "USER root", "FROM y").isEmpty());
    assertTrue(scanNew("Dockerfile", "FROM x", "USER rootless").isEmpty());
    assertTrue(scanNew("Dockerfile.md", "FROM x", "USER root").isEmpty());
  }

  @Test
  void aModifiedDockerfileIsReportedOnlyWhenTheLastHunkReachesTheEndOfTheFile() {
    var atEnd = PatchLines.parse("@@ -10,2 +10,3 @@\n RUN make\n+USER root\n CMD [\"app\"]\n");
    assertEquals(1, IacScanner.scan("Dockerfile", atEnd, false).size());

    var beforeMore =
        PatchLines.parse("@@ -10,4 +10,5 @@\n RUN make\n+USER root\n RUN a\n RUN b\n RUN c\n");
    assertTrue(IacScanner.scan("Dockerfile", beforeMore, false).isEmpty());

    var earlierHunk =
        PatchLines.parse(
            "@@ -2,2 +2,3 @@\n FROM x\n+USER root\n RUN a\n@@ -20,1 +21,1 @@\n-CMD a\n+CMD b\n");
    assertTrue(IacScanner.scan("Dockerfile", earlierHunk, false).isEmpty());
  }

  // --- Terraform encryption ---

  @Test
  void encryptionTurnedOffInTerraform() {
    assertEquals(
        List.of(SecurityRule.UNENCRYPTED_STORAGE), rules(scanNew("ebs.tf", "  encrypted = false")));
    assertEquals(1, scanNew("rds.tf", "  storage_encrypted = false").size());
    assertTrue(scanNew("ebs.tf", "  encrypted = true").isEmpty());
    assertTrue(scanNew("values.yaml", "encrypted = false").isEmpty());
  }

  @Test
  void sourceFilesAreNotScannedAndContextLinesAreNotReported() {
    assertTrue(scanNew("App.java", "String acl = \"public-read\";").isEmpty());
    var context = PatchLines.parse("@@ -1,1 +1,2 @@\n   privileged: true\n+  image: x\n");
    assertTrue(IacScanner.scan("pod.yaml", context, false).isEmpty());
  }
}
