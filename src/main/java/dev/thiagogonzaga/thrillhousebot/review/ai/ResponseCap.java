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

import dev.thiagogonzaga.thrillhousebot.review.ai.AiResponses.ModelLane;
import java.io.Serializable;

/**
 * The response cap one call was licensed: the {@code max_tokens} its request carried and the
 * setting that supplied it (#895). A truncation report states both, so the operator reads the
 * number the request actually sent next to the number the provider billed, instead of being told to
 * raise a knob whose value the report never names.
 *
 * @param lane the model binding the call ran on — the two lanes are capped by different settings
 * @param tokens the {@code max_tokens} the request carried, or {@code null} when the setting is
 *     unset and the request carried none (the provider's own default then bounded the call)
 * @param setting the operator-facing name of the setting that supplies this lane's cap
 */
public record ResponseCap(ModelLane lane, Integer tokens, String setting) implements Serializable {}
