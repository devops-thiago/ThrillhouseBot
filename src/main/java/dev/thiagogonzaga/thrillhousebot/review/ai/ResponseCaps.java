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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import dev.thiagogonzaga.thrillhousebot.config.ActiveModelSettings;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiResponses.ModelLane;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Resolves the {@linkplain ResponseCap response cap} each model lane's requests carry, from the
 * same settings {@link ChatModelCustomizers} and the named config block apply to the model
 * builders: the active model's {@code max-output-tokens} for the default binding, and {@code
 * REVIEW_CONCISE_MAX_OUTPUT_TOKENS} for the {@code concise} one. A truncation site asks this for
 * its lane's cap so the failure can state what the request licensed and whether the provider's stop
 * reached it (#895).
 */
@ApplicationScoped
public class ResponseCaps {

  /**
   * The concise named model's response cap, aliased to {@code REVIEW_CONCISE_MAX_OUTPUT_TOKENS}.
   */
  static final String CONCISE_CAP_PROPERTY =
      "quarkus.langchain4j.openai.concise.chat-model.max-tokens";

  /** The operator-facing name of the concise lane's cap. */
  static final String CONCISE_CAP_SETTING = "REVIEW_CONCISE_MAX_OUTPUT_TOKENS";

  private final ActiveModelSettings activeModel;
  private final Optional<Integer> conciseMaxTokens;

  @Inject
  public ResponseCaps(
      ActiveModelSettings activeModel,
      @ConfigProperty(name = CONCISE_CAP_PROPERTY) Optional<Integer> conciseMaxTokens) {
    this.activeModel = activeModel;
    this.conciseMaxTokens = conciseMaxTokens;
  }

  /** The cap a call on {@code lane} carries. */
  public ResponseCap forLane(ModelLane lane) {
    return lane == ModelLane.CONCISE
        ? new ResponseCap(ModelLane.CONCISE, conciseMaxTokens.orElse(null), CONCISE_CAP_SETTING)
        : activeCap(activeModel);
  }

  /**
   * The default binding's cap, for callers that already hold the active model's settings and only
   * ever call on that lane. The setting is named by its full per-model key, so the operator sees
   * which model's entry supplied the number.
   */
  public static ResponseCap activeCap(ActiveModelSettings activeModel) {
    return new ResponseCap(
        ModelLane.ACTIVE,
        activeModel.maxOutputTokens().orElse(null),
        "thrillhousebot.ai.models.\"" + activeModel.modelName() + "\".max-output-tokens");
  }
}
