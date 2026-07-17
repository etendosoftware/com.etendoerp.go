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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;
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

  @Test
  @DisplayName("Utility class hides its constructor")
  void utilityClassHidesConstructor() throws ReflectiveOperationException {
    Constructor<SelectorValidationResolver> constructor = SelectorValidationResolver.class.getDeclaredConstructor();
    assertEquals(Modifier.PRIVATE, constructor.getModifiers() & Modifier.PRIVATE);
    constructor.setAccessible(true);
    constructor.newInstance();
  }


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
  @DisplayName("resolveValidationClause — nested subquery drop")
  class NestedSubqueryDrop {
    // Validation rules whose clause contains a nested (SELECT ...) subquery (e.g. the classic
    // C_BPartner Account rule over Fin_Finacc_Paymentmethod) reference raw SQL table names the
    // generic translator cannot map to HQL, so they must be dropped here and handled by a
    // dedicated SelectorContextPolicy instead. The guard runs before any ModelProvider access.
    @Test
    @DisplayName("drops the classic financial-account IN (SELECT ...) rule")
    void dropsClauseWithInSubquery() throws Exception {
      Map<String, String> params = new HashMap<>();
      params.put("Fin_Paymentmethod_ID", "PM1");
      String clause = "Fin_Financial_Account_ID IN (SELECT Fin_Financial_Account_ID"
          + " FROM Fin_Finacc_Paymentmethod WHERE Fin_Paymentmethod_ID=@Fin_Paymentmethod_ID@)";
      assertNull(invokeStatic("resolveValidationClause",
          new Class<?>[]{ String.class, Map.class }, clause, params));
    }

    @Test
    @DisplayName("subquery detection is case-insensitive and tolerates whitespace")
    void dropsClauseWithLowercaseSubquery() throws Exception {
      assertNull(invokeStatic("resolveValidationClause",
          new Class<?>[]{ String.class, Map.class }, "x IN ( select id from t)", new HashMap<>()));
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

  /**
   * ETP-4286: dependent FK selectors (e.g. the invoice bill-to location selector) must be
   * filtered by the businessPartner supplied in the MCP recordContext. That context flows
   * into the validation-param map as {@code C_BPartner_ID}, which substitutes the
   * {@code @C_BPartner_ID@} placeholder in the real AD validation rule:
   *
   * <pre>
   *   C_BPartner_Location.C_BPartner_ID=@C_BPartner_ID@
   *     AND C_BPartner_Location.IsBillTo='Y'
   *     AND C_BPartner_Location.IsActive='Y'
   * </pre>
   *
   * <p>When the businessPartner is present the placeholder resolves to the partner id, so the
   * selector returns only that partner's bill-to locations. When it is absent the placeholder
   * degrades to the SQL literal {@code NULL} — the documented behavior that makes the dependent
   * selector return an empty set instead of silently relaxing the filter. These tests lock in
   * both branches and guard against regressions on the ETP-4286 acceptance criterion.</p>
   */
  @Nested
  @DisplayName("substituteValidationParams — ETP-4286 BP-location dependent selector")
  class BusinessPartnerLocationValidation {

    private static final String BP_LOCATION_CLAUSE =
        "C_BPartner_Location.C_BPartner_ID=@C_BPartner_ID@"
            + " AND C_BPartner_Location.IsBillTo='Y'"
            + " AND C_BPartner_Location.IsActive='Y'";

    @Test
    @DisplayName("substitutes the businessPartner id when C_BPartner_ID is present in context")
    void substitutesBusinessPartnerIdWhenPresent() throws Exception {
      Map<String, String> params = new HashMap<>();
      params.put("C_BPartner_ID", "ABC123");
      String result = (String) invokeStatic("substituteValidationParams",
          new Class<?>[]{ String.class, Map.class }, BP_LOCATION_CLAUSE, params);

      // The dependent FK placeholder resolves to the supplied partner id (SQL-quoted)...
      assertTrue(result.contains("'ABC123'"),
          "Expected substituted partner id 'ABC123' in: " + result);
      assertEquals("C_BPartner_Location.C_BPartner_ID='ABC123'"
              + " AND C_BPartner_Location.IsBillTo='Y'"
              + " AND C_BPartner_Location.IsActive='Y'",
          result);
      // ...and the filter is NOT degraded to a permanently-empty NULL comparison.
      assertFalse(result.contains("C_BPartner_ID=NULL"),
          "Partner-id filter must not degrade to NULL when context is present: " + result);
      // Static clauses survive the substitution untouched.
      assertTrue(result.contains("C_BPartner_Location.IsBillTo='Y'"),
          "IsBillTo='Y' static clause must be preserved: " + result);
      assertTrue(result.contains("C_BPartner_Location.IsActive='Y'"),
          "IsActive='Y' static clause must be preserved: " + result);
    }

    @Test
    @DisplayName("only the bare partner-id placeholder is substituted, scalar value gets quoted")
    void substitutesOnlyPartnerIdPlaceholder() throws Exception {
      Map<String, String> params = new HashMap<>();
      params.put("C_BPartner_ID", "ABC123");
      String clause = "C_BPartner_Location.C_BPartner_ID=@C_BPartner_ID@";
      String result = (String) invokeStatic("substituteValidationParams",
          new Class<?>[]{ String.class, Map.class }, clause, params);
      assertEquals("C_BPartner_Location.C_BPartner_ID='ABC123'", result);
    }

    @Test
    @DisplayName("degrades the partner-id placeholder to NULL when businessPartner is missing")
    void degradesToNullWhenBusinessPartnerMissing() throws Exception {
      Map<String, String> params = new HashMap<>();
      // No C_BPartner_ID — mirrors an MCP recordContext without a businessPartner.
      String result = (String) invokeStatic("substituteValidationParams",
          new Class<?>[]{ String.class, Map.class }, BP_LOCATION_CLAUSE, params);

      // Documented degradation: the dependent filter becomes a NULL comparison (empty result).
      assertTrue(result.contains("C_BPartner_Location.C_BPartner_ID=NULL"),
          "Expected partner-id filter to degrade to NULL when context is absent: " + result);
      assertFalse(result.contains("'ABC123'"),
          "No partner id must leak when businessPartner is absent: " + result);
      // The static bill-to / active clauses are still applied.
      assertTrue(result.contains("C_BPartner_Location.IsBillTo='Y'"),
          "IsBillTo='Y' static clause must be preserved: " + result);
      assertTrue(result.contains("C_BPartner_Location.IsActive='Y'"),
          "IsActive='Y' static clause must be preserved: " + result);
    }
  }
}
