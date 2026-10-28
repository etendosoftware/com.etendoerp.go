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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

/**
 * The caller family of the NEO write running on this thread, as far as the server can tell it
 * apart (ETP-5791). Low-cardinality labels meant for telemetry, never for authorization.
 *
 * <p>The server can only see the entry point, not the person or screen behind it:</p>
 * <ul>
 *   <li>{@value #REST} — a single {@code /sws/neo} write. The SPA's own forms and any other REST
 *       client (an integration, a script) are indistinguishable here.</li>
 *   <li>{@value #MCP} — an MCP tool call ({@code etendo_create}, {@code etendo_update}).</li>
 *   <li>{@value #REST_BATCH} — one operation of {@code POST /sws/neo/batch}, synchronous or
 *       {@code async} job. The SPA's imports and its document ingestion (OCR included) use it, as
 *       can any REST client; which one it was is not visible to the server.</li>
 *   <li>{@value #MCP_BATCH} — one operation of MCP {@code etendo_batch}, synchronous or job.</li>
 * </ul>
 *
 * <p>MCP is marked by {@link #enter(String)} around the call ({@code McpServlet} for a tool call,
 * the async-job row runner for an MCP job, which runs on a worker thread); everything else is
 * REST. Being inside a batch is read from {@link NeoBatchScope#current()}.</p>
 */
public final class NeoWriteChannel {

  /** A single REST write. */
  public static final String REST = "rest";
  /** An MCP tool call. */
  public static final String MCP = "mcp";
  /** One operation of a REST batch (sync or async). */
  public static final String REST_BATCH = "rest_batch";
  /** One operation of an MCP batch (sync or async). */
  public static final String MCP_BATCH = "mcp_batch";

  private static final ThreadLocal<String> BASE = new ThreadLocal<>();

  private NeoWriteChannel() {
  }

  /**
   * Marks the current thread as serving {@code channel}. <b>Must be paired with
   * {@link #restore(String)} in a finally block</b>, passing the value returned here: the thread is
   * pooled, and a leaked mark would label the next, unrelated request.
   *
   * @param channel {@link #MCP} or {@link #REST}
   * @return the previous mark, to restore
   */
  public static String enter(String channel) {
    String previous = BASE.get();
    BASE.set(channel);
    return previous;
  }

  /**
   * Restores the mark {@link #enter(String)} replaced.
   *
   * @param previous the value {@link #enter(String)} returned
   */
  public static void restore(String previous) {
    if (previous == null) {
      BASE.remove();
    } else {
      BASE.set(previous);
    }
  }

  /** @return the channel of the write running on this thread */
  public static String current() {
    boolean mcp = MCP.equals(BASE.get());
    if (NeoBatchScope.current() != null) {
      return mcp ? MCP_BATCH : REST_BATCH;
    }
    return mcp ? MCP : REST;
  }
}
