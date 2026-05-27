/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for the pure utility methods of {@link SelectorValidationResolver}.
 * Tests substituteValidationParams, quotedCsv, and lookupParamValue via reflection.
 */
class SelectorValidationResolverTest {

  private static Object invokeStatic(String methodName, Class<?>[] paramTypes, Object... args)
      throws Exception {
    Method method = SelectorValidationResolver.class.getDeclaredMethod(methodName, paramTypes);
    method.setAccessible(true);
    return method.invoke(null, args);
  }

  @Nested
  @DisplayName("quotedCsv")
  class QuotedCsv {
    @Test
    void nullArrayReturnsNull() throws Exception {
      assertNull(invokeStatic("quotedCsv", new Class<?>[]{ String[].class }, (Object) null));
    }

    @Test
    void emptyArrayReturnsNull() throws Exception {
      assertNull(invokeStatic("quotedCsv", new Class<?>[]{ String[].class },
          (Object) new String[]{}));
    }

    @Test
    void allBlankReturnsNull() throws Exception {
      assertNull(invokeStatic("quotedCsv", new Class<?>[]{ String[].class },
          (Object) new String[]{ "", "  " }));
    }

    @Test
    void singleIdReturnsQuoted() throws Exception {
      String result = (String) invokeStatic("quotedCsv", new Class<?>[]{ String[].class },
          (Object) new String[]{ "0" });
      assertEquals("'0'", result);
    }

    @Test
    void multipleIdsReturnsCommaSeparated() throws Exception {
      String result = (String) invokeStatic("quotedCsv", new Class<?>[]{ String[].class },
          (Object) new String[]{ "0", "1000000" });
      assertEquals("'0','1000000'", result);
    }

    @Test
    void escapesInternalSingleQuotes() throws Exception {
      String result = (String) invokeStatic("quotedCsv", new Class<?>[]{ String[].class },
          (Object) new String[]{ "it's" });
      assertEquals("'it''s'", result);
    }

    @Test
    void skipsBlankEntries() throws Exception {
      String result = (String) invokeStatic("quotedCsv", new Class<?>[]{ String[].class },
          (Object) new String[]{ "A", "", "B" });
      assertEquals("'A','B'", result);
    }
  }

  @Nested
  @DisplayName("lookupParamValue")
  class LookupParamValue {
    @Test
    void exactMatch() throws Exception {
      Map<String, String> params = new HashMap<>();
      params.put("AD_Org_ID", "org-1");
      String result = (String) invokeStatic("lookupParamValue",
          new Class<?>[]{ Map.class, String.class }, params, "AD_Org_ID");
      assertEquals("org-1", result);
    }

    @Test
    void lowercaseFallback() throws Exception {
      Map<String, String> params = new HashMap<>();
      params.put("ad_org_id", "org-lower");
      String result = (String) invokeStatic("lookupParamValue",
          new Class<?>[]{ Map.class, String.class }, params, "AD_Org_ID");
      assertEquals("org-lower", result);
    }

    @Test
    void uppercaseFallback() throws Exception {
      Map<String, String> params = new HashMap<>();
      params.put("AD_ORG_ID", "org-upper");
      String result = (String) invokeStatic("lookupParamValue",
          new Class<?>[]{ Map.class, String.class }, params, "ad_org_id");
      assertEquals("org-upper", result);
    }

    @Test
    void nullMapReturnsNull() throws Exception {
      assertNull(invokeStatic("lookupParamValue",
          new Class<?>[]{ Map.class, String.class }, null, "key"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    void blankKeyReturnsNull(String key) throws Exception {
      assertNull(invokeStatic("lookupParamValue",
          new Class<?>[]{ Map.class, String.class }, new HashMap<>(), key));
    }

    @Test
    void unknownKeyReturnsNull() throws Exception {
      Map<String, String> params = new HashMap<>();
      params.put("known", "value");
      assertNull(invokeStatic("lookupParamValue",
          new Class<?>[]{ Map.class, String.class }, params, "unknown"));
    }
  }

  @Nested
  @DisplayName("substituteValidationParams")
  class SubstituteValidationParams {
    @Test
    void substitutesQuotedParam() throws Exception {
      Map<String, String> params = new HashMap<>();
      params.put("IsSOTrx", "Y");
      String clause = "C_DocType.IsSOTrx='@IsSOTrx@'";
      String result = (String) invokeStatic("substituteValidationParams",
          new Class<?>[]{ String.class, Map.class }, clause, params);
      assertEquals("C_DocType.IsSOTrx='Y'", result);
    }

    @Test
    void unresolvedQuotedParamBecomesNull() throws Exception {
      Map<String, String> params = new HashMap<>();
      String clause = "field='@Unknown@'";
      String result = (String) invokeStatic("substituteValidationParams",
          new Class<?>[]{ String.class, Map.class }, clause, params);
      assertEquals("field=NULL", result);
    }

    @Test
    void substitutesBareParam() throws Exception {
      Map<String, String> params = new HashMap<>();
      params.put("AD_Org_ID", "org123");
      String clause = "table.ad_org_id = @AD_Org_ID@";
      String result = (String) invokeStatic("substituteValidationParams",
          new Class<?>[]{ String.class, Map.class }, clause, params);
      assertEquals("table.ad_org_id = 'org123'", result);
    }

    @Test
    void unresolvedBareParamBecomesNull() throws Exception {
      Map<String, String> params = new HashMap<>();
      String clause = "table.col = @Missing@";
      String result = (String) invokeStatic("substituteValidationParams",
          new Class<?>[]{ String.class, Map.class }, clause, params);
      assertEquals("table.col = NULL", result);
    }

    @Test
    void preQuotedValueIsEmittedRawWithoutExtraQuotes() throws Exception {
      // Verifies that values already containing single quotes (like CSV session vars)
      // are emitted raw, without additional quoting. Uses a bare @param@ (no #) to
      // avoid regex group capture complexity with the # prefix.
      Map<String, String> params = new HashMap<>();
      params.put("UserClient", "'0','1000000'");
      String clause = "ad_client_id IN (@UserClient@)";
      String result = (String) invokeStatic("substituteValidationParams",
          new Class<?>[]{ String.class, Map.class }, clause, params);
      assertEquals("ad_client_id IN ('0','1000000')", result);
    }

    @Test
    void noParamsReturnsClauseUnchanged() throws Exception {
      Map<String, String> params = new HashMap<>();
      String clause = "isactive = 'Y'";
      String result = (String) invokeStatic("substituteValidationParams",
          new Class<?>[]{ String.class, Map.class }, clause, params);
      assertEquals("isactive = 'Y'", result);
    }

    @Test
    void multipleParamsInSameClause() throws Exception {
      Map<String, String> params = new HashMap<>();
      params.put("A", "valA");
      params.put("B", "valB");
      String clause = "col1 = @A@ AND col2 = @B@";
      String result = (String) invokeStatic("substituteValidationParams",
          new Class<?>[]{ String.class, Map.class }, clause, params);
      assertEquals("col1 = 'valA' AND col2 = 'valB'", result);
    }
  }
}
