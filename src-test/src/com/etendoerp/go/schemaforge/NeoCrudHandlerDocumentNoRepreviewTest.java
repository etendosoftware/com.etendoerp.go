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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Unit tests for the ETP-5274 DocumentNo re-preview path in {@link NeoCrudHandler}:
 * {@code regenerateDocumentNoOnDocTypeChange} plus its two static helpers
 * {@code isDraftRecord} and {@code resolveStoredId}.
 *
 * <p>The bug: the user picked "Invoice" (DocumentNo preview {@code <10000000>}), switched the
 * document type to "Rectificative invoice" (preview {@code <REC-1000008>}) and saved. The record
 * persisted {@code documentNo = 10000000} — the number of the ORIGINAL doc type's sequence. On a
 * PATCH over a DRAFT record whose doc-type target changed, and where the client did not author a
 * DocumentNo of its own, the number must be re-previewed from the NEW sequence (bracketed
 * placeholder — the sequence is previewed, never consumed).
 *
 * <p>All methods under test are private, reached through reflection exactly as
 * {@code NeoCrudHandlerTest} and {@code NeoCrudHandlerStaleRecordTest} already do.
 */
class NeoCrudHandlerDocumentNoRepreviewTest {

  private static final String TABLE_ID = "TABLE_C_INVOICE";
  private static final String DAL_ENTITY_NAME = "Invoice";
  private static final String RECORD_ID = "INV_001";
  private static final String PROP_TARGET = "transactionDocument";
  private static final String PROP_DOC_NO = "documentNo";
  private static final String COL_TARGET = "C_DocTypeTarget_ID";
  private static final String COL_DOC_NO = "DocumentNo";
  private static final String OLD_DOC_TYPE = "DOCTYPE_INVOICE";
  private static final String NEW_DOC_TYPE = "DOCTYPE_RECTIFICATIVE";
  private static final String NEW_PREVIEW = "<REC-1000008>";

  private OBContext obContext;

  @BeforeEach
  void setUp() {
    obContext = mock(OBContext.class);
  }

  // -------------------------------------------------------------------------
  // Reflection plumbing
  // -------------------------------------------------------------------------

  private void invokeRegenerate(JSONObject body, NeoContext context, String dalEntityName,
      boolean clientSentDocumentNo) throws Exception {
    Method method = DocumentNoRepreviewHelper.class.getDeclaredMethod(
        "regenerateDocumentNoOnDocTypeChange",
        JSONObject.class, NeoContext.class, String.class, boolean.class);
    method.setAccessible(true);
    method.invoke(null, body, context, dalEntityName, clientSentDocumentNo);
  }

  private static boolean invokeIsDraftRecord(BaseOBObject stored, Entity dalEntity)
      throws Exception {
    Method method = DocumentNoRepreviewHelper.class.getDeclaredMethod("isDraftRecord",
        BaseOBObject.class, Entity.class);
    method.setAccessible(true);
    return (boolean) method.invoke(null, stored, dalEntity);
  }

  private static String invokeResolveStoredId(BaseOBObject stored, Property prop)
      throws Exception {
    Method method = DocumentNoRepreviewHelper.class.getDeclaredMethod("resolveStoredId",
        BaseOBObject.class, Property.class);
    method.setAccessible(true);
    return (String) method.invoke(null, stored, prop);
  }

  private static boolean invokeIsSequencePlaceholder(String value) throws Exception {
    Method method =
        DocumentNoRepreviewHelper.class.getDeclaredMethod("isSequencePlaceholder", String.class);
    method.setAccessible(true);
    return (boolean) method.invoke(null, value);
  }

  private static boolean invokeHasClientAuthoredDocumentNo(JSONObject rawBody) throws Exception {
    Method method = DocumentNoRepreviewHelper.class.getDeclaredMethod(
        "hasClientAuthoredDocumentNo", JSONObject.class);
    method.setAccessible(true);
    return (boolean) method.invoke(null, rawBody);
  }

  // -------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------

  private static Property prop(String name) {
    Property p = mock(Property.class);
    when(p.getName()).thenReturn(name);
    return p;
  }

  /** A tab whose table exposes the given AD columns. */
  private static Tab tab(List<Column> columns) {
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn(TABLE_ID);
    when(table.getADColumnList()).thenReturn(columns);
    return adTab;
  }

  private static Column column(String dbColumnName) {
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn(dbColumnName);
    return col;
  }

  private static Tab tabWithDocNoColumn() {
    return tab(List.of(column(COL_TARGET), column(COL_DOC_NO)));
  }

  private NeoContext context(Tab adTab, String recordId) {
    return NeoContext.builder()
        .specName("sales-invoice")
        .entityName("header")
        .httpMethod("PATCH")
        .recordId(recordId)
        .adTab(adTab)
        .obContext(obContext)
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  /**
   * Registers a ModelProvider whose entity resolves the doc-type target and DocumentNo
   * properties through the two-argument {@code getPropertyByColumnName} overload.
   */
  private static Entity stubEntity(MockedStatic<ModelProvider> mp, Property targetProp,
      Property docNoProp) {
    ModelProvider instance = mock(ModelProvider.class);
    Entity dalEntity = mock(Entity.class);
    mp.when(ModelProvider::getInstance).thenReturn(instance);
    when(instance.getEntityByTableId(TABLE_ID)).thenReturn(dalEntity);
    when(dalEntity.getPropertyByColumnName(COL_TARGET, false)).thenReturn(targetProp);
    when(dalEntity.getPropertyByColumnName(COL_DOC_NO, false)).thenReturn(docNoProp);
    return dalEntity;
  }

  /** A persisted record in DRAFT whose doc-type target is {@link #OLD_DOC_TYPE}. */
  private static BaseOBObject storedDraft(Entity dalEntity, String storedTargetId) {
    BaseOBObject stored = mock(BaseOBObject.class);
    when(dalEntity.hasProperty("documentStatus")).thenReturn(true);
    when(stored.get("documentStatus")).thenReturn("DR");
    BaseOBObject targetRef = mock(BaseOBObject.class);
    when(targetRef.getId()).thenReturn(storedTargetId);
    when(stored.get(PROP_TARGET)).thenReturn(targetRef);
    return stored;
  }

  private static JSONObject bodyWithTarget(String targetId) throws Exception {
    JSONObject body = new JSONObject();
    body.put(PROP_TARGET, targetId);
    return body;
  }

  // -------------------------------------------------------------------------
  // regenerateDocumentNoOnDocTypeChange — the ETP-5274 happy path
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("regenerateDocumentNoOnDocTypeChange — re-previews the new sequence")
  class HappyPath {

    @Test
    @DisplayName("Draft + changed doc-type + no client DocumentNo → body gets the new preview")
    void rewritesDocumentNoFromTheNewSequence() throws Exception {
      Tab adTab = tabWithDocNoColumn();
      JSONObject body = bodyWithTarget(NEW_DOC_TYPE);

      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
           MockedStatic<NeoDefaultsService> defaults =
               Mockito.mockStatic(NeoDefaultsService.class);
           MockedStatic<NeoSequencePreviewHelper> seq =
               Mockito.mockStatic(NeoSequencePreviewHelper.class)) {

        Entity dalEntity = stubEntity(mp, prop(PROP_TARGET), prop(PROP_DOC_NO));
        BaseOBObject stored = storedDraft(dalEntity, OLD_DOC_TYPE);

        OBDal dal = mock(OBDal.class);
        obDal.when(OBDal::getInstance).thenReturn(dal);
        when(dal.get(DAL_ENTITY_NAME, RECORD_ID)).thenReturn(stored);

        defaults.when(() -> NeoDefaultsService.buildVariablesSecureApp(any(OBContext.class),
            any(Tab.class))).thenReturn(mock(VariablesSecureApp.class));
        seq.when(() -> NeoSequencePreviewHelper.resolveSequencePreviewWithDocType(
            any(Column.class), any(VariablesSecureApp.class), any(DalConnectionProvider.class),
            anyString(), eq(NEW_DOC_TYPE), eq(NEW_DOC_TYPE))).thenReturn(NEW_PREVIEW);

        invokeRegenerate(body, context(adTab, RECORD_ID), DAL_ENTITY_NAME, false);

        assertEquals(NEW_PREVIEW, body.getString(PROP_DOC_NO));
        assertTrue(body.getString(PROP_DOC_NO).startsWith("<"),
            "the preview must stay a placeholder — the sequence is not consumed yet");
      }
    }

    @Test
    @DisplayName("A blank preview leaves DocumentNo absent rather than blanking it")
    void blankPreviewIsNotWritten() throws Exception {
      Tab adTab = tabWithDocNoColumn();
      JSONObject body = bodyWithTarget(NEW_DOC_TYPE);

      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
           MockedStatic<NeoDefaultsService> defaults =
               Mockito.mockStatic(NeoDefaultsService.class);
           MockedStatic<NeoSequencePreviewHelper> seq =
               Mockito.mockStatic(NeoSequencePreviewHelper.class)) {

        Entity dalEntity = stubEntity(mp, prop(PROP_TARGET), prop(PROP_DOC_NO));
        // Built before the stubbing call: storedDraft() itself stubs mocks, and Mockito
        // rejects that when it happens inside an unfinished when(...).thenReturn(...).
        BaseOBObject stored = storedDraft(dalEntity, OLD_DOC_TYPE);
        OBDal dal = mock(OBDal.class);
        obDal.when(OBDal::getInstance).thenReturn(dal);
        when(dal.get(DAL_ENTITY_NAME, RECORD_ID)).thenReturn(stored);

        VariablesSecureApp vars = mock(VariablesSecureApp.class);
        defaults.when(() -> NeoDefaultsService.buildVariablesSecureApp(any(OBContext.class),
            any(Tab.class))).thenReturn(vars);
        seq.when(() -> NeoSequencePreviewHelper.resolveSequencePreviewWithDocType(
            any(Column.class), any(VariablesSecureApp.class), any(DalConnectionProvider.class),
            anyString(), anyString(), anyString())).thenReturn("");

        invokeRegenerate(body, context(adTab, RECORD_ID), DAL_ENTITY_NAME, false);

        assertFalse(body.has(PROP_DOC_NO));
      }
    }

    @Test
    @DisplayName("Table without a DocumentNo AD column → no-op")
    void noDocumentNoColumnIsNoOp() throws Exception {
      Tab adTab = tab(List.of(column(COL_TARGET)));
      JSONObject body = bodyWithTarget(NEW_DOC_TYPE);

      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {

        Entity dalEntity = stubEntity(mp, prop(PROP_TARGET), prop(PROP_DOC_NO));
        BaseOBObject stored = storedDraft(dalEntity, OLD_DOC_TYPE);
        OBDal dal = mock(OBDal.class);
        obDal.when(OBDal::getInstance).thenReturn(dal);
        when(dal.get(DAL_ENTITY_NAME, RECORD_ID)).thenReturn(stored);

        invokeRegenerate(body, context(adTab, RECORD_ID), DAL_ENTITY_NAME, false);

        assertFalse(body.has(PROP_DOC_NO));
      }
    }
  }

  // -------------------------------------------------------------------------
  // regenerateDocumentNoOnDocTypeChange — every documented no-op guard
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("regenerateDocumentNoOnDocTypeChange — no-op guards")
  class Guards {

    @Test
    @DisplayName("Client authored its own DocumentNo → never touched")
    void clientSentDocumentNoWins() throws Exception {
      JSONObject body = bodyWithTarget(NEW_DOC_TYPE);
      // No static mocks at all: if the guard did not fire, ModelProvider/OBDal would be hit.
      invokeRegenerate(body, context(tabWithDocNoColumn(), RECORD_ID), DAL_ENTITY_NAME, true);
      assertFalse(body.has(PROP_DOC_NO));
    }

    @Test
    @DisplayName("Null body or null context → never throws")
    void nullInputsAreSafe() {
      assertDoesNotThrow(() -> {
        invokeRegenerate(null, context(tabWithDocNoColumn(), RECORD_ID), DAL_ENTITY_NAME, false);
        invokeRegenerate(new JSONObject(), null, DAL_ENTITY_NAME, false);
      });
    }

    @Test
    @DisplayName("No record id (a create, not a PATCH) → no-op")
    void blankRecordIdIsNoOp() throws Exception {
      JSONObject body = bodyWithTarget(NEW_DOC_TYPE);
      invokeRegenerate(body, context(tabWithDocNoColumn(), null), DAL_ENTITY_NAME, false);
      assertFalse(body.has(PROP_DOC_NO));
    }

    @Test
    @DisplayName("Tab without a table → no-op")
    void tabWithoutTableIsNoOp() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getTable()).thenReturn(null);
      JSONObject body = bodyWithTarget(NEW_DOC_TYPE);
      invokeRegenerate(body, context(adTab, RECORD_ID), DAL_ENTITY_NAME, false);
      assertFalse(body.has(PROP_DOC_NO));
    }

    @Test
    @DisplayName("ModelProvider cannot resolve the entity → no-op")
    void unknownEntityIsNoOp() throws Exception {
      JSONObject body = bodyWithTarget(NEW_DOC_TYPE);
      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
        ModelProvider instance = mock(ModelProvider.class);
        mp.when(ModelProvider::getInstance).thenReturn(instance);
        when(instance.getEntityByTableId(TABLE_ID)).thenReturn(null);

        invokeRegenerate(body, context(tabWithDocNoColumn(), RECORD_ID), DAL_ENTITY_NAME, false);
        assertFalse(body.has(PROP_DOC_NO));
      }
    }

    @Test
    @DisplayName("Entity with no doc-type target property → no-op")
    void entityWithoutTargetPropertyIsNoOp() throws Exception {
      JSONObject body = bodyWithTarget(NEW_DOC_TYPE);
      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
        stubEntity(mp, null, prop(PROP_DOC_NO));
        invokeRegenerate(body, context(tabWithDocNoColumn(), RECORD_ID), DAL_ENTITY_NAME, false);
        assertFalse(body.has(PROP_DOC_NO));
      }
    }

    @Test
    @DisplayName("Entity with no DocumentNo property → no-op")
    void entityWithoutDocumentNoPropertyIsNoOp() throws Exception {
      JSONObject body = bodyWithTarget(NEW_DOC_TYPE);
      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
        stubEntity(mp, prop(PROP_TARGET), null);
        invokeRegenerate(body, context(tabWithDocNoColumn(), RECORD_ID), DAL_ENTITY_NAME, false);
        assertFalse(body.has(PROP_DOC_NO));
      }
    }

    @Test
    @DisplayName("Body already carries a DocumentNo → preserved verbatim")
    void bodyWithDocumentNoIsPreserved() throws Exception {
      JSONObject body = bodyWithTarget(NEW_DOC_TYPE);
      body.put(PROP_DOC_NO, "MANUAL-42");
      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
        stubEntity(mp, prop(PROP_TARGET), prop(PROP_DOC_NO));
        invokeRegenerate(body, context(tabWithDocNoColumn(), RECORD_ID), DAL_ENTITY_NAME, false);
        assertEquals("MANUAL-42", body.getString(PROP_DOC_NO));
      }
    }

    @Test
    @DisplayName("Body carries no doc-type target → no-op")
    void bodyWithoutTargetIsNoOp() throws Exception {
      JSONObject body = new JSONObject();
      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
        stubEntity(mp, prop(PROP_TARGET), prop(PROP_DOC_NO));
        invokeRegenerate(body, context(tabWithDocNoColumn(), RECORD_ID), DAL_ENTITY_NAME, false);
        assertFalse(body.has(PROP_DOC_NO));
      }
    }

    @Test
    @DisplayName("Persisted record not found → no-op")
    void missingStoredRecordIsNoOp() throws Exception {
      JSONObject body = bodyWithTarget(NEW_DOC_TYPE);
      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
        stubEntity(mp, prop(PROP_TARGET), prop(PROP_DOC_NO));
        OBDal dal = mock(OBDal.class);
        obDal.when(OBDal::getInstance).thenReturn(dal);
        when(dal.get(DAL_ENTITY_NAME, RECORD_ID)).thenReturn(null);

        invokeRegenerate(body, context(tabWithDocNoColumn(), RECORD_ID), DAL_ENTITY_NAME, false);
        assertFalse(body.has(PROP_DOC_NO));
      }
    }

    @Test
    @DisplayName("Record already completed → number is frozen, no re-preview")
    void completedRecordIsNoOp() throws Exception {
      JSONObject body = bodyWithTarget(NEW_DOC_TYPE);
      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
        Entity dalEntity = stubEntity(mp, prop(PROP_TARGET), prop(PROP_DOC_NO));
        BaseOBObject stored = mock(BaseOBObject.class);
        when(dalEntity.hasProperty("documentStatus")).thenReturn(true);
        when(stored.get("documentStatus")).thenReturn("CO");

        OBDal dal = mock(OBDal.class);
        obDal.when(OBDal::getInstance).thenReturn(dal);
        when(dal.get(DAL_ENTITY_NAME, RECORD_ID)).thenReturn(stored);

        invokeRegenerate(body, context(tabWithDocNoColumn(), RECORD_ID), DAL_ENTITY_NAME, false);
        assertFalse(body.has(PROP_DOC_NO));
      }
    }

    @Test
    @DisplayName("Doc-type target unchanged → persisted number kept (SL_Invoice_Legacy parity)")
    void unchangedTargetIsNoOp() throws Exception {
      JSONObject body = bodyWithTarget(OLD_DOC_TYPE);
      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
        Entity dalEntity = stubEntity(mp, prop(PROP_TARGET), prop(PROP_DOC_NO));
        BaseOBObject stored = storedDraft(dalEntity, OLD_DOC_TYPE);
        OBDal dal = mock(OBDal.class);
        obDal.when(OBDal::getInstance).thenReturn(dal);
        when(dal.get(DAL_ENTITY_NAME, RECORD_ID)).thenReturn(stored);

        invokeRegenerate(body, context(tabWithDocNoColumn(), RECORD_ID), DAL_ENTITY_NAME, false);
        assertFalse(body.has(PROP_DOC_NO));
      }
    }

    @Test
    @DisplayName("An OBDal failure is swallowed — a stale number must not fail the update")
    void obDalFailureIsSwallowed() throws Exception {
      JSONObject body = bodyWithTarget(NEW_DOC_TYPE);
      try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
        stubEntity(mp, prop(PROP_TARGET), prop(PROP_DOC_NO));
        obDal.when(OBDal::getInstance).thenThrow(new IllegalStateException("no session"));

        assertDoesNotThrow(() -> invokeRegenerate(
            body, context(tabWithDocNoColumn(), RECORD_ID), DAL_ENTITY_NAME, false));
        assertFalse(body.has(PROP_DOC_NO));
      }
    }
  }

  // -------------------------------------------------------------------------
  // isDraftRecord
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("isDraftRecord")
  class IsDraftRecord {

    @Test
    @DisplayName("documentStatus 'DR' is a draft")
    void documentStatusDraft() throws Exception {
      Entity dalEntity = mock(Entity.class);
      BaseOBObject stored = mock(BaseOBObject.class);
      when(dalEntity.hasProperty("documentStatus")).thenReturn(true);
      when(stored.get("documentStatus")).thenReturn("DR");

      assertTrue(invokeIsDraftRecord(stored, dalEntity));
    }

    @Test
    @DisplayName("Any other documentStatus is not a draft")
    void documentStatusCompleted() throws Exception {
      Entity dalEntity = mock(Entity.class);
      BaseOBObject stored = mock(BaseOBObject.class);
      when(dalEntity.hasProperty("documentStatus")).thenReturn(true);
      when(stored.get("documentStatus")).thenReturn("CO");

      assertFalse(invokeIsDraftRecord(stored, dalEntity));
    }

    @Test
    @DisplayName("documentStatus is authoritative and wins over processed")
    void documentStatusBeatsProcessed() throws Exception {
      Entity dalEntity = mock(Entity.class);
      BaseOBObject stored = mock(BaseOBObject.class);
      when(dalEntity.hasProperty("documentStatus")).thenReturn(true);
      when(stored.get("documentStatus")).thenReturn("CO");
      // processed=false would say "draft" — it must not be consulted at all.
      when(dalEntity.hasProperty("processed")).thenReturn(true);
      when(stored.get("processed")).thenReturn(Boolean.FALSE);

      assertFalse(invokeIsDraftRecord(stored, dalEntity));
    }

    @Test
    @DisplayName("Falls back to processed when documentStatus is declared but unset")
    void nullDocumentStatusFallsBackToProcessed() throws Exception {
      Entity dalEntity = mock(Entity.class);
      BaseOBObject stored = mock(BaseOBObject.class);
      when(dalEntity.hasProperty("documentStatus")).thenReturn(true);
      when(stored.get("documentStatus")).thenReturn(null);
      when(dalEntity.hasProperty("processed")).thenReturn(true);
      when(stored.get("processed")).thenReturn(Boolean.FALSE);

      assertTrue(invokeIsDraftRecord(stored, dalEntity));
    }

    @Test
    @DisplayName("processed=false is a draft when the entity has no documentStatus")
    void processedFalseIsDraft() throws Exception {
      Entity dalEntity = mock(Entity.class);
      BaseOBObject stored = mock(BaseOBObject.class);
      when(dalEntity.hasProperty("documentStatus")).thenReturn(false);
      when(dalEntity.hasProperty("processed")).thenReturn(true);
      when(stored.get("processed")).thenReturn(Boolean.FALSE);

      assertTrue(invokeIsDraftRecord(stored, dalEntity));
    }

    @Test
    @DisplayName("processed=true is not a draft")
    void processedTrueIsNotDraft() throws Exception {
      Entity dalEntity = mock(Entity.class);
      BaseOBObject stored = mock(BaseOBObject.class);
      when(dalEntity.hasProperty("documentStatus")).thenReturn(false);
      when(dalEntity.hasProperty("processed")).thenReturn(true);
      when(stored.get("processed")).thenReturn(Boolean.TRUE);

      assertFalse(invokeIsDraftRecord(stored, dalEntity));
    }

    @Test
    @DisplayName("processed unset is not a draft (only FALSE counts)")
    void processedNullIsNotDraft() throws Exception {
      Entity dalEntity = mock(Entity.class);
      BaseOBObject stored = mock(BaseOBObject.class);
      when(dalEntity.hasProperty("documentStatus")).thenReturn(false);
      when(dalEntity.hasProperty("processed")).thenReturn(true);
      when(stored.get("processed")).thenReturn(null);

      assertFalse(invokeIsDraftRecord(stored, dalEntity));
    }

    @Test
    @DisplayName("An entity with neither property is not a document with a completion flow")
    void neitherPropertyIsNotDraft() throws Exception {
      Entity dalEntity = mock(Entity.class);
      when(dalEntity.hasProperty("documentStatus")).thenReturn(false);
      when(dalEntity.hasProperty("processed")).thenReturn(false);

      assertFalse(invokeIsDraftRecord(mock(BaseOBObject.class), dalEntity));
    }
  }

  // -------------------------------------------------------------------------
  // resolveStoredId
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("resolveStoredId")
  class ResolveStoredId {

    @Test
    @DisplayName("Unwraps the id of a reference property")
    void unwrapsReferenceId() throws Exception {
      BaseOBObject stored = mock(BaseOBObject.class);
      BaseOBObject ref = mock(BaseOBObject.class);
      when(ref.getId()).thenReturn(OLD_DOC_TYPE);
      when(stored.get(PROP_TARGET)).thenReturn(ref);

      assertEquals(OLD_DOC_TYPE, invokeResolveStoredId(stored, prop(PROP_TARGET)));
    }

    @Test
    @DisplayName("Returns null when the reference itself carries no id")
    void referenceWithoutIdReturnsNull() throws Exception {
      BaseOBObject stored = mock(BaseOBObject.class);
      BaseOBObject ref = mock(BaseOBObject.class);
      when(ref.getId()).thenReturn(null);
      when(stored.get(PROP_TARGET)).thenReturn(ref);

      assertNull(invokeResolveStoredId(stored, prop(PROP_TARGET)));
    }

    @Test
    @DisplayName("Returns null for an unset property")
    void unsetPropertyReturnsNull() throws Exception {
      BaseOBObject stored = mock(BaseOBObject.class);
      when(stored.get(PROP_TARGET)).thenReturn(null);

      assertNull(invokeResolveStoredId(stored, prop(PROP_TARGET)));
    }

    @Test
    @DisplayName("Stringifies a non-reference scalar value")
    void stringifiesScalar() throws Exception {
      BaseOBObject stored = mock(BaseOBObject.class);
      when(stored.get(PROP_TARGET)).thenReturn("RAW_ID");

      assertEquals("RAW_ID", invokeResolveStoredId(stored, prop(PROP_TARGET)));
    }
  }

  // -------------------------------------------------------------------------
  // isSequencePlaceholder (ETP-5274 round 2)
  // -------------------------------------------------------------------------

  /**
   * The backend twin of the frontend's {@code isSequencePlaceholder} in {@code useEntity.js}
   * (there: {@code /^<[^<>]+>$/}). Both must agree, otherwise a value the frontend strips would
   * still be read by the backend as a caller-authored number — exactly the ETP-5274 failure.
   * The cases below are the shared contract, asserted verbatim on both sides.
   */
  @Nested
  @DisplayName("isSequencePlaceholder")
  class IsSequencePlaceholder {

    @Test
    @DisplayName("Accepts numeric and alphanumeric-prefixed previews")
    void acceptsPreviews() throws Exception {
      assertTrue(invokeIsSequencePlaceholder("<10000000>"));
      assertTrue(invokeIsSequencePlaceholder("<REC-1000008>"));
      assertTrue(invokeIsSequencePlaceholder("<ABONO/2026/0001>"));
      // Minimum accepted length is 3 — one inner character, same as the regex's `+`.
      assertTrue(invokeIsSequencePlaceholder("<a>"));
    }

    @Test
    @DisplayName("Rejects a bare value with no angle brackets")
    void rejectsBareValues() throws Exception {
      assertFalse(invokeIsSequencePlaceholder("REC-1000008"));
      assertFalse(invokeIsSequencePlaceholder("10000003"));
      assertFalse(invokeIsSequencePlaceholder(""));
    }

    @Test
    @DisplayName("Rejects unbalanced, empty and nested-bracket shapes")
    void rejectsMalformedShapes() throws Exception {
      assertFalse(invokeIsSequencePlaceholder("<"));
      assertFalse(invokeIsSequencePlaceholder(">"));
      assertFalse(invokeIsSequencePlaceholder("<>"));
      assertFalse(invokeIsSequencePlaceholder("<10000000"));
      assertFalse(invokeIsSequencePlaceholder("10000000>"));
      assertFalse(invokeIsSequencePlaceholder("<a<b>>"));
      assertFalse(invokeIsSequencePlaceholder("<a>b>"));
      assertFalse(invokeIsSequencePlaceholder("<<a>>"));
    }
  }

  // -------------------------------------------------------------------------
  // hasClientAuthoredDocumentNo (ETP-5274 round 2)
  // -------------------------------------------------------------------------

  /**
   * Replaces the old {@code rawBody.has(FIELD_DOCUMENT_NO)} check. The frontend used to echo the
   * sequence preview back in the PATCH body; reading that as a deliberate choice suppressed the
   * re-numbering it was previewing, so the record kept the previous doc-type's number.
   */
  @Nested
  @DisplayName("hasClientAuthoredDocumentNo")
  class HasClientAuthoredDocumentNo {

    private JSONObject bodyWithDocumentNo(Object value) throws Exception {
      JSONObject body = new JSONObject();
      body.put(PROP_DOC_NO, value);
      return body;
    }

    @Test
    @DisplayName("A real number the user typed counts as authored")
    void realNumberIsAuthored() throws Exception {
      assertTrue(invokeHasClientAuthoredDocumentNo(bodyWithDocumentNo("10000003")));
      assertTrue(invokeHasClientAuthoredDocumentNo(bodyWithDocumentNo("MANUAL-42")));
    }

    @Test
    @DisplayName("A sequence placeholder does NOT count as authored (the ETP-5274 fix)")
    void placeholderIsNotAuthored() throws Exception {
      assertFalse(invokeHasClientAuthoredDocumentNo(bodyWithDocumentNo("<REC-1000008>")));
      assertFalse(invokeHasClientAuthoredDocumentNo(bodyWithDocumentNo("<10000000>")));
    }

    @Test
    @DisplayName("An absent field is not authored")
    void absentFieldIsNotAuthored() throws Exception {
      assertFalse(invokeHasClientAuthoredDocumentNo(new JSONObject()));
    }

    @Test
    @DisplayName("A null body is not authored")
    void nullBodyIsNotAuthored() throws Exception {
      assertFalse(invokeHasClientAuthoredDocumentNo(null));
    }

    @Test
    @DisplayName("An explicit JSON null is not authored")
    void jsonNullIsNotAuthored() throws Exception {
      assertFalse(invokeHasClientAuthoredDocumentNo(bodyWithDocumentNo(JSONObject.NULL)));
    }

    @Test
    @DisplayName("Blank and whitespace-only values are not authored")
    void blankIsNotAuthored() throws Exception {
      assertFalse(invokeHasClientAuthoredDocumentNo(bodyWithDocumentNo("")));
      assertFalse(invokeHasClientAuthoredDocumentNo(bodyWithDocumentNo(" ")));
      assertFalse(invokeHasClientAuthoredDocumentNo(bodyWithDocumentNo("   ")));
    }

    @Test
    @DisplayName("A padded placeholder is trimmed before the placeholder check")
    void paddedPlaceholderIsNotAuthored() throws Exception {
      assertFalse(invokeHasClientAuthoredDocumentNo(bodyWithDocumentNo("  <REC-1000008>  ")));
    }

    @Test
    @DisplayName("A non-string value is stringified, not rejected outright")
    void numericValueIsAuthored() throws Exception {
      assertTrue(invokeHasClientAuthoredDocumentNo(bodyWithDocumentNo(10000003)));
    }
  }

  // -------------------------------------------------------------------------
  // Sanity: the fixture list helper is not accidentally empty
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("A tab with no AD columns is still handled without throwing")
  void tabWithNoColumnsIsSafe() throws Exception {
    Tab adTab = tab(Collections.emptyList());
    JSONObject body = bodyWithTarget(NEW_DOC_TYPE);

    try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class);
         MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      Entity dalEntity = stubEntity(mp, prop(PROP_TARGET), prop(PROP_DOC_NO));
      BaseOBObject stored = storedDraft(dalEntity, OLD_DOC_TYPE);
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(DAL_ENTITY_NAME, RECORD_ID)).thenReturn(stored);

      assertDoesNotThrow(() -> invokeRegenerate(
          body, context(adTab, RECORD_ID), DAL_ENTITY_NAME, false));
      assertFalse(body.has(PROP_DOC_NO));
    }
  }
}
