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

import java.util.List;

/**
 * An issue tracker the linked-issue context (#58) reads a pull request's ticket from. A provider
 * does two things: it works out which of its tickets the pull request is linked to, and it reads
 * each one's title and body. What a link looks like is the provider's business — {@code Closes #57}
 * for GitHub Issues, a project key for an external tracker — so the resolution lives here, next to
 * the fetch. Rendering, sanitizing and budgeting are the {@link TicketContextResolver}'s job and
 * are the same for every provider.
 *
 * <p>A provider only reads. It never comments on, labels, transitions or otherwise changes a
 * ticket, and every failure (a missing, deleted or unreadable ticket, a tracker outage) is
 * absorbed: the ticket is left out and the review goes on without it.
 */
public interface IssueTrackerProvider {

  /** The name {@code REVIEW_TICKET_CONTEXT_PROVIDER} selects this provider by. */
  String name();

  /**
   * The tickets the pull request is linked to, in precedence order, at most {@code limit} of them,
   * each with its title and body. Tickets that could not be read are left out; never throws.
   */
  List<LinkedTicket> linkedTickets(TicketLookup lookup, int limit);

  /**
   * What a provider gets to find a pull request's tickets with.
   *
   * @param auth the installation credential for the pull request's repository
   * @param owner the repository owner
   * @param repo the repository name
   * @param prNumber the pull request number
   * @param prBody the pull request description, possibly {@code null}
   * @param fromBranch whether the head branch name may be used as a last-resort link
   */
  record TicketLookup(
      String auth, String owner, String repo, int prNumber, String prBody, boolean fromBranch) {}

  /**
   * One linked ticket as read from the tracker. The title and body are exactly what the tracker
   * returned: untrusted, unsanitized and unclipped.
   *
   * @param key how the ticket is named in the prompt, e.g. {@code #57}
   * @param via how the link was found, e.g. {@code a closing keyword in the PR body}
   * @param title the ticket title, possibly {@code null}
   * @param body the ticket body, possibly {@code null}
   */
  record LinkedTicket(String key, String via, String title, String body) {}
}
