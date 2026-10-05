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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architecture regression test for ETP-5542 — {@code etendo_list} must resolve the parent placeholders
 * of a child tab's where clause, the way the REST read does.
 *
 * <p>The Bin Contents tab stores {@code e.storageBin.id=@Locator.id@}. REST fills the placeholder
 * with the parent id; the MCP list used to pass the clause on verbatim, so the query filtered on the
 * literal text and answered {@code 200} with an empty list — no error, no log line. An agent that
 * asked "what is in this bin" was told, confidently, that the bin was empty.</p>
 *
 * <p>Nothing behavioural catches a regression here: {@code handleList} is private and needs an
 * {@code OBContext}, a live DAL and {@code DefaultJsonDataService}, whose static initialisation the
 * route tests deliberately stay clear of (see {@code McpToolRouterRouteTest}). The defect is a
 * <b>call site</b> that reads the raw clause, which is what {@code McpSourceScanner} exists for —
 * see {@code McpBillToInjectorCallSiteTest} for the precedent. The behaviour of the shared rule
 * itself is covered by {@code NeoParentTabFilterResolverTest}.</p>
 */
@DisplayName("ETP-5542 — the MCP list resolves the parent placeholders of a tab where clause")
class McpListTabWhereCallSiteTest {

  private static final String ROUTER = "com/etendoerp/go/mcp/McpToolRouter.java";

  /** The shared rule, fed with the tab and the parent id the caller passed. */
  private static final Pattern SHARED_RULE = Pattern.compile(
      "NeoParentTabFilterResolver\\s*\\.\\s*resolveTabWhere\\s*\\(\\s*adTab\\s*,\\s*parentId\\s*\\)");

  /** Any direct read of the tab's stored clause, which leaves its placeholders unresolved. */
  private static final Pattern RAW_CLAUSE_READ = Pattern.compile("\\.\\s*getHqlwhereclause\\s*\\(");

  @Test
  @DisplayName("handleList takes the tab where clause from the shared resolver, with the parent id")
  void listUsesTheSharedResolver() {
    String body = listBody();

    assertTrue(SHARED_RULE.matcher(body).find(),
        "handleList no longer calls NeoParentTabFilterResolver.resolveTabWhere(adTab, parentId)."
            + " Without it a child tab's placeholder (Bin Contents: @Locator.id@) reaches the query"
            + " unresolved and etendo_list answers 200 with an empty list (ETP-5542). If the method was"
            + " refactored, update this guard; do not delete it without moving the call.");
  }

  @Test
  @DisplayName("handleList does not read the raw tab where clause")
  void listDoesNotReadTheRawClause() {
    assertFalse(RAW_CLAUSE_READ.matcher(listBody()).find(),
        "handleList reads adTab.getHqlwhereclause() directly, so the placeholders of a child tab's"
            + " clause stay unresolved and the list comes back empty (ETP-5542). Go through"
            + " NeoParentTabFilterResolver.resolveTabWhere(adTab, parentId), the rule REST uses.");
  }

  private static String listBody() {
    return McpSourceScanner.methodBody(McpSourceScanner.read(ROUTER), "handleList");
  }
}
