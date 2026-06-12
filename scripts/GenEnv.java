/// Generate .env from the JSON returned by the GitHub App manifest conversion:
///
///   gh api --method POST /app-manifests/{code}/conversions \
///     | java scripts/GenEnv.java --host thrillhousebot.example.com
///
/// Requires Java 25 (compact source file, JEP 512). Without --host, a
/// `<your-host>` placeholder is written for you to fill in.

void main(String[] args) throws Exception {
  String host = null;
  for (int i = 0; i < args.length - 1; i++) {
    if ("--host".equals(args[i])) {
      host = args[i + 1];
    }
  }
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

      # --- AI / DeepSeek ---
      AI_API_KEY=
      AI_BASE_URL=https://api.deepseek.com/v1
      AI_MODEL=deepseek-chat

      # --- Database ---
      DB_USER=thrillhouse
      DB_PASSWORD=
      QUARKUS_DATASOURCE_USERNAME=thrillhouse
      QUARKUS_DATASOURCE_PASSWORD=
      DATABASE_URL=jdbc:postgresql://db:5432/thrillhouse

      # --- Dashboard OAuth (GitHub) ---
      GITHUB_CLIENT_ID=%s
      GITHUB_CLIENT_SECRET=%s
      DASHBOARD_URL=%s

      # --- OpenTelemetry (optional) ---
      # OTEL_EXPORTER_ENDPOINT=http://<alloy-host>:4317

      # --- Network tuning (optional; keepalive <= 0 disables it) ---
      # HTTP_CONNECT_TIMEOUT=10s
      # HTTP_REQUEST_TIMEOUT=10s
      # WEBSOCKET_KEEPALIVE_MS=25000
      """
          .formatted(
              appId, pemOneLine, nullToEmpty(webhookSecret),
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
  IO.println("");
  IO.println("👉 Still needed: AI_API_KEY, DB_PASSWORD, QUARKUS_DATASOURCE_PASSWORD");
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
