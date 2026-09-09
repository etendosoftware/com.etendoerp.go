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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Source-reading guardrail for tenant isolation in the financial-account area (ETP-4950).
 *
 * <p><b>Why a structural test and not only behavioural ones.</b> The bug this pins down was not one
 * defect, it was the same defect twenty-one times: an id arriving in a query parameter or a request
 * body, resolved with {@code OBDal.getInstance().get(...)}, which — unlike {@link
 * org.openbravo.dal.service.OBCriteria} and {@link org.openbravo.dal.service.OBQuery} — applies no
 * readable-client / readable-organization predicate. It was fixed file by file, and twice a grep for
 * "is the validation there?" declared a call site covered when the validation actually ran too late
 * or on a different id. A per-finding regression test proves one call site; it cannot stop the
 * twenty-second from appearing in a file that does not exist yet. This can.
 *
 * <p><b>The rule.</b> In the classes listed in {@link #GUARDED_CLASSES}, a bare
 * {@code OBDal.getInstance().get(} is only allowed when the line above it carries a
 * {@code // tenant-ok: <reason>} comment stating why that particular id is already safe — it is a
 * re-read of an already validated entity, it comes from a query that is itself scoped by client and
 * organization, or the entity is system reference data with no tenant of its own. Anything else must
 * go through {@link TenantOwnership#loadOwned(Class, String)}.
 *
 * <p>Same shape as the request-policy guardrails on the frontend side
 * ({@code test/no-raw-fetch.test.js}, {@code test/auth-header-policy.test.js}), which opt out with a
 * {@code raw-fetch-ok: <reason>} comment for exactly the same reason.
 *
 * <p>Adding a class to {@link #GUARDED_CLASSES} is how the rule grows; do not remove one to make a
 * failure go away.
 */
@DisplayName("Tenant isolation policy — financial account area")
class TenantIsolationPolicyTest {

  private static final String SOURCE_ROOT = "src/com/etendoerp/go/schemaforge";
  private static final String BARE_GET = "OBDal.getInstance().get(";
  private static final String OPT_OUT = "tenant-ok:";
  private static final String GUARD = "TenantOwnership.loadOwned(";

  /**
   * The classes that serve the financial-account surface: bank reconciliation, account movements,
   * bank statements, cash close, the account's own configuration tabs and the payment registration
   * they delegate to.
   */
  private static final List<String> GUARDED_CLASSES = Arrays.asList(
      "AccountingDimensionsSupport.java",
      "AddPaymentService.java",
      "AutoMatchSupport.java",
      "BankStatementsHandler.java",
      "CandidatesSupport.java",
      "CashCloseHandler.java",
      "CashCloseSupport.java",
      "FinancialAccountAccountingHandler.java",
      "FinancialAccountBankConnectionHandler.java",
      "FinancialAccountHandler.java",
      "FinancialAccountTransactionsHandler.java",
      "FinancialAccountTransactionsSupport.java",
      "NearMatchSupport.java",
      "ReconciliationDifferenceSupport.java",
      "ReconciliationFlowSupport.java",
      "ReconciliationHandler.java",
      "ReconciliationHandlerSupport.java",
      "ReconciliationWriteoffSupport.java");

  /** Resolves a guarded class, whether the tests run from the module root or the workspace root. */
  private static Path sourceOf(String fileName) {
    Path fromModule = Paths.get(SOURCE_ROOT, fileName);
    if (Files.exists(fromModule)) {
      return fromModule;
    }
    return Paths.get("modules/com.etendoerp.go", SOURCE_ROOT, fileName);
  }

  private static List<String> linesOf(String fileName) throws IOException {
    Path path = sourceOf(fileName);
    assertTrue(Files.exists(path),
        "guarded class not found — was it renamed or moved? " + fileName);
    return Files.readAllLines(path, StandardCharsets.UTF_8);
  }

  /**
   * Every bare DAL lookup in the guarded classes carries an explicit {@code tenant-ok} reason.
   *
   * @throws IOException if a guarded source file cannot be read
   */
  @Test
  @DisplayName("A bare OBDal.get on a guarded class must justify itself")
  void testEveryBareDalLookupIsJustified() throws IOException {
    List<String> offenders = new ArrayList<>();
    for (String fileName : GUARDED_CLASSES) {
      List<String> lines = linesOf(fileName);
      for (int i = 0; i < lines.size(); i++) {
        if (!lines.get(i).contains(BARE_GET)) {
          continue;
        }
        boolean justified = lines.get(i).contains(OPT_OUT)
            || (i > 0 && lines.get(i - 1).contains(OPT_OUT));
        if (!justified) {
          offenders.add(fileName + ":" + (i + 1) + " → " + lines.get(i).trim());
        }
      }
    }
    if (!offenders.isEmpty()) {
      fail("An id that reaches OBDal.getInstance().get() unguarded resolves rows of ANY tenant."
          + " Use TenantOwnership.loadOwned(...), or add a `// tenant-ok: <reason>` comment on the"
          + " line above saying why this id is already safe (re-read of a validated entity, id"
          + " originating in a client-scoped query, or system reference data). Offenders:\n  "
          + String.join("\n  ", offenders));
    }
  }

  /**
   * The guard is actually reachable from the area — a sanity check so that deleting every
   * {@code loadOwned} call and leaving only justified lookups cannot pass silently.
   *
   * @throws IOException if a guarded source file cannot be read
   */
  @Test
  @DisplayName("The area still routes request ids through TenantOwnership")
  void testTheAreaStillUsesTheOwnershipGuard() throws IOException {
    int usages = 0;
    for (String fileName : GUARDED_CLASSES) {
      for (String line : linesOf(fileName)) {
        if (line.contains(GUARD)) {
          usages++;
        }
      }
    }
    assertTrue(usages >= 15,
        "expected the financial-account area to resolve request-supplied ids through"
            + " TenantOwnership.loadOwned, found only " + usages + " call sites — if a fix was"
            + " reverted, restore it rather than lowering this floor");
  }

  /**
   * {@code TenantOwnership} itself must keep failing closed on an entity of another client. This
   * pins the one line that every other fix in the area depends on.
   *
   * @throws IOException if the helper source cannot be read
   */
  @Test
  @DisplayName("TenantOwnership compares against the readable client and org sets")
  void testOwnershipHelperChecksBothReadableSets() throws IOException {
    String source = String.join("\n", linesOf("TenantOwnership.java"));
    assertTrue(source.contains("getReadableClients()"),
        "TenantOwnership must decide visibility against OBContext.getReadableClients()");
    assertTrue(source.contains("getReadableOrganizations()"),
        "TenantOwnership must decide visibility against OBContext.getReadableOrganizations()");
  }
}
