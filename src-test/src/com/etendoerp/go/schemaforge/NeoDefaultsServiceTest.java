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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
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

  @Test
  public void testZeroQtyNoInjection() throws Exception {
    JSONObject body = new JSONObject();
    body.put("invoicedQuantity", "0");
    body.put("unitPrice", 29.70);

    NeoCommercialLinePolicy.injectLineNetAmountIfMissing(body);

    assertFalse("lineNetAmount should not be injected when invoicedQuantity is zero",
        body.has("lineNetAmount"));
  }

  @Test
  public void testMissingQtyNoInjection() throws Exception {
    JSONObject body = new JSONObject();
    body.put("unitPrice", 29.70);

    NeoCommercialLinePolicy.injectLineNetAmountIfMissing(body);

    assertFalse("lineNetAmount should not be injected when invoicedQuantity is absent",
        body.has("lineNetAmount"));
  }

  @Test
  public void testZeroUnitPriceNoInjection() throws Exception {
    JSONObject body = new JSONObject();
    body.put("invoicedQuantity", "3");
    body.put("unitPrice", 0);

    NeoCommercialLinePolicy.injectLineNetAmountIfMissing(body);

    assertFalse("lineNetAmount should not be injected when unitPrice is zero",
        body.has("lineNetAmount"));
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
      verify(vars).setSessionValue(eq("#Date"), any(String.class));
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

    Column inactiveCol = mockColumn("InactiveCol", true, false, false);
    Column nonMandatoryCol = mockColumn("OptionalCol", false, false, true);

    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("TABLE-1");
    when(table.getADColumnList()).thenReturn(Arrays.asList(inactiveCol, nonMandatoryCol));

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

      assertEquals("Inactive/non-mandatory columns should be skipped", 0, body.length());
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
         MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
             mockStatic(NeoDefaultsCascadeHelper.class)) {
      ModelProvider mp = mock(ModelProvider.class);
      modelMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableId("TABLE-1")).thenReturn(dalEntity);
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
  // buildVariablesSecureApp — delegates to NeoCalloutService and sets #Date
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testBuildVariablesSecureAppSetsDate() {
    OBContext obContext = mock(OBContext.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);

    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, null)).thenReturn(vars);

      VariablesSecureApp result = NeoDefaultsService.buildVariablesSecureApp(obContext);

      assertEquals(vars, result);
      verify(vars).setSessionValue(eq("#Date"), any(String.class));
    }
  }

  @Test
  public void testBuildVariablesSecureAppWithTabSetsDate() {
    OBContext obContext = mock(OBContext.class);
    Tab adTab = mock(Tab.class);
    VariablesSecureApp vars = mock(VariablesSecureApp.class);

    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.buildVars(obContext, adTab)).thenReturn(vars);

      VariablesSecureApp result = NeoDefaultsService.buildVariablesSecureApp(obContext, adTab);

      assertEquals(vars, result);
      verify(vars).setSessionValue(eq("#Date"), any(String.class));
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

      String result = NeoDefaultsService.resolveFirstOrgForClient("CLIENT-1");

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

      String result = NeoDefaultsService.resolveFirstOrgForClient("CLIENT-1");

      assertNull(result);
    }
  }

  @Test
  public void testResolveFirstOrgForClientExceptionReturnsNull() throws Exception {
    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection(false)).thenThrow(new RuntimeException("DB down"));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      String result = NeoDefaultsService.resolveFirstOrgForClient("CLIENT-1");

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
  // parseSQLExpression — via reflection
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  public void testParseSQLExpressionBasicSubstitution() throws Exception {
    ArrayList<String> params = new ArrayList<>();
    String sql = (String) invokePrivate("parseSQLExpression",
        new Class<?>[]{ String.class, ArrayList.class },
        "@SQL=SELECT name FROM ad_org WHERE ad_org_id = '@#AD_Org_ID@'", params);

    assertEquals("SELECT name FROM ad_org WHERE ad_org_id = ?", sql);
    assertEquals(1, params.size());
    assertEquals("#AD_Org_ID", params.get(0));
  }

  @Test
  public void testParseSQLExpressionMultipleParams() throws Exception {
    ArrayList<String> params = new ArrayList<>();
    String sql = (String) invokePrivate("parseSQLExpression",
        new Class<?>[]{ String.class, ArrayList.class },
        "@SQL=SELECT id FROM t WHERE col1 = '@A@' AND col2 = '@B@'", params);

    assertEquals("SELECT id FROM t WHERE col1 = ? AND col2 = ?", sql);
    assertEquals(2, params.size());
    assertEquals("A", params.get(0));
    assertEquals("B", params.get(1));
  }

  @Test
  public void testParseSQLExpressionNoParams() throws Exception {
    ArrayList<String> params = new ArrayList<>();
    String sql = (String) invokePrivate("parseSQLExpression",
        new Class<?>[]{ String.class, ArrayList.class },
        "@SQL=SELECT 1 FROM DUAL", params);

    assertEquals("SELECT 1 FROM DUAL", sql);
    assertTrue(params.isEmpty());
  }

  @Test
  public void testParseSQLExpressionNullReturnsEmpty() throws Exception {
    ArrayList<String> params = new ArrayList<>();
    String sql = (String) invokePrivate("parseSQLExpression",
        new Class<?>[]{ String.class, ArrayList.class },
        null, params);

    assertEquals("", sql);
    assertTrue(params.isEmpty());
  }

  @Test
  public void testParseSQLExpressionEmptyReturnsEmpty() throws Exception {
    ArrayList<String> params = new ArrayList<>();
    String sql = (String) invokePrivate("parseSQLExpression",
        new Class<?>[]{ String.class, ArrayList.class },
        "  ", params);

    assertEquals("", sql);
    assertTrue(params.isEmpty());
  }

  @Test
  public void testParseSQLExpressionUnpairedAtSign() throws Exception {
    ArrayList<String> params = new ArrayList<>();
    String sql = (String) invokePrivate("parseSQLExpression",
        new Class<?>[]{ String.class, ArrayList.class },
        "@SQL=SELECT 1 WHERE x = @incomplete", params);

    // Unpaired @ — remainder appended
    assertNotNull(sql);
    assertTrue(params.isEmpty());
  }

  @Test
  public void testParseSQLExpressionParamWithoutQuotes() throws Exception {
    ArrayList<String> params = new ArrayList<>();
    String sql = (String) invokePrivate("parseSQLExpression",
        new Class<?>[]{ String.class, ArrayList.class },
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

      String result = (String) invokePrivate("resolveDbColumnDefault",
          new Class<?>[]{ String.class, String.class }, "C_Order", "IsActive");

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

      String result = (String) invokePrivate("resolveDbColumnDefault",
          new Class<?>[]{ String.class, String.class }, "C_Order", "Description");

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

      String result = (String) invokePrivate("resolveDbColumnDefault",
          new Class<?>[]{ String.class, String.class }, "C_Order", "Description");

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

      String result = (String) invokePrivate("resolveDbColumnDefault",
          new Class<?>[]{ String.class, String.class }, "C_Order", "Description");

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

      String result = (String) invokePrivate("resolveDbColumnDefault",
          new Class<?>[]{ String.class, String.class }, "C_Order", "Line");

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

      String result = (String) invokePrivate("resolveDbColumnDefault",
          new Class<?>[]{ String.class, String.class }, "C_Order", "Line");

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
}
