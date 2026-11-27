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
package dev.thiagogonzaga.thrillhousebot.config;

import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import java.io.Serial;

/**
 * Lets the GitHub App extension read the App's credentials in every form the start-up check
 * accepts.
 *
 * <p>The extension converts {@code quarkus.github-app.private-key} while the application starts and
 * understands neither literal {@code \n} escapes (the one-line form {@code .env.example} asks for)
 * nor a key without its PEM armor. Such a value is rewritten here, before the extension sees it. A
 * value the parser rejects is left as it is, for the extension and the startup validator to judge.
 *
 * <p>The extension also signs {@code quarkus.github-app.app-id} into the App JWT as written, and
 * GitHub rejects an issuer with padding, so the id is stripped.
 */
public class GitHubAppCredentialInterceptor implements ConfigSourceInterceptor {

  static final String PRIVATE_KEY = "quarkus.github-app.private-key";
  static final String APP_ID = "quarkus.github-app.app-id";

  @Serial private static final long serialVersionUID = 1L;

  @Override
  public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
    var value = context.proceed(name);
    if (value == null) {
      return null;
    }
    return switch (name) {
      case PRIVATE_KEY ->
          RsaPrivateKeyParser.canonicalPem(value.getValue()).map(value::withValue).orElse(value);
      case APP_ID -> value.withValue(value.getValue().strip());
      default -> value;
    };
  }
}
