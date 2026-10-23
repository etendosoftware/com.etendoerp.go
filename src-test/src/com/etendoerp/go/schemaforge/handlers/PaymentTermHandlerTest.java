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

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.payment.PaymentTerm;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoMandatoryDefaultsService;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoServerOwnedFields;

/**
 * Unit tests for {@link PaymentTermHandler}, the {@code payment-term} header pre-hook that
 * validates the number of days ({@code overduePaymentDaysRule}), refuses a duplicate simple term
 * or a name already used in the target organization, and fills the defaults of a days-only
 * create.
 *
 * <p>Strategy: spy the handler, silence admin mode, and stub the package-private DAL seams
 * ({@code loadStored}, {@code hasLines}, {@code currentClientId}, {@code findSimpleTermWithDays},
 * {@code findTermWithName}, {@code resolveTargetOrgId}) so every path runs through
 * {@code handle()} without a database. The HQL seams are covered apart with a mocked
 * {@link OBDal}, and the create-time organization resolution with mocked
 * {@link NeoMandatoryDefaultsService} / {@link NeoServerOwnedFields} statics.
 *
 * @covers com.etendoerp.go.schemaforge.handlers.PaymentTermHandler
 */
public class PaymentTermHandlerTest {

  private static final String SPEC = "payment-term";
  private static final String DAYS = "overduePaymentDaysRule";
  private static final String CLIENT_ID = "23C59575B9CF467C9620760EB255B389";
  private static final String TERM_ID = "TERM-SELF";
  private static final String OTHER_ID = "TERM-OTHER";
  private static final String NAMED_ID = "TERM-NAMED";
  private static final String STORED_ORG = "ORG-STORED";
  private static final String DEFAULT_ORG = "ORG-DEFAULT";
  private static final String CALLER_ORG = "ORG-CALLER";
  private static final String NAME_22 = "22 D\u00EDas";

  private PaymentTermHandler handler;
  /** Admin mode is entered through OBContext statics; mocked so no live session is needed. */
  private MockedStatic<OBContext> obContextStatic;

  @Before
  public void setUp() {
    obContextStatic = mockStatic(OBContext.class);
    handler = spy(new PaymentTermHandler());
    doReturn(CLIENT_ID).when(handler).currentClientId();
    doReturn(null).when(handler).findSimpleTermWithDays(anyString(), anyLong(), Mockito.any());
    doReturn(null).when(handler).findTermWithName(anyString(), anyString(), anyString(),
        Mockito.any());
  }

  @After
  public void clearMocks() {
    obContextStatic.close();
    Mockito.framework().clearInlineMocks();
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static NeoContext ctx(String method, String recordId, JSONObject body,
      NeoEndpointType type) {
    return NeoContext.builder()
        .specName(SPEC)
        .entityName("header")
        .httpMethod(method)
        .recordId(recordId)
        .requestBody(body)
        .endpointType(type)
        .build();
  }

  private NeoResponse create(JSONObject body) {
    return handler.handle(ctx("POST", null, body, NeoEndpointType.CRUD));
  }

  private NeoResponse update(JSONObject body) {
    return handler.handle(ctx("PUT", TERM_ID, body, NeoEndpointType.CRUD));
  }

  private static JSONObject days(Object value) throws Exception {
    return new JSONObject().put(DAYS, value);
  }

  /** A stored term with the given shape, owned by {@link #CLIENT_ID}. */
  private PaymentTerm storeTerm(Long days, Long offset, boolean fixedDueDate, boolean lines) {
    PaymentTerm stored = mock(PaymentTerm.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    when(stored.getId()).thenReturn(TERM_ID);
    when(stored.getClient()).thenReturn(client);
    when(stored.getOverduePaymentDaysRule()).thenReturn(days);
    when(stored.getOffsetMonthDue()).thenReturn(offset);
    when(stored.isFixedDueDate()).thenReturn(fixedDueDate);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(STORED_ORG);
    when(stored.getOrganization()).thenReturn(org);
    doReturn(stored).when(handler).loadStored(TERM_ID);
    doReturn(lines).when(handler).hasLines(stored);
    return stored;
  }

  private static PaymentTerm existingTerm(boolean active) {
    PaymentTerm existing = mock(PaymentTerm.class);
    when(existing.getId()).thenReturn(OTHER_ID);
    when(existing.getName()).thenReturn("30 Días");
    when(existing.getSearchKey()).thenReturn("30D");
    when(existing.isActive()).thenReturn(active);
    return existing;
  }

  private static JSONObject error(NeoResponse response) {
    assertNotNull("expected a refusal", response);
    return response.getBody().optJSONObject("error");
  }

  private static void assertRefusal(NeoResponse response, int status, String messageKey)
      throws Exception {
    assertRefusal(response, status, messageKey, DAYS);
  }

  private static void assertRefusal(NeoResponse response, int status, String messageKey,
      String field) throws Exception {
    JSONObject error = error(response);
    assertEquals(status, response.getHttpStatus());
    assertEquals(status, error.getInt("status"));
    assertEquals(1, error.getJSONArray("messageKeys").length());
    assertEquals(messageKey, error.getJSONArray("messageKeys").getString(0));
    assertEquals(1, error.getJSONArray("fields").length());
    assertEquals(field, error.getJSONArray("fields").getString(0));
    // ensureTopLevelMessage: the error message is mirrored at the top level.
    assertEquals(error.getString("message"), response.getBody().getString("message"));
  }

  private void assertAdminModeNeverEntered() {
    obContextStatic.verify(() -> OBContext.setAdminMode(true), never());
  }

  private static PaymentTerm namedTerm(String name, boolean active) {
    PaymentTerm existing = mock(PaymentTerm.class);
    when(existing.getId()).thenReturn(NAMED_ID);
    when(existing.getName()).thenReturn(name);
    when(existing.isActive()).thenReturn(active);
    return existing;
  }

  private void assertNoNameLookup() {
    verify(handler, never()).findTermWithName(anyString(), anyString(), anyString(),
        Mockito.any());
  }

  private void assertNoDuplicateLookup() {
    verify(handler, never()).findSimpleTermWithDays(anyString(), anyLong(), Mockito.any());
  }

  // --- parsing ---------------------------------------------------------------------------------

  @Test
  public void parseWholeNumberAcceptsIntegersIntegralDecimalsAndDigitStrings() {
    assertEquals(Long.valueOf(12), PaymentTermHandler.parseWholeNumber(12));
    assertEquals(Long.valueOf(12), PaymentTermHandler.parseWholeNumber(12L));
    assertEquals(Long.valueOf(12), PaymentTermHandler.parseWholeNumber(12.0d));
    assertEquals(Long.valueOf(12), PaymentTermHandler.parseWholeNumber("12"));
    assertEquals(Long.valueOf(12), PaymentTermHandler.parseWholeNumber(" 12 "));
    assertEquals(Long.valueOf(-1), PaymentTermHandler.parseWholeNumber("-1"));
  }

  @Test
  public void parseWholeNumberRejectsFractionsTextAndExpressions() {
    assertNull(PaymentTermHandler.parseWholeNumber(7.5d));
    assertNull(PaymentTermHandler.parseWholeNumber("7,5"));
    assertNull(PaymentTermHandler.parseWholeNumber("7.5"));
    assertNull(PaymentTermHandler.parseWholeNumber("abc"));
    assertNull(PaymentTermHandler.parseWholeNumber("50% 30 / 50% 60"));
    assertNull(PaymentTermHandler.parseWholeNumber(""));
    assertNull(PaymentTermHandler.parseWholeNumber(Double.NaN));
    assertNull(PaymentTermHandler.parseWholeNumber(Boolean.TRUE));
    assertNull(PaymentTermHandler.parseWholeNumber(null));
  }

  @Test
  public void createAcceptsAJsonInteger() throws Exception {
    JSONObject body = days(12);

    assertNull(create(body));
    assertEquals(12L, body.getLong(DAYS));
  }

  @Test
  public void createStoresAnIntegralDecimalAsANumber() throws Exception {
    JSONObject body = days(12.0d);

    assertNull(create(body));
    assertEquals(Long.valueOf(12), body.get(DAYS));
  }

  @Test
  public void createStoresADigitStringAsANumber() throws Exception {
    JSONObject body = days("12");

    assertNull(create(body));
    Object stored = body.get(DAYS);
    assertTrue("days must be stored as a number, got " + stored.getClass(),
        stored instanceof Number);
    assertEquals(12L, ((Number) stored).longValue());
  }

  @Test
  public void createRefusesAValueThatIsNotAWholeNumber() throws Exception {
    for (Object invalid : new Object[] { 7.5d, "7,5", "abc", "50% 30 / 50% 60" }) {
      NeoResponse response = create(days(invalid));

      assertRefusal(response, 400, PaymentTermHandler.MSG_DAYS_INVALID);
      JSONObject error = error(response);
      assertEquals(PaymentTermHandler.CODE_DAYS_INVALID, error.getString("code"));
      assertEquals(String.valueOf(invalid),
          String.valueOf(error.getJSONObject("messageParams").get("value")));
    }
    assertNoDuplicateLookup();
  }

  @Test
  public void createRefusesMissingDays() throws Exception {
    NeoResponse response = create(new JSONObject().put("name", "Thirty"));

    assertRefusal(response, 400, PaymentTermHandler.MSG_DAYS_INVALID);
    JSONObject params = error(response).getJSONObject("messageParams");
    assertTrue(params.isNull("value"));
    assertEquals(0, params.getInt("min"));
    assertEquals(365, params.getInt("max"));
  }

  @Test
  public void createRefusesExplicitNullDays() throws Exception {
    assertRefusal(create(days(JSONObject.NULL)), 400, PaymentTermHandler.MSG_DAYS_INVALID);
  }

  @Test
  public void updateRefusesExplicitNullDays() throws Exception {
    storeTerm(30L, 0L, false, false);

    assertRefusal(update(days(JSONObject.NULL)), 400, PaymentTermHandler.MSG_DAYS_INVALID);
  }

  @Test
  public void updateWithoutDaysPassesThrough() throws Exception {
    storeTerm(30L, 0L, false, false);
    JSONObject body = new JSONObject().put("name", "Renamed");

    assertNull(update(body));
    assertFalse(body.has(DAYS));
    assertNoDuplicateLookup();
  }

  // --- range -----------------------------------------------------------------------------------

  @Test
  public void createRefusesDaysBelowZeroAndAbove365() throws Exception {
    for (long outOfRange : new long[] { -1L, 366L }) {
      NeoResponse response = create(days(outOfRange));

      assertRefusal(response, 400, PaymentTermHandler.MSG_DAYS_OUT_OF_RANGE);
      JSONObject error = error(response);
      assertEquals(PaymentTermHandler.CODE_DAYS_OUT_OF_RANGE, error.getString("code"));
      assertEquals(outOfRange, error.getJSONObject("messageParams").getLong("value"));
    }
    assertNoDuplicateLookup();
  }

  @Test
  public void createAcceptsTheRangeBounds() throws Exception {
    assertNull(create(days(0)));
    assertNull(create(days(365)));
  }

  @Test
  public void updateKeepingAnOutOfRangeLegacyValuePasses() throws Exception {
    storeTerm(400L, 0L, false, false);
    JSONObject body = days(400).put("name", "Legacy");

    assertNull(update(body));
    assertNoDuplicateLookup();
  }

  @Test
  public void updateChangingToAnOutOfRangeValueIsRefused() throws Exception {
    storeTerm(400L, 0L, false, false);

    assertRefusal(update(days(401)), 400, PaymentTermHandler.MSG_DAYS_OUT_OF_RANGE);
  }

  @Test
  public void updateFromAValidValueTo366IsRefused() throws Exception {
    storeTerm(30L, 0L, false, false);

    assertRefusal(update(days(366)), 400, PaymentTermHandler.MSG_DAYS_OUT_OF_RANGE);
  }

  // --- duplicate -------------------------------------------------------------------------------

  @Test
  public void createRefusesTheDaysOfAnActiveTermOfTheSameClient() throws Exception {
    // The lookup is scoped by client only, so a match in another organization is a duplicate.
    doReturn(existingTerm(true)).when(handler).findSimpleTermWithDays(CLIENT_ID, 30L, null);

    NeoResponse response = create(days(30));

    assertRefusal(response, 409, PaymentTermHandler.MSG_DAYS_DUPLICATE);
    JSONObject error = error(response);
    assertEquals(PaymentTermHandler.CODE_DAYS_DUPLICATE, error.getString("code"));
    JSONObject params = error.getJSONObject("messageParams");
    assertEquals(30L, params.getLong("days"));
    assertEquals(OTHER_ID, params.getString("paymentTermId"));
    assertEquals("30 Días", params.getString("paymentTermName"));
    assertEquals("30D", params.getString("paymentTermSearchKey"));
    assertTrue(params.getBoolean("paymentTermActive"));
    assertFalse(error.getString("message").contains("inactive"));
    assertTrue(error.getString("message").contains(OTHER_ID));
    assertTrue(error.getString("hint").startsWith("Reuse the existing payment term"));
    assertTrue(error.getString("hint").contains(OTHER_ID));
  }

  @Test
  public void createRefusesTheDaysOfAnInactiveTermWithAReactivateHint() throws Exception {
    doReturn(existingTerm(false)).when(handler).findSimpleTermWithDays(CLIENT_ID, 30L, null);

    NeoResponse response = create(days(30));

    assertRefusal(response, 409, PaymentTermHandler.MSG_DAYS_DUPLICATE);
    JSONObject error = error(response);
    assertFalse(error.getJSONObject("messageParams").getBoolean("paymentTermActive"));
    assertTrue(error.getString("message").contains("but is inactive"));
    assertTrue(error.getString("hint").startsWith("Reactivate the existing payment term"));
  }

  @Test
  public void updateChangingDaysLooksUpDuplicatesExcludingItself() throws Exception {
    storeTerm(30L, 0L, false, false);

    assertNull(update(days(60)));
    verify(handler).findSimpleTermWithDays(CLIENT_ID, 60L, TERM_ID);
    verify(handler, never()).currentClientId();
  }

  @Test
  public void updateChangingDaysOntoAnotherTermIsRefused() throws Exception {
    storeTerm(30L, 0L, false, false);
    doReturn(existingTerm(true)).when(handler).findSimpleTermWithDays(CLIENT_ID, 60L, TERM_ID);

    assertRefusal(update(days(60)), 409, PaymentTermHandler.MSG_DAYS_DUPLICATE);
  }

  @Test
  public void updateChangingOnlyTheShapeToSimpleRunsTheCheck() throws Exception {
    storeTerm(30L, 1L, false, false);

    assertNull(update(new JSONObject().put("offsetMonthDue", 0)));
    verify(handler).findSimpleTermWithDays(CLIENT_ID, 30L, TERM_ID);
  }

  @Test
  public void updateNotChangingDaysOrShapeSkipsTheCheck() throws Exception {
    storeTerm(30L, 0L, false, false);

    assertNull(update(days(30).put("name", "Thirty")));
    assertNoDuplicateLookup();
  }

  @Test
  public void createOfAFixedDueDateTermSkipsTheCheck() throws Exception {
    doReturn(existingTerm(true)).when(handler).findSimpleTermWithDays(anyString(), anyLong(),
        Mockito.any());

    assertNull(create(days(30).put("fixedDueDate", true)));
    assertNull(create(days(30).put("fixedDueDate", "Y")));
    assertNoDuplicateLookup();
  }

  @Test
  public void createWithAMonthOffsetSkipsTheCheck() throws Exception {
    doReturn(existingTerm(true)).when(handler).findSimpleTermWithDays(anyString(), anyLong(),
        Mockito.any());
    JSONObject body = days(30).put("offsetMonthDue", 1);

    assertNull(create(body));
    assertEquals(1, body.getInt("offsetMonthDue"));
    assertNoDuplicateLookup();
  }

  @Test
  public void updateOfATermWithLinesSkipsTheCheck() throws Exception {
    PaymentTerm stored = storeTerm(30L, 0L, false, true);

    assertNull(update(days(60)));
    verify(handler).hasLines(stored);
    assertNoDuplicateLookup();
  }

  @Test
  public void updateOfAStoredFixedDueDateTermSkipsTheCheck() throws Exception {
    storeTerm(30L, 0L, true, false);

    assertNull(update(days(60)));
    assertNoDuplicateLookup();
  }

  // --- create defaults -------------------------------------------------------------------------

  @Test
  public void createOfZeroDaysFollowsTheImmediateTerm() throws Exception {
    JSONObject body = days(0);

    assertNull(create(body));
    assertEquals("Inmediato", body.getString("name"));
    assertEquals("I", body.getString("searchKey"));
    assertEquals(0L, body.getLong("offsetMonthDue"));
    assertFalse(body.getBoolean("nextBusinessDay"));
  }

  @Test
  public void createOfOneDayUsesTheSingular() throws Exception {
    JSONObject body = days(1);

    assertNull(create(body));
    assertEquals("1 Día", body.getString("name"));
    assertEquals("1D", body.getString("searchKey"));
    assertTrue(body.getBoolean("nextBusinessDay"));
  }

  @Test
  public void createOfTwelveDaysFillsTheSeededShape() throws Exception {
    JSONObject body = days("12");

    assertNull(create(body));
    assertEquals("12 Días", body.getString("name"));
    assertEquals("12D", body.getString("searchKey"));
    assertEquals(0L, body.getLong("offsetMonthDue"));
    assertTrue(body.getBoolean("nextBusinessDay"));
  }

  @Test
  public void createKeepsCallerSuppliedValues() throws Exception {
    JSONObject body = days(12)
        .put("name", "Doce")
        .put("searchKey", "D12")
        .put("offsetMonthDue", 0)
        .put("nextBusinessDay", false);

    assertNull(create(body));
    assertEquals("Doce", body.getString("name"));
    assertEquals("D12", body.getString("searchKey"));
    assertEquals(0, body.getInt("offsetMonthDue"));
    assertFalse(body.getBoolean("nextBusinessDay"));
  }

  @Test
  public void createReplacesABlankOrNullNameAndSearchKey() throws Exception {
    JSONObject body = days(12).put("name", "   ").put("searchKey", JSONObject.NULL)
        .put("offsetMonthDue", JSONObject.NULL);

    assertNull(create(body));
    assertEquals("12 Días", body.getString("name"));
    assertEquals("12D", body.getString("searchKey"));
    assertEquals(0L, body.getLong("offsetMonthDue"));
  }

  @Test
  public void updateDoesNotInjectCreateDefaults() throws Exception {
    storeTerm(30L, 0L, false, false);
    JSONObject body = days(12);

    assertNull(update(body));
    assertFalse(body.has("name"));
    assertFalse(body.has("searchKey"));
    assertFalse(body.has("nextBusinessDay"));
  }

  // --- pass-through ----------------------------------------------------------------------------

  @Test
  public void nonCrudEndpointTypesPassThrough() throws Exception {
    for (NeoEndpointType type : new NeoEndpointType[] { NeoEndpointType.SELECTOR,
        NeoEndpointType.ACTION, NeoEndpointType.CALLOUT }) {
      JSONObject body = days("abc");

      assertNull(handler.handle(ctx("POST", null, body, type)));
      assertEquals("abc", body.getString(DAYS));
    }
    assertAdminModeNeverEntered();
  }

  @Test
  public void aNullEndpointTypeIsTreatedAsCrud() throws Exception {
    assertRefusal(handler.handle(ctx("POST", null, days("abc"), null)), 400,
        PaymentTermHandler.MSG_DAYS_INVALID);
  }

  @Test
  public void getAndDeletePassThrough() throws Exception {
    assertNull(handler.handle(ctx("GET", TERM_ID, days("abc"), NeoEndpointType.CRUD)));
    assertNull(handler.handle(ctx("DELETE", TERM_ID, days("abc"), NeoEndpointType.CRUD)));
    assertAdminModeNeverEntered();
    verify(handler, never()).loadStored(anyString());
  }

  @Test
  public void anotherSpecPassesThrough() throws Exception {
    NeoContext other = NeoContext.builder().specName("sales-order").entityName("header")
        .httpMethod("POST").requestBody(days("abc")).endpointType(NeoEndpointType.CRUD).build();

    assertNull(handler.handle(other));
  }

  @Test
  public void aMissingBodyPassesThrough() {
    assertNull(create(null));
  }

  @Test
  public void anUnknownRecordIdPassesThroughToTheGeneric404() throws Exception {
    doReturn(null).when(handler).loadStored(TERM_ID);

    assertNull(update(days("abc")));
    assertNoDuplicateLookup();
  }

  @Test
  public void theDataEnvelopeIsValidatedAndEnriched() throws Exception {
    JSONObject data = days("12");
    JSONObject body = new JSONObject().put("data", data);

    assertNull(create(body));
    assertEquals(12L, data.getLong(DAYS));
    assertEquals("12 Días", data.getString("name"));
    assertFalse(body.has("name"));
  }

  @Test
  public void theDataEnvelopeIsRefusedLikeAPlainBody() throws Exception {
    JSONObject body = new JSONObject().put("data", days(7.5d));

    assertRefusal(create(body), 400, PaymentTermHandler.MSG_DAYS_INVALID);
  }

  @Test
  public void aLookupFailureBecomesA500AndLeavesAdminMode() throws Exception {
    doThrow(new IllegalStateException("boom")).when(handler)
        .findSimpleTermWithDays(anyString(), anyLong(), Mockito.any());

    NeoResponse response = create(days(30));

    assertEquals(500, response.getHttpStatus());
    obContextStatic.verify(OBContext::restorePreviousMode);
  }

  // --- name uniqueness (per client + organization) -------------------------------------------

  @Test
  public void createWhoseDerivedNameIsTakenInTheTargetOrgIsRefused() throws Exception {
    doReturn(DEFAULT_ORG).when(handler).resolveTargetOrgId(Mockito.isNull(), Mockito.any(),
        Mockito.any());
    doReturn(namedTerm(NAME_22, true)).when(handler)
        .findTermWithName(CLIENT_ID, DEFAULT_ORG, NAME_22, null);

    NeoResponse response = create(days(22));

    assertRefusal(response, 409, PaymentTermHandler.MSG_NAME_DUPLICATE, "name");
    JSONObject error = error(response);
    assertEquals("payment_term_name_duplicate", error.getString("code"));
    assertEquals("A payment term named '" + NAME_22 + "' already exists in this organization (id "
        + NAMED_ID + ").", error.getString("message"));
    assertEquals(response.getBody().getString("message"), error.getString("message"));
    JSONObject params = error.getJSONObject("messageParams");
    assertEquals(3, params.length());
    assertEquals(NAME_22, params.getString("paymentTermName"));
    assertEquals(NAMED_ID, params.getString("paymentTermId"));
    assertTrue(params.getBoolean("paymentTermActive"));
    assertTrue(error.getString("hint").startsWith("Choose another name"));
  }

  @Test
  public void anInactiveNameClashIsFlaggedInTheMessageAndParams() throws Exception {
    doReturn(DEFAULT_ORG).when(handler).resolveTargetOrgId(Mockito.isNull(), Mockito.any(),
        Mockito.any());
    doReturn(namedTerm(NAME_22, false)).when(handler)
        .findTermWithName(CLIENT_ID, DEFAULT_ORG, NAME_22, null);

    NeoResponse response = create(days(22));

    assertRefusal(response, 409, PaymentTermHandler.MSG_NAME_DUPLICATE, "name");
    JSONObject error = error(response);
    assertFalse(error.getJSONObject("messageParams").getBoolean("paymentTermActive"));
    assertTrue(error.getString("message").contains(" (inactive)"));
  }

  @Test
  public void theSameNameInAnotherOrgPasses() throws Exception {
    doReturn("ORG-B").when(handler).resolveTargetOrgId(Mockito.isNull(), Mockito.any(),
        Mockito.any());
    // Taken in ORG-A only; the lookup for ORG-B finds nothing.
    doReturn(namedTerm(NAME_22, true)).when(handler)
        .findTermWithName(CLIENT_ID, "ORG-A", NAME_22, null);

    assertNull(create(days(22)));
    verify(handler).findTermWithName(CLIENT_ID, "ORG-B", NAME_22, null);
  }

  @Test
  public void updateRenamingIntoATakenNameIsRefusedExcludingItself() throws Exception {
    PaymentTerm stored = storeTerm(30L, 0L, false, false);
    when(stored.getName()).thenReturn("30 Días");
    doReturn(namedTerm(NAME_22, true)).when(handler)
        .findTermWithName(CLIENT_ID, STORED_ORG, NAME_22, TERM_ID);

    NeoResponse response = update(new JSONObject().put("name", NAME_22));

    assertRefusal(response, 409, PaymentTermHandler.MSG_NAME_DUPLICATE, "name");
    verify(handler).findTermWithName(CLIENT_ID, STORED_ORG, NAME_22, TERM_ID);
    verify(handler, never()).currentClientId();
  }

  @Test
  public void updateKeepingItsStoredNameNeverLooksUpTheName() throws Exception {
    PaymentTerm stored = storeTerm(30L, 0L, false, false);
    when(stored.getName()).thenReturn("30 Días");

    assertNull(update(new JSONObject().put("name", "30 Días")));
    assertNull(update(new JSONObject().put("description", "no name sent")));
    assertNull(update(days(60)));
    assertNoNameLookup();
  }

  @Test
  public void aBlankNameSkipsTheCheck() throws Exception {
    storeTerm(30L, 0L, false, false);

    assertNull(update(new JSONObject().put("name", "   ")));
    assertNull(handler.checkNameUnique(new JSONObject().put("name", " "), null, null));
    verify(handler, never()).resolveTargetOrgId(Mockito.any(), Mockito.any(), Mockito.any());
    assertNoNameLookup();
  }

  @Test
  public void anUnresolvedOrgSkipsTheCheck() throws Exception {
    doReturn(null).when(handler).resolveTargetOrgId(Mockito.isNull(), Mockito.any(),
        Mockito.any());

    assertNull(create(days(22)));
    assertNoNameLookup();
  }

  @Test
  public void theTwoArgValidateAndEnrichHasNoContextSoACreateSkipsTheNameCheck()
      throws Exception {
    JSONObject body = days(22);

    assertNull(handler.validateAndEnrich(body, null));
    assertEquals(NAME_22, body.getString("name"));
    assertNoNameLookup();
  }

  @Test
  public void updateIgnoresACallerSentOrganizationAndUsesTheStoredOne() throws Exception {
    storeTerm(30L, 0L, false, false);

    assertNull(update(new JSONObject().put("name", "Renamed").put("organization", CALLER_ORG)));
    verify(handler).findTermWithName(CLIENT_ID, STORED_ORG, "Renamed", TERM_ID);
    verify(handler, never()).findTermWithName(anyString(), eq(CALLER_ORG), anyString(),
        Mockito.any());
  }

  @Test
  public void createIgnoresACallerSentOrganizationAndUsesTheResolvedDefault() throws Exception {
    JSONObject body = days(22).put("organization", CALLER_ORG);
    NeoContext context = NeoContext.builder().specName(SPEC).entityName("header")
        .httpMethod("POST").requestBody(body).endpointType(NeoEndpointType.CRUD)
        .adTab(mock(Tab.class)).build();

    try (MockedStatic<NeoServerOwnedFields> owned = mockStatic(NeoServerOwnedFields.class);
        MockedStatic<NeoMandatoryDefaultsService> defaults =
            mockStatic(NeoMandatoryDefaultsService.class)) {
      stubStripAndDefaults(owned, defaults, new JSONObject().put("id", DEFAULT_ORG), null);

      assertNull(handler.handle(context));
    }
    verify(handler).findTermWithName(CLIENT_ID, DEFAULT_ORG, NAME_22, null);
    assertEquals("the caller's organization is left for the generic CRUD to strip", CALLER_ORG,
        body.getString("organization"));
  }

  @Test
  public void aDaysDuplicateWinsOverANameDuplicate() throws Exception {
    doReturn(existingTerm(true)).when(handler).findSimpleTermWithDays(CLIENT_ID, 22L, null);
    doReturn(DEFAULT_ORG).when(handler).resolveTargetOrgId(Mockito.isNull(), Mockito.any(),
        Mockito.any());
    doReturn(namedTerm(NAME_22, true)).when(handler)
        .findTermWithName(CLIENT_ID, DEFAULT_ORG, NAME_22, null);

    assertRefusal(create(days(22)), 409, PaymentTermHandler.MSG_DAYS_DUPLICATE);
    assertNoNameLookup();
  }

  // --- resolveTargetOrgId ----------------------------------------------------------------------

  /**
   * Makes {@code stripServerOwned} drop {@code client}/{@code organization} like the real one,
   * and {@code injectMandatoryDefaults} set {@code organization} to {@code defaultOrg}, recording
   * a snapshot of the body it received into {@code seen} (when not null).
   */
  private static void stubStripAndDefaults(MockedStatic<NeoServerOwnedFields> owned,
      MockedStatic<NeoMandatoryDefaultsService> defaults, Object defaultOrg, JSONObject[] seen) {
    owned.when(() -> NeoServerOwnedFields.stripServerOwned(Mockito.any(), Mockito.any()))
        .thenAnswer(inv -> {
          JSONObject target = inv.getArgument(0);
          target.remove("organization");
          target.remove("client");
          return new JSONObject();
        });
    defaults.when(() -> NeoMandatoryDefaultsService.injectMandatoryDefaults(Mockito.any(),
        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean())).thenAnswer(inv -> {
          JSONObject scratch = inv.getArgument(0);
          if (seen != null) {
            seen[0] = scratch;
            seen[1] = new JSONObject(scratch.toString());
          }
          scratch.put("organization", defaultOrg);
          return null;
        });
  }

  @Test
  public void resolveTargetOrgIdOnCreateRunsTheDefaultsOnAStrippedCopy() throws Exception {
    JSONObject body = new JSONObject().put("name", NAME_22).put("organization", CALLER_ORG)
        .put("client", "CLIENT-CALLER");
    String before = body.toString();
    Tab tab = mock(Tab.class);
    NeoContext context = NeoContext.builder().specName(SPEC).httpMethod("POST").adTab(tab)
        .build();
    JSONObject[] seen = new JSONObject[2];

    String orgId;
    try (MockedStatic<NeoServerOwnedFields> owned = mockStatic(NeoServerOwnedFields.class);
        MockedStatic<NeoMandatoryDefaultsService> defaults =
            mockStatic(NeoMandatoryDefaultsService.class)) {
      stubStripAndDefaults(owned, defaults, DEFAULT_ORG, seen);

      orgId = new PaymentTermHandler().resolveTargetOrgId(null, body, context);

      defaults.verify(() -> NeoMandatoryDefaultsService.injectMandatoryDefaults(
          Mockito.same(seen[0]), Mockito.same(tab), Mockito.same(context), Mockito.isNull(),
          eq(false)));
      owned.verify(() -> NeoServerOwnedFields.stripServerOwned(Mockito.same(seen[0]),
          Mockito.isNull()));
    }

    assertEquals(DEFAULT_ORG, orgId);
    assertTrue("the defaults pass must run on a copy", seen[0] != body);
    assertFalse(seen[1].has("organization"));
    assertFalse(seen[1].has("client"));
    assertEquals(NAME_22, seen[1].getString("name"));
    assertEquals("the real body must not be mutated", before, body.toString());
  }

  @Test
  public void resolveTargetOrgIdWithoutATabOrContextIsNull() throws Exception {
    PaymentTermHandler real = new PaymentTermHandler();
    JSONObject body = new JSONObject().put("name", NAME_22);

    try (MockedStatic<NeoMandatoryDefaultsService> defaults =
        mockStatic(NeoMandatoryDefaultsService.class)) {
      assertNull(real.resolveTargetOrgId(null, body, null));
      assertNull(real.resolveTargetOrgId(null, body,
          NeoContext.builder().specName(SPEC).httpMethod("POST").build()));
      defaults.verifyNoInteractions();
    }
  }

  @Test
  public void resolveTargetOrgIdOnUpdateIsTheStoredOrgOrNull() throws Exception {
    PaymentTermHandler real = new PaymentTermHandler();
    PaymentTerm withOrg = mock(PaymentTerm.class);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(STORED_ORG);
    when(withOrg.getOrganization()).thenReturn(org);
    JSONObject body = new JSONObject().put("organization", CALLER_ORG);

    assertEquals(STORED_ORG, real.resolveTargetOrgId(withOrg, body, null));
    assertNull(real.resolveTargetOrgId(mock(PaymentTerm.class), body, null));
  }

  // --- DAL seams -------------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  @Test
  public void findSimpleTermWithDaysSearchesTheWholeClientAndExcludesTheUpdatedTerm() {
    OBDal dal = mock(OBDal.class);
    OBQuery<PaymentTerm> query = mock(OBQuery.class);
    PaymentTerm match = mock(PaymentTerm.class);
    ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
    when(dal.createQuery(eq(PaymentTerm.class), hql.capture())).thenReturn(query);
    when(query.list()).thenReturn(Collections.singletonList(match));

    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);

      PaymentTerm found = new PaymentTermHandler().findSimpleTermWithDays(CLIENT_ID, 30L, TERM_ID);

      assertEquals(match, found);
    }
    String where = hql.getValue();
    assertTrue(where.contains("pt.client.id = :clientId"));
    assertTrue(where.contains("pt.id <> :excludeId"));
    assertTrue(where.contains("pt.fixedDueDate = false"));
    assertTrue(where.contains("coalesce(pt.offsetMonthDue, 0) = 0"));
    assertTrue(where.contains("FinancialMgmtPaymentTermLine"));
    assertTrue(where.contains("order by pt.active desc"));
    assertFalse("must not be scoped by organization", where.contains("organization"));
    verify(query).setFilterOnReadableClients(false);
    verify(query).setFilterOnReadableOrganization(false);
    verify(query).setFilterOnActive(false);
    verify(query).setNamedParameter("clientId", CLIENT_ID);
    verify(query).setNamedParameter("days", 30L);
    verify(query).setNamedParameter("excludeId", TERM_ID);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void findSimpleTermWithDaysOnCreateHasNoExclusionAndReturnsNullWhenNoneFound() {
    OBDal dal = mock(OBDal.class);
    OBQuery<PaymentTerm> query = mock(OBQuery.class);
    ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
    when(dal.createQuery(eq(PaymentTerm.class), hql.capture())).thenReturn(query);
    when(query.list()).thenReturn(Collections.emptyList());

    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);

      assertNull(new PaymentTermHandler().findSimpleTermWithDays(CLIENT_ID, 30L, null));
    }
    assertFalse(hql.getValue().contains("excludeId"));
    verify(query, never()).setNamedParameter(eq("excludeId"), Mockito.any());
  }

  @SuppressWarnings("unchecked")
  @Test
  public void findTermWithNameFiltersClientOrgAndNameAndExcludesOnlyOnUpdate() {
    OBDal dal = mock(OBDal.class);
    OBQuery<PaymentTerm> query = mock(OBQuery.class);
    PaymentTerm match = mock(PaymentTerm.class);
    ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
    when(dal.createQuery(eq(PaymentTerm.class), hql.capture())).thenReturn(query);
    when(query.list()).thenReturn(Collections.singletonList(match), Collections.emptyList());

    PaymentTerm onUpdate;
    PaymentTerm onCreate;
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      PaymentTermHandler real = new PaymentTermHandler();

      onUpdate = real.findTermWithName(CLIENT_ID, STORED_ORG, NAME_22, TERM_ID);
      onCreate = real.findTermWithName(CLIENT_ID, STORED_ORG, NAME_22, null);
    }

    assertEquals(match, onUpdate);
    assertNull(onCreate);
    String updateWhere = hql.getAllValues().get(0);
    String createWhere = hql.getAllValues().get(1);
    for (String where : new String[] { updateWhere, createWhere }) {
      assertTrue(where.contains("pt.client.id = :clientId"));
      assertTrue(where.contains("pt.organization.id = :orgId"));
      assertTrue(where.contains("pt.name = :name"));
    }
    assertTrue(updateWhere.contains("pt.id <> :excludeId"));
    assertFalse(createWhere.contains("excludeId"));
    verify(query, Mockito.times(2)).setFilterOnReadableClients(false);
    verify(query, Mockito.times(2)).setFilterOnReadableOrganization(false);
    verify(query, Mockito.times(2)).setFilterOnActive(false);
    verify(query, Mockito.times(2)).setNamedParameter("clientId", CLIENT_ID);
    verify(query, Mockito.times(2)).setNamedParameter("orgId", STORED_ORG);
    verify(query, Mockito.times(2)).setNamedParameter("name", NAME_22);
    verify(query, Mockito.times(1)).setNamedParameter("excludeId", TERM_ID);
    verify(query, Mockito.times(2)).setMaxResult(1);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void hasLinesReportsWhetherTheTermHasAnyLine() {
    OBDal dal = mock(OBDal.class);
    OBQuery<PaymentTerm> query = mock(OBQuery.class);
    PaymentTerm term = mock(PaymentTerm.class);
    when(term.getId()).thenReturn(TERM_ID);
    when(dal.createQuery(eq(PaymentTerm.class), anyString())).thenReturn(query);
    when(query.list()).thenReturn(Collections.singletonList(term), Collections.emptyList());

    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      PaymentTermHandler real = new PaymentTermHandler();

      assertTrue(real.hasLines(term));
      assertFalse(real.hasLines(term));
    }
    verify(query, Mockito.times(2)).setNamedParameter("id", TERM_ID);
    verify(query, Mockito.times(2)).setFilterOnReadableClients(false);
    verify(query, Mockito.times(2)).setFilterOnActive(false);
  }

  @Test
  public void loadStoredAndCurrentClientIdReadTheDalAndTheContext() {
    OBDal dal = mock(OBDal.class);
    PaymentTerm term = mock(PaymentTerm.class);
    when(dal.get(PaymentTerm.class, TERM_ID)).thenReturn(term);
    OBContext obContext = mock(OBContext.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    when(obContext.getCurrentClient()).thenReturn(client);

    obContextStatic.when(OBContext::getOBContext).thenReturn(obContext);

    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      PaymentTermHandler real = new PaymentTermHandler();

      assertEquals(term, real.loadStored(TERM_ID));
      assertEquals(CLIENT_ID, real.currentClientId());
    }
  }
}
