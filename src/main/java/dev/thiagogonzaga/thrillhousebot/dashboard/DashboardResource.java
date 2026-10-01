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
package dev.thiagogonzaga.thrillhousebot.dashboard;

import dev.thiagogonzaga.thrillhousebot.review.FindingFeedbackService;
import dev.thiagogonzaga.thrillhousebot.review.ReviewLearningService;
import dev.thiagogonzaga.thrillhousebot.review.ReviewSkipEmitter;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/dashboard")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class DashboardResource {

  // Review status constants
  private static final String STATUS_COMPLETED = "completed";
  private static final String STATUS_FAILED = "failed";

  /** Upper bound for the {@code size} query parameter on /sessions. */
  static final int MAX_PAGE_SIZE = 100;

  // JPQL field name constants
  private static final String FIELD_TIMESTAMP = "timestamp";
  private static final String FIELD_STATUS = "status";
  private static final String FIELD_MODEL = "model";
  private static final String FIELD_REPOSITORY = "repository";

  // JSON key constants
  private static final String KEY_COST = "cost";
  private static final String KEY_TOTAL_COST = "totalCost";
  private static final String KEY_TOTAL_TOKENS = "totalTokens";
  private static final String KEY_COUNT = "count";
  private static final String KEY_SINCE = "since";

  /**
   * Restricts a query to the caller's {@link RepositoryScope} (bound as {@code ?2}, lower-cased).
   * Filtering in the query rather than after it keeps paging, totals and aggregates exact.
   */
  private static final String JPQL_IN_SCOPE = "lower(s." + FIELD_REPOSITORY + ") IN ?2";

  private static final String JPQL_FROM_IN_SCOPE_SINCE =
      "FROM ReviewSession s WHERE s." + FIELD_TIMESTAMP + " > ?1 AND " + JPQL_IN_SCOPE;

  private static final String JPQL_FROM_COMPLETED_SINCE =
      JPQL_FROM_IN_SCOPE_SINCE + " AND s." + FIELD_STATUS + " = '" + STATUS_COMPLETED + "'";

  private static final String JPQL_COSTS_BY_MODEL =
      "SELECT s.model as model, COUNT(s) as "
          + KEY_COUNT
          + ", SUM(s.cost) as "
          + KEY_COST
          + ", SUM(s.inputTokens) as inputTokens, SUM(s.outputTokens) as outputTokens "
          + JPQL_FROM_COMPLETED_SINCE
          + " GROUP BY s.model ORDER BY SUM(s.cost) DESC";

  private static final String JPQL_TOKENS_BY_MODEL =
      "SELECT s.model as model, COUNT(s) as "
          + KEY_COUNT
          + ", SUM(s.inputTokens) as inputTokens, SUM(s.outputTokens) as outputTokens, "
          + "SUM(s.inputTokens + s.outputTokens) as "
          + KEY_TOTAL_TOKENS
          + " "
          + JPQL_FROM_COMPLETED_SINCE
          + " GROUP BY s.model ORDER BY SUM(s.inputTokens + s.outputTokens) DESC";

  private static final String JPQL_SUMMARY_TOTAL_COST =
      "SELECT SUM(s.cost) as " + KEY_TOTAL_COST + " " + JPQL_FROM_COMPLETED_SINCE;

  // Parameter / cookie name constants
  private static final String PARAM_PERIOD = "period";
  private static final String COOKIE_SESSION = "thrillhouse_session";

  // Error message constants
  private static final String KEY_ERROR = "error";
  private static final String MSG_NOT_AUTHENTICATED = "Not authenticated";
  private static final String MSG_SESSION_NOT_FOUND = "Session not found";
  private static final String MSG_REPOSITORY_ACCESS_DENIED = "Repository access denied";

  private final DashboardSessionValidator sessionValidator;
  private final ReviewSessionRepository reviewSessionRepository;
  private final ReviewSkipEmitter reviewSkipEmitter;
  private final FindingFeedbackService findingFeedbackService;
  private final ReviewLearningService reviewLearningService;

  @Inject
  public DashboardResource(
      DashboardSessionValidator sessionValidator,
      ReviewSessionRepository reviewSessionRepository,
      ReviewSkipEmitter reviewSkipEmitter,
      FindingFeedbackService findingFeedbackService,
      ReviewLearningService reviewLearningService) {
    this.sessionValidator = sessionValidator;
    this.reviewSessionRepository = reviewSessionRepository;
    this.reviewSkipEmitter = reviewSkipEmitter;
    this.findingFeedbackService = findingFeedbackService;
    this.reviewLearningService = reviewLearningService;
  }

  boolean isValidSession(String sessionToken) {
    return sessionValidator.isValidSession(sessionToken);
  }

  private Response unauthorizedResponse() {
    return Response.status(Response.Status.UNAUTHORIZED)
        .entity(Map.of(KEY_ERROR, MSG_NOT_AUTHENTICATED))
        .build();
  }

  private static Response repositoryDeniedResponse() {
    return Response.status(Response.Status.FORBIDDEN)
        .entity(Map.of(KEY_ERROR, MSG_REPOSITORY_ACCESS_DENIED))
        .build();
  }

  /** The scope's repositories as the {@code ?2} parameter of {@link #JPQL_IN_SCOPE}. */
  private static List<String> scopeParameter(RepositoryScope scope) {
    return List.copyOf(scope.repositories());
  }

  @GET
  @Path("/sessions")
  public Response listSessions(
      @QueryParam("page") @DefaultValue("0") int page,
      @QueryParam("size") @DefaultValue("20") int size,
      @QueryParam("repository") String repository,
      @CookieParam(COOKIE_SESSION) String sessionToken) {
    if (!isValidSession(sessionToken)) {
      return unauthorizedResponse();
    }

    var scope = sessionValidator.repositoryScope(sessionToken);
    if (repository != null && !repository.isBlank() && !scope.allows(repository)) {
      return repositoryDeniedResponse();
    }
    return sessionsPage(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), repository, scope);
  }

  /**
   * One page of sessions, newest first, from the caller's accessible repositories only — or from
   * the one requested repository, which the caller has already been checked against.
   */
  private Response sessionsPage(int page, int size, String repository, RepositoryScope scope) {
    Sort newestFirst = Sort.descending(FIELD_TIMESTAMP);
    var readable =
        repository != null && !repository.isBlank()
            ? List.of(RepositoryScope.normalize(repository))
            : scopeParameter(scope);
    PanacheQuery<ReviewSession> query =
        reviewSessionRepository.find(
            "lower(" + FIELD_REPOSITORY + ") IN ?1", newestFirst, readable);
    var sessions = query.page(page, size).list();

    var total = query.count();
    var result = new HashMap<String, Object>();
    result.put("sessions", sessions);
    result.put("total", total);
    result.put("page", page);
    result.put("size", size);
    return Response.ok(result).build();
  }

  @GET
  @Path("/sessions/{id}")
  public Response getSession(
      @PathParam("id") long id, @CookieParam(COOKIE_SESSION) String sessionToken) {
    if (!isValidSession(sessionToken)) {
      return unauthorizedResponse();
    }
    var session = reviewSessionRepository.findById(id);
    // A session from a repository the caller cannot read answers exactly like a missing one, so
    // ids do not reveal which other repositories have reviews.
    if (session == null
        || !sessionValidator.repositoryScope(sessionToken).allows(session.getRepository())) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(Map.of(KEY_ERROR, MSG_SESSION_NOT_FOUND))
          .build();
    }
    return Response.ok(toSessionDetail(session)).build();
  }

  static Map<String, Object> toSessionDetail(ReviewSession session) {
    var detail = new HashMap<String, Object>();
    detail.put("id", session.id);
    detail.put(FIELD_REPOSITORY, session.getRepository());
    detail.put("prNumber", session.getPrNumber());
    detail.put("prTitle", session.getPrTitle());
    detail.put("commitSha", session.getCommitSha());
    detail.put(FIELD_TIMESTAMP, session.getTimestamp());
    detail.put(FIELD_MODEL, session.getModel());
    detail.put("inputTokens", session.getInputTokens());
    detail.put("outputTokens", session.getOutputTokens());
    detail.put(KEY_COST, session.getCost());
    detail.put("pricingMissing", session.isPricingMissing());
    detail.put("durationMs", session.getDurationMs());
    detail.put("criticalFindings", session.getCriticalFindings());
    detail.put("highFindings", session.getHighFindings());
    detail.put("mediumFindings", session.getMediumFindings());
    detail.put("lowFindings", session.getLowFindings());
    detail.put(FIELD_STATUS, session.getStatus());
    detail.put("errorMessage", session.getErrorMessage());
    detail.put("aiResponseJson", session.getAiResponseJson());
    return detail;
  }

  @GET
  @Path("/costs")
  public Response getCosts(
      @QueryParam(PARAM_PERIOD) @DefaultValue("month") String period,
      @CookieParam(COOKIE_SESSION) String sessionToken) {
    if (!isValidSession(sessionToken)) {
      return unauthorizedResponse();
    }

    var since =
        (switch (period) {
          case "day" -> Instant.now().minus(1, ChronoUnit.DAYS);
          case "week" -> Instant.now().minus(7, ChronoUnit.DAYS);
          case "year" -> Instant.now().minus(365, ChronoUnit.DAYS);
          default -> Instant.now().minus(30, ChronoUnit.DAYS);
        });

    List<Map<String, Object>> rows =
        reviewSessionRepository
            .find(
                JPQL_COSTS_BY_MODEL,
                since,
                scopeParameter(sessionValidator.repositoryScope(sessionToken)))
            .project(Map.class)
            .list();

    var totalCost =
        rows.stream()
            .mapToDouble(r -> ((Number) r.getOrDefault(KEY_COST, 0.0)).doubleValue())
            .sum();

    return Response.ok(
            Map.of(
                PARAM_PERIOD,
                period,
                KEY_SINCE,
                since.toString(),
                KEY_TOTAL_COST,
                totalCost,
                "byModel",
                rows))
        .build();
  }

  @GET
  @Path("/tokens")
  public Response getTokens(
      @QueryParam(PARAM_PERIOD) @DefaultValue("month") String period,
      @CookieParam(COOKIE_SESSION) String sessionToken) {
    if (!isValidSession(sessionToken)) {
      return unauthorizedResponse();
    }

    var since =
        (switch (period) {
          case "day" -> Instant.now().minus(1, ChronoUnit.DAYS);
          case "week" -> Instant.now().minus(7, ChronoUnit.DAYS);
          default -> Instant.now().minus(30, ChronoUnit.DAYS);
        });

    List<Map<String, Object>> rows =
        reviewSessionRepository
            .find(
                JPQL_TOKENS_BY_MODEL,
                since,
                scopeParameter(sessionValidator.repositoryScope(sessionToken)))
            .project(Map.class)
            .list();

    var totalTokens =
        rows.stream()
            .mapToLong(r -> ((Number) r.getOrDefault(KEY_TOTAL_TOKENS, 0L)).longValue())
            .sum();

    return Response.ok(
            Map.of(
                PARAM_PERIOD,
                period,
                KEY_SINCE,
                since.toString(),
                KEY_TOTAL_TOKENS,
                totalTokens,
                "byModel",
                rows))
        .build();
  }

  /**
   * Aggregated maintainer finding feedback (👍/👎 / reply heuristics) for the learnings pipeline
   * precursor (#324). Optional {@code repository=owner/repo} filters to one repo; otherwise returns
   * all repos ordered by event count.
   */
  @GET
  @Path("/feedback")
  public Response getFeedback(
      @QueryParam("repository") String repository,
      @CookieParam(COOKIE_SESSION) String sessionToken) {
    if (!isValidSession(sessionToken)) {
      return unauthorizedResponse();
    }
    if (repository != null && !repository.isBlank()) {
      var normalizedRepository = repository.strip();
      if (!sessionValidator.hasRepositoryAccess(sessionToken, normalizedRepository)) {
        return repositoryDeniedResponse();
      }
      var summary = findingFeedbackService.summarize(normalizedRepository);
      var recent = findingFeedbackService.listRecent(normalizedRepository, 50);
      return Response.ok(
              Map.of(
                  "repositories",
                  List.of(
                      Map.of(
                          FIELD_REPOSITORY,
                          summary.repository(),
                          "usefulCount",
                          summary.usefulCount(),
                          "notUsefulCount",
                          summary.notUsefulCount(),
                          "totalEvents",
                          summary.totalEvents())),
                  "recent",
                  recent))
          .build();
    }
    var scope = sessionValidator.repositoryScope(sessionToken);
    var summaries =
        findingFeedbackService.summarizeAll().stream()
            .filter(s -> scope.allows(s.repository()))
            .toList();
    var rows =
        summaries.stream()
            .map(
                s ->
                    Map.<String, Object>of(
                        FIELD_REPOSITORY,
                        s.repository(),
                        "usefulCount",
                        s.usefulCount(),
                        "notUsefulCount",
                        s.notUsefulCount(),
                        "totalEvents",
                        s.totalEvents()))
            .toList();
    return Response.ok(Map.of("repositories", rows)).build();
  }

  /**
   * The newest review learnings of one repository (#38, at most {@link
   * ReviewLearningService#MAX_AUDIT_ROWS}), retracted ones included, newest first — the audit view:
   * what is remembered, the comment that taught it, and who retracted it. {@code repository} is
   * required and access-checked like {@link #getFeedback}; retraction stays with the {@code
   * /forget} command, which re-checks write access on the repository itself.
   */
  @GET
  @Path("/learnings")
  public Response getLearnings(
      @QueryParam("repository") String repository,
      @CookieParam(COOKIE_SESSION) String sessionToken) {
    if (!isValidSession(sessionToken)) {
      return unauthorizedResponse();
    }
    if (repository == null || repository.isBlank()) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity(Map.of(KEY_ERROR, "repository is required"))
          .build();
    }
    var normalizedRepository = repository.strip();
    if (!sessionValidator.hasRepositoryAccess(sessionToken, normalizedRepository)) {
      return repositoryDeniedResponse();
    }
    return Response.ok(
            Map.of(
                FIELD_REPOSITORY,
                normalizedRepository,
                "learnings",
                reviewLearningService.listForAudit(
                    normalizedRepository, ReviewLearningService.MAX_AUDIT_ROWS)))
        .build();
  }

  @GET
  @Path("/summary")
  public Response getSummary(@CookieParam(COOKIE_SESSION) String sessionToken) {
    if (!isValidSession(sessionToken)) {
      return unauthorizedResponse();
    }

    var since = Instant.now().minus(30, ChronoUnit.DAYS);
    var scope = sessionValidator.repositoryScope(sessionToken);
    var readable = scopeParameter(scope);

    var totalReviews = countInScopeSince(null, since, readable);
    var completedReviews = countInScopeSince(STATUS_COMPLETED, since, readable);
    var failedReviews = countInScopeSince(STATUS_FAILED, since, readable);

    var costRow =
        reviewSessionRepository
            .find(JPQL_SUMMARY_TOTAL_COST, since, readable)
            .project(Map.class)
            .singleResult();
    var totalCost = extractTotalCost(costRow, KEY_TOTAL_COST);

    var modelRow =
        reviewSessionRepository
            .find(
                "SELECT s.model as model, COUNT(s) as cnt "
                    + JPQL_FROM_IN_SCOPE_SINCE
                    + " GROUP BY s.model"
                    + " ORDER BY COUNT(s) DESC",
                since,
                readable)
            .project(Map.class)
            .firstResult();
    String topModel = extractTopModel(modelRow);

    return Response.ok(
            Map.of(
                "totalReviews",
                totalReviews,
                "completedReviews",
                completedReviews,
                "failedReviews",
                failedReviews,
                KEY_TOTAL_COST,
                totalCost,
                "topModel",
                topModel,
                "skippedReviewsByReason",
                // Skip counters are installation-wide and carry no repository, so only the account
                // owner (who can read every installed repository) sees them.
                scope.accountOwner() ? reviewSkipEmitter.countsByReason() : Map.of(),
                KEY_SINCE,
                since.toString()))
        .build();
  }

  /** Sessions since {@code since} in the caller's scope, optionally of one status only. */
  private long countInScopeSince(String status, Instant since, List<String> readable) {
    var where = FIELD_TIMESTAMP + " > ?1 AND lower(" + FIELD_REPOSITORY + ") IN ?2";
    if (status == null) {
      return nullSafeCount(reviewSessionRepository.count(where, since, readable));
    }
    return nullSafeCount(
        reviewSessionRepository.count(
            where + " AND " + FIELD_STATUS + " = ?3", since, readable, status));
  }

  static String extractTopModel(Map<?, ?> modelRow) {
    if (modelRow == null) {
      return "N/A";
    }
    var model = modelRow.get(FIELD_MODEL);
    if (model instanceof String modelName && !modelName.isBlank()) {
      return modelName;
    }
    return "N/A";
  }

  static long nullSafeCount(Long count) {
    return count != null ? count : 0L;
  }

  static double extractTotalCost(Map<?, ?> costRow, String key) {
    if (costRow == null) {
      return 0.0;
    }
    var costVal = costRow.get(key);
    if (costVal instanceof Number number) {
      return number.doubleValue();
    }
    return 0.0;
  }
}
