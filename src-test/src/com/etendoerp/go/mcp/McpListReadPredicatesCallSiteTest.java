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

package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architecture regression test for ETP-5009 — {@code etendo_list} must AND the entity
 * customization's {@code NeoHandler#readPredicates} into its query, the way the REST list GET and
 * its {@code ?_distinct=} fetch do.
 *
 * <p>Without the call, a row a customization excludes from the REST list (the Product window's
 * {@code ETGO_DTO} under the system "Discounts" category) is still returned to an agent, with no
 * error and no log line. {@code handleList} needs an {@code OBContext}, a live DAL and
 * {@code DefaultJsonDataService}, so — like {@code McpListTabWhereCallSiteTest} — the guard is on
 * the call site; the resolution rule itself is covered by {@code NeoReadPredicatesTest}.</p>
 *
 * @covers com.etendoerp.go.mcp.McpToolRouter
 */
@DisplayName("ETP-5009 — the MCP list applies the customization's read predicates")
class McpListReadPredicatesCallSiteTest {

  private static final String ROUTER = "com/etendoerp/go/mcp/McpToolRouter.java";

  /** The shared resolver, on the MCP channel. */
  private static final Pattern RESOLVE_ON_MCP = Pattern.compile(
      "NeoReadPredicates\\s*\\.\\s*resolve\\s*\\((?s:.*?)NeoExtensionChannel\\s*\\.\\s*MCP\\s*\\)");

  /** The resolved predicate is ANDed onto the where clause built so far. */
  private static final Pattern AND_ONTO_WHERE = Pattern.compile(
      "NeoReadPredicates\\s*\\.\\s*and\\s*\\(\\s*params\\s*\\.\\s*get\\s*\\("
          + "\\s*JsonConstants\\s*\\.\\s*WHERE_AND_FILTER_CLAUSE\\s*\\)");

  /** The list query itself. */
  private static final Pattern FETCH = Pattern.compile("jsonService\\s*\\.\\s*fetch\\s*\\(");

  @Test
  @DisplayName("handleList resolves the read predicates through NeoReadPredicates on MCP")
  void listResolvesReadPredicatesOnMcp() {
    assertTrue(RESOLVE_ON_MCP.matcher(listBody()).find(),
        "handleList no longer calls NeoReadPredicates.resolve(..., NeoExtensionChannel.MCP)."
            + " Without it etendo_list returns rows the REST list excludes in the query (ETP-5009).");
  }

  @Test
  @DisplayName("handleList ANDs the read predicate onto the existing where clause")
  void listAndsThePredicateOntoTheWhereClause() {
    assertTrue(AND_ONTO_WHERE.matcher(listBody()).find(),
        "handleList must AND the read predicate onto params' whereAndFilterClause via"
            + " NeoReadPredicates.and, not replace the filters/tab where already there (ETP-5009).");
  }

  @Test
  @DisplayName("handleList applies the read predicate before the list query runs")
  void predicateIsAppliedBeforeTheFetch() {
    String body = listBody();
    Matcher resolve = RESOLVE_ON_MCP.matcher(body);
    Matcher fetch = FETCH.matcher(body);
    assertTrue(resolve.find(), "NeoReadPredicates.resolve call not found in handleList");
    assertTrue(fetch.find(), "jsonService.fetch call not found in handleList");
    assertTrue(resolve.start() < fetch.start(),
        "The read predicate must be resolved before jsonService.fetch(params) runs, or it never"
            + " reaches the query (ETP-5009).");
  }

  private static String listBody() {
    return McpSourceScanner.methodBody(McpSourceScanner.read(ROUTER), "handleList");
  }
}
