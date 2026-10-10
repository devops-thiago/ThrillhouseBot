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

/**
 * One step of {@link FindingScreen}: takes one review call's screening state and returns the state
 * the next step sees. A stage owns exactly the collaborator its step needs; the order the stages
 * run in is {@link FindingScreen}'s, not theirs.
 */
@FunctionalInterface
interface FindingStage {

  ScreeningContext apply(ScreeningContext context);
}
