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

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Unit tests for the wiring a header handler holds to offer follow-up documents (ETP-5576):
 * {@link FollowUpSupport} — a supplier that yields {@code null} offers no follow-up at all,
 * neither an annotation nor an action — and {@link FollowUpFlow}, the immutable composition it
 * is fed with, which rejects a missing part and only delegates (the caller's
 * {@link FollowUpInputs} included).
 *
 * @covers com.etendoerp.go.schemaforge.FollowUpSupport
 * @covers com.etendoerp.go.schemaforge.FollowUpFlow
 * @covers com.etendoerp.go.schemaforge.TargetCreator
 */
class FollowUpSupportTest {

  // ── FollowUpSupport ───────────────────────────────────────────────────────

  @Test
  void nullFlowListAnnotatesNothingAndNeverQueries() throws Exception {
    FollowUpSupport support = new FollowUpSupport(() -> null);
    JSONArray page = new JSONArray().put(new JSONObject().put("id", "inv-1"));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      support.annotate(page);

      obDal.verifyNoInteractions();
    }
    assertFalse(page.getJSONObject(0).has(FollowUpDocumentService.FIELD_FOLLOW_UP));
  }

  @Test
  void nullFlowListServesNoAction() {
    FollowUpSupport support = new FollowUpSupport(() -> null);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("createShipment").recordId("inv-1").build();

    assertNull(support.actionHandler().handle(ctx));
    assertTrue(support.actionContracts().isEmpty(), "no flow, no declared action");
    assertTrue(support.responseFields().isEmpty(), "no flow, no followUp key on the records");
  }

  /**
   * MCP-8 / obs. 11: a registered flow is discoverable — its action is declared with the inputs
   * its creator reads — and its annotation key is declared as a response key, so an MCP
   * {@code fields:["followUp"]} projection does not call it unknown.
   */
  @Test
  void registeredFlowDeclaresItsActionAndItsResponseKey() {
    TargetCreator creator = mock(TargetCreator.class);
    NeoActionContract.Param warehouse =
        NeoActionContract.Param.optional("warehouseId", NeoActionContract.TYPE_STRING, "w");
    when(creator.inputParams()).thenReturn(List.of(warehouse));
    FollowUpFlow flow = FollowUpFlow.of(FollowUpTarget.GOODS_RECEIPT, mock(PendingResolver.class),
        creator);
    FollowUpSupport support = new FollowUpSupport(() -> List.of(flow));

    Map<String, NeoActionContract> contracts = support.actionContracts();

    assertEquals(List.of("createGoodsReceipt"), List.copyOf(contracts.keySet()));
    NeoActionContract contract = contracts.get("createGoodsReceipt");
    assertTrue(contract.isMutating());
    assertNull(NeoActionContract.validate(contracts, "createGoodsReceipt",
        new JSONObject()), "every input is optional: an empty body is a valid call");
    assertEquals(Set.of(FollowUpDocumentService.FIELD_FOLLOW_UP), support.responseFields());
  }

  // ── FollowUpFlow ──────────────────────────────────────────────────────────

  @ParameterizedTest(name = "missing {0}")
  @ValueSource(strings = { "target", "resolver", "creator" })
  void ofRejectsAMissingPartNamingIt(String missing) {
    FollowUpTarget target = "target".equals(missing) ? null : FollowUpTarget.GOODS_SHIPMENT;
    PendingResolver resolver = "resolver".equals(missing) ? null : mock(PendingResolver.class);
    TargetCreator creator = "creator".equals(missing) ? null : mock(TargetCreator.class);

    NullPointerException e = assertThrows(NullPointerException.class,
        () -> FollowUpFlow.of(target, resolver, creator));

    assertEquals(missing, e.getMessage());
  }

  @Test
  void flowDelegatesEveryCallToItsResolverAndCreator() throws Exception {
    PendingResolver resolver = mock(PendingResolver.class);
    TargetCreator creator = mock(TargetCreator.class);
    doReturn(Invoice.class).when(resolver).sourceEntity();
    Collection<String> ids = Collections.singletonList("inv-1");
    Map<String, PendingResolver.Source> verdicts = Collections.singletonMap("inv-1",
        PendingResolver.Source.unavailable("inv-1", FollowUpException.Reason.NOTHING_PENDING));
    when(resolver.loadSources(ids)).thenReturn(verdicts);
    List<PendingResolver.SourceLine> lines = Collections.singletonList(
        new PendingResolver.SourceLine("il-1", BigDecimal.ONE));
    TargetCreator.Result created = new TargetCreator.Result("io-1", "DOC-1", 1);
    FollowUpInputs inputs = FollowUpInputs.fromRequestBody(
        new JSONObject().put("warehouseId", "wh-1"));
    when(creator.createTarget("inv-1", lines, inputs)).thenReturn(created);

    FollowUpFlow flow = FollowUpFlow.of(FollowUpTarget.GOODS_RECEIPT, resolver, creator);
    flow.lockSource("inv-1");

    assertSame(FollowUpTarget.GOODS_RECEIPT, flow.target());
    assertSame(resolver, flow.resolver());
    assertSame(creator, flow.creator());
    assertEquals(Invoice.class, flow.sourceEntity());
    assertSame(verdicts, flow.loadSources(ids));
    assertSame(created, flow.createTarget("inv-1", lines, inputs));
    verify(resolver).lockSource("inv-1");
  }
}
