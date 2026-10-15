# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| latest  | :white_check_mark: |

## Reporting a Vulnerability

We take the security of this project seriously. If you believe you have found a security vulnerability, please report it responsibly.

**Please do NOT report security vulnerabilities through public GitHub issues.**

Instead, please use [GitHub's private vulnerability reporting](https://github.com/devops-thiago/ThrillhouseBot/security/advisories/new) to submit your report.

You should receive a response within 48 hours. If for some reason you do not, please follow up to ensure we received your original report.

Please include the following information in your report:

- Type of issue (e.g., buffer overflow, SQL injection, cross-site scripting, etc.)
- Full paths of source file(s) related to the issue
- The location of the affected source code (tag/branch/commit or direct URL)
- Any special configuration required to reproduce the issue
- Step-by-step instructions to reproduce the issue
- Proof-of-concept or exploit code (if possible)
- Impact of the issue, including how an attacker might exploit it

## How reports are handled

1. We acknowledge the report within 48 hours and keep the discussion in the
   private advisory.
2. We reproduce the issue, assess its severity with CVSS, and tell you whether
   we accept it as a vulnerability. If we do not, we explain why.
3. We develop the fix in the advisory's temporary private fork, with tests, so
   it stays private until release. You are welcome to review it there.
4. We release the fix in a new patch version, signed and published by the
   release workflow, and list it under `### Security` in that release's
   [CHANGELOG.md](CHANGELOG.md) section. We aim to ship a fix within 60 days of the
   report, and sooner for critical issues.
5. We publish the GitHub security advisory and request a CVE for it.
6. We credit you in the advisory and in the CHANGELOG entry, by the name or
   handle you choose, unless you ask to stay anonymous.

## Security model

[docs/SECURITY_MODEL.md](docs/SECURITY_MODEL.md) describes what you can and
cannot expect from ThrillhouseBot's security, its threat model and trust
boundaries, and how common weaknesses are countered.

## Preferred Languages

We prefer all communications to be in English.

## Disclosure Policy

We follow the principle of [Coordinated Vulnerability Disclosure](https://en.wikipedia.org/wiki/Coordinated_vulnerability_disclosure).
