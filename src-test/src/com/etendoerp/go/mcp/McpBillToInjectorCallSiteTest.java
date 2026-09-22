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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
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
 * Architecture regression test for ETP-5335 — both MCP write paths must derive the bill-to.
 *
 * <p>{@code neo_batch} never reaches {@code handleCreate}: it builds its own per-operation pre-pass
 * and hands the body straight to {@code BatchService}. So a compensation wired into the create verb
 * alone covers exactly half of the MCP writes, and the uncovered half fails nowhere near the cause
 * — the order persists with a null {@code BillTo_ID} and the defect surfaces much later, when
 * {@code C_INVOICE_CREATE} copies that null into the {@code NOT NULL}
 * {@code C_Invoice.C_BPartner_Location_ID}.</p>
 *
 * <p>Nothing behavioural can catch that: {@link McpBillToInjector}'s own unit tests keep passing, no
 * signature changes, and both router methods are private and need an {@code OBContext}, a live DAL
 * and an {@code AD_Tab}. The defect is a <b>missing (or misplaced) call site</b>, which is what
 * {@code McpSourceScanner} exists for — see {@code McpWriteVerbCoercionCallSiteTest} for the
 * precedent.</p>
 *
 * <p>Position matters as much as presence, in both directions, so each path is also pinned against
 * one anchor on each side:</p>
 * <ul>
 *   <li>after {@code McpFkResolver.resolveFkNames} — before it, a business partner given by name
 *       (or as a {@code $ref:} inside a batch) is not an id yet and the injector would abstain on
 *       every such write;</li>
 *   <li>before {@code McpWriteRequestSupport.validateMandatoryFields} — after it, the create is
 *       already refused for the very field the injector was about to fill, which is the whole
 *       defect ETP-5335 reports.</li>
 * </ul>
 */
@DisplayName("ETP-5335 — every MCP write path derives the bill-to, in the right order")
class McpBillToInjectorCallSiteTest {

  private static final String ROUTER = "com/etendoerp/go/mcp/McpToolRouter.java";

  /** The derivation both write paths must run. */
  private static final Pattern INJECTOR_CALL =
      Pattern.compile("McpBillToInjector\\s*\\.\\s*injectIfMissing\\s*\\(");

  /** The FK pre-pass that turns names and {@code $ref:} placeholders into ids. */
  private static final Pattern FK_RESOLUTION =
      Pattern.compile("McpFkResolver\\s*\\.\\s*resolveFkNames\\s*\\(");

  /** The mandatory-field check that refuses the write the injector is there to rescue. */
  private static final Pattern MANDATORY_CHECK =
      Pattern.compile("validateMandatoryFields\\s*\\(");

  @Test
  @DisplayName("neo_create derives the bill-to after FK resolution and before the mandatory check")
  void createPathDerivesBillTo() {
    String body = routerMethod("handleCreate");

    int injector = indexOfOrFail(body, INJECTOR_CALL, "handleCreate",
        "McpBillToInjector.injectIfMissing(filteredBody, dalEntity, log)");
    int fkResolution = indexOfOrFail(body, FK_RESOLUTION, "handleCreate",
        "McpFkResolver.resolveFkNames(...)");
    int mandatoryCheck = indexOfOrFail(body, MANDATORY_CHECK, "handleCreate",
        "McpWriteRequestSupport.validateMandatoryFields(...)");

    assertTrue(fkResolution < injector,
        "handleCreate derives the bill-to before resolving FK names, so the business partner is"
            + " still a name and the injector abstains on every write that names its partner."
            + " Move the injectIfMissing call after McpFkResolver.resolveFkNames.");
    assertTrue(injector < mandatoryCheck,
        "handleCreate derives the bill-to after validateMandatoryFields, so the create is already"
            + " refused for BillTo_ID — the exact defect ETP-5335 fixes, and a field view:\"create\""
            + " never offered the agent. Move the injectIfMissing call before the mandatory check.");
  }

  @Test
  @DisplayName("neo_batch derives the bill-to too, after its own FK pre-pass")
  void batchPathDerivesBillTo() {
    String body = routerMethod("resolveBatchOpFkNames");

    int injector = indexOfOrFail(body, INJECTOR_CALL, "resolveBatchOpFkNames",
        "McpBillToInjector.injectIfMissing(body, dalEntity, log)");
    int fkResolution = indexOfOrFail(body, FK_RESOLUTION, "resolveBatchOpFkNames",
        "McpFkResolver.resolveFkNames(...)");

    assertTrue(fkResolution < injector,
        "resolveBatchOpFkNames derives the bill-to before its FK pre-pass, so a partner given by"
            + " name or as a $ref: placeholder is not an id yet and the injector abstains."
            + " Move the injectIfMissing call to the end of the pre-pass.");
  }

  private static String routerMethod(String name) {
    return McpSourceScanner.methodBody(McpSourceScanner.read(ROUTER), name);
  }

  /**
   * @return the offset of {@code pattern} inside {@code body}
   * @throws AssertionError with the call that is missing — a guard that stops finding its anchors
   *     is mute, not passing
   */
  private static int indexOfOrFail(String body, Pattern pattern, String method, String expected) {
    Matcher matcher = pattern.matcher(body);
    assertTrue(matcher.find(),
        method + " no longer contains " + expected + ". Either the call site was dropped — the"
            + " ETP-5335 regression — or the method was refactored and this guard needs updating;"
            + " do not delete the guard without moving the call.");
    return matcher.start();
  }
}
