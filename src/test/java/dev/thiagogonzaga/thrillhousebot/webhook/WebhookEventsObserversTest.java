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
package dev.thiagogonzaga.thrillhousebot.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.Event;
import io.quarkiverse.githubapp.event.RawEvent;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins the extension-facing wiring of {@link WebhookEvents}: the event annotations on the observer
 * methods are how the Quarkiverse GitHub App extension decides what to deliver, so a lost or
 * mistyped qualifier fails silently in production (the event never arrives and not a single unit
 * test over the routing logic notices). These tests fail the build instead.
 *
 * <p>The resolution follows the extension's own annotation model: an action qualifier (for example
 * {@code @PullRequest.Opened}) carries the parent qualifier {@code @PullRequest("opened"), and the
 * parent declares the meta {@code @Event(name = "pull_request")}. {@code @RawEvent(event = "x")}
 * carries both members directly.
 */
class WebhookEventsObserversTest {

  /**
   * Every (event, action) pair GitHub currently subscribes the bot to, by webhook configuration.
   */
  private static final Set<List<String>> SUBSCRIBED_EVENT_ACTIONS =
      Set.of(
          List.of("pull_request", "opened"),
          List.of("pull_request", "reopened"),
          List.of("pull_request", "synchronize"),
          List.of("pull_request", "ready_for_review"),
          List.of("issue_comment", "created"),
          List.of("pull_request_review_comment", "created"),
          List.of("check_suite", "completed"),
          List.of("status", "*"),
          List.of("ping", "*"),
          List.of("installation", "*"),
          List.of("installation_repositories", "*"),
          List.of("repository", "*"));

  @Test
  void everySubscribedEventActionStillHasAnObservingQualifier() {
    var observed = new LinkedHashSet<List<String>>();
    for (var method : observerMethods()) {
      for (var annotation : method.getParameterAnnotations()[0]) {
        resolveEventAction(annotation).ifPresent(observed::add);
      }
    }
    var missing = new ArrayList<>(SUBSCRIBED_EVENT_ACTIONS);
    missing.removeAll(observed);
    assertTrue(
        missing.isEmpty(),
        "GitHub delivers events the bot no longer observes: "
            + missing
            + " — an observer lost its qualifier and those deliveries are silently dropped.");
  }

  @Test
  void everyQualifierObservationIsExpectedByTheBot() {
    var observed = new LinkedHashSet<List<String>>();
    for (var method : observerMethods()) {
      for (var annotation : method.getParameterAnnotations()[0]) {
        resolveEventAction(annotation).ifPresent(observed::add);
      }
    }
    var unexpected = new ArrayList<>(observed);
    unexpected.removeAll(SUBSCRIBED_EVENT_ACTIONS);
    assertTrue(
        unexpected.isEmpty(),
        "The bot observes deliveries it never routes (new qualifier without a pin): "
            + unexpected
            + " — add the pair to SUBSCRIBED_EVENT_ACTIONS so the wiring stays deliberate.");
  }

  /**
   * Every annotation-carrying method takes only the raw {@link GitHubEvent} and is package-private
   * and void: no payload is bound and no installation client is built on the ack path, which keeps
   * a webhook delivery free of GitHub calls before the routing decides otherwise.
   */
  @Test
  void observersTakeOnlyTheRawEventWithoutBindingAPayloadOrClient() {
    var names = new LinkedHashSet<String>();
    for (var method : observerMethods()) {
      assertEquals(1, method.getParameterCount(), method.getName());
      assertEquals(
          GitHubEvent.class,
          method.getParameterTypes()[0],
          method.getName() + ": observers bind no payload; the ack path builds no client");
      assertEquals(void.class, method.getReturnType(), method.getName());
      assertEquals(
          0,
          method.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE),
          method.getName() + ": observers stay package-private like the product convention");
      names.add(method.getName());
    }
    assertEquals(
        9,
        names.size(),
        "Observer set changed: update this pin deliberately. Expected onPullRequest, "
            + "onIssueComment, onReviewComment, onCheckSuiteCompleted, onStatus, onPing, "
            + "onInstallationEvent, onInstallationRepositoriesEvent and onRepositoryEvent.");
  }

  /**
   * The parameter annotations carry the event qualifiers: observers qualify their {@code
   * GitHubEvent}.
   */
  private static List<Method> observerMethods() {
    var methods = new ArrayList<Method>();
    for (var method : WebhookEvents.class.getDeclaredMethods()) {
      if (method.getParameterAnnotations().length == 0
          || method.getParameterAnnotations()[0].length == 0) {
        continue;
      }
      methods.add(method);
    }
    return methods;
  }

  /**
   * Resolves the (event, action) one annotation instance subscribes to, with {@code *} meaning
   * every action of the event.
   */
  private static Optional<List<String>> resolveEventAction(Annotation annotation) {
    var type = annotation.annotationType();
    if (type == RawEvent.class) {
      var rawEvent = (RawEvent) annotation;
      var action = rawEvent.action();
      return Optional.of(
          List.of(rawEvent.event(), action == null || action.isBlank() ? "*" : action));
    }
    var event = type.getAnnotation(Event.class);
    if (event != null) {
      // @Status, @Ping and friends: the qualifier itself is the event, every action.
      return Optional.of(List.of(event.name(), "*"));
    }
    // An action qualifier: some other annotation ON it carries the action as its value while
    // that one carries @Event(name = ...).
    for (var parent : type.getAnnotations()) {
      var parentEvent = parent.annotationType().getAnnotation(Event.class);
      if (parentEvent == null) {
        continue;
      }
      var action = valueOf(parent);
      return Optional.of(
          List.of(parentEvent.name(), action == null || action.isBlank() ? "*" : action));
    }
    return Optional.empty();
  }

  /** Reads {@code value()} off a qualifier instance reflectively, with no value meaning blank. */
  private static String valueOf(Annotation annotation) {
    try {
      var method = annotation.annotationType().getMethod("value");
      return String.valueOf(method.invoke(annotation));
    } catch (ReflectiveOperationException _) {
      return null;
    }
  }
}
