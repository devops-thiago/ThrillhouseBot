/// Generate .env from the JSON returned by the GitHub App manifest conversion:
///
///   gh api --method POST /app-manifests/{code}/conversions \
///     | java scripts/GenEnv.java --host thrillhousebot.example.com
///
/// Requires Java 25 (compact source file, JEP 512). Without --host, a
/// `<your-host>` placeholder is written for you to fill in.
///
/// For an app registered on GitHub Enterprise Server or GHE.com, ask that GitHub
/// for the conversion and pass its web address, so the bot calls the same one:
///
///   gh api --hostname github.example.com --method POST /app-manifests/{code}/conversions \
///     | java scripts/GenEnv.java --host thrillhousebot.example.com \
///         --github-url https://github.example.com
///
/// --github-url must be an https address with no user name or password, the same
/// rule the bot applies to GITHUB_URL when it starts. Anything else is refused
/// and no .env is written.

void main(String[] args) throws Exception {
  String host = null;
  String githubUrl = null;
  for (int i = 0; i < args.length - 1; i++) {
    if ("--host".equals(args[i])) {
      host = args[i + 1];
    } else if ("--github-url".equals(args[i])) {
      githubUrl = args[i + 1].strip().replaceAll("/+$", "");
    }
  }
  var refused = githubUrl == null || githubUrl.isEmpty() ? null : githubUrlProblem(githubUrl);
  if (refused != null) {
    System.err.println(
        "❌ --github-url must be an https address, like https://github.example.com: found "
            + refused
            + ". No .env was written.");
    System.exit(1);
  }
  var githubUrlLine = githubUrl == null || githubUrl.isEmpty() ? "" : "GITHUB_URL=" + githubUrl + "\n";
  var dashboardUrl = "https://" + (host != null ? host : "<your-host>");

  var json = new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

  String appId = numberField(json, "id");
  if (appId == null) {
    System.err.println("❌ No \"id\" found in input — pipe the manifest conversion JSON on stdin.");
    System.exit(1);
  }
  String pem = stringField(json, "pem");
  String webhookSecret = stringField(json, "webhook_secret");
  String clientId = stringField(json, "client_id");
  String clientSecret = stringField(json, "client_secret");
  String slug = stringField(json, "slug");

  // Collapse the PEM into a single-line value safe for .env files
  String pemOneLine = pem == null ? "" : pem.replace("\n", "\\n").strip();

  var env =
      """
      # ============================================================
      # ThrillhouseBot — Production Environment Variables
      # Generated from GitHub App manifest conversion
      # ============================================================

      IMAGE_TAG=latest

      # --- GitHub App ---
      GITHUB_APP_ID=%s
      GITHUB_PRIVATE_KEY="%s"
      GITHUB_WEBHOOK_SECRET=%s
      %s
      # --- AI / DeepSeek ---
      AI_API_KEY=
      AI_BASE_URL=https://api.deepseek.com/v1
      AI_MODEL=deepseek-chat

      # --- Database (single source of truth; compose derives QUARKUS_DATASOURCE_* from these) ---
      DB_USER=thrillhouse
      DB_PASSWORD=
      DATABASE_URL=jdbc:postgresql://db:5432/thrillhouse

      # --- Dashboard OAuth (GitHub) ---
      GITHUB_CLIENT_ID=%s
      GITHUB_CLIENT_SECRET=%s
      DASHBOARD_URL=%s

      # --- OpenTelemetry (optional) ---
      # OTEL_EXPORTER_ENDPOINT=http://<alloy-host>:4317
      # OTEL_SDK_DISABLED=true   # no collector: export nothing

      # --- Network tuning (optional; keepalive <= 0 disables it) ---
      # HTTP_CONNECT_TIMEOUT=10s
      # HTTP_REQUEST_TIMEOUT=10s
      # WEBSOCKET_KEEPALIVE_MS=25000
      """
          .formatted(
              appId, pemOneLine, nullToEmpty(webhookSecret), githubUrlLine,
              nullToEmpty(clientId), nullToEmpty(clientSecret), dashboardUrl);

  java.nio.file.Files.writeString(java.nio.file.Path.of(".env"), env);

  IO.println("✅ Written to .env");
  IO.println("   App ID      : " + appId);
  IO.println("   Client ID   : " + nullToEmpty(clientId));
  IO.println("   Slug        : " + nullToEmpty(slug));
  IO.println("   PEM         : " + (pem != null && !pem.isBlank()
      ? "✅ included"
      : "❌ missing — download from GitHub settings"));
  if (host == null) {
    IO.println("   Host        : ⚠️ no --host given — replace <your-host> in DASHBOARD_URL");
  }
  if (!githubUrlLine.isEmpty()) {
    IO.println("   GitHub      : " + githubUrl);
  }
  IO.println("");
  IO.println("👉 Still needed: AI_API_KEY, DB_PASSWORD");
}

/// What keeps the bot from starting on this GITHUB_URL, or null when it would accept it.
/// The value is never repeated, since it can hold a password.
String githubUrlProblem(String value) {
  java.net.URI uri;
  try {
    uri = new java.net.URI(value);
  } catch (java.net.URISyntaxException e) {
    return "text that is not a URL";
  }
  if (uri.getScheme() == null) {
    return "no scheme";
  }
  var scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
  if (scheme.equals("http")) {
    return "the scheme http";
  }
  if (!scheme.equals("https")) {
    return "a scheme other than https";
  }
  if (uri.getHost() == null) {
    return "no valid host name";
  }
  return uri.getRawUserInfo() == null ? null : "a user name or password in the address";
}

/// First numeric value for the key (the conversion response has the app id first).
String numberField(String json, String key) {
  var m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
  return m.find() ? m.group(1) : null;
}

/// First string value for the key, with JSON escape sequences decoded.
String stringField(String json, String key) {
  var m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"").matcher(json);
  if (!m.find()) {
    return null;
  }
  var sb = new StringBuilder();
  for (int i = m.end(); i < json.length(); i++) {
    char c = json.charAt(i);
    if (c == '"') {
      return sb.toString();
    }
    if (c == '\\' && i + 1 < json.length()) {
      char e = json.charAt(++i);
      switch (e) {
        case 'n' -> sb.append('\n');
        case 'r' -> sb.append('\r');
        case 't' -> sb.append('\t');
        case 'b' -> sb.append('\b');
        case 'f' -> sb.append('\f');
        case 'u' -> {
          sb.append((char) Integer.parseInt(json, i + 1, i + 5, 16));
          i += 4;
        }
        default -> sb.append(e);
      }
    } else {
      sb.append(c);
    }
  }
  return null;
}

String nullToEmpty(String s) {
  return s == null ? "" : s;
}
