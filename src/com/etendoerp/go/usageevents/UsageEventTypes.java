/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.usageevents;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The defined set of event types {@code ETGO_USAGE_EVENT} accepts (decision D4).
 *
 * <p>The list is closed on purpose. {@code EVENT_TYPE} is what every query on the table groups by,
 * and the UI endpoint lets a browser name the type; an open column would fill with typos and
 * arbitrary strings that no dashboard can tell apart from real events. {@link UsageEventRecorder}
 * drops any type that is not listed here and logs it at ERROR — never an exception, never an HTTP
 * failure — so an older or newer caller degrades to "not recorded" instead of breaking.</p>
 *
 * <h2>Adding an event type</h2>
 *
 * <ol>
 *   <li>Agree the event first: it must answer a concrete product question nobody can already answer
 *       from {@code ETGO_MCP_USAGE}, {@code ETGO_BILLING_EVENT} or Mixpanel.</li>
 *   <li>Add a constant here, matching {@link #PATTERN} ({@code area.subject[.verb]}, lower case).</li>
 *   <li>Add it to {@link #KNOWN}. A constant that is not in the set is silently useless: every event
 *       carrying it is dropped.</li>
 * </ol>
 */
public final class UsageEventTypes {

  /**
   * Shape every event type must have: lower case, dot-separated, at most 60 characters so it fits
   * {@code EVENT_TYPE VARCHAR(60)}.
   */
  public static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9_.]{1,59}$");

  /** One completed AI agent chat turn, recorded by the AI BFF with the token usage (§9.1). */
  public static final String AI_AGENT_MESSAGE = "ai.agent.message";

  /** One completed support chat (ValerIA) turn, recorded by the backend with the token usage. */
  public static final String AI_SUPPORT_MESSAGE = "ai.support.message";

  /**
   * One successful entry into an environment, recorded by the backend once the environment's
   * credential is issued ({@link SessionLoginUsage}). {@code action} tells the two paths apart.
   */
  public static final String SESSION_LOGIN = "session.login";

  /** Every accepted event type. Keep in sync with the constants above. */
  private static final Set<String> KNOWN = Set.of(
      AI_AGENT_MESSAGE,
      AI_SUPPORT_MESSAGE,
      SESSION_LOGIN);

  private UsageEventTypes() {
  }

  /**
   * @param eventType candidate event type, possibly null
   * @return true only when {@code eventType} is one of the defined types
   */
  public static boolean isKnown(String eventType) {
    return eventType != null && KNOWN.contains(eventType);
  }

  /**
   * List every event type the recorder accepts.
   *
   * @return an immutable view of every accepted event type
   */
  public static Set<String> all() {
    return KNOWN;
  }
}
