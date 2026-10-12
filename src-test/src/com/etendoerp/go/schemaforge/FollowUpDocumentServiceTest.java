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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Savepoint;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for the generic follow-up layer (ETP-5576): the pending-quantity helper, the verdict
 * factories of {@link PendingResolver.Source}, the create path's order of operations and the GET
 * annotation; the caller's {@link FollowUpInputs} reach the creator. Every flow is a real {@link FollowUpFlow} over a mocked {@link PendingResolver} and
 * {@link TargetCreator}, so nothing here depends on any entity.
 *
 * @covers com.etendoerp.go.schemaforge.FollowUpDocumentService
 * @covers com.etendoerp.go.schemaforge.PendingResolver
 */
class FollowUpDocumentServiceTest {

  /** A second, non-movement target so the annotation is shown to be target-agnostic. */
  private static final FollowUpTarget INVOICE_TARGET =
      new FollowUpTarget("invoice", "createInvoice", "sales-invoice", "header");

  private static final PendingResolver.SourceLine LINE_1 =
      new PendingResolver.SourceLine("line-1", new BigDecimal("2"));
  private static final PendingResolver.SourceLine LINE_2 =
      new PendingResolver.SourceLine("line-2", new BigDecimal("1"));

  private MockedStatic<OBDal> obDalStatic;
  private Connection conn;

  @BeforeEach
  void setUp() {
    obDalStatic = mockStatic(OBDal.class);
    OBDal dal = mock(OBDal.class);
    conn = mock(Connection.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
    when(dal.getConnection()).thenReturn(conn);
  }

  @AfterEach
  void tearDown() {
    obDalStatic.close();
  }

  // ── pendingQuantity ───────────────────────────────────────────────────────

  @ParameterizedTest(name = "source={0} moved={1} upstream={2} -> {3}")
  @CsvSource({
      ",,,0",        // every input null: nothing pending
      "10,,,10",     // null moved counts as zero; null upstream = no cap
      "10,4,,6",
      "10,10,,0",    // fully moved
      "10,12,,0",    // over-moved never goes negative
      "10,4,3,3",    // upstream cap lower than own pending wins
      "10,4,8,6",    // upstream cap higher than own pending is ignored
      "10,4,-2,0",   // negative upstream cap reads as nothing pending
  })
  void pendingQuantityAppliesOwnPendingThenUpstreamCapAndNeverGoesNegative(String source,
      String moved, String upstream, String expected) {
    BigDecimal pending = FollowUpDocumentService.pendingQuantity(dec(source), dec(moved),
        dec(upstream));

    assertEquals(0, new BigDecimal(expected).compareTo(pending),
        "expected " + expected + " but was " + pending);
  }

  // ── Source factories ──────────────────────────────────────────────────────

  @Test
  void fromLinesReportsTheIneligibilityEvenWhenLinesArePending() {
    PendingResolver.Source source = PendingResolver.Source.fromLines("inv-1",
        FollowUpException.Reason.NOT_COMPLETED, Collections.singletonList(LINE_1));

    assertFalse(source.isAvailable());
    assertEquals(FollowUpException.Reason.NOT_COMPLETED, source.getUnavailability());
    assertTrue(source.getLines().isEmpty());
  }

  @ParameterizedTest
  @NullAndEmptySource
  void fromLinesWithoutPendingLinesIsNothingPending(List<PendingResolver.SourceLine> lines) {
    PendingResolver.Source source = PendingResolver.Source.fromLines("inv-1", null, lines);

    assertFalse(source.isAvailable());
    assertEquals(FollowUpException.Reason.NOTHING_PENDING, source.getUnavailability());
  }

  @Test
  void fromLinesWithPendingLinesIsAvailableWithThoseLines() {
    PendingResolver.Source source = PendingResolver.Source.fromLines("inv-1", null,
        Arrays.asList(LINE_1, LINE_2));

    assertTrue(source.isAvailable());
    assertNull(source.getUnavailability());
    assertEquals("inv-1", source.getId());
    assertEquals(Arrays.asList(LINE_1, LINE_2), source.getLines());
  }

  // ── create ────────────────────────────────────────────────────────────────

  @Test
  void createLocksTheSourceBeforeEvaluatingItAndPassesThePendingLinesAndInputsToTheCreator()
      throws Exception {
    PendingResolver resolver = mock(PendingResolver.class);
    TargetCreator creator = mock(TargetCreator.class);
    List<PendingResolver.SourceLine> lines = Arrays.asList(LINE_1, LINE_2);
    Map<String, PendingResolver.Source> verdicts =
        Collections.singletonMap("inv-1", PendingResolver.Source.available("inv-1", lines));
    when(resolver.loadSources(anyCollection())).thenReturn(verdicts);
    TargetCreator.Result created = new TargetCreator.Result("io-1", "ALB-1", 2);
    FollowUpInputs inputs = FollowUpInputs.fromRequestBody(
        new JSONObject().put("warehouseId", "wh-1"));
    when(creator.createTarget("inv-1", lines, inputs)).thenReturn(created);
    FollowUpFlow flow = FollowUpFlow.of(FollowUpTarget.GOODS_SHIPMENT, resolver, creator);

    TargetCreator.Result result = FollowUpDocumentService.create("inv-1", flow, inputs);

    assertSame(created, result);
    InOrder order = inOrder(resolver, creator);
    order.verify(resolver).lockSource("inv-1");
    order.verify(resolver).loadSources(Collections.singletonList("inv-1"));
    order.verify(creator).createTarget("inv-1", lines, inputs);
    // The create path owns no savepoint: a failure there must abort the whole request.
    verifyNoInteractions(conn);
  }

  @Test
  void createHandsNoInputsToTheCreatorWhenTheCallerPassesNull() {
    PendingResolver resolver = mock(PendingResolver.class);
    TargetCreator creator = mock(TargetCreator.class);
    List<PendingResolver.SourceLine> lines = Collections.singletonList(LINE_1);
    Map<String, PendingResolver.Source> verdicts =
        Collections.singletonMap("inv-1", PendingResolver.Source.available("inv-1", lines));
    when(resolver.loadSources(anyCollection())).thenReturn(verdicts);
    TargetCreator.Result created = new TargetCreator.Result("io-1", "ALB-1", 1);
    when(creator.createTarget("inv-1", lines, FollowUpInputs.none())).thenReturn(created);
    FollowUpFlow flow = FollowUpFlow.of(FollowUpTarget.GOODS_SHIPMENT, resolver, creator);

    assertSame(created, FollowUpDocumentService.create("inv-1", flow, null));
    verify(creator).createTarget("inv-1", lines, FollowUpInputs.none());
  }

  /**
   * A warehouse the caller must choose is only discovered by the movement creator's mapper, after
   * the source is locked and evaluated; the rejection carries the choice and no movement is
   * built.
   */
  @Test
  void createRejectsWithWarehouseRequiredAfterLockAndEvaluateAndBuildsNothing() {
    PendingResolver resolver = mock(PendingResolver.class);
    List<PendingResolver.SourceLine> lines = Collections.singletonList(LINE_1);
    Map<String, PendingResolver.Source> verdicts =
        Collections.singletonMap("inv-1", PendingResolver.Source.available("inv-1", lines));
    when(resolver.loadSources(anyCollection())).thenReturn(verdicts);
    InOutFollowUpCreator.SourceMapper mapper = mock(InOutFollowUpCreator.SourceMapper.class);
    FollowUpException.RequiredInput choice = new FollowUpException.RequiredInput("warehouseId",
        Collections.singletonList(new FollowUpException.RequiredInput.Option("wh-1", "Main")));
    FollowUpException required = new FollowUpException(
        FollowUpException.Reason.WAREHOUSE_REQUIRED, "choose", choice);
    when(mapper.map("inv-1", lines, FollowUpInputs.none())).thenThrow(required);
    InOutFollowUpCreator creator = new InOutFollowUpCreator(InOutTargetBuilder.Direction.SALES,
        mapper, mock(InOutTargetBuilder.LineLinker.class));
    FollowUpFlow flow = FollowUpFlow.of(FollowUpTarget.GOODS_SHIPMENT, resolver, creator);

    try (MockedStatic<InOutTargetBuilder> builder = mockStatic(InOutTargetBuilder.class)) {
      FollowUpException e = assertThrows(FollowUpException.class,
          () -> FollowUpDocumentService.create("inv-1", flow, FollowUpInputs.none()));

      assertEquals(FollowUpException.Reason.WAREHOUSE_REQUIRED, e.getReason());
      assertSame(choice, e.getRequiredInput());
      InOrder order = inOrder(resolver, mapper);
      order.verify(resolver).lockSource("inv-1");
      order.verify(resolver).loadSources(Collections.singletonList("inv-1"));
      order.verify(mapper).map("inv-1", lines, FollowUpInputs.none());
      builder.verifyNoInteractions();
    }
  }

  static Stream<Arguments> rejectedVerdicts() {
    Map<String, PendingResolver.Source> unknownId = Collections.emptyMap();
    Map<String, PendingResolver.Source> draft = Collections.singletonMap("inv-1",
        PendingResolver.Source.unavailable("inv-1", FollowUpException.Reason.DRAFT_IN_PROGRESS));
    Map<String, PendingResolver.Source> noLines = Collections.singletonMap("inv-1",
        PendingResolver.Source.available("inv-1", Collections.emptyList()));
    return Stream.of(
        Arguments.of("id unknown to the resolver", unknownId, FollowUpException.Reason.NOT_FOUND),
        Arguments.of("resolver says unavailable", draft,
            FollowUpException.Reason.DRAFT_IN_PROGRESS),
        Arguments.of("available but no line to carry", noLines,
            FollowUpException.Reason.NOTHING_PENDING));
  }

  @ParameterizedTest(name = "{0} -> {2}")
  @MethodSource("rejectedVerdicts")
  void createRejectsWithTheVerdictReasonAndNeverCallsTheCreator(String label,
      Map<String, PendingResolver.Source> verdicts, FollowUpException.Reason expected) {
    PendingResolver resolver = mock(PendingResolver.class);
    TargetCreator creator = mock(TargetCreator.class);
    when(resolver.loadSources(anyCollection())).thenReturn(verdicts);
    FollowUpFlow flow = FollowUpFlow.of(FollowUpTarget.GOODS_SHIPMENT, resolver, creator);

    FollowUpException e = assertThrows(FollowUpException.class,
        () -> FollowUpDocumentService.create("inv-1", flow, FollowUpInputs.none()));

    assertEquals(expected, e.getReason());
    verify(resolver).lockSource("inv-1");
    verify(creator, never()).createTarget(anyString(), anyList(), any());
  }

  // ── annotatePage ──────────────────────────────────────────────────────────

  @Test
  void annotatePageListsAvailableFollowUpsInRegistrationOrderAndExplainsTheOthers()
      throws Exception {
    Savepoint sp = mock(Savepoint.class);
    when(conn.setSavepoint()).thenReturn(sp);
    // shipment: a and b needed, c nothing pending
    Map<String, PendingResolver.Source> shipmentVerdicts = new HashMap<>();
    shipmentVerdicts.put("a",
        PendingResolver.Source.available("a", Arrays.asList(LINE_1, LINE_2)));
    shipmentVerdicts.put("b",
        PendingResolver.Source.available("b", Collections.singletonList(LINE_1)));
    shipmentVerdicts.put("c",
        PendingResolver.Source.unavailable("c", FollowUpException.Reason.NOTHING_PENDING));
    // invoice: a needed, b not completed, c unknown to the resolver
    Map<String, PendingResolver.Source> invoiceVerdicts = new HashMap<>();
    invoiceVerdicts.put("a",
        PendingResolver.Source.available("a", Collections.singletonList(LINE_2)));
    invoiceVerdicts.put("b",
        PendingResolver.Source.unavailable("b", FollowUpException.Reason.NOT_COMPLETED));
    PendingResolver shipmentResolver = mock(PendingResolver.class);
    PendingResolver invoiceResolver = mock(PendingResolver.class);
    when(shipmentResolver.loadSources(anyCollection())).thenReturn(shipmentVerdicts);
    when(invoiceResolver.loadSources(anyCollection())).thenReturn(invoiceVerdicts);
    JSONArray page = page("a", "b", "c");

    FollowUpDocumentService.annotatePage(page, Arrays.asList(
        flow(FollowUpTarget.GOODS_SHIPMENT, shipmentResolver),
        flow(INVOICE_TARGET, invoiceResolver)));

    // One lookup per flow for the whole page, each in its own released savepoint.
    verify(shipmentResolver).loadSources(Arrays.asList("a", "b", "c"));
    verify(invoiceResolver).loadSources(Arrays.asList("a", "b", "c"));
    verify(conn, times(2)).releaseSavepoint(sp);
    verify(conn, never()).rollback(any(Savepoint.class));

    JSONObject a = followUp(page, 0);
    assertEquals("[\"shipment\",\"invoice\"]", a.getJSONArray("available").toString());
    JSONObject aShipment = a.getJSONObject("shipment");
    assertTrue(aShipment.getBoolean("needed"));
    assertTrue(aShipment.isNull("reason"));
    assertEquals(2, aShipment.getInt("pendingLines"));
    assertEquals("createShipment", aShipment.getString("action"));
    assertEquals("goods-shipment", aShipment.getString("targetSpec"));
    assertEquals("goodsShipment", aShipment.getString("targetEntity"));

    JSONObject b = followUp(page, 1);
    assertEquals("[\"shipment\"]", b.getJSONArray("available").toString());
    JSONObject bInvoice = b.getJSONObject("invoice");
    assertFalse(bInvoice.getBoolean("needed"));
    assertEquals("FOLLOW_UP_SOURCE_NOT_COMPLETED", bInvoice.getString("reason"));
    assertEquals(0, bInvoice.getInt("pendingLines"));
    assertEquals("createInvoice", bInvoice.getString("action"));
    assertEquals("sales-invoice", bInvoice.getString("targetSpec"));

    JSONObject c = followUp(page, 2);
    assertEquals(0, c.getJSONArray("available").length());
    assertEquals("FOLLOW_UP_NOTHING_PENDING", c.getJSONObject("shipment").getString("reason"));
    assertEquals("FOLLOW_UP_SOURCE_NOT_FOUND", c.getJSONObject("invoice").getString("reason"));
  }

  @Test
  void annotatePageConfinesAFailingLookupToItsOwnKeyAndRollsBackToItsSavepoint()
      throws Exception {
    Savepoint failedSp = mock(Savepoint.class);
    Savepoint okSp = mock(Savepoint.class);
    when(conn.setSavepoint()).thenReturn(failedSp, okSp);
    PendingResolver shipmentResolver = mock(PendingResolver.class);
    PendingResolver invoiceResolver = mock(PendingResolver.class);
    when(shipmentResolver.loadSources(anyCollection()))
        .thenThrow(new OBException("lookup failed"));
    Map<String, PendingResolver.Source> invoiceVerdicts = Collections.singletonMap("a",
        PendingResolver.Source.available("a", Collections.singletonList(LINE_1)));
    when(invoiceResolver.loadSources(anyCollection())).thenReturn(invoiceVerdicts);
    JSONArray page = page("a");

    FollowUpDocumentService.annotatePage(page, Arrays.asList(
        flow(FollowUpTarget.GOODS_SHIPMENT, shipmentResolver),
        flow(INVOICE_TARGET, invoiceResolver)));

    verify(conn).rollback(failedSp);
    verify(conn, never()).releaseSavepoint(failedSp);
    verify(conn).releaseSavepoint(okSp);
    verify(conn, never()).rollback(okSp);
    JSONObject a = followUp(page, 0);
    assertEquals("[\"invoice\"]", a.getJSONArray("available").toString());
    JSONObject failed = a.getJSONObject("shipment");
    assertFalse(failed.getBoolean("needed"));
    assertEquals(FollowUpDocumentService.REASON_LOOKUP_FAILED, failed.getString("reason"));
    assertEquals(0, failed.getInt("pendingLines"));
    assertTrue(a.getJSONObject("invoice").getBoolean("needed"));
  }

  @Test
  void annotatePageExtendsAnExistingFollowUpObjectInsteadOfReplacingIt() throws Exception {
    Savepoint sp = mock(Savepoint.class);
    when(conn.setSavepoint()).thenReturn(sp);
    PendingResolver resolver = mock(PendingResolver.class);
    Map<String, PendingResolver.Source> verdicts = Collections.singletonMap("a",
        PendingResolver.Source.available("a", Collections.singletonList(LINE_1)));
    when(resolver.loadSources(anyCollection())).thenReturn(verdicts);
    JSONObject existing = new JSONObject()
        .put("available", new JSONArray().put("other"))
        .put("other", new JSONObject().put("needed", true));
    JSONArray page = new JSONArray()
        .put(new JSONObject().put("id", "a").put("followUp", existing));

    FollowUpDocumentService.annotatePage(page,
        Collections.singletonList(flow(FollowUpTarget.GOODS_SHIPMENT, resolver)));

    JSONObject a = followUp(page, 0);
    assertEquals("[\"other\",\"shipment\"]", a.getJSONArray("available").toString());
    assertTrue(a.getJSONObject("other").getBoolean("needed"));
    assertTrue(a.getJSONObject("shipment").getBoolean("needed"));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /** A flow whose creator the read side never reaches. */
  private static FollowUpFlow flow(FollowUpTarget target, PendingResolver resolver) {
    return FollowUpFlow.of(target, resolver, mock(TargetCreator.class));
  }

  private static JSONArray page(String... ids) throws Exception {
    JSONArray page = new JSONArray();
    for (String id : ids) {
      page.put(new JSONObject().put("id", id));
    }
    return page;
  }

  private static JSONObject followUp(JSONArray page, int index) throws Exception {
    return page.getJSONObject(index).getJSONObject(FollowUpDocumentService.FIELD_FOLLOW_UP);
  }

  private static BigDecimal dec(String value) {
    return value != null ? new BigDecimal(value) : null;
  }
}
