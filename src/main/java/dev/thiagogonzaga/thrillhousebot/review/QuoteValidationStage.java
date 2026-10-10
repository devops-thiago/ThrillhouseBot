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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Checks that each finding's quoted code exists in the text its call was given. */
@ApplicationScoped
class QuoteValidationStage implements FindingStage {

  private final FindingQuoteValidator quoteValidator;

  @Inject
  QuoteValidationStage(FindingQuoteValidator quoteValidator) {
    this.quoteValidator = quoteValidator;
  }

  @Override
  public ScreeningContext apply(ScreeningContext context) {
    return context.withResponse(quoteValidator.validate(context.response(), context.quoteSource()));
  }
}
