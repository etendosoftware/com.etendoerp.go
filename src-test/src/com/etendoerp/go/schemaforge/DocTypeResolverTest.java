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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Collections;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Unit tests for {@link DocTypeResolver}.
 */
class DocTypeResolverTest {

  private static Object invokePrivate(String methodName, Class<?>[] paramTypes, Object... args)
      throws Exception {
    Method method = DocTypeResolver.class.getDeclaredMethod(methodName, paramTypes);
    method.setAccessible(true);
    return method.invoke(null, args);
  }

  // -------------------------------------------------------------------------
  // resolveDocBaseType
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("resolveDocBaseType")
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
    @DisplayName("Maps table name and IsSOTrx to correct DocBaseType")
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
    @DisplayName("Returns [null, null] when context is null")
    void nullContext() throws Exception {
      String[] result = invokeParseSubTypeFilters(null);
      assertArrayEquals(new String[] { null, null }, result);
    }

    @Test
    @DisplayName("Returns [null, null] when sfEntity is null")
    void nullSfEntity() throws Exception {
      NeoContext ctx = NeoContext.builder().build();
      String[] result = invokeParseSubTypeFilters(ctx);
      assertArrayEquals(new String[] { null, null }, result);
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
}