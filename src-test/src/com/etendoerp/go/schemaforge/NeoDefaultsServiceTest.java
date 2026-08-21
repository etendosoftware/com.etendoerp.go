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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.domain.Reference;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.ad.utility.Sequence;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.client.kernel.KernelUtils;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.go.schemaforge.NeoMandatoryFieldValidator;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.sequences.SequenceUtils;

/**
 * Unit tests for {@link NeoDefaultsService}.
 *
 * <p>Covers resolveDefaults, injectMandatoryDefaults, findMissingMandatoryFields,
 * buildVariablesSecureApp, resolveFirstOrgForClient, CalloutCascadeResult, and
 * parseSQLExpression. Also retains the original NeoCommercialLinePolicy tests.</p>
 */
public class NeoDefaultsServiceTest {

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private static void assertAmountEquals(double expected, double actual) {
    assertEquals("expected " + expected + " but got " + actual,
        expected, actual, 0.005);
  }

  /**
   * Invoke a private static method on NeoDefaultsService via reflection.
   */
  private static Object invokePrivate(String methodName, Class<?>[] paramTypes, Object... args)
      throws Exception {
    Method method = NeoDefaultsService.class.getDeclaredMethod(methodName, paramTypes);
    method.setAccessible(true);
    return method.invoke(null, args);
  }

  /**
   * Creates a mock Column with basic properties set.
   */
  private Column mockColumn(String dbColumnName, boolean mandatory, boolean isKey,
      boolean isActive) {
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn(dbColumnName);
    when(col.isMandatory()).thenReturn(mandatory);
    when(col.isKeyColumn()).thenReturn(isKey);
    when(col.isActive()).thenReturn(isActive);
    return col;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // injectLineNetAmountIfMissing tests (original)
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testNullBodyIsIgnored() {
    NeoCommercialLinePolicy.injectLineNetAmountIfMissing(null);
  }

  /**
   * ETP-4727 (backend counterpart of the useLineGrossAmount.js frontend fix): invoicedQuantity
   * explicitly edited to 0 on an existing line is deterministic and must force lineNetAmount to
   * 0 — leaving it untouched let NEO's partial-update PATCH keep the stale pre-edit amount in
   * the DB even though the frontend had already computed and sent 0.
   */
  @Test
  public void testZeroQtyForcesLineNetAmountZero() throws Exception {
    JSONObject body = new JSONObject();
    body.put("invoicedQuantity", "0");
    body.put("unitPrice", 29.70);

    NeoCommercialLinePolicy.injectLineNetAmountIfMissing(body);

    assertTrue("lineNetAmount should be forced to 0 when invoicedQuantity is explicitly 0",
        body.has("lineNetAmount"));
    assertEquals(0.0, body.getDouble("lineNetAmount"), 0.001);
  }

  @Test
  public void testMissingQtyNoInjection() throws Exception {
    JSONObject body = new JSONObject();
    body.put("unitPrice", 29.70);

    NeoCommercialLinePolicy.injectLineNetAmountIfMissing(body);

    assertFalse("lineNetAmount should not be injected when invoicedQuantity is absent",
        body.has("lineNetAmount"));
  }

  /** ETP-4727 (backend counterpart): unitPrice explicitly edited to 0 must also force the zero. */
  @Test
  public void testZeroUnitPriceForcesLineNetAmountZero() throws Exception {
    JSONObject body = new JSONObject();
    body.put("invoicedQuantity", "3");
    body.put("unitPrice", 0);

    NeoCommercialLinePolicy.injectLineNetAmountIfMissing(body);

    assertTrue("lineNetAmount should be forced to 0 when unitPrice is explicitly 0",
        body.has("lineNetAmount"));
    assertEquals(0.0, body.getDouble("lineNetAmount"), 0.001);
  }

  @Test
  public void testNormalPathComputation() throws Exception {
    JSONObject body = new JSONObject();
    body.put("invoicedQuantity", "3");
    body.put("unitPrice", 29.70);

    NeoCommercialLinePolicy.injectLineNetAmountIfMissing(body);

    assertTrue("lineNetAmount should be injected", body.has("lineNetAmount"));
    assertAmountEquals(89.10, body.getDouble("lineNetAmount"));
  }

  @Test
  public void testInvoicedQtyAsStringIsParsed() throws Exception {
    JSONObject body = new JSONObject();
    body.put("invoicedQuantity", "5");
    body.put("unitPrice", 10.00);

    NeoCommercialLinePolicy.injectLineNetAmountIfMissing(body);

    assertTrue("lineNetAmount should be injected", body.has("lineNetAmount"));
    assertAmountEquals(50.00, body.getDouble("lineNetAmount"));
  }

  @Test
  public void testAlwaysRecomputes() throws Exception {
    JSONObject body = new JSONObject();
    body.put("invoicedQuantity", "3");
    body.put("unitPrice", 29.70);
    body.put("lineNetAmount", 999.99);

    NeoCommercialLinePolicy.injectLineNetAmountIfMissing(body);

    assertAmountEquals(89.10, body.getDouble("lineNetAmount"));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // resolveTransactionalSequencePreview (original tests)
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testTransactionalPreviewSequenceFoundReturnsFormattedValue() {
    Column column = mock(Column.class);
    OBContext obContextMock = mock(OBContext.class);
    Organization orgMock = mock(Organization.class);
    OBDal obDalMock = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBCriteria<Sequence> criteriaMock = mock(OBCriteria.class);
    Sequence sequenceMock = mock(Sequence.class);

    when(obContextMock.getCurrentOrganization()).thenReturn(orgMock);
    when(orgMock.getId()).thenReturn("TEST_ORG");
    when(obDalMock.get(eq(Organization.class), eq("TEST_ORG"))).thenReturn(orgMock);
    when(obDalMock.createCriteria(Sequence.class)).thenReturn(criteriaMock);
    when(criteriaMock.uniqueResult()).thenReturn(sequenceMock);
    when(sequenceMock.getNextAssignedNumber()).thenReturn(1000067L);

    try (MockedStatic<OBContext> mockedCtx = mockStatic(OBContext.class);
         MockedStatic<OBDal> mockedDal = mockStatic(OBDal.class)) {
      mockedCtx.when(OBContext::getOBContext).thenReturn(obContextMock);
      mockedDal.when(OBDal::getInstance).thenReturn(obDalMock);

      String result = NeoDefaultsService.resolveTransactionalSequencePreview(column);

      assertEquals("<1000067>", result);
    }
  }

  @Test
  public void testTransactionalPreviewSequenceNotFoundReturnsNull() {
    Column column = mock(Column.class);
    OBContext obContextMock = mock(OBContext.class);
    Organization orgMock = mock(Organization.class);
    OBDal obDalMock = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBCriteria<Sequence> criteriaMock = mock(OBCriteria.class);

    when(obContextMock.getCurrentOrganization()).thenReturn(orgMock);
    when(orgMock.getId()).thenReturn("TEST_ORG");
    when(obDalMock.get(eq(Organization.class), eq("TEST_ORG"))).thenReturn(orgMock);
    when(obDalMock.createCriteria(Sequence.class)).thenReturn(criteriaMock);
    when(criteriaMock.uniqueResult()).thenReturn(null);

    try (MockedStatic<OBContext> mockedCtx = mockStatic(OBContext.class);
         MockedStatic<OBDal> mockedDal = mockStatic(OBDal.class)) {
      mockedCtx.when(OBContext::getOBContext).thenReturn(obContextMock);
      mockedDal.when(OBDal::getInstance).thenReturn(obDalMock);

      String result = NeoDefaultsService.resolveTransactionalSequencePreview(column);

      assertNull("Should return null when no sequence is found", result);
    }
  }

  @Test
  public void testTransactionalPreviewDalThrowsReturnsNull() {
    Column column = mock(Column.class);
    OBContext obContextMock = mock(OBContext.class);
    Organization orgMock = mock(Organization.class);
    OBDal obDalMock = mock(OBDal.class);

    when(obContextMock.getCurrentOrganization()).thenReturn(orgMock);
    when(orgMock.getId()).thenReturn("TEST_ORG");
    when(obDalMock.get(eq(Organization.class), eq("TEST_ORG"))).thenReturn(orgMock);
    when(obDalMock.createCriteria(any(Class.class)))
        .thenThrow(new RuntimeException("DAL unavailable"));

    try (MockedStatic<OBContext> mockedCtx = mockStatic(OBContext.class);
         MockedStatic<OBDal> mockedDal = mockStatic(OBDal.class)) {
      mockedCtx.when(OBContext::getOBContext).thenReturn(obContextMock);
      mockedDal.when(OBDal::getInstance).thenReturn(obDalMock);

      String result = NeoDefaultsService.resolveTransactionalSequencePreview(column);

      assertNull("Exception should be swallowed and null returned", result);
    }
  }

  // ── resolveDefaults — readonly combo skipped (original) ──────────────────

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveDefaultsSkipsReadonlyComboAutopick() throws Exception {
    OBDal dal = mock(OBDal.class);
    OBCriteria<SFField> fieldCriteria = mock(OBCriteria.class);
    SFField sfField = mock(SFField.class);
    Column adColumn = mock(Column.class);
    SFEntity sfEntity = mock(SFEntity.class);
    OBContext obContext = mock(OBContext.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    Entity dalEntity = mock(Entity.class);

    when(sfEntity.getId()).thenReturn("sf-entity-1");
    when(sfField.getADColumn()).thenReturn(adColumn);
    when(sfField.isReadOnly()).thenReturn(true);
    when(sfField.getDefaultValue()).thenReturn(null);
    when(adColumn.getDBColumnName()).thenReturn("C_Reject_Reason_ID");
    when(adColumn.getDefaultValue()).thenReturn(null);
    when(adColumn.isUseAutomaticSequence()).thenReturn(false);
    when(fieldCriteria.add(any())).thenReturn(fieldCriteria);
    when(fieldCriteria.list()).thenReturn(Collections.singletonList(sfField));
    when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class);
         MockedStatic<SequenceUtils> sequenceMock = mockStatic(SequenceUtils.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class);
         MockedStatic<DocTypeResolver> docTypeMock = mockStatic(DocTypeResolver.class);
         MockedStatic<NeoSelectorService> selectorMock =
             mockStatic(NeoSelectorService.class)) {
      obContextMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, null)).thenReturn(vars);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper.resolveDalEntity(sfEntity))
          .thenReturn(dalEntity);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper
          .resolvePropertyName(dalEntity, "C_Reject_Reason_ID"))
          .thenReturn("rejectReason");
      sequenceMock.when(() -> SequenceUtils.isSequence(adColumn)).thenReturn(false);
      utilityMock.when(() -> Utility.getPreference(vars, "C_Reject_Reason_ID", ""))
          .thenReturn(null);
      docTypeMock.when(() -> DocTypeResolver.resolveDefaultDocTypeId(adColumn, ctx))
          .thenReturn(null);

      NeoResponse response = NeoDefaultsService.resolveDefaults(ctx, null);

      assertEquals(200, response.getHttpStatus());
      assertFalse(response.getBody().getJSONObject("defaults").has("rejectReason"));
      selectorMock.verify(() -> NeoSelectorService.getBaseReferenceId(adColumn), never());
      selectorMock.verify(() -> NeoSelectorService.hasObuiselSelector(adColumn), never());
      selectorMock.verify(() -> NeoSelectorService.querySelectorByColumn(
          adColumn,
          "C_Reject_Reason_ID",
          null,
          1,
          0,
          Collections.emptyMap()), never());
      // The "#Date" session seeding this used to also assert here was removed by
      // ETP-4793 / IMP-16 and is now owned by
      // testBuildVariablesSecureAppDoesNotSeedDateSessionValue.
      verify(dal, never()).get(eq(Organization.class), any(String.class));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // resolveDefaults — IsActive always defaults to true
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveDefaultsIsActiveAlwaysTrue() throws Exception {
    OBDal dal = mock(OBDal.class);
    OBCriteria<SFField> fieldCriteria = mock(OBCriteria.class);
    SFField sfField = mock(SFField.class);
    Column adColumn = mock(Column.class);
    SFEntity sfEntity = mock(SFEntity.class);
    OBContext obContext = mock(OBContext.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    Entity dalEntity = mock(Entity.class);
    Property prop = mock(Property.class);

    when(sfEntity.getId()).thenReturn("sf-entity-1");
    when(sfField.getADColumn()).thenReturn(adColumn);
    when(sfField.isReadOnly()).thenReturn(false);
    when(sfField.getDefaultValue()).thenReturn(null);
    when(adColumn.getDBColumnName()).thenReturn("IsActive");
    when(adColumn.getDefaultValue()).thenReturn(null);
    when(adColumn.isUseAutomaticSequence()).thenReturn(false);
    when(fieldCriteria.add(any())).thenReturn(fieldCriteria);
    when(fieldCriteria.list()).thenReturn(Collections.singletonList(sfField));
    when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);
    when(prop.isPrimitive()).thenReturn(true);
    when(dalEntity.getProperty("active")).thenReturn(prop);

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class);
         MockedStatic<SequenceUtils> sequenceMock = mockStatic(SequenceUtils.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class);
         MockedStatic<DocTypeResolver> docTypeMock = mockStatic(DocTypeResolver.class)) {
      obContextMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, null)).thenReturn(vars);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper.resolveDalEntity(sfEntity))
          .thenReturn(dalEntity);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper.resolvePropertyName(dalEntity, "IsActive"))
          .thenReturn("active");
      sequenceMock.when(() -> SequenceUtils.isSequence(adColumn)).thenReturn(false);

      NeoResponse response = NeoDefaultsService.resolveDefaults(ctx, null);

      assertEquals(200, response.getHttpStatus());
      JSONObject defaults = response.getBody().getJSONObject("defaults");
      assertTrue("IsActive should default to true", defaults.getBoolean("active"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // resolveDefaults — link-to-parent column uses parentId
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveDefaultsLinkToParentUsesParentId() throws Exception {
    OBDal dal = mock(OBDal.class);
    OBCriteria<SFField> fieldCriteria = mock(OBCriteria.class);
    SFField sfField = mock(SFField.class);
    Column adColumn = mock(Column.class);
    SFEntity sfEntity = mock(SFEntity.class);
    OBContext obContext = mock(OBContext.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    Entity dalEntity = mock(Entity.class);
    Property prop = mock(Property.class);

    when(sfEntity.getId()).thenReturn("sf-entity-1");
    when(sfField.getADColumn()).thenReturn(adColumn);
    when(sfField.isReadOnly()).thenReturn(false);
    when(sfField.getDefaultValue()).thenReturn(null);
    when(adColumn.getDBColumnName()).thenReturn("C_Order_ID");
    when(adColumn.getDefaultValue()).thenReturn(null);
    when(adColumn.isLinkToParentColumn()).thenReturn(true);
    when(adColumn.isUseAutomaticSequence()).thenReturn(false);
    when(fieldCriteria.add(any())).thenReturn(fieldCriteria);
    when(fieldCriteria.list()).thenReturn(Collections.singletonList(sfField));
    when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);
    when(prop.isPrimitive()).thenReturn(false);
    Entity targetEntity = mock(Entity.class);
    when(targetEntity.getName()).thenReturn("Order");
    when(prop.getTargetEntity()).thenReturn(targetEntity);
    when(dalEntity.getProperty("salesOrder")).thenReturn(prop);
    BaseOBObject targetObj = mock(BaseOBObject.class);
    when(targetObj.getIdentifier()).thenReturn("SO-12345");
    when(dal.get(eq("Order"), eq("PARENT-123"))).thenReturn(targetObj);

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class);
         MockedStatic<SequenceUtils> sequenceMock = mockStatic(SequenceUtils.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class);
         MockedStatic<DocTypeResolver> docTypeMock = mockStatic(DocTypeResolver.class)) {
      obContextMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, null)).thenReturn(vars);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper.resolveDalEntity(sfEntity))
          .thenReturn(dalEntity);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper
          .resolvePropertyName(dalEntity, "C_Order_ID"))
          .thenReturn("salesOrder");
      sequenceMock.when(() -> SequenceUtils.isSequence(adColumn)).thenReturn(false);

      NeoResponse response = NeoDefaultsService.resolveDefaults(ctx, "PARENT-123");

      assertEquals(200, response.getHttpStatus());
      JSONObject defaults = response.getBody().getJSONObject("defaults");
      assertEquals("PARENT-123", defaults.getString("salesOrder"));
      // Also check that $_identifier was injected
      assertTrue("Should inject $_identifier for FK fields",
          defaults.has("salesOrder$_identifier"));
      assertEquals("SO-12345", defaults.getString("salesOrder$_identifier"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // resolveDefaults — literal default value
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveDefaultsLiteralDefault() throws Exception {
    OBDal dal = mock(OBDal.class);
    OBCriteria<SFField> fieldCriteria = mock(OBCriteria.class);
    SFField sfField = mock(SFField.class);
    Column adColumn = mock(Column.class);
    SFEntity sfEntity = mock(SFEntity.class);
    OBContext obContext = mock(OBContext.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    Entity dalEntity = mock(Entity.class);
    Property prop = mock(Property.class);

    when(sfEntity.getId()).thenReturn("sf-entity-1");
    when(sfField.getADColumn()).thenReturn(adColumn);
    when(sfField.isReadOnly()).thenReturn(false);
    when(sfField.getDefaultValue()).thenReturn(null);
    when(adColumn.getDBColumnName()).thenReturn("PaymentRule");
    when(adColumn.getDefaultValue()).thenReturn("P");
    when(adColumn.isLinkToParentColumn()).thenReturn(false);
    when(adColumn.isUseAutomaticSequence()).thenReturn(false);
    when(fieldCriteria.add(any())).thenReturn(fieldCriteria);
    when(fieldCriteria.list()).thenReturn(Collections.singletonList(sfField));
    when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);
    when(prop.isPrimitive()).thenReturn(true);
    when(dalEntity.getProperty("paymentRule")).thenReturn(prop);

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class);
         MockedStatic<SequenceUtils> sequenceMock = mockStatic(SequenceUtils.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class);
         MockedStatic<DocTypeResolver> docTypeMock = mockStatic(DocTypeResolver.class)) {
      obContextMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, null)).thenReturn(vars);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper.resolveDalEntity(sfEntity))
          .thenReturn(dalEntity);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper
          .resolvePropertyName(dalEntity, "PaymentRule"))
          .thenReturn("paymentRule");
      sequenceMock.when(() -> SequenceUtils.isSequence(adColumn)).thenReturn(false);
      // Utility.getDefault returns the literal "P"
      utilityMock.when(() -> Utility.getDefault(any(), eq(vars), eq("PaymentRule"),
          eq("P"), anyString(), eq("")))
          .thenReturn("P");

      NeoResponse response = NeoDefaultsService.resolveDefaults(ctx, null);

      assertEquals(200, response.getHttpStatus());
      JSONObject defaults = response.getBody().getJSONObject("defaults");
      assertEquals("P", defaults.getString("paymentRule"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // resolveDefaults — sfFieldDefault overrides column default
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveDefaultsSfFieldDefaultOverridesColumnDefault() throws Exception {
    OBDal dal = mock(OBDal.class);
    OBCriteria<SFField> fieldCriteria = mock(OBCriteria.class);
    SFField sfField = mock(SFField.class);
    Column adColumn = mock(Column.class);
    SFEntity sfEntity = mock(SFEntity.class);
    OBContext obContext = mock(OBContext.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    Entity dalEntity = mock(Entity.class);
    Property prop = mock(Property.class);

    when(sfEntity.getId()).thenReturn("sf-entity-1");
    when(sfField.getADColumn()).thenReturn(adColumn);
    when(sfField.isReadOnly()).thenReturn(false);
    // ETGO_SF_FIELD default overrides the AD_Column default
    when(sfField.getDefaultValue()).thenReturn("CUSTOM_VALUE");
    when(adColumn.getDBColumnName()).thenReturn("DocStatus");
    when(adColumn.getDefaultValue()).thenReturn("DR");
    when(adColumn.isLinkToParentColumn()).thenReturn(false);
    when(adColumn.isUseAutomaticSequence()).thenReturn(false);
    when(fieldCriteria.add(any())).thenReturn(fieldCriteria);
    when(fieldCriteria.list()).thenReturn(Collections.singletonList(sfField));
    when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);
    when(prop.isPrimitive()).thenReturn(true);
    when(dalEntity.getProperty("documentStatus")).thenReturn(prop);

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class);
         MockedStatic<SequenceUtils> sequenceMock = mockStatic(SequenceUtils.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class);
         MockedStatic<DocTypeResolver> docTypeMock = mockStatic(DocTypeResolver.class)) {
      obContextMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, null)).thenReturn(vars);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper.resolveDalEntity(sfEntity))
          .thenReturn(dalEntity);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper
          .resolvePropertyName(dalEntity, "DocStatus"))
          .thenReturn("documentStatus");
      sequenceMock.when(() -> SequenceUtils.isSequence(adColumn)).thenReturn(false);
      // Should be called with the sfField default, NOT the column default
      utilityMock.when(() -> Utility.getDefault(any(), eq(vars), eq("DocStatus"),
          eq("CUSTOM_VALUE"), anyString(), eq("")))
          .thenReturn("CUSTOM_VALUE");

      NeoResponse response = NeoDefaultsService.resolveDefaults(ctx, null);

      assertEquals(200, response.getHttpStatus());
      JSONObject defaults = response.getBody().getJSONObject("defaults");
      assertEquals("CUSTOM_VALUE", defaults.getString("documentStatus"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // resolveDefaults — null adColumn skipped
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveDefaultsSkipsNullAdColumn() throws Exception {
    OBDal dal = mock(OBDal.class);
    OBCriteria<SFField> fieldCriteria = mock(OBCriteria.class);
    SFField sfField = mock(SFField.class);
    SFEntity sfEntity = mock(SFEntity.class);
    OBContext obContext = mock(OBContext.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    Entity dalEntity = mock(Entity.class);

    when(sfEntity.getId()).thenReturn("sf-entity-1");
    when(sfField.getADColumn()).thenReturn(null);
    when(fieldCriteria.add(any())).thenReturn(fieldCriteria);
    when(fieldCriteria.list()).thenReturn(Collections.singletonList(sfField));
    when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class);
         MockedStatic<SequenceUtils> sequenceMock = mockStatic(SequenceUtils.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class);
         MockedStatic<DocTypeResolver> docTypeMock = mockStatic(DocTypeResolver.class)) {
      obContextMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, null)).thenReturn(vars);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper.resolveDalEntity(sfEntity))
          .thenReturn(dalEntity);

      NeoResponse response = NeoDefaultsService.resolveDefaults(ctx, null);

      assertEquals(200, response.getHttpStatus());
      JSONObject defaults = response.getBody().getJSONObject("defaults");
      assertEquals("Defaults should be empty when adColumn is null", 0, defaults.length());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // resolveDefaults — empty-string literal default ("")
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveDefaultsEmptyStringLiteral() throws Exception {
    OBDal dal = mock(OBDal.class);
    OBCriteria<SFField> fieldCriteria = mock(OBCriteria.class);
    SFField sfField = mock(SFField.class);
    Column adColumn = mock(Column.class);
    SFEntity sfEntity = mock(SFEntity.class);
    OBContext obContext = mock(OBContext.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    Entity dalEntity = mock(Entity.class);
    Property prop = mock(Property.class);

    when(sfEntity.getId()).thenReturn("sf-entity-1");
    when(sfField.getADColumn()).thenReturn(adColumn);
    when(sfField.isReadOnly()).thenReturn(false);
    when(sfField.getDefaultValue()).thenReturn(null);
    when(adColumn.getDBColumnName()).thenReturn("Description");
    when(adColumn.getDefaultValue()).thenReturn("\"\"");
    when(adColumn.isLinkToParentColumn()).thenReturn(false);
    when(adColumn.isUseAutomaticSequence()).thenReturn(false);
    when(fieldCriteria.add(any())).thenReturn(fieldCriteria);
    when(fieldCriteria.list()).thenReturn(Collections.singletonList(sfField));
    when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);
    when(prop.isPrimitive()).thenReturn(true);
    when(dalEntity.getProperty("description")).thenReturn(prop);

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class);
         MockedStatic<SequenceUtils> sequenceMock = mockStatic(SequenceUtils.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class);
         MockedStatic<DocTypeResolver> docTypeMock = mockStatic(DocTypeResolver.class)) {
      obContextMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, null)).thenReturn(vars);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper.resolveDalEntity(sfEntity))
          .thenReturn(dalEntity);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper
          .resolvePropertyName(dalEntity, "Description"))
          .thenReturn("description");
      sequenceMock.when(() -> SequenceUtils.isSequence(adColumn)).thenReturn(false);

      NeoResponse response = NeoDefaultsService.resolveDefaults(ctx, null);

      assertEquals(200, response.getHttpStatus());
      JSONObject defaults = response.getBody().getJSONObject("defaults");
      assertEquals("", defaults.getString("description"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // resolveDefaults — sequence field deferred to pass 2 with doctype
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveDefaultsSequenceFieldDeferredToPass2() throws Exception {
    OBDal dal = mock(OBDal.class);
    OBCriteria<SFField> fieldCriteria = mock(OBCriteria.class);
    SFField sfFieldDocNo = mock(SFField.class);
    Column adColumnDocNo = mock(Column.class);
    Table table = mock(Table.class);
    SFEntity sfEntity = mock(SFEntity.class);
    OBContext obContext = mock(OBContext.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    Entity dalEntity = mock(Entity.class);
    Property propDocNo = mock(Property.class);

    when(sfEntity.getId()).thenReturn("sf-entity-1");

    // DocumentNo field — classic sequence
    when(sfFieldDocNo.getADColumn()).thenReturn(adColumnDocNo);
    when(sfFieldDocNo.isReadOnly()).thenReturn(false);
    when(sfFieldDocNo.getDefaultValue()).thenReturn(null);
    when(adColumnDocNo.getDBColumnName()).thenReturn("DocumentNo");
    when(adColumnDocNo.isUseAutomaticSequence()).thenReturn(false);
    when(adColumnDocNo.getTable()).thenReturn(table);
    when(table.getDBTableName()).thenReturn("C_Order");

    when(fieldCriteria.add(any())).thenReturn(fieldCriteria);
    when(fieldCriteria.list()).thenReturn(Collections.singletonList(sfFieldDocNo));
    when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);
    when(propDocNo.isPrimitive()).thenReturn(true);
    when(dalEntity.getProperty("documentNo")).thenReturn(propDocNo);

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class);
         MockedStatic<SequenceUtils> sequenceMock = mockStatic(SequenceUtils.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class);
         MockedStatic<DocTypeResolver> docTypeMock = mockStatic(DocTypeResolver.class)) {
      obContextMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, null)).thenReturn(vars);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper.resolveDalEntity(sfEntity))
          .thenReturn(dalEntity);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper
          .resolvePropertyName(dalEntity, "DocumentNo"))
          .thenReturn("documentNo");
      // isSequenceField returns true for DocumentNo
      sequenceMock.when(() -> SequenceUtils.isSequence(adColumnDocNo)).thenReturn(false);
      // resolveSequencePreviewWithDocType called with empty doctype strings
      utilityMock.when(() -> Utility.getDocumentNo(any(), eq(vars), anyString(),
          eq("C_Order"), eq(""), eq(""), eq(false), eq(false)))
          .thenReturn("1000100");

      NeoResponse response = NeoDefaultsService.resolveDefaults(ctx, null);

      assertEquals(200, response.getHttpStatus());
      JSONObject defaults = response.getBody().getJSONObject("defaults");
      assertEquals("<1000100>", defaults.getString("documentNo"));
      // Check metadata contains sequenceFields
      JSONArray seqFields = response.getBody().getJSONObject("metadata")
          .getJSONArray("sequenceFields");
      boolean found = false;
      for (int i = 0; i < seqFields.length(); i++) {
        if ("documentNo".equals(seqFields.getString(i))) {
          found = true;
          break;
        }
      }
      assertTrue("documentNo should be listed in sequenceFields metadata", found);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // resolveDefaults — transactional sequence in pass 2
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveDefaultsTransactionalSequenceInPass2() throws Exception {
    OBDal dal = mock(OBDal.class);
    OBCriteria<SFField> fieldCriteria = mock(OBCriteria.class);
    SFField sfFieldSeq = mock(SFField.class);
    Column adColumnSeq = mock(Column.class);
    SFEntity sfEntity = mock(SFEntity.class);
    OBContext obContext = mock(OBContext.class);
    Organization orgMock = mock(Organization.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    Entity dalEntity = mock(Entity.class);
    Property propSeq = mock(Property.class);
    @SuppressWarnings("unchecked")
    OBCriteria<Sequence> seqCriteria = mock(OBCriteria.class);
    Sequence seq = mock(Sequence.class);

    when(sfEntity.getId()).thenReturn("sf-entity-1");
    when(sfFieldSeq.getADColumn()).thenReturn(adColumnSeq);
    when(sfFieldSeq.isReadOnly()).thenReturn(false);
    when(sfFieldSeq.getDefaultValue()).thenReturn(null);
    when(adColumnSeq.getDBColumnName()).thenReturn("Value");
    when(adColumnSeq.isUseAutomaticSequence()).thenReturn(true);

    when(fieldCriteria.add(any())).thenReturn(fieldCriteria);
    when(fieldCriteria.list()).thenReturn(Collections.singletonList(sfFieldSeq));
    when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);
    when(propSeq.isPrimitive()).thenReturn(true);
    when(dalEntity.getProperty("searchKey")).thenReturn(propSeq);
    when(obContext.getCurrentOrganization()).thenReturn(orgMock);
    when(orgMock.getId()).thenReturn("ORG-1");
    when(dal.get(eq(Organization.class), eq("ORG-1"))).thenReturn(orgMock);
    when(dal.createCriteria(Sequence.class)).thenReturn(seqCriteria);
    when(seqCriteria.uniqueResult()).thenReturn(seq);
    when(seq.getNextAssignedNumber()).thenReturn(5000L);

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class);
         MockedStatic<SequenceUtils> sequenceMock = mockStatic(SequenceUtils.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class);
         MockedStatic<DocTypeResolver> docTypeMock = mockStatic(DocTypeResolver.class)) {
      obContextMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, null)).thenReturn(vars);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper.resolveDalEntity(sfEntity))
          .thenReturn(dalEntity);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper
          .resolvePropertyName(dalEntity, "Value"))
          .thenReturn("searchKey");
      // SequenceUtils.isSequence returns true for this column
      sequenceMock.when(() -> SequenceUtils.isSequence(adColumnSeq)).thenReturn(true);

      NeoResponse response = NeoDefaultsService.resolveDefaults(ctx, null);

      assertEquals(200, response.getHttpStatus());
      JSONObject defaults = response.getBody().getJSONObject("defaults");
      assertEquals("<5000>", defaults.getString("searchKey"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // resolveDefaults — top-level exception returns 500 error
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testResolveDefaultsTopLevelExceptionReturns500() {
    NeoContext ctx = NeoContext.builder()
        .sfEntity(null)
        .obContext(mock(OBContext.class))
        .build();

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obContextMock.when(OBContext::setAdminMode)
          .thenThrow(new RuntimeException("Simulated failure"));

      NeoResponse response = NeoDefaultsService.resolveDefaults(ctx, null);

      assertEquals(500, response.getHttpStatus());
      assertNotNull(response.getBody());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // resolveDefaults — resolveWindowId returns window ID from SFSpec
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveDefaultsResolvesWindowIdFromSfSpec() throws Exception {
    OBDal dal = mock(OBDal.class);
    OBCriteria<SFField> fieldCriteria = mock(OBCriteria.class);
    SFEntity sfEntity = mock(SFEntity.class);
    SFSpec sfSpec = mock(SFSpec.class);
    Window window = mock(Window.class);
    OBContext obContext = mock(OBContext.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    Entity dalEntity = mock(Entity.class);

    when(sfEntity.getId()).thenReturn("sf-entity-1");
    when(sfEntity.getETGOSFSpec()).thenReturn(sfSpec);
    when(sfSpec.getADWindow()).thenReturn(window);
    when(window.getId()).thenReturn("WINDOW-123");
    when(fieldCriteria.add(any())).thenReturn(fieldCriteria);
    when(fieldCriteria.list()).thenReturn(Collections.emptyList());
    when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class);
         MockedStatic<SequenceUtils> sequenceMock = mockStatic(SequenceUtils.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class);
         MockedStatic<DocTypeResolver> docTypeMock = mockStatic(DocTypeResolver.class)) {
      obContextMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, null)).thenReturn(vars);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper.resolveDalEntity(sfEntity))
          .thenReturn(dalEntity);

      NeoResponse response = NeoDefaultsService.resolveDefaults(ctx, null);

      assertEquals(200, response.getHttpStatus());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // resolveDefaults — callout cascade executed when adTab present
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveDefaultsExecutesCascadeWhenAdTabPresent() throws Exception {
    OBDal dal = mock(OBDal.class);
    OBCriteria<SFField> fieldCriteria = mock(OBCriteria.class);
    SFEntity sfEntity = mock(SFEntity.class);
    Tab adTab = mock(Tab.class);
    OBContext obContext = mock(OBContext.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    Entity dalEntity = mock(Entity.class);

    when(sfEntity.getId()).thenReturn("sf-entity-1");
    when(fieldCriteria.add(any())).thenReturn(fieldCriteria);
    when(fieldCriteria.list()).thenReturn(Collections.emptyList());
    when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .adTab(adTab)
        .build();

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class);
         MockedStatic<SequenceUtils> sequenceMock = mockStatic(SequenceUtils.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class);
         MockedStatic<DocTypeResolver> docTypeMock = mockStatic(DocTypeResolver.class)) {
      obContextMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, adTab)).thenReturn(vars);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper.resolveDalEntity(sfEntity))
          .thenReturn(dalEntity);

      NeoResponse response = NeoDefaultsService.resolveDefaults(ctx, null);

      assertEquals(200, response.getHttpStatus());
      // Verify cascade was called
      cascadeMock.verify(() -> NeoDefaultsCascadeHelper.executeCalloutCascade(
          eq(ctx), eq(adTab), any(JSONObject.class), any(Set.class)));
      // Verify DocTypeResolver reapply was called
      docTypeMock.verify(() -> DocTypeResolver.reapplyDocTypeFromTabFilter(
          any(JSONObject.class), eq(adTab), eq(ctx)));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // injectMandatoryDefaults — null guards
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testInjectMandatoryDefaultsNullBodyNoOp() {
    NeoDefaultsService.injectMandatoryDefaults(null, mock(Tab.class),
        mock(NeoContext.class));
    // Should not throw
  }

  @Test
  public void testInjectMandatoryDefaultsNullTabNoOp() {
    JSONObject body = new JSONObject();
    NeoDefaultsService.injectMandatoryDefaults(body, null, mock(NeoContext.class));
    assertEquals(0, body.length());
  }

  @Test
  public void testInjectMandatoryDefaultsNullCtxNoOp() {
    JSONObject body = new JSONObject();
    NeoDefaultsService.injectMandatoryDefaults(body, mock(Tab.class), null);
    assertEquals(0, body.length());
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // injectMandatoryDefaults — skips key columns and audit columns
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void testInjectMandatoryDefaultsSkipsKeyAndAuditColumns() {
    JSONObject body = new JSONObject();
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    OBContext obContext = mock(OBContext.class);
    SFEntity sfEntity = mock(SFEntity.class);
    Entity dalEntity = mock(Entity.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);

    // Key column — should be skipped
    Column keyCol = mockColumn("C_Order_ID", true, true, true);
    // Audit columns — should be skipped
    Column createdCol = mockColumn("Created", true, false, true);
    Column updatedCol = mockColumn("Updated", true, false, true);
    Column createdByCol = mockColumn("CreatedBy", true, false, true);
    Column updatedByCol = mockColumn("UpdatedBy", true, false, true);

    Property createdProp = mock(Property.class);
    when(createdProp.isAuditInfo()).thenReturn(true);
    Property updatedProp = mock(Property.class);
    when(updatedProp.isAuditInfo()).thenReturn(true);
    Property createdByProp = mock(Property.class);
    when(createdByProp.isAuditInfo()).thenReturn(true);
    Property updatedByProp = mock(Property.class);
    when(updatedByProp.isAuditInfo()).thenReturn(true);

    when(dalEntity.getPropertyByColumnName("Created")).thenReturn(createdProp);
    when(dalEntity.getPropertyByColumnName("Updated")).thenReturn(updatedProp);
    when(dalEntity.getPropertyByColumnName("CreatedBy")).thenReturn(createdByProp);
    when(dalEntity.getPropertyByColumnName("UpdatedBy")).thenReturn(updatedByProp);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-1");
    when(table.getADColumnList()).thenReturn(
        Arrays.asList(keyCol, createdCol, updatedCol, createdByCol, updatedByCol));

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class)) {
      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-1")).thenReturn(dalEntity);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, adTab)).thenReturn(vars);

      NeoDefaultsService.injectMandatoryDefaults(body, adTab, ctx);

      // Body should still be empty — all columns were skipped
      assertEquals("Key and audit columns should be skipped", 0, body.length());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // injectMandatoryDefaults — skips inactive and non-mandatory columns
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void testInjectMandatoryDefaultsSkipsInactiveAndNonMandatory() {
    JSONObject body = new JSONObject();
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    OBContext obContext = mock(OBContext.class);
    SFEntity sfEntity = mock(SFEntity.class);
    Entity dalEntity = mock(Entity.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);

    // Inactive column — skipped by the !col.isActive() continue.
    Column inactiveCol = mockColumn("InactiveCol", true, false, false);
    // Non-mandatory column with a real DAL property but NO resolvable default. Post-ETP-4274
    // the loop no longer skips it on !isMandatory(); it now enters
    // injectMandatoryDefaultForColumn and runs passes 1-3, which all fail here, so the
    // !mandatory gate must leave it absent from the body (no combo/safe-type fallback).
    Column nonMandatoryCol = mockColumn("OptionalCol", false, false, true);
    when(nonMandatoryCol.getDefaultValue()).thenReturn(null);
    when(nonMandatoryCol.isLinkToParentColumn()).thenReturn(false);
    when(nonMandatoryCol.isUseAutomaticSequence()).thenReturn(false);
    Property optionalProp = mock(Property.class);
    when(optionalProp.isAuditInfo()).thenReturn(false);
    when(optionalProp.getName()).thenReturn("optional");
    when(dalEntity.getPropertyByColumnName("OptionalCol")).thenReturn(optionalProp);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-1");
    when(table.getADColumnList()).thenReturn(Arrays.asList(inactiveCol, nonMandatoryCol));

    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBCriteria<SFField> sfFieldCriteria = mock(OBCriteria.class);
    when(sfFieldCriteria.add(any())).thenReturn(sfFieldCriteria);
    when(sfFieldCriteria.list()).thenReturn(Collections.emptyList());
    when(obDal.createCriteria(SFField.class)).thenReturn(sfFieldCriteria);
    when(sfEntity.getId()).thenReturn("entity-1");
    when(vars.getSessionValue(anyString())).thenReturn("");

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class);
         MockedStatic<SequenceUtils> sequenceMock = mockStatic(SequenceUtils.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class);
         MockedStatic<DocTypeResolver> docTypeMock = mockStatic(DocTypeResolver.class);
         MockedStatic<NeoSelectorService> selectorMock = mockStatic(NeoSelectorService.class);
         MockedStatic<NeoParentValuesLoader> parentMock =
             mockStatic(NeoParentValuesLoader.class)) {
      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-1")).thenReturn(dalEntity);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      obContextMock.when(() -> OBContext.setAdminMode(true)).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, adTab)).thenReturn(vars);
      sequenceMock.when(() -> SequenceUtils.isSequence(nonMandatoryCol)).thenReturn(false);
      utilityMock.when(() -> Utility.getPreference(eq(vars), eq("OptionalCol"), anyString()))
          .thenReturn(null);
      docTypeMock.when(() -> DocTypeResolver.resolveDefaultDocTypeId(eq(nonMandatoryCol), any()))
          .thenReturn(null);
      parentMock.when(() -> NeoParentValuesLoader.load(adTab, null))
          .thenReturn(java.util.Collections.emptyMap());

      NeoDefaultsService.injectMandatoryDefaults(body, adTab, ctx);

      assertEquals("Inactive/non-mandatory columns should be skipped", 0, body.length());
      // The !mandatory gate must prevent the NOT-NULL safety fallbacks from firing on the
      // optional column (preserves ETP-3894 — no silent combo first-pick / safe-type default).
      selectorMock.verify(() -> NeoSelectorService.querySelectorByColumn(
          any(), anyString(), any(), anyInt(), anyInt(), any()), never());
      cascadeMock.verify(() -> NeoDefaultsCascadeHelper.injectSafeTypeDefault(
          any(), anyString(), any()), never());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // injectMandatoryDefaults — skips already-present fields
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void testInjectMandatoryDefaultsSkipsAlreadyPresentFields() throws Exception {
    JSONObject body = new JSONObject();
    body.put("organization", "ORG-1");

    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    OBContext obContext = mock(OBContext.class);
    SFEntity sfEntity = mock(SFEntity.class);
    Entity dalEntity = mock(Entity.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);

    Column orgCol = mockColumn("AD_Org_ID", true, false, true);
    Property orgProp = mock(Property.class);
    when(orgProp.isAuditInfo()).thenReturn(false);
    when(orgProp.getName()).thenReturn("organization");
    when(dalEntity.getPropertyByColumnName("AD_Org_ID")).thenReturn(orgProp);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-1");
    when(table.getADColumnList()).thenReturn(Collections.singletonList(orgCol));

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class)) {
      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-1")).thenReturn(dalEntity);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, adTab)).thenReturn(vars);

      NeoDefaultsService.injectMandatoryDefaults(body, adTab, ctx);

      // Field already present — should not be overwritten
      assertEquals("ORG-1", body.getString("organization"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // injectMandatoryDefaults — runCascade=false skips cascade
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void testInjectMandatoryDefaultsNoCascadeWhenRunCascadeFalse() {
    JSONObject body = new JSONObject();
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    OBContext obContext = mock(OBContext.class);
    SFEntity sfEntity = mock(SFEntity.class);
    Entity dalEntity = mock(Entity.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-1");
    when(table.getADColumnList()).thenReturn(Collections.emptyList());

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class)) {
      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-1")).thenReturn(dalEntity);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, adTab)).thenReturn(vars);

      NeoDefaultsService.injectMandatoryDefaults(body, adTab, ctx, null, false);

      // Cascade should NOT be called when runCascade is false
      cascadeMock.verify(() -> NeoDefaultsCascadeHelper.executeCalloutCascadeForCreate(
          any(), any(), any()), never());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // injectMandatoryDefaults — two-arg overload delegates to full overload
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void testInjectMandatoryDefaultsTwoArgOverload() {
    JSONObject body = new JSONObject();
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    OBContext obContext = mock(OBContext.class);
    SFEntity sfEntity = mock(SFEntity.class);
    Entity dalEntity = mock(Entity.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-1");
    when(table.getADColumnList()).thenReturn(Collections.emptyList());

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class)) {
      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-1")).thenReturn(dalEntity);
      OBDal obDal = mock(OBDal.class);
      @SuppressWarnings("unchecked")
      OBCriteria<SFField> sfFieldCriteria = mock(OBCriteria.class);
      when(sfFieldCriteria.add(any())).thenReturn(sfFieldCriteria);
      when(sfFieldCriteria.list()).thenReturn(Collections.emptyList());
      when(obDal.createCriteria(SFField.class)).thenReturn(sfFieldCriteria);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      obContextMock.when(() -> OBContext.setAdminMode(true)).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, adTab)).thenReturn(vars);

      // Two-arg overload — should run cascade by default
      NeoDefaultsService.injectMandatoryDefaults(body, adTab, ctx, "PARENT-1");

      cascadeMock.verify(() -> NeoDefaultsCascadeHelper.executeCalloutCascadeForCreate(
          eq(ctx), eq(adTab), eq(body)));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // injectMandatoryDefaults — null dalEntity early return
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void testInjectMandatoryDefaultsNullDalEntityReturnsEarly() {
    JSONObject body = new JSONObject();
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-MISSING");

    NeoContext ctx = NeoContext.builder()
        .obContext(mock(OBContext.class))
        .build();

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class)) {
      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-MISSING")).thenReturn(null);
      calloutMock.when(() -> NeoCalloutService.buildVars(any(), any()))
          .thenReturn(mock(VariablesSecureApp.class));

      NeoDefaultsService.injectMandatoryDefaults(body, adTab, ctx);

      assertEquals("Body should remain empty when dalEntity is null", 0, body.length());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // findMissingMandatoryFields — null/empty guards
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testFindMissingMandatoryFieldsNullBodyReturnsEmpty() {
    List<String> missing = NeoMandatoryFieldValidator.findMissingMandatoryFields(null, mock(Tab.class));
    assertNotNull(missing);
    assertTrue(missing.isEmpty());
  }

  @Test
  public void testFindMissingMandatoryFieldsNullTabReturnsEmpty() {
    List<String> missing = NeoMandatoryFieldValidator.findMissingMandatoryFields(
        new JSONObject(), null);
    assertNotNull(missing);
    assertTrue(missing.isEmpty());
  }

  @Test
  public void testFindMissingMandatoryFieldsNullTableReturnsEmpty() {
    Tab adTab = mock(Tab.class);
    when(adTab.getTable()).thenReturn(null);
    List<String> missing = NeoMandatoryFieldValidator.findMissingMandatoryFields(
        new JSONObject(), adTab);
    assertNotNull(missing);
    assertTrue(missing.isEmpty());
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // findMissingMandatoryFields — skips key columns, audit, numerics, booleans
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testFindMissingMandatoryFieldsSkipsKeyAndAuditAndPrimitiveTypes() {
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    Entity dalEntity = mock(Entity.class);

    // Key column — skipped
    Column keyCol = mockColumn("C_Order_ID", true, true, true);
    // Audit column — skipped
    Column updatedCol = mockColumn("Updated", true, false, true);
    Property updatedProp = mock(Property.class);
    when(updatedProp.isAuditInfo()).thenReturn(true);
    when(dalEntity.getPropertyByColumnName("Updated")).thenReturn(updatedProp);
    // Numeric column (ref=22) — skipped
    Column numericCol = mockColumn("Amount", true, false, true);
    Reference numRef = mock(Reference.class);
    when(numRef.getId()).thenReturn("22");
    when(numericCol.getReference()).thenReturn(numRef);
    Property amountProp = mock(Property.class);
    when(amountProp.isAuditInfo()).thenReturn(false);
    when(amountProp.getName()).thenReturn("amount");
    when(dalEntity.getPropertyByColumnName("Amount")).thenReturn(amountProp);
    // Boolean column (ref=20) — skipped
    Column boolCol = mockColumn("IsActive", true, false, true);
    Reference boolRef = mock(Reference.class);
    when(boolRef.getId()).thenReturn("20");
    when(boolCol.getReference()).thenReturn(boolRef);
    Property boolProp = mock(Property.class);
    when(boolProp.isAuditInfo()).thenReturn(false);
    when(boolProp.getName()).thenReturn("active");
    when(dalEntity.getPropertyByColumnName("IsActive")).thenReturn(boolProp);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-1");
    when(table.getADColumnList()).thenReturn(
        Arrays.asList(keyCol, updatedCol, numericCol, boolCol));

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class)) {
      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-1")).thenReturn(dalEntity);

      List<String> missing = NeoMandatoryFieldValidator.findMissingMandatoryFields(
          new JSONObject(), adTab);

      assertTrue("All columns should be skipped", missing.isEmpty());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // findMissingMandatoryFields — detects missing FK field
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testFindMissingMandatoryFieldsDetectsMissingFk() throws Exception {
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    Entity dalEntity = mock(Entity.class);

    Column bpCol = mockColumn("C_BPartner_ID", true, false, true);
    Reference tableRef = mock(Reference.class);
    when(tableRef.getId()).thenReturn("19"); // TableDir
    when(bpCol.getReference()).thenReturn(tableRef);
    Property bpProp = mock(Property.class);
    when(bpProp.isAuditInfo()).thenReturn(false);
    when(bpProp.getName()).thenReturn("businessPartner");
    when(dalEntity.getPropertyByColumnName("C_BPartner_ID")).thenReturn(bpProp);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-1");
    when(table.getADColumnList()).thenReturn(Collections.singletonList(bpCol));

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class)) {
      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-1")).thenReturn(dalEntity);

      JSONObject body = new JSONObject();
      List<String> missing = NeoMandatoryFieldValidator.findMissingMandatoryFields(body, adTab);

      assertEquals(1, missing.size());
      assertEquals("businessPartner", missing.get(0));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // findMissingMandatoryFields — detects null/empty/JSONObject.NULL values
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testFindMissingMandatoryFieldsDetectsEmptyAndNullValues() throws Exception {
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    Entity dalEntity = mock(Entity.class);

    // Column with null value
    Column col1 = mockColumn("C_BPartner_ID", true, false, true);
    Reference ref1 = mock(Reference.class);
    when(ref1.getId()).thenReturn("19");
    when(col1.getReference()).thenReturn(ref1);
    Property prop1 = mock(Property.class);
    when(prop1.isAuditInfo()).thenReturn(false);
    when(prop1.getName()).thenReturn("businessPartner");
    when(dalEntity.getPropertyByColumnName("C_BPartner_ID")).thenReturn(prop1);

    // Column with empty string value
    Column col2 = mockColumn("DocStatus", true, false, true);
    Reference ref2 = mock(Reference.class);
    when(ref2.getId()).thenReturn("17"); // List
    when(col2.getReference()).thenReturn(ref2);
    Property prop2 = mock(Property.class);
    when(prop2.isAuditInfo()).thenReturn(false);
    when(prop2.getName()).thenReturn("documentStatus");
    when(dalEntity.getPropertyByColumnName("DocStatus")).thenReturn(prop2);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-1");
    when(table.getADColumnList()).thenReturn(Arrays.asList(col1, col2));

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class)) {
      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-1")).thenReturn(dalEntity);

      JSONObject body = new JSONObject();
      body.put("businessPartner", JSONObject.NULL);
      body.put("documentStatus", "  ");

      List<String> missing = NeoMandatoryFieldValidator.findMissingMandatoryFields(body, adTab);

      assertEquals(2, missing.size());
      assertTrue(missing.contains("businessPartner"));
      assertTrue(missing.contains("documentStatus"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // findMissingMandatoryFields — userSubmittedFields filter
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testFindMissingMandatoryFieldsRespectsUserSubmittedFilter() throws Exception {
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    Entity dalEntity = mock(Entity.class);

    // This column is missing but NOT in userSubmittedFields — should be excluded
    Column col1 = mockColumn("C_BPartner_ID", true, false, true);
    Reference ref1 = mock(Reference.class);
    when(ref1.getId()).thenReturn("19");
    when(col1.getReference()).thenReturn(ref1);
    Property prop1 = mock(Property.class);
    when(prop1.isAuditInfo()).thenReturn(false);
    when(prop1.getName()).thenReturn("businessPartner");
    when(dalEntity.getPropertyByColumnName("C_BPartner_ID")).thenReturn(prop1);

    // This column is missing AND in userSubmittedFields — should be reported
    Column col2 = mockColumn("M_Product_ID", true, false, true);
    Reference ref2 = mock(Reference.class);
    when(ref2.getId()).thenReturn("30"); // Search
    when(col2.getReference()).thenReturn(ref2);
    Property prop2 = mock(Property.class);
    when(prop2.isAuditInfo()).thenReturn(false);
    when(prop2.getName()).thenReturn("product");
    when(dalEntity.getPropertyByColumnName("M_Product_ID")).thenReturn(prop2);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-1");
    when(table.getADColumnList()).thenReturn(Arrays.asList(col1, col2));

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class)) {
      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-1")).thenReturn(dalEntity);

      JSONObject body = new JSONObject();
      Set<String> userFields = new HashSet<>();
      userFields.add("product");

      List<String> missing = NeoMandatoryFieldValidator.findMissingMandatoryFields(
          body, adTab, userFields);

      assertEquals(1, missing.size());
      assertEquals("product", missing.get(0));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // findMissingMandatoryFields — valid value present means not missing
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testFindMissingMandatoryFieldsValidValueNotMissing() throws Exception {
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    Entity dalEntity = mock(Entity.class);

    Column col = mockColumn("C_BPartner_ID", true, false, true);
    Reference ref = mock(Reference.class);
    when(ref.getId()).thenReturn("19");
    when(col.getReference()).thenReturn(ref);
    Property prop = mock(Property.class);
    when(prop.isAuditInfo()).thenReturn(false);
    when(prop.getName()).thenReturn("businessPartner");
    when(dalEntity.getPropertyByColumnName("C_BPartner_ID")).thenReturn(prop);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-1");
    when(table.getADColumnList()).thenReturn(Collections.singletonList(col));

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class)) {
      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-1")).thenReturn(dalEntity);

      JSONObject body = new JSONObject();
      body.put("businessPartner", "BP-123");

      List<String> missing = NeoMandatoryFieldValidator.findMissingMandatoryFields(body, adTab);

      assertTrue("Should not be missing when value is present", missing.isEmpty());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // findMissingMandatoryFields — null dalEntity returns empty
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testFindMissingMandatoryFieldsNullDalEntityReturnsEmpty() {
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-MISSING");

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class)) {
      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-MISSING")).thenReturn(null);

      List<String> missing = NeoMandatoryFieldValidator.findMissingMandatoryFields(
          new JSONObject(), adTab);
      assertTrue(missing.isEmpty());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // buildVariablesSecureApp — pure delegation to NeoCalloutService, no #Date seeding
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * ETP-4793 / IMP-16 removed the ISO {@code "#Date"} session seeding from this method: core
   * never reads the session for that name ({@code Utility.getContext} special-cases it and
   * returns {@code DateTimeData.today}, formatted with a hardcoded {@code dd-MM-yyyy}), so the
   * seeding was dead code that made {@code @#Date@} defaults look canonicalized when they were
   * not. These two tests pin the removal: re-adding the seeding would revive the illusion and
   * push canonicalization back off the resolved value, where it actually has to happen.
   */
  @Test
  public void testBuildVariablesSecureAppDoesNotSeedDateSessionValue() {
    OBContext obContext = mock(OBContext.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);

    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, null)).thenReturn(vars);

      VariablesSecureApp result = NeoDefaultsService.buildVariablesSecureApp(obContext);

      assertEquals(vars, result);
      verify(vars, never()).setSessionValue(eq("#Date"), any(String.class));
      verifyNoInteractions(vars);
    }
  }

  @Test
  public void testBuildVariablesSecureAppWithTabDoesNotSeedDateSessionValue() {
    OBContext obContext = mock(OBContext.class);
    Tab adTab = mock(Tab.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);

    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, adTab)).thenReturn(vars);

      VariablesSecureApp result = NeoDefaultsService.buildVariablesSecureApp(obContext, adTab);

      assertEquals(vars, result);
      verify(vars, never()).setSessionValue(eq("#Date"), any(String.class));
      verifyNoInteractions(vars);
    }
  }

  @Test
  public void testBuildVariablesSecureAppNoArgDelegatesToTwoArg() {
    OBContext obContext = mock(OBContext.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);

    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, null)).thenReturn(vars);

      VariablesSecureApp result = NeoDefaultsService.buildVariablesSecureApp(obContext);

      assertNotNull(result);
      // Verify it was called with null tab
      calloutMock.verify(() -> NeoCalloutService.buildVars(obContext, null));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // resolveFirstOrgForClient — DB query via PreparedStatement
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testResolveFirstOrgForClientReturnsOrgId() throws Exception {
    OBDal obDal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(obDal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getString(1)).thenReturn("ORG-ABC");

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      String result = NeoDefaultsSqlHelper.resolveFirstOrgForClient("CLIENT-1");

      assertEquals("ORG-ABC", result);
      verify(ps).setString(1, "CLIENT-1");
    }
  }

  @Test
  public void testResolveFirstOrgForClientNoResultReturnsNull() throws Exception {
    OBDal obDal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(obDal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      String result = NeoDefaultsSqlHelper.resolveFirstOrgForClient("CLIENT-1");

      assertNull(result);
    }
  }

  @Test
  public void testResolveFirstOrgForClientExceptionReturnsNull() throws Exception {
    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection(false)).thenThrow(new RuntimeException("DB down"));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      String result = NeoDefaultsSqlHelper.resolveFirstOrgForClient("CLIENT-1");

      assertNull("Exception should be swallowed and null returned", result);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CalloutCascadeResult — inner class tests
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testCalloutCascadeResultHasResultsEmptyIsFalse() {
    NeoDefaultsService.CalloutCascadeResult result =
        new NeoDefaultsService.CalloutCascadeResult();
    assertFalse(result.hasResults());
    assertEquals(0, result.updatedFieldCount());
  }

  @Test
  public void testCalloutCascadeResultMergeUpdates() throws Exception {
    NeoDefaultsService.CalloutCascadeResult result =
        new NeoDefaultsService.CalloutCascadeResult();

    JSONObject updates = new JSONObject();
    updates.put("field1", "value1");
    updates.put("field2", "value2");
    result.mergeUpdates(updates);

    assertTrue(result.hasResults());
    assertEquals(2, result.updatedFieldCount());

    JSONObject json = result.toJSON();
    assertEquals("value1", json.getJSONObject("updates").getString("field1"));
    assertEquals("value2", json.getJSONObject("updates").getString("field2"));
  }

  @Test
  public void testCalloutCascadeResultMergeCombos() throws Exception {
    NeoDefaultsService.CalloutCascadeResult result =
        new NeoDefaultsService.CalloutCascadeResult();

    JSONObject combos = new JSONObject();
    combos.put("combo1", new JSONArray());
    result.mergeCombos(combos);

    assertTrue(result.hasResults());

    JSONObject json = result.toJSON();
    assertNotNull(json.getJSONObject("combos").getJSONArray("combo1"));
  }

  @Test
  public void testCalloutCascadeResultMergeMessages() throws Exception {
    NeoDefaultsService.CalloutCascadeResult result =
        new NeoDefaultsService.CalloutCascadeResult();

    JSONArray messages = new JSONArray();
    messages.put("msg1");
    messages.put("msg2");
    result.mergeMessages(messages);

    assertTrue(result.hasResults());

    JSONObject json = result.toJSON();
    assertEquals(2, json.getJSONArray("messages").length());
    assertEquals("msg1", json.getJSONArray("messages").getString(0));
  }

  @Test
  public void testCalloutCascadeResultMergeNullUpdatesNoOp() {
    NeoDefaultsService.CalloutCascadeResult result =
        new NeoDefaultsService.CalloutCascadeResult();
    result.mergeUpdates(null);
    assertFalse(result.hasResults());
  }

  @Test
  public void testCalloutCascadeResultMergeNullCombosNoOp() {
    NeoDefaultsService.CalloutCascadeResult result =
        new NeoDefaultsService.CalloutCascadeResult();
    result.mergeCombos(null);
    assertFalse(result.hasResults());
  }

  @Test
  public void testCalloutCascadeResultToJSONStructure() throws Exception {
    NeoDefaultsService.CalloutCascadeResult result =
        new NeoDefaultsService.CalloutCascadeResult();

    JSONObject json = result.toJSON();
    assertNotNull(json.getJSONObject("updates"));
    assertNotNull(json.getJSONObject("combos"));
    assertNotNull(json.getJSONArray("messages"));
  }

  @Test
  public void testCalloutCascadeResultChainDepthAndTruncated() {
    NeoDefaultsService.CalloutCascadeResult result =
        new NeoDefaultsService.CalloutCascadeResult();
    result.chainDepth = 5;
    result.truncated = true;

    assertEquals(5, result.chainDepth);
    assertTrue(result.truncated);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // coerceBooleanDefault — via reflection (100% branch coverage)
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testCoerceBooleanDefaultNullEntityReturnsValueUnchanged() throws Exception {
    Object result = invokePrivate("coerceBooleanDefault",
        new Class<?>[]{ Entity.class, String.class, Object.class },
        null, "depreciate", "Y");
    assertEquals("Y", result);
  }

  @Test
  public void testCoerceBooleanDefaultNonStringValueReturnsUnchanged() throws Exception {
    Object result = invokePrivate("coerceBooleanDefault",
        new Class<?>[]{ Entity.class, String.class, Object.class },
        mock(Entity.class), "depreciate", 42);
    assertEquals(42, result);
  }

  @Test
  public void testCoerceBooleanDefaultNullPropertyReturnsValueUnchanged() throws Exception {
    Entity entity = mock(Entity.class);
    when(entity.getProperty("depreciate")).thenReturn(null);
    Object result = invokePrivate("coerceBooleanDefault",
        new Class<?>[]{ Entity.class, String.class, Object.class },
        entity, "depreciate", "Y");
    assertEquals("Y", result);
  }

  @Test
  public void testCoerceBooleanDefaultNotPrimitiveReturnsValueUnchanged() throws Exception {
    Entity entity = mock(Entity.class);
    Property prop = mock(Property.class);
    when(entity.getProperty("businessPartner")).thenReturn(prop);
    when(prop.isPrimitive()).thenReturn(false);
    Object result = invokePrivate("coerceBooleanDefault",
        new Class<?>[]{ Entity.class, String.class, Object.class },
        entity, "businessPartner", "Y");
    assertEquals("Y", result);
  }

  @Test
  public void testCoerceBooleanDefaultNullPrimitiveTypeReturnsValueUnchanged() throws Exception {
    Entity entity = mock(Entity.class);
    Property prop = mock(Property.class);
    when(entity.getProperty("depreciate")).thenReturn(prop);
    when(prop.isPrimitive()).thenReturn(true);
    // getPrimitiveObjectType() returns Class<?> — use doReturn to avoid wildcard compile error
    doReturn(null).when(prop).getPrimitiveObjectType();
    Object result = invokePrivate("coerceBooleanDefault",
        new Class<?>[]{ Entity.class, String.class, Object.class },
        entity, "depreciate", "Y");
    assertEquals("Y", result);
  }

  @Test
  public void testCoerceBooleanDefaultNonBooleanTypeReturnsValueUnchanged() throws Exception {
    Entity entity = mock(Entity.class);
    Property prop = mock(Property.class);
    when(entity.getProperty("name")).thenReturn(prop);
    when(prop.isPrimitive()).thenReturn(true);
    doReturn(String.class).when(prop).getPrimitiveObjectType();
    Object result = invokePrivate("coerceBooleanDefault",
        new Class<?>[]{ Entity.class, String.class, Object.class },
        entity, "name", "Y");
    assertEquals("Y", result);
  }

  @Test
  public void testCoerceBooleanDefaultYValueReturnsTrueBoolean() throws Exception {
    Entity entity = mock(Entity.class);
    Property prop = mock(Property.class);
    when(entity.getProperty("depreciate")).thenReturn(prop);
    when(prop.isPrimitive()).thenReturn(true);
    doReturn(Boolean.class).when(prop).getPrimitiveObjectType();
    Object result = invokePrivate("coerceBooleanDefault",
        new Class<?>[]{ Entity.class, String.class, Object.class },
        entity, "depreciate", "Y");
    assertEquals(Boolean.TRUE, result);
  }

  @Test
  public void testCoerceBooleanDefaultTrueLowerCaseReturnsTrueBoolean() throws Exception {
    Entity entity = mock(Entity.class);
    Property prop = mock(Property.class);
    when(entity.getProperty("depreciate")).thenReturn(prop);
    when(prop.isPrimitive()).thenReturn(true);
    doReturn(Boolean.class).when(prop).getPrimitiveObjectType();
    Object result = invokePrivate("coerceBooleanDefault",
        new Class<?>[]{ Entity.class, String.class, Object.class },
        entity, "depreciate", "true");
    assertEquals(Boolean.TRUE, result);
  }

  @Test
  public void testCoerceBooleanDefaultTrueMixedCaseReturnsTrueBoolean() throws Exception {
    Entity entity = mock(Entity.class);
    Property prop = mock(Property.class);
    when(entity.getProperty("depreciate")).thenReturn(prop);
    when(prop.isPrimitive()).thenReturn(true);
    doReturn(Boolean.class).when(prop).getPrimitiveObjectType();
    Object result = invokePrivate("coerceBooleanDefault",
        new Class<?>[]{ Entity.class, String.class, Object.class },
        entity, "depreciate", "TRUE");
    assertEquals(Boolean.TRUE, result);
  }

  @Test
  public void testCoerceBooleanDefaultNValueReturnsFalseBoolean() throws Exception {
    Entity entity = mock(Entity.class);
    Property prop = mock(Property.class);
    when(entity.getProperty("depreciate")).thenReturn(prop);
    when(prop.isPrimitive()).thenReturn(true);
    doReturn(Boolean.class).when(prop).getPrimitiveObjectType();
    Object result = invokePrivate("coerceBooleanDefault",
        new Class<?>[]{ Entity.class, String.class, Object.class },
        entity, "depreciate", "N");
    assertEquals(Boolean.FALSE, result);
  }

  @Test
  public void testCoerceBooleanDefaultFalseValueReturnsFalseBoolean() throws Exception {
    Entity entity = mock(Entity.class);
    Property prop = mock(Property.class);
    when(entity.getProperty("depreciate")).thenReturn(prop);
    when(prop.isPrimitive()).thenReturn(true);
    doReturn(Boolean.class).when(prop).getPrimitiveObjectType();
    Object result = invokePrivate("coerceBooleanDefault",
        new Class<?>[]{ Entity.class, String.class, Object.class },
        entity, "depreciate", "false");
    assertEquals(Boolean.FALSE, result);
  }

  @Test
  public void testCoerceBooleanDefaultGetPropertyThrowsReturnsValueUnchanged() throws Exception {
    Entity entity = mock(Entity.class);
    when(entity.getProperty("depreciate")).thenThrow(new RuntimeException("property not found"));
    Object result = invokePrivate("coerceBooleanDefault",
        new Class<?>[]{ Entity.class, String.class, Object.class },
        entity, "depreciate", "Y");
    assertEquals("Y", result);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // canonicalizeBooleanDefaults — the post-pass (ETP-4793)
  //
  // coerceBooleanDefault above is only reachable from pass 1, so every other producer that
  // writes into `defaults` (the callout writeback and combo preselection in
  // NeoDefaultsCascadeHelper, NeoHiddenMandatoryDefaultsResolver, handler-injected values) left
  // raw "Y"/"N" strings in the response. That is why the same c_invoice column came back as a
  // boolean on sales-invoice and as a string on purchase-invoice, with the direction inverted
  // per field. These tests pin the post-pass that closes that hole.
  // ═══════════════════════════════════════════════════════════════════════════

  /** Wires {@code entity.getProperty(name)} to a primitive property of the given Java type. */
  private static Entity entityWithPrimitiveProperty(String name, Class<?> type) {
    Entity entity = mock(Entity.class);
    when(entity.getName()).thenReturn("Invoice");
    Property prop = mock(Property.class);
    when(prop.isPrimitive()).thenReturn(true);
    doReturn(type).when(prop).getPrimitiveObjectType();
    when(entity.getProperty(name)).thenReturn(prop);
    return entity;
  }

  private static void invokeCanonicalizeBooleanDefaults(JSONObject defaults, Entity entity)
      throws Exception {
    invokePrivate("canonicalizeBooleanDefaults",
        new Class<?>[]{ JSONObject.class, Entity.class }, defaults, entity);
  }

  @Test
  public void testCanonicalizeBooleanDefaultsRewritesStorageEncoding() throws Exception {
    JSONObject defaults = new JSONObject();
    defaults.put("etvfacSentToVerifac", "N");
    Entity entity = entityWithPrimitiveProperty("etvfacSentToVerifac", Boolean.class);

    invokeCanonicalizeBooleanDefaults(defaults, entity);

    // Not just "falsy": the value must be a JSON boolean, because "N" is truthy in JavaScript.
    assertEquals(Boolean.FALSE, defaults.get("etvfacSentToVerifac"));
  }

  @Test
  public void testCanonicalizeBooleanDefaultsRewritesYToTrue() throws Exception {
    JSONObject defaults = new JSONObject();
    defaults.put("printDiscount", "Y");
    Entity entity = entityWithPrimitiveProperty("printDiscount", Boolean.class);

    invokeCanonicalizeBooleanDefaults(defaults, entity);

    assertEquals(Boolean.TRUE, defaults.get("printDiscount"));
  }

  @Test
  public void testCanonicalizeBooleanDefaultsLeavesAlreadyBooleanValueUntouched() throws Exception {
    JSONObject defaults = new JSONObject();
    defaults.put("printDiscount", true);
    Entity entity = entityWithPrimitiveProperty("printDiscount", Boolean.class);

    invokeCanonicalizeBooleanDefaults(defaults, entity);

    assertEquals(Boolean.TRUE, defaults.get("printDiscount"));
  }

  @Test
  public void testCanonicalizeBooleanDefaultsLeavesNonBooleanPropertyUntouched() throws Exception {
    JSONObject defaults = new JSONObject();
    defaults.put("documentNo", "Y");
    Entity entity = entityWithPrimitiveProperty("documentNo", String.class);

    invokeCanonicalizeBooleanDefaults(defaults, entity);

    assertEquals("Y", defaults.get("documentNo"));
  }

  @Test
  public void testCanonicalizeBooleanDefaultsLeavesUnrecognizedShapeVerbatim() throws Exception {
    JSONObject defaults = new JSONObject();
    defaults.put("printDiscount", "banana");
    Entity entity = entityWithPrimitiveProperty("printDiscount", Boolean.class);

    invokeCanonicalizeBooleanDefaults(defaults, entity);

    // Guessing false would state something the ERP never stated — the value passes through.
    assertEquals("banana", defaults.get("printDiscount"));
  }

  @Test
  public void testCanonicalizeBooleanDefaultsSkipsEmptyString() throws Exception {
    JSONObject defaults = new JSONObject();
    defaults.put("printDiscount", "");
    Entity entity = entityWithPrimitiveProperty("printDiscount", Boolean.class);

    invokeCanonicalizeBooleanDefaults(defaults, entity);

    assertEquals("", defaults.get("printDiscount"));
  }

  @Test
  public void testCanonicalizeBooleanDefaultsSurvivesGetPropertyThrowing() throws Exception {
    // $_identifier companion keys are not properties at all; getProperty throws for them.
    JSONObject defaults = new JSONObject();
    defaults.put("businessPartner$_identifier", "Y");
    Entity entity = mock(Entity.class);
    when(entity.getName()).thenReturn("Invoice");
    when(entity.getProperty("businessPartner$_identifier"))
        .thenThrow(new RuntimeException("not a property"));

    invokeCanonicalizeBooleanDefaults(defaults, entity);

    assertEquals("Y", defaults.get("businessPartner$_identifier"));
  }

  @Test
  public void testCanonicalizeBooleanDefaultsNullArgumentsAreNoOp() throws Exception {
    // Neither call may throw; a null entity means "we cannot tell booleans apart", so do nothing.
    invokeCanonicalizeBooleanDefaults(null, mock(Entity.class));
    JSONObject defaults = new JSONObject();
    defaults.put("printDiscount", "Y");
    invokeCanonicalizeBooleanDefaults(defaults, null);
    assertEquals("Y", defaults.get("printDiscount"));
  }

  @Test
  public void testCanonicalizeBooleanDefaultsNormalizesMultipleFieldsIndependently()
      throws Exception {
    // The real defect: two boolean columns on the same entity, one already coerced by pass 1 and
    // one left as a string by the cascade. Both must end up as JSON booleans.
    JSONObject defaults = new JSONObject();
    defaults.put("printDiscount", "Y");
    defaults.put("etvfacSimpinvart7273", false);
    defaults.put("etvfacInvNoIDArt61d", "N");
    defaults.put("documentNo", "1000042");

    Entity entity = mock(Entity.class);
    when(entity.getName()).thenReturn("Invoice");
    Property boolProp = mock(Property.class);
    when(boolProp.isPrimitive()).thenReturn(true);
    doReturn(Boolean.class).when(boolProp).getPrimitiveObjectType();
    Property strProp = mock(Property.class);
    when(strProp.isPrimitive()).thenReturn(true);
    doReturn(String.class).when(strProp).getPrimitiveObjectType();
    when(entity.getProperty("printDiscount")).thenReturn(boolProp);
    when(entity.getProperty("etvfacSimpinvart7273")).thenReturn(boolProp);
    when(entity.getProperty("etvfacInvNoIDArt61d")).thenReturn(boolProp);
    when(entity.getProperty("documentNo")).thenReturn(strProp);

    invokeCanonicalizeBooleanDefaults(defaults, entity);

    assertEquals(Boolean.TRUE, defaults.get("printDiscount"));
    assertEquals(Boolean.FALSE, defaults.get("etvfacSimpinvart7273"));
    assertEquals(Boolean.FALSE, defaults.get("etvfacInvNoIDArt61d"));
    assertEquals("1000042", defaults.get("documentNo"));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // parseSQLExpression — via reflection
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testParseSQLExpressionBasicSubstitution() throws Exception {
    ArrayList<String> params = new ArrayList<>();
    String sql = NeoDefaultsSqlHelper.parseSQLExpression(
        "@SQL=SELECT name FROM ad_org WHERE ad_org_id = '@#AD_Org_ID@'", params);

    assertEquals("SELECT name FROM ad_org WHERE ad_org_id = ?", sql);
    assertEquals(1, params.size());
    assertEquals("#AD_Org_ID", params.get(0));
  }

  @Test
  public void testParseSQLExpressionMultipleParams() throws Exception {
    ArrayList<String> params = new ArrayList<>();
    String sql = NeoDefaultsSqlHelper.parseSQLExpression(
        "@SQL=SELECT id FROM t WHERE col1 = '@A@' AND col2 = '@B@'", params);

    assertEquals("SELECT id FROM t WHERE col1 = ? AND col2 = ?", sql);
    assertEquals(2, params.size());
    assertEquals("A", params.get(0));
    assertEquals("B", params.get(1));
  }

  @Test
  public void testParseSQLExpressionNoParams() throws Exception {
    ArrayList<String> params = new ArrayList<>();
    String sql = NeoDefaultsSqlHelper.parseSQLExpression(
        "@SQL=SELECT 1 FROM DUAL", params);

    assertEquals("SELECT 1 FROM DUAL", sql);
    assertTrue(params.isEmpty());
  }

  @Test
  public void testParseSQLExpressionNullReturnsEmpty() throws Exception {
    ArrayList<String> params = new ArrayList<>();
    String sql = NeoDefaultsSqlHelper.parseSQLExpression(
        null, params);

    assertEquals("", sql);
    assertTrue(params.isEmpty());
  }

  @Test
  public void testParseSQLExpressionEmptyReturnsEmpty() throws Exception {
    ArrayList<String> params = new ArrayList<>();
    String sql = NeoDefaultsSqlHelper.parseSQLExpression(
        "  ", params);

    assertEquals("", sql);
    assertTrue(params.isEmpty());
  }

  @Test
  public void testParseSQLExpressionUnpairedAtSign() throws Exception {
    ArrayList<String> params = new ArrayList<>();
    String sql = NeoDefaultsSqlHelper.parseSQLExpression(
        "@SQL=SELECT 1 WHERE x = @incomplete", params);

    // Unpaired @ — remainder appended
    assertNotNull(sql);
    assertTrue(params.isEmpty());
  }

  @Test
  public void testParseSQLExpressionParamWithoutQuotes() throws Exception {
    ArrayList<String> params = new ArrayList<>();
    String sql = NeoDefaultsSqlHelper.parseSQLExpression(
        "@SQL=SELECT id FROM t WHERE col = @MyParam@", params);

    assertEquals("SELECT id FROM t WHERE col = ?", sql);
    assertEquals(1, params.size());
    assertEquals("MyParam", params.get(0));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // isAuditColumn — via reflection
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testIsAuditColumnCreated() throws Exception {
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("Created");
    boolean result = (boolean) invokePrivate("isAuditColumn",
        new Class<?>[]{ Column.class }, col);
    assertTrue(result);
  }

  @Test
  public void testIsAuditColumnUpdated() throws Exception {
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("Updated");
    boolean result = (boolean) invokePrivate("isAuditColumn",
        new Class<?>[]{ Column.class }, col);
    assertTrue(result);
  }

  @Test
  public void testIsAuditColumnCreatedBy() throws Exception {
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("CreatedBy");
    boolean result = (boolean) invokePrivate("isAuditColumn",
        new Class<?>[]{ Column.class }, col);
    assertTrue(result);
  }

  @Test
  public void testIsAuditColumnUpdatedBy() throws Exception {
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("UpdatedBy");
    boolean result = (boolean) invokePrivate("isAuditColumn",
        new Class<?>[]{ Column.class }, col);
    assertTrue(result);
  }

  @Test
  public void testIsAuditColumnNonAudit() throws Exception {
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("Name");
    boolean result = (boolean) invokePrivate("isAuditColumn",
        new Class<?>[]{ Column.class }, col);
    assertFalse(result);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // isSequenceField — via reflection
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testIsSequenceFieldDocumentNo() throws Exception {
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("DocumentNo");
    when(col.isUseAutomaticSequence()).thenReturn(false);

    try (MockedStatic<SequenceUtils> seqMock = mockStatic(SequenceUtils.class)) {
      seqMock.when(() -> SequenceUtils.isSequence(col)).thenReturn(false);

      boolean result = (boolean) invokePrivate("isSequenceField",
          new Class<?>[]{ Column.class }, col);
      assertTrue("DocumentNo should be a sequence field", result);
    }
  }

  @Test
  public void testIsSequenceFieldValueWithAutoSequence() throws Exception {
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("Value");
    when(col.isUseAutomaticSequence()).thenReturn(true);

    try (MockedStatic<SequenceUtils> seqMock = mockStatic(SequenceUtils.class)) {
      seqMock.when(() -> SequenceUtils.isSequence(col)).thenReturn(false);

      boolean result = (boolean) invokePrivate("isSequenceField",
          new Class<?>[]{ Column.class }, col);
      assertTrue("Value with autoSequence should be a sequence field", result);
    }
  }

  @Test
  public void testIsSequenceFieldValueWithoutAutoSequence() throws Exception {
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("Value");
    when(col.isUseAutomaticSequence()).thenReturn(false);

    try (MockedStatic<SequenceUtils> seqMock = mockStatic(SequenceUtils.class)) {
      seqMock.when(() -> SequenceUtils.isSequence(col)).thenReturn(false);

      boolean result = (boolean) invokePrivate("isSequenceField",
          new Class<?>[]{ Column.class }, col);
      assertFalse("Value without autoSequence should not be a sequence field", result);
    }
  }

  @Test
  public void testIsSequenceFieldBySequenceUtils() throws Exception {
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("SomeCustomField");
    when(col.isUseAutomaticSequence()).thenReturn(false);

    try (MockedStatic<SequenceUtils> seqMock = mockStatic(SequenceUtils.class)) {
      seqMock.when(() -> SequenceUtils.isSequence(col)).thenReturn(true);

      boolean result = (boolean) invokePrivate("isSequenceField",
          new Class<?>[]{ Column.class }, col);
      assertTrue("SequenceUtils.isSequence=true should make it a sequence field", result);
    }
  }

  @Test
  public void testIsSequenceFieldRegularColumn() throws Exception {
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("Name");
    when(col.isUseAutomaticSequence()).thenReturn(false);

    try (MockedStatic<SequenceUtils> seqMock = mockStatic(SequenceUtils.class)) {
      seqMock.when(() -> SequenceUtils.isSequence(col)).thenReturn(false);

      boolean result = (boolean) invokePrivate("isSequenceField",
          new Class<?>[]{ Column.class }, col);
      assertFalse("Regular column should not be a sequence field", result);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // resolveWindowId — via reflection
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testResolveWindowIdWithSpec() throws Exception {
    SFEntity sfEntity = mock(SFEntity.class);
    SFSpec sfSpec = mock(SFSpec.class);
    Window window = mock(Window.class);

    when(sfEntity.getETGOSFSpec()).thenReturn(sfSpec);
    when(sfSpec.getADWindow()).thenReturn(window);
    when(window.getId()).thenReturn("WIN-123");

    String result = (String) invokePrivate("resolveWindowId",
        new Class<?>[]{ SFEntity.class }, sfEntity);
    assertEquals("WIN-123", result);
  }

  @Test
  public void testResolveWindowIdNullSpec() throws Exception {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getETGOSFSpec()).thenReturn(null);

    String result = (String) invokePrivate("resolveWindowId",
        new Class<?>[]{ SFEntity.class }, sfEntity);
    assertEquals("", result);
  }

  @Test
  public void testResolveWindowIdNullWindow() throws Exception {
    SFEntity sfEntity = mock(SFEntity.class);
    SFSpec sfSpec = mock(SFSpec.class);
    when(sfEntity.getETGOSFSpec()).thenReturn(sfSpec);
    when(sfSpec.getADWindow()).thenReturn(null);

    String result = (String) invokePrivate("resolveWindowId",
        new Class<?>[]{ SFEntity.class }, sfEntity);
    assertEquals("", result);
  }

  @Test
  public void testResolveWindowIdExceptionReturnsEmpty() throws Exception {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getETGOSFSpec()).thenThrow(new RuntimeException("boom"));

    String result = (String) invokePrivate("resolveWindowId",
        new Class<?>[]{ SFEntity.class }, sfEntity);
    assertEquals("", result);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // isFICComboReference — via reflection
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testIsFICComboReferenceTableDir() throws Exception {
    boolean result = (boolean) invokePrivate("isFICComboReference",
        new Class<?>[]{ String.class }, "19");
    assertTrue(result);
  }

  @Test
  public void testIsFICComboReferenceTable() throws Exception {
    boolean result = (boolean) invokePrivate("isFICComboReference",
        new Class<?>[]{ String.class }, "18");
    assertTrue(result);
  }

  @Test
  public void testIsFICComboReferenceList() throws Exception {
    boolean result = (boolean) invokePrivate("isFICComboReference",
        new Class<?>[]{ String.class }, "17");
    assertTrue(result);
  }

  @Test
  public void testIsFICComboReferenceSearchIsNot() throws Exception {
    boolean result = (boolean) invokePrivate("isFICComboReference",
        new Class<?>[]{ String.class }, "30");
    assertFalse(result);
  }

  @Test
  public void testIsFICComboReferenceNullIsNot() throws Exception {
    boolean result = (boolean) invokePrivate("isFICComboReference",
        new Class<?>[]{ String.class }, (Object) null);
    assertFalse(result);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // tryInjectIdentifier — via reflection
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testTryInjectIdentifierNullDalEntityNoOp() throws Exception {
    JSONObject defaults = new JSONObject();
    invokePrivate("tryInjectIdentifier",
        new Class<?>[]{ JSONObject.class, Entity.class, String.class, Object.class },
        defaults, null, "field", "value");
    assertFalse(defaults.has("field$_identifier"));
  }

  @Test
  public void testTryInjectIdentifierNullValueNoOp() throws Exception {
    JSONObject defaults = new JSONObject();
    Entity entity = mock(Entity.class);
    invokePrivate("tryInjectIdentifier",
        new Class<?>[]{ JSONObject.class, Entity.class, String.class, Object.class },
        defaults, entity, "field", null);
    assertFalse(defaults.has("field$_identifier"));
  }

  @Test
  public void testTryInjectIdentifierPrimitivePropertyNoOp() throws Exception {
    JSONObject defaults = new JSONObject();
    Entity entity = mock(Entity.class);
    Property prop = mock(Property.class);
    when(entity.getProperty("field")).thenReturn(prop);
    when(prop.isPrimitive()).thenReturn(true);

    invokePrivate("tryInjectIdentifier",
        new Class<?>[]{ JSONObject.class, Entity.class, String.class, Object.class },
        defaults, entity, "field", "value");
    assertFalse(defaults.has("field$_identifier"));
  }

  @Test
  public void testTryInjectIdentifierFKPropertyInjectsIdentifier() throws Exception {
    JSONObject defaults = new JSONObject();
    Entity entity = mock(Entity.class);
    Property prop = mock(Property.class);
    Entity targetEntity = mock(Entity.class);
    BaseOBObject targetObj = mock(BaseOBObject.class);
    OBDal dal = mock(OBDal.class);

    when(entity.getProperty("businessPartner")).thenReturn(prop);
    when(prop.isPrimitive()).thenReturn(false);
    when(prop.getTargetEntity()).thenReturn(targetEntity);
    when(targetEntity.getName()).thenReturn("BusinessPartner");
    when(dal.get("BusinessPartner", "BP-123")).thenReturn(targetObj);
    when(targetObj.getIdentifier()).thenReturn("Acme Corp");

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      invokePrivate("tryInjectIdentifier",
          new Class<?>[]{ JSONObject.class, Entity.class, String.class, Object.class },
          defaults, entity, "businessPartner", "BP-123");

      assertTrue(defaults.has("businessPartner$_identifier"));
      assertEquals("Acme Corp", defaults.getString("businessPartner$_identifier"));
    }
  }

  @Test
  public void testTryInjectIdentifierNullTargetEntityNoOp() throws Exception {
    JSONObject defaults = new JSONObject();
    Entity entity = mock(Entity.class);
    Property prop = mock(Property.class);

    when(entity.getProperty("field")).thenReturn(prop);
    when(prop.isPrimitive()).thenReturn(false);
    when(prop.getTargetEntity()).thenReturn(null);

    invokePrivate("tryInjectIdentifier",
        new Class<?>[]{ JSONObject.class, Entity.class, String.class, Object.class },
        defaults, entity, "field", "val");
    assertFalse(defaults.has("field$_identifier"));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // resolveDocTypeIdsFromDefaults — via reflection
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testResolveDocTypeIdsFromDefaultsNullEntity() throws Exception {
    JSONObject defaults = new JSONObject();
    String[] result = (String[]) invokePrivate("resolveDocTypeIdsFromDefaults",
        new Class<?>[]{ JSONObject.class, Entity.class }, defaults, null);
    assertEquals("", result[0]);
    assertEquals("", result[1]);
  }

  @Test
  public void testResolveDocTypeIdsFromDefaultsWithDocTypes() throws Exception {
    JSONObject defaults = new JSONObject();
    defaults.put("documentType", "DT-TARGET-123");
    defaults.put("transactionDocument", "DT-123");

    Entity dalEntity = mock(Entity.class);
    Property docTypeTargetProp = mock(Property.class);
    when(docTypeTargetProp.getName()).thenReturn("documentType");
    Property docTypeProp = mock(Property.class);
    when(docTypeProp.getName()).thenReturn("transactionDocument");

    when(dalEntity.getPropertyByColumnName("C_DocTypeTarget_ID"))
        .thenReturn(docTypeTargetProp);
    when(dalEntity.getPropertyByColumnName("C_DocType_ID"))
        .thenReturn(docTypeProp);

    String[] result = (String[]) invokePrivate("resolveDocTypeIdsFromDefaults",
        new Class<?>[]{ JSONObject.class, Entity.class }, defaults, dalEntity);

    assertEquals("DT-TARGET-123", result[0]);
    assertEquals("DT-123", result[1]);
  }

  @Test
  public void testResolveDocTypeIdsSkipsLegacyZeroDocType() throws Exception {
    JSONObject defaults = new JSONObject();
    defaults.put("transactionDocument", "0");

    Entity dalEntity = mock(Entity.class);
    Property docTypeProp = mock(Property.class);
    when(docTypeProp.getName()).thenReturn("transactionDocument");
    when(dalEntity.getPropertyByColumnName("C_DocType_ID")).thenReturn(docTypeProp);
    when(dalEntity.getPropertyByColumnName("C_DocTypeTarget_ID"))
        .thenThrow(new RuntimeException("no such property"));

    String[] result = (String[]) invokePrivate("resolveDocTypeIdsFromDefaults",
        new Class<?>[]{ JSONObject.class, Entity.class }, defaults, dalEntity);

    assertEquals("", result[0]);
    assertEquals("", result[1]); // "0" is skipped
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // findMissingMandatoryFields — two-arg backward-compatible overload
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testFindMissingMandatoryFieldsTwoArgOverloadChecksAll() throws Exception {
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    Entity dalEntity = mock(Entity.class);

    Column col = mockColumn("C_BPartner_ID", true, false, true);
    Reference ref = mock(Reference.class);
    when(ref.getId()).thenReturn("19");
    when(col.getReference()).thenReturn(ref);
    Property prop = mock(Property.class);
    when(prop.isAuditInfo()).thenReturn(false);
    when(prop.getName()).thenReturn("businessPartner");
    when(dalEntity.getPropertyByColumnName("C_BPartner_ID")).thenReturn(prop);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-1");
    when(table.getADColumnList()).thenReturn(Collections.singletonList(col));

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class)) {
      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-1")).thenReturn(dalEntity);

      // Two-arg overload should check all mandatory columns (no filter)
      List<String> missing = NeoMandatoryFieldValidator.findMissingMandatoryFields(
          new JSONObject(), adTab);

      assertEquals(1, missing.size());
      assertEquals("businessPartner", missing.get(0));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // findMissingMandatoryFields — Integer reference (11) is skipped
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testFindMissingMandatoryFieldsSkipsIntegerRef() {
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    Entity dalEntity = mock(Entity.class);

    Column col = mockColumn("Line", true, false, true);
    Reference ref = mock(Reference.class);
    when(ref.getId()).thenReturn("11"); // Integer
    when(col.getReference()).thenReturn(ref);
    Property prop = mock(Property.class);
    when(prop.isAuditInfo()).thenReturn(false);
    when(prop.getName()).thenReturn("lineNo");
    when(dalEntity.getPropertyByColumnName("Line")).thenReturn(prop);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-1");
    when(table.getADColumnList()).thenReturn(Collections.singletonList(col));

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class)) {
      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-1")).thenReturn(dalEntity);

      List<String> missing = NeoMandatoryFieldValidator.findMissingMandatoryFields(
          new JSONObject(), adTab);

      assertTrue("Integer fields should be skipped", missing.isEmpty());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // findMissingMandatoryFields — Amount reference (12) is skipped
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testFindMissingMandatoryFieldsSkipsAmountRef() {
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    Entity dalEntity = mock(Entity.class);

    Column col = mockColumn("GrandTotal", true, false, true);
    Reference ref = mock(Reference.class);
    when(ref.getId()).thenReturn("12"); // Amount
    when(col.getReference()).thenReturn(ref);
    Property prop = mock(Property.class);
    when(prop.isAuditInfo()).thenReturn(false);
    when(prop.getName()).thenReturn("grandTotalAmount");
    when(dalEntity.getPropertyByColumnName("GrandTotal")).thenReturn(prop);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-1");
    when(table.getADColumnList()).thenReturn(Collections.singletonList(col));

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class)) {
      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-1")).thenReturn(dalEntity);

      List<String> missing = NeoMandatoryFieldValidator.findMissingMandatoryFields(
          new JSONObject(), adTab);

      assertTrue("Amount fields should be skipped", missing.isEmpty());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // findMissingMandatoryFields — Quantity reference (29) is skipped
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testFindMissingMandatoryFieldsSkipsQuantityRef() {
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    Entity dalEntity = mock(Entity.class);

    Column col = mockColumn("QtyOrdered", true, false, true);
    Reference ref = mock(Reference.class);
    when(ref.getId()).thenReturn("29"); // Quantity
    when(col.getReference()).thenReturn(ref);
    Property prop = mock(Property.class);
    when(prop.isAuditInfo()).thenReturn(false);
    when(prop.getName()).thenReturn("orderedQuantity");
    when(dalEntity.getPropertyByColumnName("QtyOrdered")).thenReturn(prop);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-1");
    when(table.getADColumnList()).thenReturn(Collections.singletonList(col));

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class)) {
      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-1")).thenReturn(dalEntity);

      List<String> missing = NeoMandatoryFieldValidator.findMissingMandatoryFields(
          new JSONObject(), adTab);

      assertTrue("Quantity fields should be skipped", missing.isEmpty());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // findMissingMandatoryFields — null property skipped
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testFindMissingMandatoryFieldsNullPropertySkipped() {
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    Entity dalEntity = mock(Entity.class);

    Column col = mockColumn("Phantom_ID", true, false, true);
    when(dalEntity.getPropertyByColumnName("Phantom_ID")).thenReturn(null);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-1");
    when(table.getADColumnList()).thenReturn(Collections.singletonList(col));

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class)) {
      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-1")).thenReturn(dalEntity);

      List<String> missing = NeoMandatoryFieldValidator.findMissingMandatoryFields(
          new JSONObject(), adTab);

      assertTrue("Null property should be skipped", missing.isEmpty());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // resolveDbColumnDefault — via reflection
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testResolveDbColumnDefaultQuotedValue() throws Exception {
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(dal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getString(1)).thenReturn("'N'::character varying");

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      String result = NeoDefaultsSqlHelper.resolveDbColumnDefault("C_Order", "IsActive");

      assertEquals("N", result);
    }
  }

  @Test
  public void testResolveDbColumnDefaultNullResult() throws Exception {
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(dal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getString(1)).thenReturn(null);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      String result = NeoDefaultsSqlHelper.resolveDbColumnDefault("C_Order", "Description");

      assertNull(result);
    }
  }

  @Test
  public void testResolveDbColumnDefaultEmptyResult() throws Exception {
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(dal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getString(1)).thenReturn("");

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      String result = NeoDefaultsSqlHelper.resolveDbColumnDefault("C_Order", "Description");

      assertNull(result);
    }
  }

  @Test
  public void testResolveDbColumnDefaultNoRow() throws Exception {
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(dal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      String result = NeoDefaultsSqlHelper.resolveDbColumnDefault("C_Order", "Description");

      assertNull(result);
    }
  }

  @Test
  public void testResolveDbColumnDefaultCastNotation() throws Exception {
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(dal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    // PostgreSQL-style cast: 0::numeric
    when(rs.getString(1)).thenReturn("0::numeric");

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      String result = NeoDefaultsSqlHelper.resolveDbColumnDefault("C_Order", "Line");

      assertEquals("0", result);
    }
  }

  @Test
  public void testResolveDbColumnDefaultSqlExceptionReturnsNull() throws Exception {
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenThrow(new java.sql.SQLException("conn error"));
    when(dal.getConnection(false)).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      String result = NeoDefaultsSqlHelper.resolveDbColumnDefault("C_Order", "Line");

      assertNull(result);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // resolveOrFirstComboOption — via reflection
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testResolveOrFirstComboOptionReturnsResolvedWhenNotNull() throws Exception {
    NeoContext ctx = NeoContext.builder()
        .sfEntity(mock(SFEntity.class))
        .obContext(mock(OBContext.class))
        .build();
    Column column = mock(Column.class);

    // When resolved is non-null it must be returned verbatim, without touching the selector.
    try (MockedStatic<NeoSelectorService> selectorMock = mockStatic(NeoSelectorService.class)) {
      Object result = invokePrivate("resolveOrFirstComboOption",
          new Class<?>[]{ NeoContext.class, Column.class, Object.class },
          ctx, column, "ALREADY-RESOLVED");

      assertEquals("ALREADY-RESOLVED", result);
      selectorMock.verify(() -> NeoSelectorService.getBaseReferenceId(any(Column.class)),
          never());
    }
  }

  @Test
  public void testResolveOrFirstComboOptionFallsBackToFirstComboOption() throws Exception {
    NeoContext ctx = NeoContext.builder()
        .sfEntity(mock(SFEntity.class))
        .obContext(mock(OBContext.class))
        .build();
    Column column = mock(Column.class);
    when(column.getDBColumnName()).thenReturn("C_Reject_Reason_ID");

    JSONObject item = new JSONObject();
    item.put("id", "FIRST-OPTION-ID");
    JSONArray items = new JSONArray();
    items.put(item);
    JSONObject selectorBody = new JSONObject();
    selectorBody.put("items", items);
    NeoResponse selectorResp = NeoResponse.ok(selectorBody);

    // baseRefId "17" is a List reference (FIC combo) so resolveFirstComboOption proceeds.
    try (MockedStatic<NeoSelectorService> selectorMock = mockStatic(NeoSelectorService.class)) {
      selectorMock.when(() -> NeoSelectorService.getBaseReferenceId(column)).thenReturn("17");
      selectorMock.when(() -> NeoSelectorService.hasObuiselSelector(column)).thenReturn(false);
      selectorMock.when(() -> NeoSelectorService.querySelectorByColumn(
          eq(column), eq("C_Reject_Reason_ID"), eq(null), eq(1), eq(0), any()))
          .thenReturn(selectorResp);

      Object result = invokePrivate("resolveOrFirstComboOption",
          new Class<?>[]{ NeoContext.class, Column.class, Object.class },
          ctx, column, null);

      assertEquals("FIRST-OPTION-ID", result);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // getSfFieldColumns — via reflection
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void testGetSfFieldColumnsUpperCasesAndSkipsNulls() throws Exception {
    // Field with a valid lowercase column name → expected upper-cased.
    SFField fieldWithColumn = mock(SFField.class);
    Column adColumn = mock(Column.class);
    when(adColumn.getDBColumnName()).thenReturn("documentno");
    when(fieldWithColumn.getADColumn()).thenReturn(adColumn);

    // Field whose adColumn is null → skipped.
    SFField fieldNullColumn = mock(SFField.class);
    when(fieldNullColumn.getADColumn()).thenReturn(null);

    // A null SFField entry → skipped.
    List<SFField> fields = Arrays.asList(fieldWithColumn, fieldNullColumn, null);

    Set<String> result = (Set<String>) invokePrivate("getSfFieldColumns",
        new Class<?>[]{ List.class }, fields);

    assertEquals("Only the field with a column name should be included", 1, result.size());
    assertTrue("Column name must be upper-cased", result.contains("DOCUMENTNO"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testGetSfFieldColumnsHandlesNullList() throws Exception {
    Set<String> result = (Set<String>) invokePrivate("getSfFieldColumns",
        new Class<?>[]{ List.class }, new Object[]{ null });

    assertNotNull(result);
    assertTrue("Result should be empty for a null field list", result.isEmpty());
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // isColumnReferencingParentTab — Issue 1 fix: discriminate FK target entities
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Helper to invoke the private static {@code isColumnReferencingParentTab} via reflection.
   */
  private static boolean invokeIsColumnReferencingParentTab(Column column, NeoContext ctx)
      throws Exception {
    return (Boolean) invokePrivate(
        "isColumnReferencingParentTab",
        new Class<?>[]{ Column.class, NeoContext.class },
        column, ctx);
  }

  /**
   * IRCPT-1: ctx is null → returns true (permissive fallback).
   */
  @Test
  public void testIsColumnReferencingParentTabNullCtxReturnsTrue() throws Exception {
    Column column = mock(Column.class);
    when(column.getDBColumnName()).thenReturn("A_Amortization_ID");

    boolean result = invokeIsColumnReferencingParentTab(column, null);

    assertTrue("null ctx should fall back to true", result);
  }

  /**
   * IRCPT-2: ctx.getAdTab() is null → returns true (permissive fallback).
   */
  @Test
  public void testIsColumnReferencingParentTabNullTabReturnsTrue() throws Exception {
    Column column = mock(Column.class);
    when(column.getDBColumnName()).thenReturn("A_Amortization_ID");

    NeoContext ctx = NeoContext.builder().adTab(null).build();

    boolean result = invokeIsColumnReferencingParentTab(column, ctx);

    assertTrue("null adTab should fall back to true", result);
  }

  /**
   * IRCPT-3: column's target entity matches parent tab's table entity → true (the real FK case).
   */
  @Test
  public void testIsColumnReferencingParentTabMatchingEntityReturnsTrue() throws Exception {
    Column column = mock(Column.class);
    when(column.getDBColumnName()).thenReturn("A_Amortization_ID");

    Tab childTab = mock(Tab.class);
    Table childTable = mock(Table.class);
    when(childTab.getTable()).thenReturn(childTable);
    when(childTable.getId()).thenReturn("CHILD-TABLE-ID");

    Tab parentTab = mock(Tab.class);
    Table parentTable = mock(Table.class);
    when(parentTab.getTable()).thenReturn(parentTable);
    when(parentTable.getId()).thenReturn("PARENT-TABLE-ID");

    NeoContext ctx = NeoContext.builder().adTab(childTab).build();

    // The shared entity instance: parentEntity == prop.getTargetEntity()
    Entity sharedEntity = mock(Entity.class);
    Entity childEntity = mock(Entity.class);
    Property prop = mock(Property.class);
    when(prop.getTargetEntity()).thenReturn(sharedEntity);
    when(childEntity.getPropertyByColumnName("A_Amortization_ID", false)).thenReturn(prop);

    try (MockedStatic<KernelUtils> kernelMock = mockStatic(KernelUtils.class);
         MockedStatic<ModelProvider> modelProviderMock = mockStatic(ModelProvider.class)) {

      KernelUtils kernelUtils = mock(KernelUtils.class);
      kernelMock.when(KernelUtils::getInstance).thenReturn(kernelUtils);
      when(kernelUtils.getParentTab(childTab)).thenReturn(parentTab);

      ModelProvider mp = mock(ModelProvider.class);
      modelProviderMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("PARENT-TABLE-ID")).thenReturn(sharedEntity);
      when(mp.getEntityByTableId("CHILD-TABLE-ID")).thenReturn(childEntity);

      boolean result = invokeIsColumnReferencingParentTab(column, ctx);

      assertTrue("matching target entity should return true", result);
    }
  }

  // buildSfFieldDefaultsMap — via reflection
  //
  // Verifies the CREATE path uses ETGO_SF_FIELD.defaultvalue overrides and that
  // columns without an SFField entry fall back to the AD_Column default.
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * When an SFField has a non-blank defaultValue, buildSfFieldDefaultsMap must include it
   * keyed by the DB column name in upper-case.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testBuildSfFieldDefaultsMapIncludesNonBlankDefaultValues() throws Exception {
    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBCriteria<SFField> fieldCriteria = mock(OBCriteria.class);

    SFField sfField = mock(SFField.class);
    Column adColumn = mock(Column.class);
    when(adColumn.getDBColumnName()).thenReturn("CalculateType");
    when(sfField.getADColumn()).thenReturn(adColumn);
    // ETGO_SF_FIELD override "TI" — the AD_Column default would be "PE"
    when(sfField.getDefaultValue()).thenReturn("TI");

    when(fieldCriteria.add(any())).thenReturn(fieldCriteria);
    when(fieldCriteria.list()).thenReturn(Collections.singletonList(sfField));
    when(obDal.createCriteria(SFField.class)).thenReturn(fieldCriteria);

    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getId()).thenReturn("entity-assets");

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(mock(OBContext.class))
        .build();

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      ctxMock.when(() -> OBContext.setAdminMode(true)).thenAnswer(inv -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      Map<String, String> result = (Map<String, String>) invokePrivate(
          "buildSfFieldDefaultsMap",
          new Class<?>[]{ NeoContext.class }, ctx);

      assertNotNull(result);
      assertEquals("Should contain exactly one entry", 1, result.size());
      // Key must be upper-cased DB column name; value must be the SFField override
      assertEquals("TI", result.get("CALCULATETYPE"));
    }
  }

  /**
   * When an SFField has a blank or null defaultValue, buildSfFieldDefaultsMap must NOT include
   * it — blank entries must be absent so resolveFieldDefault falls back to AD_Column default.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testBuildSfFieldDefaultsMapExcludesBlankAndNullDefaultValues() throws Exception {
    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBCriteria<SFField> fieldCriteria = mock(OBCriteria.class);

    // SFField with null defaultValue
    SFField sfFieldNull = mock(SFField.class);
    Column colNull = mock(Column.class);
    when(colNull.getDBColumnName()).thenReturn("Depreciate");
    when(sfFieldNull.getADColumn()).thenReturn(colNull);
    when(sfFieldNull.getDefaultValue()).thenReturn(null);

    // SFField with blank defaultValue
    SFField sfFieldBlank = mock(SFField.class);
    Column colBlank = mock(Column.class);
    when(colBlank.getDBColumnName()).thenReturn("DocStatus");
    when(sfFieldBlank.getADColumn()).thenReturn(colBlank);
    when(sfFieldBlank.getDefaultValue()).thenReturn("   ");

    when(fieldCriteria.add(any())).thenReturn(fieldCriteria);
    when(fieldCriteria.list()).thenReturn(Arrays.asList(sfFieldNull, sfFieldBlank));
    when(obDal.createCriteria(SFField.class)).thenReturn(fieldCriteria);

    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getId()).thenReturn("entity-1");

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(mock(OBContext.class))
        .build();

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      ctxMock.when(() -> OBContext.setAdminMode(true)).thenAnswer(inv -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      Map<String, String> result = (Map<String, String>) invokePrivate(
          "buildSfFieldDefaultsMap",
          new Class<?>[]{ NeoContext.class }, ctx);

      assertNotNull(result);
      assertTrue("Blank/null SFField defaults must not be included", result.isEmpty());
    }
  }

  /**
   * When ctx or ctx.getSfEntity() is null, buildSfFieldDefaultsMap returns an empty map
   * (no NPE) and the caller falls back entirely to AD_Column defaults.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testBuildSfFieldDefaultsMapReturnsEmptyWhenCtxOrEntityIsNull() throws Exception {
    // null ctx
    Map<String, String> resultNullCtx = (Map<String, String>) invokePrivate(
        "buildSfFieldDefaultsMap",
        new Class<?>[]{ NeoContext.class }, new Object[]{ null });
    assertNotNull(resultNullCtx);
    assertTrue(resultNullCtx.isEmpty());

    // ctx with null sfEntity
    NeoContext ctxNullEntity = NeoContext.builder()
        .sfEntity(null)
        .obContext(mock(OBContext.class))
        .build();
    Map<String, String> resultNullEntity = (Map<String, String>) invokePrivate(
        "buildSfFieldDefaultsMap",
        new Class<?>[]{ NeoContext.class }, ctxNullEntity);
    assertNotNull(resultNullEntity);
    assertTrue(resultNullEntity.isEmpty());
  }

  /**
   * When an SFField has a null AD_Column reference, buildSfFieldDefaultsMap skips that entry
   * (null-safe) so it does not throw and the other valid entries are still included.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testBuildSfFieldDefaultsMapSkipsNullAdColumn() throws Exception {
    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBCriteria<SFField> fieldCriteria = mock(OBCriteria.class);

    // SFField with null adColumn — must be skipped
    SFField sfFieldNullCol = mock(SFField.class);
    when(sfFieldNullCol.getADColumn()).thenReturn(null);
    when(sfFieldNullCol.getDefaultValue()).thenReturn("TI");

    // SFField with a valid adColumn — must be included
    SFField sfFieldValid = mock(SFField.class);
    Column validCol = mock(Column.class);
    when(validCol.getDBColumnName()).thenReturn("CalculateType");
    when(sfFieldValid.getADColumn()).thenReturn(validCol);
    when(sfFieldValid.getDefaultValue()).thenReturn("TI");

    when(fieldCriteria.add(any())).thenReturn(fieldCriteria);
    when(fieldCriteria.list()).thenReturn(Arrays.asList(sfFieldNullCol, sfFieldValid));
    when(obDal.createCriteria(SFField.class)).thenReturn(fieldCriteria);

    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getId()).thenReturn("entity-1");

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(mock(OBContext.class))
        .build();

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      ctxMock.when(() -> OBContext.setAdminMode(true)).thenAnswer(inv -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      Map<String, String> result = (Map<String, String>) invokePrivate(
          "buildSfFieldDefaultsMap",
          new Class<?>[]{ NeoContext.class }, ctx);

      assertEquals("Only the valid SFField entry should be included", 1, result.size());
      assertEquals("TI", result.get("CALCULATETYPE"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // injectMandatoryDefaults — ETGO_SF_FIELD default honoured on CREATE path
  //
  // These are the regression tests for the bug described in ETP-4230:
  // the CREATE path must use ETGO_SF_FIELD.defaultvalue (not AD_Column default)
  // for columns that have a per-window override, exactly as /defaults does.
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * When a column has an ETGO_SF_FIELD default ("TI"), the CREATE path must inject "TI",
   * NOT the AD_Column default ("PE").  This is the core regression test for ETP-4230.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testInjectMandatoryDefaultsUsesSfFieldDefaultOverAdColumnDefault()
      throws Exception {
    // --- Setup ---
    JSONObject body = new JSONObject();
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    SFEntity sfEntity = mock(SFEntity.class);
    OBContext obContext = mock(OBContext.class);
    Entity dalEntity = mock(Entity.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    OBDal obDal = mock(OBDal.class);

    // The AD column: DB name "CalculateType", mandatory, NOT a key/audit column.
    Column calcTypeCol = mock(Column.class);
    when(calcTypeCol.getDBColumnName()).thenReturn("CalculateType");
    when(calcTypeCol.isMandatory()).thenReturn(true);
    when(calcTypeCol.isActive()).thenReturn(true);
    when(calcTypeCol.isKeyColumn()).thenReturn(false);
    // AD_Column default is "PE" — this is what the bug returned before the fix
    when(calcTypeCol.getDefaultValue()).thenReturn("PE");
    when(calcTypeCol.isLinkToParentColumn()).thenReturn(false);
    when(calcTypeCol.isUseAutomaticSequence()).thenReturn(false);

    // DAL property
    Property calcTypeProp = mock(Property.class);
    when(calcTypeProp.getName()).thenReturn("calculateType");
    when(calcTypeProp.isAuditInfo()).thenReturn(false);
    when(calcTypeProp.isPrimitive()).thenReturn(true);
    when(dalEntity.getPropertyByColumnName("CalculateType")).thenReturn(calcTypeProp);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-ASSETS");
    when(table.getADColumnList()).thenReturn(Collections.singletonList(calcTypeCol));

    // SFField for this column: defaultValue = "TI" (the ETGO_SF_FIELD override)
    SFField sfField = mock(SFField.class);
    when(sfField.getADColumn()).thenReturn(calcTypeCol);
    when(sfField.getDefaultValue()).thenReturn("TI");
    @SuppressWarnings("unchecked")
    OBCriteria<SFField> sfFieldCriteria = mock(OBCriteria.class);
    when(sfFieldCriteria.add(any())).thenReturn(sfFieldCriteria);
    when(sfFieldCriteria.list()).thenReturn(Collections.singletonList(sfField));
    when(obDal.createCriteria(SFField.class)).thenReturn(sfFieldCriteria);

    when(sfEntity.getId()).thenReturn("entity-assets");

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class);
         MockedStatic<SequenceUtils> sequenceMock = mockStatic(SequenceUtils.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class);
         MockedStatic<NeoParentValuesLoader> parentMock =
             mockStatic(NeoParentValuesLoader.class)) {

      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-ASSETS")).thenReturn(dalEntity);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      obContextMock.when(() -> OBContext.setAdminMode(true)).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, adTab)).thenReturn(vars);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper.resolveDalEntity(sfEntity))
          .thenReturn(dalEntity);
      sequenceMock.when(() -> SequenceUtils.isSequence(calcTypeCol)).thenReturn(false);
      parentMock.when(() -> NeoParentValuesLoader.load(adTab, null))
          .thenReturn(java.util.Collections.emptyMap());
      // Utility.getDefault called with "TI" (the SFField override), not "PE" (AD_Column default)
      utilityMock.when(() -> Utility.getDefault(any(), eq(vars), eq("CalculateType"),
          eq("TI"), anyString(), eq("")))
          .thenReturn("TI");

      NeoDefaultsService.injectMandatoryDefaults(body, adTab, ctx);

      assertTrue("calculateType must be injected", body.has("calculateType"));
      assertEquals(
          "CREATE path must use ETGO_SF_FIELD default 'TI', not AD_Column default 'PE'",
          "TI", body.getString("calculateType"));
    }
  }

  /**
   * IRCPT-4: column's target entity does NOT match parent tab's table entity → false.
   * This is the A_Asset_ID bug case — the column FK points to A_Asset, not A_Amortization.
   */
  @Test
  public void testIsColumnReferencingParentTabNonMatchingEntityReturnsFalse() throws Exception {
    Column column = mock(Column.class);
    when(column.getDBColumnName()).thenReturn("A_Asset_ID");

    Tab childTab = mock(Tab.class);
    Table childTable = mock(Table.class);
    when(childTab.getTable()).thenReturn(childTable);
    when(childTable.getId()).thenReturn("CHILD-TABLE-ID");

    Tab parentTab = mock(Tab.class);
    Table parentTable = mock(Table.class);
    when(parentTab.getTable()).thenReturn(parentTable);
    when(parentTable.getId()).thenReturn("PARENT-TABLE-ID");

    NeoContext ctx = NeoContext.builder().adTab(childTab).build();

    // parentEntity and the column's target entity are DIFFERENT mocks
    Entity parentEntity = mock(Entity.class);   // A_Amortization entity
    Entity assetEntity = mock(Entity.class);    // A_Asset entity — different instance
    Entity childEntity = mock(Entity.class);    // child line entity
    Property prop = mock(Property.class);
    when(prop.getTargetEntity()).thenReturn(assetEntity); // FK points to A_Asset
    when(childEntity.getPropertyByColumnName("A_Asset_ID", false)).thenReturn(prop);

    try (MockedStatic<KernelUtils> kernelMock = mockStatic(KernelUtils.class);
         MockedStatic<ModelProvider> modelProviderMock = mockStatic(ModelProvider.class)) {

      KernelUtils kernelUtils = mock(KernelUtils.class);
      kernelMock.when(KernelUtils::getInstance).thenReturn(kernelUtils);
      when(kernelUtils.getParentTab(childTab)).thenReturn(parentTab);

      ModelProvider mp = mock(ModelProvider.class);
      modelProviderMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("PARENT-TABLE-ID")).thenReturn(parentEntity);
      when(mp.getEntityByTableId("CHILD-TABLE-ID")).thenReturn(childEntity);

      boolean result = invokeIsColumnReferencingParentTab(column, ctx);

      assertFalse("A_Asset_ID pointing to A_Asset should NOT match the parent A_Amortization entity",
          result);
    }
  }

  /**
   * IRCPT-5: getParentTab throws → returns true (error fallback, preserve legacy behavior).
   */
  @Test
  public void testIsColumnReferencingParentTabExceptionReturnsTrue() throws Exception {
    Column column = mock(Column.class);
    when(column.getDBColumnName()).thenReturn("A_Amortization_ID");

    Tab childTab = mock(Tab.class);
    NeoContext ctx = NeoContext.builder().adTab(childTab).build();

    try (MockedStatic<KernelUtils> kernelMock = mockStatic(KernelUtils.class)) {
      KernelUtils kernelUtils = mock(KernelUtils.class);
      kernelMock.when(KernelUtils::getInstance).thenReturn(kernelUtils);
      when(kernelUtils.getParentTab(childTab)).thenThrow(new RuntimeException("KernelUtils boom"));

      boolean result = invokeIsColumnReferencingParentTab(column, ctx);

      assertTrue("exception during resolution should fall back to true", result);
    }
  }

  /**
   * When a column has NO ETGO_SF_FIELD default configured, the CREATE path must fall back to
   * the AD_Column default — verifying no regression for the common case.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testInjectMandatoryDefaultsFallsBackToAdColumnDefaultWhenNoSfFieldDefault()
      throws Exception {
    JSONObject body = new JSONObject();
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    SFEntity sfEntity = mock(SFEntity.class);
    OBContext obContext = mock(OBContext.class);
    Entity dalEntity = mock(Entity.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    OBDal obDal = mock(OBDal.class);

    // AD column with a default of "DR"
    Column docStatusCol = mock(Column.class);
    when(docStatusCol.getDBColumnName()).thenReturn("DocStatus");
    when(docStatusCol.isMandatory()).thenReturn(true);
    when(docStatusCol.isActive()).thenReturn(true);
    when(docStatusCol.isKeyColumn()).thenReturn(false);
    when(docStatusCol.getDefaultValue()).thenReturn("DR");
    when(docStatusCol.isLinkToParentColumn()).thenReturn(false);
    when(docStatusCol.isUseAutomaticSequence()).thenReturn(false);

    Property docStatusProp = mock(Property.class);
    when(docStatusProp.getName()).thenReturn("documentStatus");
    when(docStatusProp.isAuditInfo()).thenReturn(false);
    when(docStatusProp.isPrimitive()).thenReturn(true);
    when(dalEntity.getPropertyByColumnName("DocStatus")).thenReturn(docStatusProp);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-DOC");
    when(table.getADColumnList()).thenReturn(Collections.singletonList(docStatusCol));

    // SFField exists but has NO defaultValue (null) — AD_Column default must be used
    SFField sfField = mock(SFField.class);
    when(sfField.getADColumn()).thenReturn(docStatusCol);
    when(sfField.getDefaultValue()).thenReturn(null);
    @SuppressWarnings("unchecked")
    OBCriteria<SFField> sfFieldCriteria = mock(OBCriteria.class);
    when(sfFieldCriteria.add(any())).thenReturn(sfFieldCriteria);
    when(sfFieldCriteria.list()).thenReturn(Collections.singletonList(sfField));
    when(obDal.createCriteria(SFField.class)).thenReturn(sfFieldCriteria);

    when(sfEntity.getId()).thenReturn("entity-doc");

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class);
         MockedStatic<SequenceUtils> sequenceMock = mockStatic(SequenceUtils.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class);
         MockedStatic<NeoParentValuesLoader> parentMock =
             mockStatic(NeoParentValuesLoader.class)) {

      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-DOC")).thenReturn(dalEntity);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      obContextMock.when(() -> OBContext.setAdminMode(true)).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, adTab)).thenReturn(vars);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper.resolveDalEntity(sfEntity))
          .thenReturn(dalEntity);
      sequenceMock.when(() -> SequenceUtils.isSequence(docStatusCol)).thenReturn(false);
      parentMock.when(() -> NeoParentValuesLoader.load(adTab, null))
          .thenReturn(java.util.Collections.emptyMap());
      // Utility.getDefault is called with "DR" (the AD_Column default, because SFField has null)
      utilityMock.when(() -> Utility.getDefault(any(), eq(vars), eq("DocStatus"),
          eq("DR"), anyString(), eq("")))
          .thenReturn("DR");

      NeoDefaultsService.injectMandatoryDefaults(body, adTab, ctx);

      assertTrue("documentStatus must be injected", body.has("documentStatus"));
      assertEquals(
          "Without ETGO_SF_FIELD default, AD_Column default 'DR' must be used",
          "DR", body.getString("documentStatus"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // injectMandatoryDefaults — ETP-4274: non-mandatory column WITH a genuine
  // resolvable default IS injected on create (regression for the !isMandatory()
  // early-continue that previously skipped such columns entirely)
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void testInjectMandatoryDefaultsNonMandatoryWithSessionDefaultIsInjected()
      throws Exception {
    JSONObject body = new JSONObject();
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    OBContext obContext = mock(OBContext.class);
    SFEntity sfEntity = mock(SFEntity.class);
    Entity dalEntity = mock(Entity.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    OBDal obDal = mock(OBDal.class);

    // Non-mandatory FK column whose value resolves through the session-context pass.
    // The AD-default and parent-value passes both miss, so only the session pass
    // supplies a value for the currency property here.
    Column currencyCol = mock(Column.class);
    when(currencyCol.getDBColumnName()).thenReturn("C_Currency_ID");
    when(currencyCol.isMandatory()).thenReturn(false);
    when(currencyCol.isActive()).thenReturn(true);
    when(currencyCol.isKeyColumn()).thenReturn(false);
    when(currencyCol.getDefaultValue()).thenReturn(null);
    when(currencyCol.isLinkToParentColumn()).thenReturn(false);
    when(currencyCol.isUseAutomaticSequence()).thenReturn(false);

    Property currencyProp = mock(Property.class);
    when(currencyProp.getName()).thenReturn("currency");
    when(currencyProp.isAuditInfo()).thenReturn(false);
    when(dalEntity.getPropertyByColumnName("C_Currency_ID")).thenReturn(currencyProp);
    // tryInjectIdentifier looks up getProperty(propName); null => harmless no-op
    when(dalEntity.getProperty("currency")).thenReturn(null);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-CUR");
    when(table.getADColumnList()).thenReturn(Collections.singletonList(currencyCol));

    // Pass 1 (resolveFieldDefault) must yield null: no AD default, no preference, no doctype.
    // Pass 2 (session) returns the currency id under the "#C_Currency_ID" key.
    when(vars.getSessionValue("#C_Currency_ID")).thenReturn("CUR-EUR");

    OBCriteria<SFField> sfFieldCriteria = mock(OBCriteria.class);
    when(sfFieldCriteria.add(any())).thenReturn(sfFieldCriteria);
    when(sfFieldCriteria.list()).thenReturn(Collections.emptyList());
    when(obDal.createCriteria(SFField.class)).thenReturn(sfFieldCriteria);
    when(sfEntity.getId()).thenReturn("entity-cur");

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class);
         MockedStatic<SequenceUtils> sequenceMock = mockStatic(SequenceUtils.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class);
         MockedStatic<DocTypeResolver> docTypeMock = mockStatic(DocTypeResolver.class);
         MockedStatic<NeoParentValuesLoader> parentMock =
             mockStatic(NeoParentValuesLoader.class)) {
      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-CUR")).thenReturn(dalEntity);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      obContextMock.when(() -> OBContext.setAdminMode(true)).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, adTab)).thenReturn(vars);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper.resolveDalEntity(sfEntity))
          .thenReturn(dalEntity);
      sequenceMock.when(() -> SequenceUtils.isSequence(currencyCol)).thenReturn(false);
      utilityMock.when(() -> Utility.getPreference(eq(vars), eq("C_Currency_ID"), anyString()))
          .thenReturn(null);
      docTypeMock.when(() -> DocTypeResolver.resolveDefaultDocTypeId(eq(currencyCol), any()))
          .thenReturn(null);
      parentMock.when(() -> NeoParentValuesLoader.load(adTab, null))
          .thenReturn(java.util.Collections.emptyMap());

      NeoDefaultsService.injectMandatoryDefaults(body, adTab, ctx);

      assertTrue("Non-mandatory column with a session default must be injected (ETP-4274)",
          body.has("currency"));
      assertEquals("CUR-EUR", body.getString("currency"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // injectMandatoryDefaults — ETP-4274: a mandatory column with NO genuine
  // default still runs the safe-type fallback (NOT NULL parity is unchanged)
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void testInjectMandatoryDefaultsMandatoryWithoutDefaultRunsSafeTypeFallback() {
    JSONObject body = new JSONObject();
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    OBContext obContext = mock(OBContext.class);
    SFEntity sfEntity = mock(SFEntity.class);
    Entity dalEntity = mock(Entity.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    OBDal obDal = mock(OBDal.class);

    // Mandatory non-FK column with no AD default, no session, no parent value. Passes 1-3
    // fail; because it IS mandatory, execution must continue past the !mandatory gate and
    // reach the NOT-NULL fallbacks (combo lookup, then injectSafeTypeDefault).
    Column flagCol = mock(Column.class);
    when(flagCol.getDBColumnName()).thenReturn("ProcessingStatus");
    when(flagCol.isMandatory()).thenReturn(true);
    when(flagCol.isActive()).thenReturn(true);
    when(flagCol.isKeyColumn()).thenReturn(false);
    when(flagCol.getDefaultValue()).thenReturn(null);
    when(flagCol.isLinkToParentColumn()).thenReturn(false);
    when(flagCol.isUseAutomaticSequence()).thenReturn(false);

    Property flagProp = mock(Property.class);
    when(flagProp.getName()).thenReturn("processingStatus");
    when(flagProp.isAuditInfo()).thenReturn(false);
    when(dalEntity.getPropertyByColumnName("ProcessingStatus")).thenReturn(flagProp);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-FLAG");
    when(table.getADColumnList()).thenReturn(Collections.singletonList(flagCol));

    when(vars.getSessionValue(anyString())).thenReturn("");

    OBCriteria<SFField> sfFieldCriteria = mock(OBCriteria.class);
    when(sfFieldCriteria.add(any())).thenReturn(sfFieldCriteria);
    when(sfFieldCriteria.list()).thenReturn(Collections.emptyList());
    when(obDal.createCriteria(SFField.class)).thenReturn(sfFieldCriteria);
    when(sfEntity.getId()).thenReturn("entity-flag");

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class);
         MockedStatic<SequenceUtils> sequenceMock = mockStatic(SequenceUtils.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class);
         MockedStatic<DocTypeResolver> docTypeMock = mockStatic(DocTypeResolver.class);
         MockedStatic<NeoSelectorService> selectorMock = mockStatic(NeoSelectorService.class);
         MockedStatic<NeoParentValuesLoader> parentMock =
             mockStatic(NeoParentValuesLoader.class)) {
      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-FLAG")).thenReturn(dalEntity);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      obContextMock.when(() -> OBContext.setAdminMode(true)).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, adTab)).thenReturn(vars);
      sequenceMock.when(() -> SequenceUtils.isSequence(flagCol)).thenReturn(false);
      utilityMock.when(() -> Utility.getPreference(eq(vars), eq("ProcessingStatus"), anyString()))
          .thenReturn(null);
      docTypeMock.when(() -> DocTypeResolver.resolveDefaultDocTypeId(eq(flagCol), any()))
          .thenReturn(null);
      // Combo first-pick (pass 4) returns no combo reference, so pass 5 (safe-type) must run.
      selectorMock.when(() -> NeoSelectorService.getBaseReferenceId(flagCol)).thenReturn("10");
      parentMock.when(() -> NeoParentValuesLoader.load(adTab, null))
          .thenReturn(java.util.Collections.emptyMap());

      NeoDefaultsService.injectMandatoryDefaults(body, adTab, ctx);

      // The mandatory column must reach the safe-type fallback — proving the !mandatory gate
      // does NOT short-circuit mandatory columns (NOT NULL parity preserved).
      cascadeMock.verify(() -> NeoDefaultsCascadeHelper.injectSafeTypeDefault(
          eq(body), eq("processingStatus"), eq(flagCol)));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // injectMandatoryDefaults — ETP-4274: a user-supplied value always wins
  // (body.has(propName) early-return), even for a non-mandatory column
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void testInjectMandatoryDefaultsUserValueWinsForNonMandatoryColumn() throws Exception {
    JSONObject body = new JSONObject();
    body.put("currency", "USER-USD");

    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    OBContext obContext = mock(OBContext.class);
    SFEntity sfEntity = mock(SFEntity.class);
    Entity dalEntity = mock(Entity.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    OBDal obDal = mock(OBDal.class);

    Column currencyCol = mock(Column.class);
    when(currencyCol.getDBColumnName()).thenReturn("C_Currency_ID");
    when(currencyCol.isMandatory()).thenReturn(false);
    when(currencyCol.isActive()).thenReturn(true);
    when(currencyCol.isKeyColumn()).thenReturn(false);

    Property currencyProp = mock(Property.class);
    when(currencyProp.getName()).thenReturn("currency");
    when(currencyProp.isAuditInfo()).thenReturn(false);
    when(dalEntity.getPropertyByColumnName("C_Currency_ID")).thenReturn(currencyProp);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-CUR");
    when(table.getADColumnList()).thenReturn(Collections.singletonList(currencyCol));
    // A session value exists — but the user value already in the body must win.
    when(vars.getSessionValue("#C_Currency_ID")).thenReturn("CUR-EUR");

    OBCriteria<SFField> sfFieldCriteria = mock(OBCriteria.class);
    when(sfFieldCriteria.add(any())).thenReturn(sfFieldCriteria);
    when(sfFieldCriteria.list()).thenReturn(Collections.emptyList());
    when(obDal.createCriteria(SFField.class)).thenReturn(sfFieldCriteria);
    when(sfEntity.getId()).thenReturn("entity-cur");

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class);
         MockedStatic<NeoParentValuesLoader> parentMock =
             mockStatic(NeoParentValuesLoader.class)) {
      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-CUR")).thenReturn(dalEntity);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      obContextMock.when(() -> OBContext.setAdminMode(true)).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, adTab)).thenReturn(vars);
      parentMock.when(() -> NeoParentValuesLoader.load(adTab, null))
          .thenReturn(java.util.Collections.emptyMap());

      NeoDefaultsService.injectMandatoryDefaults(body, adTab, ctx);

      assertEquals("User-supplied value must not be overwritten", "USER-USD",
          body.getString("currency"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // applyResolvedDefault — type-aware String-default coercion (ETP-4668)
  // ═══════════════════════════════════════════════════════════════════════════

  private static final Class<?>[] APPLY_RESOLVED_DEFAULT_PARAMS = new Class<?>[] {
      JSONObject.class, Column.class, String.class, Object.class, NeoContext.class,
      Property.class };

  /**
   * ETP-4668 regression: {@code BusinessPartner.invoiceGrouping} ({@code C_BPartner.Invoicegrouping})
   * is a genuine {@link String} DAL property whose AD_Column default is the 15-digit
   * List-reference code {@code "000000000000000"}. The column name does not end in {@code _ID}
   * and the string parses cleanly as a number, so the old name-based heuristic silently coerced
   * it to {@code 0L}, corrupting the value and failing the List-reference validator downstream.
   * The DAL property type is {@code String}, so the value MUST be stored verbatim.
   */
  @Test
  public void testApplyResolvedDefaultKeepsAllDigitStringForStringProperty() throws Exception {
    JSONObject body = new JSONObject();
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("Invoicegrouping");
    Property prop = mock(Property.class);
    when(prop.isPrimitive()).thenReturn(true);
    doReturn(String.class).when(prop).getPrimitiveObjectType();

    invokePrivate("applyResolvedDefault", APPLY_RESOLVED_DEFAULT_PARAMS,
        body, col, "invoiceGrouping", "000000000000000", null, prop);

    Object stored = body.get("invoiceGrouping");
    assertTrue("String-typed List-reference default must stay a String, got "
        + stored.getClass().getSimpleName(), stored instanceof String);
    assertEquals("List-reference code must be preserved verbatim, not coerced to 0",
        "000000000000000", stored);
  }

  /**
   * Guards the legitimate path the coercion block exists for: a genuinely numeric ({@link Long})
   * DAL property whose resolved default arrives as a String (e.g. a line number from an SQL
   * {@code COALESCE(MAX(Line),0)+10} default) must still be coerced to {@link Long}.
   */
  @Test
  public void testApplyResolvedDefaultCoercesNumericStringForLongProperty() throws Exception {
    JSONObject body = new JSONObject();
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("Line");
    Property prop = mock(Property.class);
    when(prop.isPrimitive()).thenReturn(true);
    doReturn(Long.class).when(prop).getPrimitiveObjectType();

    invokePrivate("applyResolvedDefault", APPLY_RESOLVED_DEFAULT_PARAMS,
        body, col, "lineNo", "10", null, prop);

    Object stored = body.get("lineNo");
    assertTrue("Numeric Long property default must coerce to Long, got "
        + stored.getClass().getSimpleName(), stored instanceof Long);
    assertEquals(10L, stored);
  }

  /**
   * Guards the fractional numeric path: a {@link java.math.BigDecimal} DAL property whose
   * resolved default arrives as a decimal String must be coerced to {@link java.math.BigDecimal}.
   */
  @Test
  public void testApplyResolvedDefaultCoercesDecimalStringForBigDecimalProperty() throws Exception {
    JSONObject body = new JSONObject();
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("PriceStd");
    Property prop = mock(Property.class);
    when(prop.isPrimitive()).thenReturn(true);
    doReturn(java.math.BigDecimal.class).when(prop).getPrimitiveObjectType();

    invokePrivate("applyResolvedDefault", APPLY_RESOLVED_DEFAULT_PARAMS,
        body, col, "priceStd", "10.50", null, prop);

    Object stored = body.get("priceStd");
    assertTrue("Decimal property default must coerce to BigDecimal, got "
        + stored.getClass().getSimpleName(), stored instanceof java.math.BigDecimal);
    assertEquals(0, new java.math.BigDecimal("10.50").compareTo((java.math.BigDecimal) stored));
  }

  /**
   * A non-numeric String default (status flag / doc number) on a String property must be left
   * untouched — no coercion attempted.
   */
  @Test
  public void testApplyResolvedDefaultKeepsNonNumericStringForStringProperty() throws Exception {
    JSONObject body = new JSONObject();
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("DocStatus");
    Property prop = mock(Property.class);
    when(prop.isPrimitive()).thenReturn(true);
    doReturn(String.class).when(prop).getPrimitiveObjectType();

    invokePrivate("applyResolvedDefault", APPLY_RESOLVED_DEFAULT_PARAMS,
        body, col, "documentStatus", "DR", null, prop);

    assertEquals("DR", body.get("documentStatus"));
  }

  /**
   * ETP-4904 regression: a discarded FK column (e.g. {@code C_Project.AD_User_ID}) whose
   * {@code AD_Column.DefaultValue} is the literal {@code "-1"} — Etendo Classic's UI sentinel for
   * "nothing selected" — must NOT be persisted as a real FK id. Before the fix, "-1" reached
   * {@code body.put(propName, "-1")} verbatim and NEO Headless later tried to resolve it as a
   * real {@code AD_User(-1)} row, failing the insert with "refered to but not present in the
   * import set". The column is not a doctype target, so {@link DocTypeResolver} must return null
   * and the property must be left absent from the body (treated as null/omitted).
   */
  @Test
  public void testApplyResolvedDefaultSkipsSentinelMinusOneForForeignKeyColumn() throws Exception {
    JSONObject body = new JSONObject();
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("AD_User_ID");
    Property prop = mock(Property.class);

    try (MockedStatic<DocTypeResolver> docTypeMock = mockStatic(DocTypeResolver.class)) {
      docTypeMock.when(() -> DocTypeResolver.resolveDefaultDocTypeId(eq(col), any()))
          .thenReturn(null);

      invokePrivate("applyResolvedDefault", APPLY_RESOLVED_DEFAULT_PARAMS,
          body, col, "createdBy", "-1", null, prop);

      assertFalse("Sentinel '-1' FK default must not be persisted as a real id",
          body.has("createdBy"));
    }
  }

  /**
   * Same sentinel-skip behavior must hold for the legacy "0" sentinel on a doctype-target column
   * when no default doctype can be resolved — guards that the "-1" fix did not regress the
   * pre-existing "0" handling.
   */
  @Test
  public void testApplyResolvedDefaultSkipsSentinelZeroWhenNoDocTypeResolved() throws Exception {
    JSONObject body = new JSONObject();
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("C_DocTypeTarget_ID");
    Property prop = mock(Property.class);

    try (MockedStatic<DocTypeResolver> docTypeMock = mockStatic(DocTypeResolver.class)) {
      docTypeMock.when(() -> DocTypeResolver.resolveDefaultDocTypeId(eq(col), any()))
          .thenReturn(null);

      invokePrivate("applyResolvedDefault", APPLY_RESOLVED_DEFAULT_PARAMS,
          body, col, "transactionDocument", "0", null, prop);

      assertFalse("Sentinel '0' FK default must not be persisted when no doctype resolves",
          body.has("transactionDocument"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // applyDeclaredDefaultsToBackgroundEntity (ETP-4888) — non-HTTP background callers
  // ═══════════════════════════════════════════════════════════════════════════
  //
  // These tests mockStatic(NeoDefaultsService.class, CALLS_REAL_METHODS) — the SAME idiom
  // already used in FinancialAccountHandlerTest for self-static-mocking a sibling method on
  // the class under test — and stub ONLY the public resolveDefaults(NeoContext, String) entry
  // point. This lets applyDeclaredDefaultsToBackgroundEntity's own real body run end-to-end
  // (including its private siblings resolveBackgroundDefaults/findBackgroundEntity/
  // applyDeclaredDefaultIfMissing/applyDeclaredFkDefaultIfMissing/resolveFkDefaultTarget/
  // coercePrimitiveDefault), while resolveDefaults's own extensively-covered query/cascade
  // logic (see the resolveDefaults tests above) is replaced with a canned response — keeping
  // these tests focused purely on the background-entity application contract.

  private static final String BG_SPEC_NAME = "sales-invoice";
  private static final String BG_ENTITY_NAME = "header";
  private static final String BG_PARENT_ID = "parent-doc-1";

  /**
   * Wires NeoServletSupport.findSpec + the OBDal/OBContext chain resolveBackgroundDefaults
   * needs, reusing an OBDal mock the caller already created (and may have added further stubs
   * to, e.g. {@code dal.get(...)} for an FK-resolution test) rather than creating its own — a
   * second, independent {@code OBDal.getInstance()} stub would silently shadow the caller's.
   */
  private static SFEntity wireBackgroundEntityLookup(MockedStatic<NeoServletSupport> servletSupportMock,
      MockedStatic<OBDal> dalMock, MockedStatic<OBContext> obContextMock, OBDal dal) {
    SFSpec sfSpec = mock(SFSpec.class);
    when(sfSpec.getId()).thenReturn("spec-1");
    servletSupportMock.when(() -> NeoServletSupport.findSpec(BG_SPEC_NAME)).thenReturn(sfSpec);

    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getId()).thenReturn("sf-entity-1");

    dalMock.when(OBDal::getInstance).thenReturn(dal);
    @SuppressWarnings("unchecked")
    OBCriteria<SFEntity> entityCriteria = mock(OBCriteria.class);
    when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
    when(entityCriteria.list()).thenReturn(Collections.singletonList(sfEntity));

    OBContext obContext = mock(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);

    return sfEntity;
  }

  private static NeoResponse cannedDefaultsResponse(JSONObject defaults) throws JSONException {
    return new NeoResponse(200, new JSONObject().put("defaults", defaults));
  }

  /**
   * A primitive declared-default (a date, mirroring the real-world {@code etsgDateOperation}/
   * {@code aeatsiiFechaRegCont} fields) is filled in on a background-built entity when the
   * caller left it blank.
   */
  @Test
  public void testApplyDeclaredDefaultsToBackgroundEntityFillsBlankPrimitiveDateField()
      throws Exception {
    BaseOBObject entity = mock(BaseOBObject.class);
    Entity dalEntity = mock(Entity.class);
    Property prop = mock(Property.class);
    when(entity.getEntity()).thenReturn(dalEntity);
    when(entity.get("etsgDateOperation")).thenReturn(null);
    when(dalEntity.getProperty("etsgDateOperation")).thenReturn(prop);
    when(prop.isPrimitive()).thenReturn(true);
    doReturn(Date.class).when(prop).getPrimitiveObjectType();

    JSONObject defaults = new JSONObject().put("etsgDateOperation", "2026-08-20");

    try (MockedStatic<NeoServletSupport> servletSupportMock = mockStatic(NeoServletSupport.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<NeoDefaultsService> serviceMock =
             mockStatic(NeoDefaultsService.class, CALLS_REAL_METHODS)) {
      wireBackgroundEntityLookup(servletSupportMock, dalMock, obContextMock, dal(dalMock));
      serviceMock.when(() -> NeoDefaultsService.resolveDefaults(any(NeoContext.class),
          eq(BG_PARENT_ID))).thenReturn(cannedDefaultsResponse(defaults));

      NeoDefaultsService.applyDeclaredDefaultsToBackgroundEntity(
          BG_SPEC_NAME, BG_ENTITY_NAME, entity, BG_PARENT_ID);

      Date expected = new SimpleDateFormat("yyyy-MM-dd").parse("2026-08-20");
      verify(entity).set("etsgDateOperation", expected);
    }
  }

  /**
   * A field the caller ALREADY set explicitly on the background-built entity must never be
   * clobbered by a declared default — mirrors the "skip if already present" rule
   * {@link NeoDefaultsService#injectMandatoryDefaults} applies on the HTTP create path.
   */
  @Test
  public void testApplyDeclaredDefaultsToBackgroundEntityNeverClobbersAlreadySetField()
      throws Exception {
    BaseOBObject entity = mock(BaseOBObject.class);
    Entity dalEntity = mock(Entity.class);
    when(entity.getEntity()).thenReturn(dalEntity);
    // Caller already set this field explicitly (e.g. the builder's own accountingDate setter).
    when(entity.get("etsgDateOperation")).thenReturn(new Date());

    JSONObject defaults = new JSONObject().put("etsgDateOperation", "2026-08-20");

    try (MockedStatic<NeoServletSupport> servletSupportMock = mockStatic(NeoServletSupport.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<NeoDefaultsService> serviceMock =
             mockStatic(NeoDefaultsService.class, CALLS_REAL_METHODS)) {
      wireBackgroundEntityLookup(servletSupportMock, dalMock, obContextMock, dal(dalMock));
      serviceMock.when(() -> NeoDefaultsService.resolveDefaults(any(NeoContext.class),
          eq(BG_PARENT_ID))).thenReturn(cannedDefaultsResponse(defaults));

      NeoDefaultsService.applyDeclaredDefaultsToBackgroundEntity(
          BG_SPEC_NAME, BG_ENTITY_NAME, entity, BG_PARENT_ID);

      verify(entity, never()).set(eq("etsgDateOperation"), any());
    }
  }

  /**
   * ETP-4888 today's addition: an FK-typed declared default (mirroring {@code
   * aeatsiiDescription}/{@code EM_Aeatsii_Description_ID}) resolves to a real related-entity
   * bean via {@code OBDal.get(targetEntity, id)} and is set onto the background entity.
   */
  @Test
  public void testApplyDeclaredDefaultsToBackgroundEntityResolvesFkDeclaredDefaultToRelatedBean()
      throws Exception {
    BaseOBObject entity = mock(BaseOBObject.class);
    Entity dalEntity = mock(Entity.class);
    Property prop = mock(Property.class);
    Entity targetEntity = mock(Entity.class);
    BaseOBObject relatedBean = mock(BaseOBObject.class);
    when(entity.getEntity()).thenReturn(dalEntity);
    when(entity.get("aeatsiiDescription")).thenReturn(null);
    when(dalEntity.getProperty("aeatsiiDescription")).thenReturn(prop);
    when(prop.isPrimitive()).thenReturn(false);
    when(prop.getTargetEntity()).thenReturn(targetEntity);
    when(targetEntity.getName()).thenReturn("AeatsiiDescription");

    JSONObject defaults = new JSONObject().put("aeatsiiDescription", "desc-id-123");

    try (MockedStatic<NeoServletSupport> servletSupportMock = mockStatic(NeoServletSupport.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<NeoDefaultsService> serviceMock =
             mockStatic(NeoDefaultsService.class, CALLS_REAL_METHODS)) {
      OBDal dal = dal(dalMock);
      wireBackgroundEntityLookup(servletSupportMock, dalMock, obContextMock, dal);
      when(dal.get("AeatsiiDescription", "desc-id-123")).thenReturn(relatedBean);
      serviceMock.when(() -> NeoDefaultsService.resolveDefaults(any(NeoContext.class),
          eq(BG_PARENT_ID))).thenReturn(cannedDefaultsResponse(defaults));

      NeoDefaultsService.applyDeclaredDefaultsToBackgroundEntity(
          BG_SPEC_NAME, BG_ENTITY_NAME, entity, BG_PARENT_ID);

      verify(entity).set("aeatsiiDescription", relatedBean);
    }
  }

  /**
   * Returns the same {@code OBDal} mock instance that {@link #wireBackgroundEntityLookup} is
   * about to register on {@code dalMock}, so a test can add further stubs (e.g. {@code
   * dal.get(...)}) on it BEFORE calling {@code wireBackgroundEntityLookup}. Since {@code
   * OBDal::getInstance} is stubbed only once per {@code MockedStatic}, this must create and
   * register the mock itself — {@link #wireBackgroundEntityLookup} is then adjusted to reuse it
   * via an overload, avoiding a double {@code OBDal::getInstance} stub (Mockito allows
   * re-stubbing, but the LAST one silently wins, which would hide a bug in one of the two).
   */
  private static OBDal dal(MockedStatic<OBDal> dalMock) {
    OBDal dal = mock(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(dal);
    return dal;
  }

  /**
   * FK resolution failure — a bad/nonexistent id (record not found via {@code OBDal.get}) —
   * must leave the field untouched and must NOT throw: "never abort the rest of the pass".
   */
  @Test
  public void testApplyDeclaredDefaultsToBackgroundEntityLeavesFieldUntouchedWhenFkIdNotFound()
      throws Exception {
    BaseOBObject entity = mock(BaseOBObject.class);
    Entity dalEntity = mock(Entity.class);
    Property prop = mock(Property.class);
    Entity targetEntity = mock(Entity.class);
    when(entity.getEntity()).thenReturn(dalEntity);
    when(entity.get("aeatsiiDescription")).thenReturn(null);
    when(dalEntity.getProperty("aeatsiiDescription")).thenReturn(prop);
    when(prop.isPrimitive()).thenReturn(false);
    when(prop.getTargetEntity()).thenReturn(targetEntity);
    when(targetEntity.getName()).thenReturn("AeatsiiDescription");

    JSONObject defaults = new JSONObject().put("aeatsiiDescription", "does-not-exist");

    try (MockedStatic<NeoServletSupport> servletSupportMock = mockStatic(NeoServletSupport.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<NeoDefaultsService> serviceMock =
             mockStatic(NeoDefaultsService.class, CALLS_REAL_METHODS)) {
      OBDal dal = dal(dalMock);
      wireBackgroundEntityLookup(servletSupportMock, dalMock, obContextMock, dal);
      when(dal.get("AeatsiiDescription", "does-not-exist")).thenReturn(null);
      serviceMock.when(() -> NeoDefaultsService.resolveDefaults(any(NeoContext.class),
          eq(BG_PARENT_ID))).thenReturn(cannedDefaultsResponse(defaults));

      NeoDefaultsService.applyDeclaredDefaultsToBackgroundEntity(
          BG_SPEC_NAME, BG_ENTITY_NAME, entity, BG_PARENT_ID);

      verify(entity, never()).set(eq("aeatsiiDescription"), any());
    }
  }

  /**
   * FK resolution failure — {@code prop.getTargetEntity()} returns {@code null} (property is
   * not actually a resolvable FK) — must also leave the field untouched and not throw, and must
   * never even reach {@code OBDal.get(...)}.
   */
  @Test
  public void testApplyDeclaredDefaultsToBackgroundEntityLeavesFieldUntouchedWhenTargetEntityIsNull()
      throws Exception {
    BaseOBObject entity = mock(BaseOBObject.class);
    Entity dalEntity = mock(Entity.class);
    Property prop = mock(Property.class);
    when(entity.getEntity()).thenReturn(dalEntity);
    when(entity.get("aeatsiiDescription")).thenReturn(null);
    when(dalEntity.getProperty("aeatsiiDescription")).thenReturn(prop);
    when(prop.isPrimitive()).thenReturn(false);
    when(prop.getTargetEntity()).thenReturn(null);

    JSONObject defaults = new JSONObject().put("aeatsiiDescription", "some-id");

    try (MockedStatic<NeoServletSupport> servletSupportMock = mockStatic(NeoServletSupport.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<NeoDefaultsService> serviceMock =
             mockStatic(NeoDefaultsService.class, CALLS_REAL_METHODS)) {
      OBDal dal = dal(dalMock);
      wireBackgroundEntityLookup(servletSupportMock, dalMock, obContextMock, dal);
      serviceMock.when(() -> NeoDefaultsService.resolveDefaults(any(NeoContext.class),
          eq(BG_PARENT_ID))).thenReturn(cannedDefaultsResponse(defaults));

      NeoDefaultsService.applyDeclaredDefaultsToBackgroundEntity(
          BG_SPEC_NAME, BG_ENTITY_NAME, entity, BG_PARENT_ID);

      verify(entity, never()).set(eq("aeatsiiDescription"), any());
      verify(dal, never()).get(anyString(), anyString());
    }
  }

  /**
   * {@code resolveBackgroundDefaults} (and therefore the whole public entry point) must be a
   * graceful no-op — never throw, never touch the entity — when no active {@code SFSpec}
   * matches {@code specName}.
   */
  @Test
  public void testApplyDeclaredDefaultsToBackgroundEntityNoOpsWhenNoSfSpecFound() {
    BaseOBObject entity = mock(BaseOBObject.class);

    try (MockedStatic<NeoServletSupport> servletSupportMock = mockStatic(NeoServletSupport.class);
         MockedStatic<NeoDefaultsService> serviceMock =
             mockStatic(NeoDefaultsService.class, CALLS_REAL_METHODS)) {
      servletSupportMock.when(() -> NeoServletSupport.findSpec("unknown-spec")).thenReturn(null);

      NeoDefaultsService.applyDeclaredDefaultsToBackgroundEntity(
          "unknown-spec", BG_ENTITY_NAME, entity, BG_PARENT_ID);

      verify(entity, never()).set(anyString(), any());
      serviceMock.verify(() -> NeoDefaultsService.resolveDefaults(any(), any()), never());
    }
  }

  /**
   * Same graceful no-op contract when the {@code SFSpec} resolves but no active/included {@code
   * SFEntity} named {@code entityName} exists under it.
   */
  @Test
  public void testApplyDeclaredDefaultsToBackgroundEntityNoOpsWhenNoSfEntityFound() {
    BaseOBObject entity = mock(BaseOBObject.class);
    SFSpec sfSpec = mock(SFSpec.class);
    when(sfSpec.getId()).thenReturn("spec-1");

    try (MockedStatic<NeoServletSupport> servletSupportMock = mockStatic(NeoServletSupport.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<NeoDefaultsService> serviceMock =
             mockStatic(NeoDefaultsService.class, CALLS_REAL_METHODS)) {
      servletSupportMock.when(() -> NeoServletSupport.findSpec(BG_SPEC_NAME)).thenReturn(sfSpec);
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<SFEntity> entityCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
      when(entityCriteria.list()).thenReturn(Collections.emptyList());

      NeoDefaultsService.applyDeclaredDefaultsToBackgroundEntity(
          BG_SPEC_NAME, BG_ENTITY_NAME, entity, BG_PARENT_ID);

      verify(entity, never()).set(anyString(), any());
      serviceMock.verify(() -> NeoDefaultsService.resolveDefaults(any(), any()), never());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // resolveSQLDefaultWithOutcome — SqlDefaultOutcome diagnostics (ETP-4918)
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testResolveSqlDefaultOutcomeMissingParentTokenWhenParentIdNotProvided()
      throws Exception {
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    DalConnectionProvider connProvider = mock(DalConnectionProvider.class);
    Column adColumn = mock(Column.class);
    when(adColumn.getDBColumnName()).thenReturn("M_Locator_ID");

    when(dal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      // Utility.getContext is left unstubbed under mockStatic — returns null, matching a
      // token with no session equivalent (e.g. M_Warehouse_ID).

      NeoDefaultsSqlHelper.SqlDefaultOutcome outcome =
          NeoDefaultsSqlHelper.resolveSQLDefaultWithOutcome(
              "@SQL=SELECT id FROM t WHERE M_Warehouse_ID = '@M_Warehouse_ID@'",
              vars, connProvider, "WIN-1", adColumn, null, false);

      assertNull(outcome.getValue());
      assertEquals("M_Warehouse_ID", outcome.getMissingParentToken());
      assertFalse(outcome.isZeroRows());
    }
  }

  @Test
  public void testResolveSqlDefaultOutcomeZeroRowsWhenParentIdProvided() throws Exception {
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    DalConnectionProvider connProvider = mock(DalConnectionProvider.class);
    Column adColumn = mock(Column.class);
    when(adColumn.getDBColumnName()).thenReturn("M_Locator_ID");

    when(dal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      // parentIdProvided=true this time: a missing/empty token must NOT be blamed on a
      // forgotten parentId — the query simply matched nothing.
      NeoDefaultsSqlHelper.SqlDefaultOutcome outcome =
          NeoDefaultsSqlHelper.resolveSQLDefaultWithOutcome(
              "@SQL=SELECT id FROM t WHERE M_Warehouse_ID = '@M_Warehouse_ID@'",
              vars, connProvider, "WIN-1", adColumn, null, true);

      assertNull(outcome.getValue());
      assertNull(outcome.getMissingParentToken());
      assertTrue(outcome.isZeroRows());
    }
  }

  @Test
  public void testResolveSqlDefaultOutcomeResolvedValue() throws Exception {
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    DalConnectionProvider connProvider = mock(DalConnectionProvider.class);
    Column adColumn = mock(Column.class);
    when(adColumn.getDBColumnName()).thenReturn("M_Locator_ID");

    when(dal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getString(1)).thenReturn("LOC-99");

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoDefaultsSqlHelper.SqlDefaultOutcome outcome =
          NeoDefaultsSqlHelper.resolveSQLDefaultWithOutcome(
              "@SQL=SELECT id FROM t WHERE M_Warehouse_ID = '@M_Warehouse_ID@'",
              vars, connProvider, "WIN-1", adColumn, null, true);

      assertEquals("LOC-99", outcome.getValue());
      assertNull("A resolved value has no diagnostic to report", outcome.getMissingParentToken());
      assertFalse(outcome.isZeroRows());
    }
  }

  @Test
  public void testResolveSqlDefaultOutcomeSessionParamNeverBlamedOnMissingParentId()
      throws Exception {
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    DalConnectionProvider connProvider = mock(DalConnectionProvider.class);
    Column adColumn = mock(Column.class);
    when(adColumn.getDBColumnName()).thenReturn("Description");

    when(dal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      // "#AD_Org_ID" is a session param, not a parent token — even with parentId absent it
      // must fall through to the zero-rows case, never missingParentToken.
      NeoDefaultsSqlHelper.SqlDefaultOutcome outcome =
          NeoDefaultsSqlHelper.resolveSQLDefaultWithOutcome(
              "@SQL=SELECT name FROM ad_org WHERE ad_org_id = '@#AD_Org_ID@'",
              vars, connProvider, "WIN-1", adColumn, null, false);

      assertNull(outcome.getMissingParentToken());
      assertTrue(outcome.isZeroRows());
    }
  }

  @Test
  public void testResolveSqlDefaultOutcomeSqlExceptionReturnsUnresolved() throws Exception {
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    DalConnectionProvider connProvider = mock(DalConnectionProvider.class);
    Column adColumn = mock(Column.class);
    when(adColumn.getDBColumnName()).thenReturn("M_Locator_ID");

    when(dal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenThrow(new java.sql.SQLException("boom"));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoDefaultsSqlHelper.SqlDefaultOutcome outcome =
          NeoDefaultsSqlHelper.resolveSQLDefaultWithOutcome(
              "@SQL=SELECT id FROM t WHERE M_Warehouse_ID = '@M_Warehouse_ID@'",
              vars, connProvider, "WIN-1", adColumn, null, false);

      assertNull(outcome.getValue());
      assertNull("An exception is not one of the two known-cause diagnoses — no attribution",
          outcome.getMissingParentToken());
      assertFalse(outcome.isZeroRows());
    }
  }

  @Test
  public void testResolveSqlDefaultRegressionUnaffectedByOutcomeDiagnostics() throws Exception {
    // Pins resolveSQLDefault (the pre-existing 6-arg entry point used by every caller other
    // than pass 1 of resolveDefaults) to still return just the plain value, unaffected by the
    // new diagnostic machinery layered underneath it (ETP-4918).
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    DalConnectionProvider connProvider = mock(DalConnectionProvider.class);
    Column adColumn = mock(Column.class);
    when(adColumn.getDBColumnName()).thenReturn("M_Locator_ID");

    when(dal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      String result = NeoDefaultsSqlHelper.resolveSQLDefault(
          "@SQL=SELECT id FROM t WHERE M_Warehouse_ID = '@M_Warehouse_ID@'",
          vars, connProvider, "WIN-1", adColumn, null);

      assertNull(result);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // appendSqlDefaultNote — via reflection (ETP-4918)
  // ═══════════════════════════════════════════════════════════════════════════
  //
  // SqlDefaultOutcome's factory methods are private to NeoDefaultsSqlHelper, so each test
  // below obtains a real instance by driving resolveSQLDefaultWithOutcome against a mocked
  // JDBC layer — the same outcome appendSqlDefaultNote would actually receive in production.

  private static final Class<?>[] APPEND_SQL_DEFAULT_NOTE_PARAMS =
      new Class<?>[]{ JSONArray.class, String.class, NeoDefaultsSqlHelper.SqlDefaultOutcome.class };

  @Test
  public void testAppendSqlDefaultNoteMissingParentTokenNamesFieldAndAction() throws Exception {
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    DalConnectionProvider connProvider = mock(DalConnectionProvider.class);
    Column adColumn = mock(Column.class);

    when(dal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);

    NeoDefaultsSqlHelper.SqlDefaultOutcome outcome;
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      outcome = NeoDefaultsSqlHelper.resolveSQLDefaultWithOutcome(
          "@SQL=SELECT id FROM M_Locator WHERE M_Warehouse_ID = '@M_Warehouse_ID@'",
          vars, connProvider, "WIN-1", adColumn, null, false);
    }

    JSONArray notes = new JSONArray();
    invokePrivate("appendSqlDefaultNote", APPEND_SQL_DEFAULT_NOTE_PARAMS,
        notes, "storageBin", outcome);

    assertEquals(1, notes.length());
    String note = notes.getString(0);
    assertTrue("Note must name the field", note.contains("storageBin"));
    assertTrue("Note must name the missing token", note.contains("M_Warehouse_ID"));
    assertTrue("Note must name the concrete action", note.contains("parentId"));
  }

  @Test
  public void testAppendSqlDefaultNoteZeroRowsNamesFieldAndAction() throws Exception {
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    DalConnectionProvider connProvider = mock(DalConnectionProvider.class);
    Column adColumn = mock(Column.class);

    when(dal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);

    NeoDefaultsSqlHelper.SqlDefaultOutcome outcome;
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      // parentIdProvided=true forces the zero-rows branch even though the token is empty.
      outcome = NeoDefaultsSqlHelper.resolveSQLDefaultWithOutcome(
          "@SQL=SELECT id FROM M_Locator WHERE M_Warehouse_ID = '@M_Warehouse_ID@'",
          vars, connProvider, "WIN-1", adColumn, null, true);
    }

    JSONArray notes = new JSONArray();
    invokePrivate("appendSqlDefaultNote", APPEND_SQL_DEFAULT_NOTE_PARAMS,
        notes, "storageBin", outcome);

    assertEquals(1, notes.length());
    String note = notes.getString(0);
    assertTrue("Note must name the field", note.contains("storageBin"));
    assertTrue("Note must state the concrete action", note.contains("Set this field explicitly"));
  }

  @Test
  public void testAppendSqlDefaultNoteNullOutcomeAddsNothing() throws Exception {
    JSONArray notes = new JSONArray();

    invokePrivate("appendSqlDefaultNote", APPEND_SQL_DEFAULT_NOTE_PARAMS,
        notes, "storageBin", null);

    assertEquals("A field with no @SQL= default (null outcome) must never earn a note",
        0, notes.length());
  }

  @Test
  public void testAppendSqlDefaultNoteResolvedOutcomeAddsNothing() throws Exception {
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    DalConnectionProvider connProvider = mock(DalConnectionProvider.class);
    Column adColumn = mock(Column.class);

    when(dal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getString(1)).thenReturn("LOC-99");

    NeoDefaultsSqlHelper.SqlDefaultOutcome outcome;
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      outcome = NeoDefaultsSqlHelper.resolveSQLDefaultWithOutcome(
          "@SQL=SELECT id FROM M_Locator WHERE M_Warehouse_ID = '@M_Warehouse_ID@'",
          vars, connProvider, "WIN-1", adColumn, null, true);
    }

    JSONArray notes = new JSONArray();
    invokePrivate("appendSqlDefaultNote", APPEND_SQL_DEFAULT_NOTE_PARAMS,
        notes, "storageBin", outcome);

    assertEquals("A resolved value has nothing to explain — no note", 0, notes.length());
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // resolveDefaults — metadata.notes end-to-end (ETP-4918)
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveDefaultsSqlDefaultMissingParentTokenAddsNote() throws Exception {
    OBDal dal = mock(OBDal.class);
    OBCriteria<SFField> fieldCriteria = mock(OBCriteria.class);
    SFField sfField = mock(SFField.class);
    Column adColumn = mock(Column.class);
    SFEntity sfEntity = mock(SFEntity.class);
    OBContext obContext = mock(OBContext.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    Entity dalEntity = mock(Entity.class);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(sfEntity.getId()).thenReturn("sf-entity-1");
    when(sfField.getADColumn()).thenReturn(adColumn);
    // Readonly: the combo-fallback in applyDefaultWithComboFallback is gated out, so a null
    // resolvedValue here means the field is genuinely absent — exactly the storageBin case.
    when(sfField.isReadOnly()).thenReturn(true);
    when(sfField.getDefaultValue()).thenReturn(null);
    when(adColumn.getDBColumnName()).thenReturn("M_Locator_ID");
    when(adColumn.getDefaultValue()).thenReturn(
        "@SQL=SELECT M_Locator_ID FROM M_Locator WHERE M_Warehouse_ID = '@M_Warehouse_ID@'");
    when(adColumn.isLinkToParentColumn()).thenReturn(false);
    when(adColumn.isUseAutomaticSequence()).thenReturn(false);
    when(fieldCriteria.add(any())).thenReturn(fieldCriteria);
    when(fieldCriteria.list()).thenReturn(Collections.singletonList(sfField));
    when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);
    when(dal.getConnection(false)).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class);
         MockedStatic<SequenceUtils> sequenceMock = mockStatic(SequenceUtils.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class);
         MockedStatic<DocTypeResolver> docTypeMock = mockStatic(DocTypeResolver.class)) {
      obContextMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, null)).thenReturn(vars);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper.resolveDalEntity(sfEntity))
          .thenReturn(dalEntity);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper
          .resolvePropertyName(dalEntity, "M_Locator_ID"))
          .thenReturn("storageBin");
      sequenceMock.when(() -> SequenceUtils.isSequence(adColumn)).thenReturn(false);

      // parentId omitted — the exact failure mode this feature exists to explain.
      NeoResponse response = NeoDefaultsService.resolveDefaults(ctx, null);

      assertEquals(200, response.getHttpStatus());
      JSONObject defaults = response.getBody().getJSONObject("defaults");
      assertFalse("storageBin must be absent, not silently defaulted",
          defaults.has("storageBin"));

      JSONObject metadata = response.getBody().getJSONObject("metadata");
      assertEquals("unresolvedFields must stay untouched by this clean-null case (pinned)",
          0, metadata.getJSONArray("unresolvedFields").length());
      assertTrue("metadata.notes must be present", metadata.has("notes"));
      JSONArray notes = metadata.getJSONArray("notes");
      assertEquals(1, notes.length());
      String note = notes.getString(0);
      assertTrue(note.contains("storageBin"));
      assertTrue(note.contains("M_Warehouse_ID"));
      assertTrue(note.contains("parentId"));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveDefaultsNoDefaultExpressionAddsNoNote() throws Exception {
    // Regression guard (the one that matters most): a field with no default expression at
    // all — the ordinary, overwhelmingly common case — must never earn a note. If this ever
    // regresses, metadata.notes degrades into the vague noise it was designed to avoid.
    OBDal dal = mock(OBDal.class);
    OBCriteria<SFField> fieldCriteria = mock(OBCriteria.class);
    SFField sfField = mock(SFField.class);
    Column adColumn = mock(Column.class);
    SFEntity sfEntity = mock(SFEntity.class);
    OBContext obContext = mock(OBContext.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    Entity dalEntity = mock(Entity.class);

    when(sfEntity.getId()).thenReturn("sf-entity-1");
    when(sfField.getADColumn()).thenReturn(adColumn);
    when(sfField.isReadOnly()).thenReturn(true);
    when(sfField.getDefaultValue()).thenReturn(null);
    when(adColumn.getDBColumnName()).thenReturn("C_Reject_Reason_ID");
    when(adColumn.getDefaultValue()).thenReturn(null);
    when(adColumn.isUseAutomaticSequence()).thenReturn(false);
    when(fieldCriteria.add(any())).thenReturn(fieldCriteria);
    when(fieldCriteria.list()).thenReturn(Collections.singletonList(sfField));
    when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);

    NeoContext ctx = NeoContext.builder()
        .sfEntity(sfEntity)
        .obContext(obContext)
        .build();

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class);
         MockedStatic<SequenceUtils> sequenceMock = mockStatic(SequenceUtils.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class);
         MockedStatic<DocTypeResolver> docTypeMock = mockStatic(DocTypeResolver.class);
         MockedStatic<NeoSelectorService> selectorMock =
             mockStatic(NeoSelectorService.class)) {
      obContextMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, null)).thenReturn(vars);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper.resolveDalEntity(sfEntity))
          .thenReturn(dalEntity);
      cascadeMock.when(() -> NeoDefaultsCascadeHelper
          .resolvePropertyName(dalEntity, "C_Reject_Reason_ID"))
          .thenReturn("rejectReason");
      sequenceMock.when(() -> SequenceUtils.isSequence(adColumn)).thenReturn(false);
      utilityMock.when(() -> Utility.getPreference(vars, "C_Reject_Reason_ID", ""))
          .thenReturn(null);
      docTypeMock.when(() -> DocTypeResolver.resolveDefaultDocTypeId(adColumn, ctx))
          .thenReturn(null);

      NeoResponse response = NeoDefaultsService.resolveDefaults(ctx, null);

      assertEquals(200, response.getHttpStatus());
      assertFalse(response.getBody().getJSONObject("defaults").has("rejectReason"));
      JSONObject metadata = response.getBody().getJSONObject("metadata");
      assertFalse("No @SQL= default was ever attempted — metadata.notes must be absent",
          metadata.has("notes"));
    }
  }
}
