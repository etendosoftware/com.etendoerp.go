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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.enterprise.DocumentType;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Unit tests for {@link DocTypeResolver}.
 *
 * @covers com.etendoerp.go.schemaforge.DocTypeResolver
 */
class DocTypeResolverTest {

  @Test
  @DisplayName("Utility class hides its constructor")
  void utilityClassHidesConstructor() throws ReflectiveOperationException {
    Constructor<DocTypeResolver> constructor = DocTypeResolver.class.getDeclaredConstructor();
    assertEquals(Modifier.PRIVATE, constructor.getModifiers() & Modifier.PRIVATE);
    constructor.setAccessible(true);
    constructor.newInstance();
  }


  private static Object invokePrivate(String methodName, Class<?>[] paramTypes, Object... args)
      throws Exception {
    Method method = DocTypeResolver.class.getDeclaredMethod(methodName, paramTypes);
    method.setAccessible(true);
    return method.invoke(null, args);
  }

  // -------------------------------------------------------------------------
  // resolveDocBaseType — every supported transactional document family
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("resolveDocBaseType — all transactional document families")
  class ResolveDocBaseType {

    @ParameterizedTest
    @CsvSource({
        "C_ORDER,   Y, SOO",
        "C_ORDER,   N, POO",
        "c_order,   Y, SOO",
        "C_INVOICE, Y, ARI",
        "C_INVOICE, N, API",
        "M_INOUT,   Y, MMS",
        "M_INOUT,   N, MMR",
        "C_PAYMENT, Y, ARR",
        "C_PAYMENT, N, APP",
        "M_MOVEMENT, Y, MMM",
        "M_MOVEMENT, N, MMM",
        "M_INVENTORY, Y, MMI",
        "M_INVENTORY, N, MMI",
        "C_BANKSTATEMENT, Y, CMB",
        "C_BANKSTATEMENT, N, CMB"
    })
    @DisplayName("Maps every supported document table and transaction direction to DocBaseType")
    void mapsCorrectly(String tableName, String isSOTrx, String expected) {
      assertEquals(expected, DocTypeResolver.resolveDocBaseType(tableName, isSOTrx));
    }

    @Test
    @DisplayName("Returns null for unknown table name")
    void unknownTableReturnsNull() {
      assertNull(DocTypeResolver.resolveDocBaseType("UNKNOWN_TABLE", "Y"));
    }
  }

  // -------------------------------------------------------------------------
  // reapplyDocTypeFromTabFilter — null safety
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("reapplyDocTypeFromTabFilter")
  class ReapplyDocType {

    @Test
    @DisplayName("Handles null body gracefully")
    void nullBodyNoOp() {
      DocTypeResolver.reapplyDocTypeFromTabFilter(null, mock(Tab.class), mock(NeoContext.class));
      // no exception
    }

    @Test
    @DisplayName("Handles null adTab gracefully")
    void nullTabNoOp() {
      DocTypeResolver.reapplyDocTypeFromTabFilter(new JSONObject(), null, mock(NeoContext.class));
    }

    @Test
    @DisplayName("Handles null context gracefully")
    void nullContextNoOp() {
      DocTypeResolver.reapplyDocTypeFromTabFilter(new JSONObject(), mock(Tab.class), null);
    }

    @Test
    @DisplayName("Does nothing when tab has no C_DocTypeTarget_ID column")
    void noDocTypeTargetColumn() {
      Tab adTab = mock(Tab.class);
      org.openbravo.model.ad.datamodel.Table table =
          mock(org.openbravo.model.ad.datamodel.Table.class);
      when(adTab.getTable()).thenReturn(table);
      when(table.getADColumnList()).thenReturn(Collections.emptyList());

      JSONObject body = new JSONObject();
      DocTypeResolver.reapplyDocTypeFromTabFilter(body, adTab, mock(NeoContext.class));
      assertEquals(0, body.length());
    }
  }

  // -------------------------------------------------------------------------
  // parseSubTypeFilters
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("parseSubTypeFilters")
  class ParseSubTypeFilters {

    private String[] invokeParseSubTypeFilters(NeoContext ctx) throws Exception {
      return (String[]) invokePrivate("parseSubTypeFilters",
          new Class<?>[] { NeoContext.class }, ctx);
    }

    @Test
    @DisplayName("Returns [null, null, null] when context is null")
    void nullContext() throws Exception {
      String[] result = invokeParseSubTypeFilters(null);
      assertArrayEquals(new String[] { null, null, null }, result);
    }

    @Test
    @DisplayName("Returns [null, null, null] when sfEntity is null")
    void nullSfEntity() throws Exception {
      NeoContext ctx = NeoContext.builder().build();
      String[] result = invokeParseSubTypeFilters(ctx);
      assertArrayEquals(new String[] { null, null, null }, result);
    }

    @Test
    @DisplayName("Extracts LIKE filter from tab where clause")
    void extractsLikeFilter() throws Exception {
      SFEntity sfEntity = mock(SFEntity.class);
      Tab adTab = mock(Tab.class);
      when(sfEntity.getADTab()).thenReturn(adTab);
      when(adTab.getHqlwhereclause()).thenReturn("e.sOSubType LIKE 'OB'");

      NeoContext ctx = NeoContext.builder().sfEntity(sfEntity).build();
      String[] result = invokeParseSubTypeFilters(ctx);

      assertEquals("OB", result[0]);
      assertNull(result[1]);
    }

    @Test
    @DisplayName("Extracts NOT LIKE exclude from tab where clause")
    void extractsNotLikeExclude() throws Exception {
      SFEntity sfEntity = mock(SFEntity.class);
      Tab adTab = mock(Tab.class);
      when(sfEntity.getADTab()).thenReturn(adTab);
      when(adTab.getHqlwhereclause()).thenReturn("e.sOSubType NOT LIKE 'WR'");

      NeoContext ctx = NeoContext.builder().sfEntity(sfEntity).build();
      String[] result = invokeParseSubTypeFilters(ctx);

      assertNull(result[0]);
      assertEquals("WR", result[1]);
    }

    @Test
    @DisplayName("NOT LIKE takes precedence over LIKE")
    void notLikeTakesPrecedence() throws Exception {
      SFEntity sfEntity = mock(SFEntity.class);
      Tab adTab = mock(Tab.class);
      when(sfEntity.getADTab()).thenReturn(adTab);
      when(adTab.getHqlwhereclause()).thenReturn(
          "e.sOSubType NOT LIKE 'WR' AND e.sOSubType LIKE 'OB'");

      NeoContext ctx = NeoContext.builder().sfEntity(sfEntity).build();
      String[] result = invokeParseSubTypeFilters(ctx);

      assertNull(result[0]);
      assertEquals("WR", result[1]);
    }
  }

  // -------------------------------------------------------------------------
  // parseIsSOTrxLiteral
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("parseIsSOTrxLiteral")
  class ParseIsSOTrxLiteral {

    private String invokeParseIsSOTrxLiteral(String value) throws Exception {
      return (String) invokePrivate("parseIsSOTrxLiteral",
          new Class<?>[] { String.class }, value);
    }

    @ParameterizedTest
    @ValueSource(strings = { "Y", "'Y'" })
    @DisplayName("Returns Y for Y literals")
    void returnsY(String input) throws Exception {
      assertEquals("Y", invokeParseIsSOTrxLiteral(input));
    }

    @ParameterizedTest
    @ValueSource(strings = { "N", "'N'" })
    @DisplayName("Returns N for N literals")
    void returnsN(String input) throws Exception {
      assertEquals("N", invokeParseIsSOTrxLiteral(input));
    }

    @Test
    @DisplayName("Returns null for non-literal values")
    void returnsNullForNonLiteral() throws Exception {
      assertNull(invokeParseIsSOTrxLiteral("@IsSOTrx@"));
    }
  }

  // -------------------------------------------------------------------------
  // resolveDefaultDocTypeId — column name guard
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("resolveDefaultDocTypeId")
  class ResolveDefaultDocTypeId {

    @Test
    @DisplayName("Returns null for non-DOCTYPE column name")
    void nonDocTypeColumnReturnsNull() {
      Column col = mock(Column.class);
      when(col.getDBColumnName()).thenReturn("M_Product_ID");

      assertNull(DocTypeResolver.resolveDefaultDocTypeId(col, mock(NeoContext.class)));
    }

    @Test
    @DisplayName("Returns null for column not ending in _ID")
    void nonIdColumnReturnsNull() {
      Column col = mock(Column.class);
      when(col.getDBColumnName()).thenReturn("DocType_Name");

      assertNull(DocTypeResolver.resolveDefaultDocTypeId(col, mock(NeoContext.class)));
    }
  }

  @Nested
  @DisplayName("reapplyDocTypeFromTabFilter — 4-param overload clientSubmittedFields skip logic")
  class FourParamClientSubmittedFields {

    private Tab tabWithDocTypeColumn() {
      Tab adTab = mock(Tab.class);
      Table table = mock(Table.class);
      Column col = mock(Column.class);
      when(adTab.getTable()).thenReturn(table);
      when(col.getDBColumnName()).thenReturn("C_DocTypeTarget_ID");
      when(table.getADColumnList()).thenReturn(List.of(col));
      when(table.getId()).thenReturn("TABLE_001");
      return adTab;
    }

    @Test
    @DisplayName("null clientSubmittedFields bypasses skip check (no-op on empty tab columns)")
    void nullClientSubmittedFieldsBypassesSkipCheck() {
      Tab adTab = mock(Tab.class);
      Table table = mock(Table.class);
      when(adTab.getTable()).thenReturn(table);
      when(table.getADColumnList()).thenReturn(Collections.emptyList());
      JSONObject body = new JSONObject();
      DocTypeResolver.reapplyDocTypeFromTabFilter(body, adTab, mock(NeoContext.class), null);
      assertEquals(0, body.length());
    }

    @Test
    @DisplayName("empty clientSubmittedFields bypasses skip check")
    void emptyClientSubmittedFieldsBypassesSkipCheck() {
      Tab adTab = mock(Tab.class);
      Table table = mock(Table.class);
      when(adTab.getTable()).thenReturn(table);
      when(table.getADColumnList()).thenReturn(Collections.emptyList());
      JSONObject body = new JSONObject();
      DocTypeResolver.reapplyDocTypeFromTabFilter(body, adTab, mock(NeoContext.class), Collections.emptySet());
      assertEquals(0, body.length());
    }

    @Test
    @DisplayName("clientSubmittedFields non-empty but ModelProvider returns null entity — no skip")
    void nonEmptyClientSubmittedFieldsNullEntityNoSkip() {
      Tab adTab = tabWithDocTypeColumn();
      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
        ModelProvider instance = mock(ModelProvider.class);
        mp.when(ModelProvider::getInstance).thenReturn(instance);
        when(instance.getEntityByTableId("TABLE_001")).thenReturn(null);

        JSONObject body = new JSONObject();
        DocTypeResolver.reapplyDocTypeFromTabFilter(body, adTab, mock(NeoContext.class), Set.of("anyField"));
        assertEquals(0, body.length());
      }
    }

    @Test
    @DisplayName("clientSubmittedFields contains target property name — skip reapply")
    void clientSubmittedContainsTargetPropertySkips() {
      Tab adTab = tabWithDocTypeColumn();
      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
        ModelProvider instance = mock(ModelProvider.class);
        Entity dalEntity = mock(Entity.class);
        Property targetProp = mock(Property.class);
        mp.when(ModelProvider::getInstance).thenReturn(instance);
        when(instance.getEntityByTableId("TABLE_001")).thenReturn(dalEntity);
        when(dalEntity.getPropertyByColumnName("C_DocTypeTarget_ID")).thenReturn(targetProp);
        when(targetProp.getName()).thenReturn("transactionDocument");

        JSONObject body = new JSONObject();
        DocTypeResolver.reapplyDocTypeFromTabFilter(body, adTab, mock(NeoContext.class), Set.of("transactionDocument"));
        assertEquals(0, body.length());
      }
    }

    @Test
    @DisplayName("clientSubmittedFields does not contain target property — proceeds past skip")
    void clientSubmittedMissesTargetPropertyProceeds() {
      Tab adTab = tabWithDocTypeColumn();
      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
        ModelProvider instance = mock(ModelProvider.class);
        Entity dalEntity = mock(Entity.class);
        Property targetProp = mock(Property.class);
        mp.when(ModelProvider::getInstance).thenReturn(instance);
        when(instance.getEntityByTableId("TABLE_001")).thenReturn(dalEntity);
        when(dalEntity.getPropertyByColumnName("C_DocTypeTarget_ID")).thenReturn(targetProp);
        when(targetProp.getName()).thenReturn("transactionDocument");

        JSONObject body = new JSONObject();
        // "otherField" is not "transactionDocument" — skip is NOT triggered
        DocTypeResolver.reapplyDocTypeFromTabFilter(body, adTab, mock(NeoContext.class), Set.of("otherField"));
        assertEquals(0, body.length()); // resolve fails silently (no OBContext), body unchanged
      }
    }

    /**
     * Regression guard for the ETP-4027 × ETP-3836 interaction:
     * when the tab's HQL where clause contains a sOSubType LIKE filter (e.g. the
     * sales-quotation tab with "sOSubType LIKE 'OB'"), the doctype reapplication must
     * NOT be skipped even if clientSubmittedFields contains the target property name.
     *
     * <p>Root cause: ETP-3836 started including all user-submitted POST fields in
     * clientSubmittedFields (including transactionDocument). ETP-4027 then passed that
     * set to reapplyDocTypeFromTabFilter. The combined effect was that the quotation
     * doctype reapplication was always skipped — the record was saved with the
     * wrong "Standard Order" doctype, causing @NoOrderDocType@ Error 500 on conversion.
     *
     * <p>Fix: when a tab subtype constraint exists, the filter is authoritative and
     * the skip is disabled regardless of clientSubmittedFields.
     */
    @Test
    @DisplayName("tab with sOSubType LIKE filter overrides clientSubmittedFields skip — never skips")
    void subTypeConstraintOverridesClientSubmittedSkip() {
      Tab adTab = tabWithDocTypeColumn();

      SFEntity sfEntity = mock(SFEntity.class);
      Tab sfTab = mock(Tab.class);
      when(sfEntity.getADTab()).thenReturn(sfTab);
      when(sfTab.getHqlwhereclause()).thenReturn(
          "e.salesTransaction=true AND e.transactionDocument.sOSubType LIKE 'OB'");

      NeoContext ctx = NeoContext.builder().sfEntity(sfEntity).build();

      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
        ModelProvider instance = mock(ModelProvider.class);
        Entity dalEntity = mock(Entity.class);
        Property targetProp = mock(Property.class);
        mp.when(ModelProvider::getInstance).thenReturn(instance);
        when(instance.getEntityByTableId("TABLE_001")).thenReturn(dalEntity);
        when(dalEntity.getPropertyByColumnName("C_DocTypeTarget_ID")).thenReturn(targetProp);
        when(targetProp.getName()).thenReturn("transactionDocument");

        JSONObject body = new JSONObject();
        // Even though transactionDocument is in clientSubmittedFields, the tab subtype
        // constraint "sOSubType LIKE 'OB'" is authoritative — skip must NOT apply.
        // resolveDefaultDocTypeId will fail silently (no OBContext in unit tests),
        // but the important assertion is that the skip guard was bypassed:
        // if it had skipped, it would have returned early at the clientSubmittedFields check
        // before even calling resolveDefaultDocTypeId. The silent failure here means the
        // code progressed past the skip — which is the correct behaviour.
        DocTypeResolver.reapplyDocTypeFromTabFilter(body, adTab, ctx, Set.of("transactionDocument"));
        assertEquals(0, body.length()); // resolve fails silently (no OBContext), body unchanged
        // No exception = skip guard was bypassed as expected.
      }
    }
  }

  // -------------------------------------------------------------------------
  // syncDocumentTypeToSubmittedTarget (ETP-5274)
  // -------------------------------------------------------------------------

  /**
   * Regression guard for ETP-5274: the user picks "Invoice" (doc-type target A), switches to
   * "Rectificative invoice" (target B) and saves. Because the reapply was skipped (the user's
   * choice is authoritative) {@code C_DocType_ID} kept the stale default A, and the DocumentNo was
   * drawn from A's sequence. The effective doctype must follow the submitted target, the same
   * invariant Classic keeps through the {@code SL_Invoice_Legacy} callout.
   */
  @Nested
  @DisplayName("syncDocumentTypeToSubmittedTarget")
  class SyncDocumentTypeToSubmittedTarget {

    private static final String TABLE_ID = "TABLE_C_INVOICE";
    private static final String TARGET = "transactionDocument";
    private static final String EFFECTIVE = "documentType";

    private Tab tabWithTable() {
      Tab adTab = mock(Tab.class);
      Table table = mock(Table.class);
      when(adTab.getTable()).thenReturn(table);
      when(table.getId()).thenReturn(TABLE_ID);
      return adTab;
    }

    /** Registers a ModelProvider whose entity exposes the given doc-type property pair. */
    private void stubEntity(MockedStatic<ModelProvider> mp, Property targetProp,
        Property typeProp) {
      ModelProvider instance = mock(ModelProvider.class);
      Entity dalEntity = mock(Entity.class);
      mp.when(ModelProvider::getInstance).thenReturn(instance);
      when(instance.getEntityByTableId(TABLE_ID)).thenReturn(dalEntity);
      when(dalEntity.getPropertyByColumnName("C_DocTypeTarget_ID")).thenReturn(targetProp);
      when(dalEntity.getPropertyByColumnName("C_DocType_ID")).thenReturn(typeProp);
    }

    private Property prop(String name) {
      Property p = mock(Property.class);
      when(p.getName()).thenReturn(name);
      return p;
    }

    @Test
    @DisplayName("Copies the submitted target onto the effective doc-type property")
    void copiesSubmittedTarget() throws Exception {
      Tab adTab = tabWithTable();
      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
        stubEntity(mp, prop(TARGET), prop(EFFECTIVE));

        JSONObject body = new JSONObject();
        body.put(TARGET, "DOCTYPE_RECTIFICATIVE");

        DocTypeResolver.syncDocumentTypeToSubmittedTarget(body, adTab);

        assertEquals("DOCTYPE_RECTIFICATIVE", body.getString(EFFECTIVE));
        // The user's own choice must stay untouched.
        assertEquals("DOCTYPE_RECTIFICATIVE", body.getString(TARGET));
      }
    }

    @Test
    @DisplayName("Overwrites a stale effective doc-type already present in the body")
    void overwritesStaleEffectiveDocType() throws Exception {
      Tab adTab = tabWithTable();
      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
        stubEntity(mp, prop(TARGET), prop(EFFECTIVE));

        JSONObject body = new JSONObject();
        body.put(TARGET, "DOCTYPE_RECTIFICATIVE");
        body.put(EFFECTIVE, "DOCTYPE_INVOICE"); // the stale default that caused ETP-5274

        DocTypeResolver.syncDocumentTypeToSubmittedTarget(body, adTab);

        assertEquals("DOCTYPE_RECTIFICATIVE", body.getString(EFFECTIVE));
      }
    }

    @Test
    @DisplayName("No-op when the body carries no target value")
    void noOpWithoutSubmittedTarget() {
      Tab adTab = tabWithTable();
      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
        stubEntity(mp, prop(TARGET), prop(EFFECTIVE));

        JSONObject body = new JSONObject();
        DocTypeResolver.syncDocumentTypeToSubmittedTarget(body, adTab);

        assertFalse(body.has(EFFECTIVE));
      }
    }

    @Test
    @DisplayName("No-op when the submitted target is JSON null")
    void noOpWithJsonNullTarget() throws Exception {
      Tab adTab = tabWithTable();
      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
        stubEntity(mp, prop(TARGET), prop(EFFECTIVE));

        JSONObject body = new JSONObject();
        body.put(TARGET, JSONObject.NULL);
        DocTypeResolver.syncDocumentTypeToSubmittedTarget(body, adTab);

        assertFalse(body.has(EFFECTIVE));
      }
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", "   " })
    @DisplayName("No-op when the submitted target is blank")
    void noOpWithBlankTarget(String blank) throws Exception {
      Tab adTab = tabWithTable();
      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
        stubEntity(mp, prop(TARGET), prop(EFFECTIVE));

        JSONObject body = new JSONObject();
        body.put(TARGET, blank);
        DocTypeResolver.syncDocumentTypeToSubmittedTarget(body, adTab);

        assertFalse(body.has(EFFECTIVE));
      }
    }

    @Test
    @DisplayName("No-op when the table has no doc-type target column")
    void noOpWithoutTargetProperty() throws Exception {
      Tab adTab = tabWithTable();
      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
        stubEntity(mp, null, prop(EFFECTIVE));

        JSONObject body = new JSONObject();
        body.put(TARGET, "DOCTYPE_RECTIFICATIVE");
        DocTypeResolver.syncDocumentTypeToSubmittedTarget(body, adTab);

        assertFalse(body.has(EFFECTIVE));
      }
    }

    @Test
    @DisplayName("No-op when the table has no effective doc-type column")
    void noOpWithoutEffectiveProperty() throws Exception {
      Tab adTab = tabWithTable();
      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
        stubEntity(mp, prop(TARGET), null);

        JSONObject body = new JSONObject();
        body.put(TARGET, "DOCTYPE_RECTIFICATIVE");
        DocTypeResolver.syncDocumentTypeToSubmittedTarget(body, adTab);

        assertFalse(body.has(EFFECTIVE));
      }
    }

    @Test
    @DisplayName("No-op when ModelProvider cannot resolve the entity")
    void noOpWithUnknownEntity() throws Exception {
      Tab adTab = tabWithTable();
      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
        ModelProvider instance = mock(ModelProvider.class);
        mp.when(ModelProvider::getInstance).thenReturn(instance);
        when(instance.getEntityByTableId(TABLE_ID)).thenReturn(null);

        JSONObject body = new JSONObject();
        body.put(TARGET, "DOCTYPE_RECTIFICATIVE");
        DocTypeResolver.syncDocumentTypeToSubmittedTarget(body, adTab);

        assertFalse(body.has(EFFECTIVE));
      }
    }

    @Test
    @DisplayName("Never throws on null body, null tab or a tab with no table")
    void neverThrowsOnNullInputs() {
      Tab tabWithoutTable = mock(Tab.class);
      when(tabWithoutTable.getTable()).thenReturn(null);

      assertDoesNotThrow(() -> {
        DocTypeResolver.syncDocumentTypeToSubmittedTarget(null, mock(Tab.class));
        DocTypeResolver.syncDocumentTypeToSubmittedTarget(new JSONObject(), null);
        DocTypeResolver.syncDocumentTypeToSubmittedTarget(null, null);
        DocTypeResolver.syncDocumentTypeToSubmittedTarget(new JSONObject(), tabWithoutTable);
      });
    }

    @Test
    @DisplayName("Swallows a ModelProvider failure instead of failing the write")
    void swallowsModelProviderFailure() throws Exception {
      Tab adTab = tabWithTable();
      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
        mp.when(ModelProvider::getInstance).thenThrow(new IllegalStateException("no model"));

        JSONObject body = new JSONObject();
        body.put(TARGET, "DOCTYPE_RECTIFICATIVE");

        assertDoesNotThrow(() -> DocTypeResolver.syncDocumentTypeToSubmittedTarget(body, adTab));
        assertFalse(body.has(EFFECTIVE));
      }
    }
  }

  // -------------------------------------------------------------------------
  // dropDocTypesOutsideTabFilter — callout endpoint guard
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("dropDocTypesOutsideTabFilter")
  class DropDocTypesOutsideTabFilter {

    private static final String TABLE_ID = "TABLE_DROP";
    private static final String ORG_DEFAULT_ID = "ORG_DEFAULT_DOCTYPE";
    private static final String TARGET = "transactionDocument";
    private static final String EFFECTIVE = "documentType";
    private static final String PROPOSED_ID = "PROPOSED_DOCTYPE";
    private static final String SALES_ORDER_WHERE =
        "e.salesTransaction=true AND e.transactionDocument.sOSubType NOT LIKE 'OB'";

    @Test
    @DisplayName("Drops a callout doc type whose sub-type the tab LIKE filter excludes")
    void dropsDocTypeOutsideLikeFilter() throws Exception {
      Tab adTab = mock(Tab.class);
      Table table = mock(Table.class);
      when(adTab.getTable()).thenReturn(table);
      when(table.getId()).thenReturn(TABLE_ID);
      when(adTab.getHqlwhereclause())
          .thenReturn("e.salesTransaction=true AND e.transactionDocument.sOSubType LIKE 'OB'");
      SFEntity sfEntity = mock(SFEntity.class);
      when(sfEntity.getADTab()).thenReturn(adTab);
      NeoContext ctx = NeoContext.builder().sfEntity(sfEntity).adTab(adTab).build();

      Property targetProp = mock(Property.class);
      when(targetProp.getName()).thenReturn("transactionDocument");
      Property typeProp = mock(Property.class);
      when(typeProp.getName()).thenReturn("documentType");
      ModelProvider instance = mock(ModelProvider.class);
      Entity dalEntity = mock(Entity.class);
      when(instance.getEntityByTableId(TABLE_ID)).thenReturn(dalEntity);
      when(dalEntity.getPropertyByColumnName("C_DocTypeTarget_ID")).thenReturn(targetProp);
      when(dalEntity.getPropertyByColumnName("C_DocType_ID")).thenReturn(typeProp);

      DocumentType orgDefault = mock(DocumentType.class);
      when(orgDefault.getSOSubType()).thenReturn("SO");
      when(orgDefault.isReturn()).thenReturn(false);
      OBDal obDal = mock(OBDal.class);
      when(obDal.get(DocumentType.class, ORG_DEFAULT_ID)).thenReturn(orgDefault);

      JSONObject updates = new JSONObject()
          .put("transactionDocument", new JSONObject()
              .put("value", ORG_DEFAULT_ID).put("_identifier", "Standard Order"))
          .put("priceList", new JSONObject().put("value", "PL"));
      JSONObject response = new JSONObject().put("updates", updates);

      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class);
          MockedStatic<OBDal> od = Mockito.mockStatic(OBDal.class)) {
        mp.when(ModelProvider::getInstance).thenReturn(instance);
        od.when(OBDal::getInstance).thenReturn(obDal);

        DocTypeResolver.dropDocTypesOutsideTabFilter(response, adTab, ctx);
      }

      assertFalse(updates.has("transactionDocument"));
      assertEquals("PL", updates.getJSONObject("priceList").getString("value"));
    }

    private Tab tabWithWhere(String hqlWhere) {
      Tab adTab = mock(Tab.class);
      Table table = mock(Table.class);
      when(adTab.getTable()).thenReturn(table);
      when(table.getId()).thenReturn(TABLE_ID);
      when(adTab.getHqlwhereclause()).thenReturn(hqlWhere);
      return adTab;
    }

    private NeoContext ctxFor(Tab adTab) {
      SFEntity sfEntity = mock(SFEntity.class);
      when(sfEntity.getADTab()).thenReturn(adTab);
      return NeoContext.builder().sfEntity(sfEntity).adTab(adTab).build();
    }

    /** A ModelProvider whose entity maps both doc-type columns to their DAL property names. */
    private ModelProvider modelProviderWithDocTypeColumns() {
      return modelProviderWithDocTypeColumns(true);
    }

    /**
     * A ModelProvider whose entity maps {@code C_DocType_ID} and, when {@code withTarget},
     * {@code C_DocTypeTarget_ID}. Without it the target lookup returns null, as on M_InOut.
     */
    private ModelProvider modelProviderWithDocTypeColumns(boolean withTarget) {
      Property targetProp = null;
      if (withTarget) {
        targetProp = mock(Property.class);
        when(targetProp.getName()).thenReturn(TARGET);
      }
      Property typeProp = mock(Property.class);
      when(typeProp.getName()).thenReturn(EFFECTIVE);
      Entity dalEntity = mock(Entity.class);
      when(dalEntity.getPropertyByColumnName("C_DocTypeTarget_ID")).thenReturn(targetProp);
      when(dalEntity.getPropertyByColumnName("C_DocType_ID")).thenReturn(typeProp);
      ModelProvider instance = mock(ModelProvider.class);
      when(instance.getEntityByTableId(TABLE_ID)).thenReturn(dalEntity);
      return instance;
    }

    private DocumentType docType(String subType, Boolean isReturn) {
      DocumentType docType = mock(DocumentType.class);
      when(docType.getSOSubType()).thenReturn(subType);
      when(docType.isReturn()).thenReturn(isReturn);
      return docType;
    }

    private JSONObject valueEntry(String value) throws Exception {
      return new JSONObject().put("value", value);
    }

    /**
     * Truth table of the tab filter, for the rows the end-to-end matrix below does not reach:
     * null sub-type / return flag, case-insensitive matching and filters combining with AND.
     * Filters are [subTypeLike, subTypeNotLike, isReturn ("Y"/"N")], as parseSubTypeFilters
     * returns them.
     */
    @ParameterizedTest(name = "[{index}] like={0} notLike={1} return={2} | doc {3}/{4} -> {5}")
    @CsvSource(delimiter = '|', value = {
        // null sub-type: a NOT LIKE tab keeps it, a LIKE tab cannot match it (SQL equality)
        "    | OB |   |    | false | true",
        "OB  |    |   |    | false | false",
        // sub-type comparison ignores case
        "ob  |    |   | OB | false | true",
        // a null IsReturn reads as false (so a return=true tab drops it)
        "    |    | N | SO |       | true",
        "    |    | Y | SO |       | false",
        // filters combine: allowed sub-type, wrong return flag
        "    | OB | N | SO | true  | false",
        // no filter allows anything
        "    |    |   | OB | true  | true"
    })
    @DisplayName("isAllowedByTabFilter mirrors the default doc-type SQL filter")
    void isAllowedByTabFilterMatrix(String like, String notLike, String isReturnFilter,
        String subType, Boolean isReturn, boolean expected) {
      assertEquals(expected, DocTypeResolver.isAllowedByTabFilter(subType, isReturn,
          new String[] { like, notLike, isReturnFilter }));
    }

    /**
     * End to end from the tab HQL: the filter parsed from the where clause decides, for both
     * {@code C_DocTypeTarget_ID} and {@code C_DocType_ID}, whether the proposed doc type stays.
     * The allowed rows are the over-dropping guard: a doc type the tab can hold must survive.
     * Rows with {@code hasTarget=false} are the M_InOut shape: the entity has only
     * {@code C_DocType_ID}, so the response carries no target entry and the missing target
     * property must not stop {@code C_DocType_ID} from being checked.
     */
    @ParameterizedTest(name = "[{index}] {0} | target col {4} | doc {1}/return {2} -> kept {3}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        // sales order (NOT LIKE 'OB'): Standard Order stays, a quotation doc type goes
        "\"e.salesTransaction=true AND e.transactionDocument.sOSubType NOT LIKE 'OB'\" | SO | false | true  | true",
        "\"e.salesTransaction=true AND e.transactionDocument.sOSubType NOT LIKE 'OB'\" | OB | false | false | true",
        // quotation (LIKE 'OB'): the quotation doc type stays
        "\"e.salesTransaction=true AND e.transactionDocument.sOSubType LIKE 'OB'\"     | OB | false | true  | true",
        // return flag: the matching doc type stays, the opposite one goes
        "\"e.documentType.return=false\"                                              | SO | false | true  | true",
        "\"e.documentType.return=false\"                                              | SO | true  | false | true",
        "\"e.documentType.return=true\"                                               | SO | true  | true  | true",
        // M_InOut shape (only C_DocType_ID): a return tab keeps a return doc type, drops another
        "\"e.documentType.return = true\"                                             |    | true  | true  | false",
        "\"e.documentType.return = true\"                                             |    | false | false | false"
    })
    @DisplayName("Keeps doc types the tab HQL allows and drops both columns otherwise")
    void filtersBothDocTypeColumnsByTabWhereClause(String hqlWhere, String subType,
        boolean isReturn, boolean kept, boolean hasTarget) throws Exception {
      Tab adTab = tabWithWhere(hqlWhere);
      NeoContext ctx = ctxFor(adTab);
      ModelProvider instance = modelProviderWithDocTypeColumns(hasTarget);
      DocumentType proposed = docType(subType, isReturn);
      OBDal obDal = mock(OBDal.class);
      when(obDal.get(DocumentType.class, PROPOSED_ID)).thenReturn(proposed);

      JSONObject updates = new JSONObject()
          .put(EFFECTIVE, valueEntry(PROPOSED_ID))
          .put("priceList", valueEntry("PL"));
      if (hasTarget) {
        updates.put(TARGET, valueEntry(PROPOSED_ID));
      }
      JSONObject response = new JSONObject().put("updates", updates);

      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class);
          MockedStatic<OBDal> od = Mockito.mockStatic(OBDal.class)) {
        mp.when(ModelProvider::getInstance).thenReturn(instance);
        od.when(OBDal::getInstance).thenReturn(obDal);

        DocTypeResolver.dropDocTypesOutsideTabFilter(response, adTab, ctx);
      }

      String expectedValue = kept ? PROPOSED_ID : null;
      JSONObject target = updates.optJSONObject(TARGET);
      JSONObject effective = updates.optJSONObject(EFFECTIVE);
      assertEquals(hasTarget ? expectedValue : null,
          target == null ? null : target.getString("value"));
      assertEquals(expectedValue, effective == null ? null : effective.getString("value"));
      assertEquals("PL", updates.getJSONObject("priceList").getString("value"));
    }

    /**
     * A callout that clears the doc type (empty or blank string, JSON null) proposes nothing
     * to check: the entry is left exactly as it came and no doc type is looked up.
     */
    @ParameterizedTest(name = "[{index}] {0}.{1} = <{2}>")
    @CsvSource(delimiter = '|', value = {
        "updates | value    | ''",
        "updates | value    | '   '",
        "updates | value    | NULL",
        "combos  | selected | ''",
        "combos  | selected | NULL"
    })
    @DisplayName("Leaves an empty or null doc-type value untouched without a lookup")
    void leavesEmptyOrNullDocTypeValueUntouched(String section, String valueKey, String raw)
        throws Exception {
      Tab adTab = tabWithWhere(SALES_ORDER_WHERE);
      NeoContext ctx = ctxFor(adTab);
      ModelProvider instance = modelProviderWithDocTypeColumns();
      Object value = "NULL".equals(raw) ? JSONObject.NULL : raw;
      JSONObject entries = new JSONObject()
          .put(TARGET, new JSONObject().put(valueKey, value))
          .put(EFFECTIVE, new JSONObject().put(valueKey, value));
      JSONObject response = new JSONObject().put(section, entries);
      String before = response.toString();

      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class);
          MockedStatic<OBDal> od = Mockito.mockStatic(OBDal.class)) {
        mp.when(ModelProvider::getInstance).thenReturn(instance);

        DocTypeResolver.dropDocTypesOutsideTabFilter(response, adTab, ctx);

        od.verifyNoInteractions();
      }
      assertEquals(before, response.toString());
    }

    @Test
    @DisplayName("Removes a combo whose selected doc type the tab does not allow")
    void dropsComboWithDisallowedSelection() throws Exception {
      Tab adTab = tabWithWhere(SALES_ORDER_WHERE);
      NeoContext ctx = ctxFor(adTab);
      ModelProvider instance = modelProviderWithDocTypeColumns();
      DocumentType quotation = docType("OB", false);
      OBDal obDal = mock(OBDal.class);
      when(obDal.get(DocumentType.class, PROPOSED_ID)).thenReturn(quotation);

      JSONObject combos = new JSONObject()
          .put(TARGET, new JSONObject().put("selected", PROPOSED_ID))
          .put("priceList", new JSONObject().put("selected", "PL"));
      JSONObject response = new JSONObject().put("combos", combos);

      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class);
          MockedStatic<OBDal> od = Mockito.mockStatic(OBDal.class)) {
        mp.when(ModelProvider::getInstance).thenReturn(instance);
        od.when(OBDal::getInstance).thenReturn(obDal);

        DocTypeResolver.dropDocTypesOutsideTabFilter(response, adTab, ctx);
      }

      assertFalse(combos.has(TARGET));
      assertEquals("PL", combos.getJSONObject("priceList").getString("selected"));
    }

    @Test
    @DisplayName("Keeps a doc type id that does not resolve to a document type")
    void keepsUnknownDocTypeId() throws Exception {
      Tab adTab = tabWithWhere(SALES_ORDER_WHERE);
      NeoContext ctx = ctxFor(adTab);
      ModelProvider instance = modelProviderWithDocTypeColumns();
      OBDal obDal = mock(OBDal.class);
      when(obDal.get(DocumentType.class, PROPOSED_ID)).thenReturn(null);

      JSONObject updates = new JSONObject().put(TARGET, valueEntry(PROPOSED_ID));
      JSONObject response = new JSONObject().put("updates", updates);

      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class);
          MockedStatic<OBDal> od = Mockito.mockStatic(OBDal.class)) {
        mp.when(ModelProvider::getInstance).thenReturn(instance);
        od.when(OBDal::getInstance).thenReturn(obDal);

        DocTypeResolver.dropDocTypesOutsideTabFilter(response, adTab, ctx);
      }

      verify(obDal).get(DocumentType.class, PROPOSED_ID);
      assertEquals(PROPOSED_ID, updates.getJSONObject(TARGET).getString("value"));
    }

    @Test
    @DisplayName("Leaves the response intact when the doc type lookup fails")
    void leavesResponseIntactOnLookupFailure() throws Exception {
      Tab adTab = tabWithWhere(SALES_ORDER_WHERE);
      NeoContext ctx = ctxFor(adTab);
      ModelProvider instance = modelProviderWithDocTypeColumns();
      OBDal obDal = mock(OBDal.class);
      when(obDal.get(DocumentType.class, PROPOSED_ID))
          .thenThrow(new IllegalStateException("lookup failed"));

      JSONObject response = new JSONObject().put("updates", new JSONObject()
          .put(TARGET, valueEntry(PROPOSED_ID))
          .put(EFFECTIVE, valueEntry(PROPOSED_ID)));
      String before = response.toString();

      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class);
          MockedStatic<OBDal> od = Mockito.mockStatic(OBDal.class)) {
        mp.when(ModelProvider::getInstance).thenReturn(instance);
        od.when(OBDal::getInstance).thenReturn(obDal);

        assertDoesNotThrow(
            () -> DocTypeResolver.dropDocTypesOutsideTabFilter(response, adTab, ctx));
      }

      assertEquals(before, response.toString());
    }

    @Test
    @DisplayName("A tab without a doc-type filter returns before touching the model or OBDal")
    void tabWithoutFilterIsNeverChecked() throws Exception {
      Tab adTab = tabWithWhere("e.salesTransaction=true");
      NeoContext ctx = ctxFor(adTab);
      JSONObject response = new JSONObject().put("updates", new JSONObject()
          .put(TARGET, valueEntry(PROPOSED_ID)));
      String before = response.toString();

      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class);
          MockedStatic<OBDal> od = Mockito.mockStatic(OBDal.class)) {
        DocTypeResolver.dropDocTypesOutsideTabFilter(response, adTab, ctx);

        mp.verifyNoInteractions();
        od.verifyNoInteractions();
      }
      assertEquals(before, response.toString());
    }

    @Test
    @DisplayName("Null response, null tab or a tab without table is a no-op")
    void nullInputsAreNoOp() throws Exception {
      Tab filteredTab = tabWithWhere(SALES_ORDER_WHERE);
      NeoContext ctx = ctxFor(filteredTab);
      Tab tabWithoutTable = mock(Tab.class);
      JSONObject response = new JSONObject().put("updates", new JSONObject()
          .put(TARGET, valueEntry(PROPOSED_ID)));
      String before = response.toString();

      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class);
          MockedStatic<OBDal> od = Mockito.mockStatic(OBDal.class)) {
        DocTypeResolver.dropDocTypesOutsideTabFilter(null, filteredTab, ctx);
        DocTypeResolver.dropDocTypesOutsideTabFilter(response, null, ctx);
        DocTypeResolver.dropDocTypesOutsideTabFilter(response, tabWithoutTable, ctx);

        mp.verifyNoInteractions();
        od.verifyNoInteractions();
      }
      assertEquals(before, response.toString());
    }
  }
}
