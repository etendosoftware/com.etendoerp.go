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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.sequences.SequenceUtils;

/**
 * Unit tests for {@link NeoSequencePreviewHelper}: the model-based sequence check and the choice
 * between the transactional and the DocumentNo preview.
 *
 * @covers com.etendoerp.go.schemaforge.NeoSequencePreviewHelper
 */
public class NeoSequencePreviewHelperTest {

  private static Column column(String dbColumnName, String tableName) {
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn(dbColumnName);
    if (tableName != null) {
      Table table = mock(Table.class);
      when(table.getDBTableName()).thenReturn(tableName);
      when(col.getTable()).thenReturn(table);
    }
    return col;
  }

  private static void stubEntity(MockedStatic<ModelProvider> modelMock, String tableName,
      Entity entity) {
    ModelProvider mp = mock(ModelProvider.class);
    modelMock.when(ModelProvider::getInstance).thenReturn(mp);
    when(mp.getEntityByTableName(tableName)).thenReturn(entity);
  }

  private static void stubSequenceFlag(MockedStatic<ModelProvider> modelMock, String tableName,
      String columnName, boolean isSequence) {
    Property prop = mock(Property.class);
    when(prop.isSequence()).thenReturn(isSequence);
    Entity entity = mock(Entity.class);
    when(entity.getPropertyByColumnName(columnName, false)).thenReturn(prop);
    stubEntity(modelMock, tableName, entity);
  }

  // ===================================================================
  // isModelSequenceColumn — in-memory model flag, no DB round trip
  // ===================================================================

  @Test
  public void testIsModelSequenceColumnFalseWithoutTable() {
    Column col = column("EM_Etgo_Identifier", null);

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class)) {
      assertFalse(NeoSequencePreviewHelper.isModelSequenceColumn(col));
      modelMock.verify(ModelProvider::getInstance, never());
    }
  }

  @Test
  public void testIsModelSequenceColumnFalseWithoutDbTableName() {
    Column col = column("EM_Etgo_Identifier", null);
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn(null);
    when(col.getTable()).thenReturn(table);

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class)) {
      assertFalse(NeoSequencePreviewHelper.isModelSequenceColumn(col));
      modelMock.verify(ModelProvider::getInstance, never());
    }
  }

  @Test
  public void testIsModelSequenceColumnFalseForViewWithoutEntity() {
    Column col = column("Name", "Some_View");

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class)) {
      stubEntity(modelMock, "Some_View", null);

      assertFalse("A table without a runtime entity is never a model sequence",
          NeoSequencePreviewHelper.isModelSequenceColumn(col));
    }
  }

  @Test
  public void testIsModelSequenceColumnFalseWhenPropertyIsMissing() {
    Column col = column("Name", "C_Thing");
    Entity entity = mock(Entity.class);

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class)) {
      stubEntity(modelMock, "C_Thing", entity);
      when(entity.getPropertyByColumnName("Name", false)).thenReturn(null);

      assertFalse(NeoSequencePreviewHelper.isModelSequenceColumn(col));
    }
  }

  @Test
  public void testIsModelSequenceColumnSwallowsModelRuntimeException() {
    Column col = column("EM_Etgo_Identifier", "C_BPartner");

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class)) {
      modelMock.when(ModelProvider::getInstance)
          .thenThrow(new IllegalStateException("model not loaded"));

      assertFalse("A model failure degrades to not-a-sequence, never to an exception",
          NeoSequencePreviewHelper.isModelSequenceColumn(col));
    }
  }

  @Test
  public void testIsModelSequenceColumnFollowsPropertySequenceFlag() {
    Column sequenceCol = column("EM_Etgo_Identifier", "C_BPartner");
    Column plainCol = column("Description", "C_BPartner");

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class)) {
      stubSequenceFlag(modelMock, "C_BPartner", "EM_Etgo_Identifier", true);
      assertTrue(NeoSequencePreviewHelper.isModelSequenceColumn(sequenceCol));
    }

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class)) {
      stubSequenceFlag(modelMock, "C_BPartner", "Description", false);
      assertFalse(NeoSequencePreviewHelper.isModelSequenceColumn(plainCol));
    }
  }

  // ===================================================================
  // resolveSequencePreviewForColumn — transactional vs DocumentNo path
  // ===================================================================

  @Test
  public void testModelSequenceColumnUsesTransactionalPreview() {
    Column col = column("EM_Etgo_Identifier", "C_BPartner");
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    DalConnectionProvider conn = mock(DalConnectionProvider.class);

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class);
         MockedStatic<NeoDefaultsService> serviceMock = mockStatic(NeoDefaultsService.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class);
         MockedStatic<SequenceUtils> seqMock = mockStatic(SequenceUtils.class)) {
      stubSequenceFlag(modelMock, "C_BPartner", "EM_Etgo_Identifier", true);
      serviceMock.when(() -> NeoDefaultsService.resolveTransactionalSequencePreview(col))
          .thenReturn("<5000>");

      assertEquals("<5000>", NeoSequencePreviewHelper.resolveSequencePreviewForColumn(
          col, vars, conn, "WIN-1", "TGT", "DT"));

      serviceMock.verify(() -> NeoDefaultsService.resolveTransactionalSequencePreview(col));
      utilityMock.verifyNoInteractions();
      seqMock.verifyNoInteractions();
    }
  }

  @Test
  public void testNonSequenceColumnUsesDocTypePreview() {
    Column col = column("Description", "C_Order");
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    DalConnectionProvider conn = mock(DalConnectionProvider.class);

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class);
         MockedStatic<NeoDefaultsService> serviceMock = mockStatic(NeoDefaultsService.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class);
         MockedStatic<SequenceUtils> seqMock = mockStatic(SequenceUtils.class)) {
      stubSequenceFlag(modelMock, "C_Order", "Description", false);
      utilityMock.when(() -> Utility.getDocumentNo(any(), eq(vars), eq("WIN-1"), eq("C_Order"),
          eq("TGT"), eq("DT"), eq(false), eq(false))).thenReturn("DOC-1");

      assertEquals("<DOC-1>", NeoSequencePreviewHelper.resolveSequencePreviewForColumn(
          col, vars, conn, "WIN-1", "TGT", "DT"));

      utilityMock.verify(() -> Utility.getDocumentNo(any(), eq(vars), eq("WIN-1"),
          eq("C_Order"), eq("TGT"), eq("DT"), eq(false), eq(false)));
      serviceMock.verify(() -> NeoDefaultsService.resolveTransactionalSequencePreview(any()),
          never());
      seqMock.verifyNoInteractions();
    }
  }

  @Test
  public void testDocTypePreviewIsNullWhenNoNumberIsGenerated() {
    Column col = column("Description", "C_Order");
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    DalConnectionProvider conn = mock(DalConnectionProvider.class);

    try (MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class);
         MockedStatic<Utility> utilityMock = mockStatic(Utility.class)) {
      stubSequenceFlag(modelMock, "C_Order", "Description", false);
      utilityMock.when(() -> Utility.getDocumentNo(any(), eq(vars), anyString(), anyString(),
          anyString(), anyString(), eq(false), eq(false))).thenReturn("");

      assertNull(NeoSequencePreviewHelper.resolveSequencePreviewForColumn(
          col, vars, conn, "WIN-1", "TGT", "DT"));
    }
  }
}
