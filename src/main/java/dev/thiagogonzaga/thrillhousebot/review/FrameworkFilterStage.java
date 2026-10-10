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

/** Drops known framework false positives, judged against the text the call was given. */
@ApplicationScoped
class FrameworkFilterStage implements FindingStage {

  private final FrameworkFalsePositiveFilter frameworkFilter;

  @Inject
  FrameworkFilterStage(FrameworkFalsePositiveFilter frameworkFilter) {
    this.frameworkFilter = frameworkFilter;
  }

  @Override
  public ScreeningContext apply(ScreeningContext context) {
    return context.withResponse(frameworkFilter.filter(context.response(), context.quoteSource()));
  }
}
