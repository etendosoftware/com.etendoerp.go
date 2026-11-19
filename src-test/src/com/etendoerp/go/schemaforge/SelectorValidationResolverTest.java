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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.domain.Validation;

import com.etendoerp.go.schemaforge.selector.meta.SearchableFragment;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/**
 * Unit tests for {@link SelectorValidationResolver}: the pure utility methods (via reflection)
 * and how a resolved validation rule composes with the rest of the selector query.
 *
 * @covers com.etendoerp.go.schemaforge.SelectorValidationResolver
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
      SelectorQueryBuilder.HqlWithParams result = (SelectorQueryBuilder.HqlWithParams) invokeStatic(
          "substituteValidationParams", new Class<?>[]{ String.class, Map.class }, clause, params);
      assertEquals("C_DocType.IsSOTrx=:valParam0", result.getHql());
      assertEquals("Y", result.getParams().get("valParam0"));
    }

    @Test
    void unresolvedQuotedParamBecomesNull() throws Exception {
      Map<String, String> params = new HashMap<>();
      String clause = "field='@Unknown@'";
      SelectorQueryBuilder.HqlWithParams result = (SelectorQueryBuilder.HqlWithParams) invokeStatic(
          "substituteValidationParams", new Class<?>[]{ String.class, Map.class }, clause, params);
      assertEquals("field=NULL", result.getHql());
      assertTrue(result.getParams().isEmpty());
    }

    @Test
    void substitutesBareParam() throws Exception {
      Map<String, String> params = new HashMap<>();
      params.put("AD_Org_ID", "org123");
      String clause = "table.ad_org_id = @AD_Org_ID@";
      SelectorQueryBuilder.HqlWithParams result = (SelectorQueryBuilder.HqlWithParams) invokeStatic(
          "substituteValidationParams", new Class<?>[]{ String.class, Map.class }, clause, params);
      assertEquals("table.ad_org_id = :valParam0", result.getHql());
      assertEquals("org123", result.getParams().get("valParam0"));
    }

    @Test
    void unresolvedBareParamBecomesNull() throws Exception {
      Map<String, String> params = new HashMap<>();
      String clause = "table.col = @Missing@";
      SelectorQueryBuilder.HqlWithParams result = (SelectorQueryBuilder.HqlWithParams) invokeStatic(
          "substituteValidationParams", new Class<?>[]{ String.class, Map.class }, clause, params);
      assertEquals("table.col = NULL", result.getHql());
      assertTrue(result.getParams().isEmpty());
    }

    @Test
    void preQuotedValueIsEmittedRawWithoutExtraQuotes() throws Exception {
      // Verifies that trusted session variables already containing single quotes (like CSV session vars)
      // are emitted raw, without additional quoting. Uses a bare @param@ to verify list syntax.
      Map<String, String> params = new HashMap<>();
      params.put("UserClient", "'0','1000000'");
      String clause = "ad_client_id IN (@UserClient@)";
      SelectorQueryBuilder.HqlWithParams result = (SelectorQueryBuilder.HqlWithParams) invokeStatic(
          "substituteValidationParams", new Class<?>[]{ String.class, Map.class }, clause, params);
      assertEquals("ad_client_id IN ('0','1000000')", result.getHql());
      assertTrue(result.getParams().isEmpty());
    }

    @Test
    void noParamsReturnsClauseUnchanged() throws Exception {
      Map<String, String> params = new HashMap<>();
      String clause = "isactive = 'Y'";
      SelectorQueryBuilder.HqlWithParams result = (SelectorQueryBuilder.HqlWithParams) invokeStatic(
          "substituteValidationParams", new Class<?>[]{ String.class, Map.class }, clause, params);
      assertEquals("isactive = 'Y'", result.getHql());
      assertTrue(result.getParams().isEmpty());
    }

    @Test
    void multipleParamsInSameClause() throws Exception {
      Map<String, String> params = new HashMap<>();
      params.put("A", "valA");
      params.put("B", "valB");
      String clause = "col1 = @A@ AND col2 = @B@";
      SelectorQueryBuilder.HqlWithParams result = (SelectorQueryBuilder.HqlWithParams) invokeStatic(
          "substituteValidationParams", new Class<?>[]{ String.class, Map.class }, clause, params);
      assertEquals("col1 = :valParam0 AND col2 = :valParam1", result.getHql());
      assertEquals("valA", result.getParams().get("valParam0"));
      assertEquals("valB", result.getParams().get("valParam1"));
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
   * <p>When the businessPartner is present the placeholder resolves to a named bind parameter,
   * so the selector returns only that partner's bill-to locations. When it is absent the
   * placeholder degrades to the SQL literal {@code NULL} — the documented behavior that makes
   * the dependent selector return an empty set instead of silently relaxing the filter.</p>
   */
  @Nested
  @DisplayName("substituteValidationParams — ETP-4286 BP-location dependent selector")
  class BusinessPartnerLocationValidation {

    private static final String BP_LOCATION_CLAUSE =
        "C_BPartner_Location.C_BPartner_ID=@C_BPartner_ID@"
            + " AND C_BPartner_Location.IsBillTo='Y'"
            + " AND C_BPartner_Location.IsActive='Y'";

    @Test
    @DisplayName("substitutes the businessPartner id as named param when C_BPartner_ID is present in context")
    void substitutesBusinessPartnerIdWhenPresent() throws Exception {
      Map<String, String> params = new HashMap<>();
      params.put("C_BPartner_ID", "ABC123");
      SelectorQueryBuilder.HqlWithParams result = (SelectorQueryBuilder.HqlWithParams) invokeStatic(
          "substituteValidationParams", new Class<?>[]{ String.class, Map.class }, BP_LOCATION_CLAUSE, params);

      // The dependent FK placeholder binds as a named query parameter
      assertEquals("C_BPartner_Location.C_BPartner_ID=:valParam0"
              + " AND C_BPartner_Location.IsBillTo='Y'"
              + " AND C_BPartner_Location.IsActive='Y'",
          result.getHql());
      assertEquals("ABC123", result.getParams().get("valParam0"));
      // ...and the filter is NOT degraded to a permanently-empty NULL comparison.
      assertFalse(result.getHql().contains("C_BPartner_ID=NULL"),
          "Partner-id filter must not degrade to NULL when context is present: " + result.getHql());
      // Static clauses survive the substitution untouched.
      assertTrue(result.getHql().contains("C_BPartner_Location.IsBillTo='Y'"),
          "IsBillTo='Y' static clause must be preserved: " + result.getHql());
      assertTrue(result.getHql().contains("C_BPartner_Location.IsActive='Y'"),
          "IsActive='Y' static clause must be preserved: " + result.getHql());
    }

    @Test
    @DisplayName("only the bare partner-id placeholder is substituted, scalar value gets bound")
    void substitutesOnlyPartnerIdPlaceholder() throws Exception {
      Map<String, String> params = new HashMap<>();
      params.put("C_BPartner_ID", "ABC123");
      String clause = "C_BPartner_Location.C_BPartner_ID=@C_BPartner_ID@";
      SelectorQueryBuilder.HqlWithParams result = (SelectorQueryBuilder.HqlWithParams) invokeStatic(
          "substituteValidationParams", new Class<?>[]{ String.class, Map.class }, clause, params);
      assertEquals("C_BPartner_Location.C_BPartner_ID=:valParam0", result.getHql());
      assertEquals("ABC123", result.getParams().get("valParam0"));
    }

    @Test
    @DisplayName("degrades the partner-id placeholder to NULL when businessPartner is missing")
    void degradesToNullWhenBusinessPartnerMissing() throws Exception {
      Map<String, String> params = new HashMap<>();
      SelectorQueryBuilder.HqlWithParams result = (SelectorQueryBuilder.HqlWithParams) invokeStatic(
          "substituteValidationParams", new Class<?>[]{ String.class, Map.class }, BP_LOCATION_CLAUSE, params);

      assertTrue(result.getHql().contains("C_BPartner_Location.C_BPartner_ID=NULL"),
          "Expected partner-id filter to degrade to NULL when context is absent: " + result.getHql());
      assertTrue(result.getParams().isEmpty());
      assertTrue(result.getHql().contains("C_BPartner_Location.IsBillTo='Y'"),
          "IsBillTo='Y' static clause must be preserved: " + result.getHql());
      assertTrue(result.getHql().contains("C_BPartner_Location.IsActive='Y'"),
          "IsActive='Y' static clause must be preserved: " + result.getHql());
    }
  }

  @Nested
  @DisplayName("buildValidationParamMap — caller params never override session variables")
  class CallerParams {

    private Map<String, String> build(String code, Map<String, String> caller) {
      OBContext obContext = mock(OBContext.class, RETURNS_DEEP_STUBS);
      when(obContext.getCurrentClient().getId()).thenReturn("SESSION_CLIENT");
      when(obContext.getCurrentOrganization().getId()).thenReturn("SESSION_ORG");
      when(obContext.getUser().getId()).thenReturn("SESSION_USER");
      when(obContext.getRole().getId()).thenReturn("SESSION_ROLE");
      when(obContext.getReadableClients()).thenReturn(new String[] { "0", "SESSION_CLIENT" });
      try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class)) {
        ctx.when(OBContext::getOBContext).thenReturn(obContext);
        return SelectorValidationResolver.buildValidationParamMap(code, caller);
      }
    }

    @Test
    @DisplayName("session variables win over caller params of the same name")
    void sessionVariablesAreNotOverridden() {
      Map<String, String> caller = new HashMap<>();
      caller.put("AD_Client_ID", "OTHER");
      caller.put("ad_org_id", "OTHER");
      caller.put("#User_Client", "'OTHER'");
      Map<String, String> result = build(
          "AD_Client_ID IN (@#User_Client@) AND AD_Org_ID=@AD_Org_ID@ AND x=@AD_Client_ID@", caller);

      assertEquals("SESSION_CLIENT", result.get("AD_Client_ID"));
      assertEquals("SESSION_ORG", result.get("AD_Org_ID"));
      assertEquals("SESSION_ORG", result.get("ad_org_id"));
      assertEquals("'0','SESSION_CLIENT'", result.get("#User_Client"));
    }

    @Test
    @DisplayName("mixed-case, user, role and parent-org names keep the session value")
    void otherSessionIdsAreNotOverridden() {
      Map<String, String> caller = new HashMap<>();
      caller.put("Ad_Client_Id", "OTHER");
      caller.put("AD_User_ID", "OTHER");
      caller.put("AD_Role_ID", "OTHER");
      caller.put("Parent_AD_Org", "OTHER");
      Map<String, String> result = build(
          "x=@Ad_Client_Id@ AND y=@AD_User_ID@ AND z=@AD_Role_ID@ AND w=@Parent_AD_Org@", caller);

      assertEquals("SESSION_CLIENT", result.get("Ad_Client_Id".toLowerCase()));
      assertEquals("SESSION_CLIENT", result.get("AD_Client_ID"));
      assertEquals("SESSION_USER", result.get("AD_User_ID"));
      assertEquals("SESSION_ROLE", result.get("AD_Role_ID"));
      assertFalse(result.containsKey("Parent_AD_Org"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "  " })
    @DisplayName("a null or blank rule code accepts no caller params")
    void blankRuleAcceptsNoCallerParams(String code) {
      Map<String, String> result = build(code, Collections.singletonMap("IsSOTrx", "Y"));

      assertFalse(result.containsKey("IsSOTrx"));
    }

    @Test
    @DisplayName("params declared by the rule are accepted")
    void declaredParamsAreAccepted() {
      Map<String, String> result = build("C_Order.IsSOTrx='@IsSOTrx@'",
          Collections.singletonMap("IsSOTrx", "Y"));

      assertEquals("Y", result.get("IsSOTrx"));
    }

    @Test
    @DisplayName("params the rule does not declare are ignored")
    void undeclaredParamsAreIgnored() {
      Map<String, String> result = build("C_Order.IsSOTrx='@IsSOTrx@'",
          Collections.singletonMap("Other", "Y"));

      assertFalse(result.containsKey("Other"));
    }
  }

  @Nested
  @DisplayName("resolveValidationFilter — the rule is one group in the selector query")
  class RuleGrouping {

    /** AD rule 800001 (header C_Project_ID): a top-level OR keeps the current project selectable. */
    private static final String PROJECT_RULE = "C_Project.projectstatus in ('OP', 'OR') and "
        + "(C_Project.c_bpartner_id=@C_BPartner_ID@ or '@IsSOTrx@'='N') "
        + "or C_Project.c_project_id = @c_project_id@";

    /**
     * Resolves {@code code} as the validation rule of a column, with a context whose ids are all
     * null and an entity unknown to the model, so the rule text is only param-substituted.
     */
    private String resolve(String code, Map<String, String> contextParams) {
      Validation validation = mock(Validation.class);
      when(validation.getValidationCode()).thenReturn(code);
      Column column = mock(Column.class);
      when(column.getValidation()).thenReturn(validation);
      ModelProvider modelProvider = mock(ModelProvider.class);
      when(modelProvider.getEntity(anyString())).thenReturn(null);
      when(modelProvider.getEntityByTableName(anyString())).thenReturn(null);
      try (MockedStatic<ModelProvider> mp = mockStatic(ModelProvider.class);
          MockedStatic<OBContext> ctx = mockStatic(OBContext.class)) {
        mp.when(ModelProvider::getInstance).thenReturn(modelProvider);
        ctx.when(OBContext::getOBContext).thenReturn(mock(OBContext.class, RETURNS_DEEP_STUBS));
        SelectorQueryBuilder.HqlWithParams result = SelectorValidationResolver.resolveValidationFilter(column, "Project", contextParams);
        return result != null ? result.getHql() : null;
      }
    }

    /** True when the first '(' of {@code s} closes at its last character. */
    private boolean isOneParenthesizedGroup(String s) {
      if (!s.startsWith("(") || !s.endsWith(")")) {
        return false;
      }
      int depth = 0;
      for (int i = 0; i < s.length(); i++) {
        depth += s.charAt(i) == '(' ? 1 : s.charAt(i) == ')' ? -1 : 0;
        if (depth == 0 && i < s.length() - 1) {
          return false;
        }
      }
      return true;
    }

    /** True when {@code s} has an OR outside every parenthesis and string literal. */
    private boolean hasTopLevelOr(String s) {
      String upper = s.toUpperCase();
      int depth = 0;
      boolean inLiteral = false;
      for (int i = 0; i < upper.length(); i++) {
        char c = upper.charAt(i);
        if (c == '\'') {
          inLiteral = !inLiteral;
        } else if (!inLiteral && c == '(') {
          depth++;
        } else if (!inLiteral && c == ')') {
          depth--;
        } else if (!inLiteral && depth == 0 && upper.startsWith(" OR ", i)) {
          return true;
        }
      }
      return false;
    }

    @Test
    @DisplayName("a rule with a top-level OR is wrapped as a single group")
    void wrapsRuleWithTopLevelOr() {
      String filter = resolve(PROJECT_RULE, Collections.singletonMap("IsSOTrx", "N"));

      assertTrue(isOneParenthesizedGroup(filter), "Rule must be one group: " + filter);
      assertTrue(filter.contains(":valParam"), "IsSOTrx must be bound as param: " + filter);
    }

    @Test
    @DisplayName("a rule made only of ANDs is wrapped too, with the same meaning")
    void wrapsAndOnlyRule() {
      assertEquals("(C_Project.projectstatus = 'OP' AND C_Project.isactive = 'Y')",
          resolve("C_Project.projectstatus = 'OP' and C_Project.isactive = 'Y'",
              Collections.emptyMap()));
    }

    @Test
    @DisplayName("the search text filters every branch of a rule with a top-level OR")
    void searchAppliesOutsideTheOrGroup() {
      String filter = resolve(PROJECT_RULE, Collections.singletonMap("IsSOTrx", "N"));
      SelectorMeta meta = new SelectorMeta.Builder("Project", "name")
          .isRich(true)
          .searchableProperties(Collections.singletonList(SearchableFragment.ofRelativePath("name")))
          .build();

      String where;
      try (MockedStatic<ModelProvider> mp = mockStatic(ModelProvider.class);
          MockedStatic<OBContext> ctx = mockStatic(OBContext.class)) {
        mp.when(ModelProvider::getInstance).thenReturn(mock(ModelProvider.class));
        ctx.when(OBContext::getOBContext).thenReturn(null);
        where = SelectorQueryBuilder.buildRichQueryWhereClause(meta, "beta", filter, "e", null)
            .getHql();
      }

      assertEquals("as e where " + filter
          + " AND ((upper(COALESCE(cast(e.name as string), '')) LIKE :searchLoose AND lower(COALESCE(cast(e.name as string), '')) LIKE :search))", where);
      assertFalse(hasTopLevelOr(where.substring("as e where ".length())),
          "An OR outside parentheses would let rows skip the search: " + where);
    }
  }

  @Nested
  @DisplayName("HQL Injection Prevention (ETP-6021)")
  class HqlInjectionPrevention {

    @Test
    @DisplayName("quote injection payload is safely bound as a named parameter")
    void quoteInjectionPayloadIsBound() throws Exception {
      Map<String, String> params = new HashMap<>();
      String payload = "foo' OR '1'='1";
      params.put("C_BPartner_ID", payload);
      String clause = "C_BPartner_Location.C_BPartner_ID=@C_BPartner_ID@";
      SelectorQueryBuilder.HqlWithParams result = (SelectorQueryBuilder.HqlWithParams) invokeStatic(
          "substituteValidationParams", new Class<?>[]{ String.class, Map.class }, clause, params);

      assertEquals("C_BPartner_Location.C_BPartner_ID=:valParam0", result.getHql());
      assertEquals(payload, result.getParams().get("valParam0"));
      assertFalse(result.getHql().contains("'1'='1'"));
    }

    @Test
    @DisplayName("comment injection payload is safely bound as a named parameter")
    void commentInjectionPayloadIsBound() throws Exception {
      Map<String, String> params = new HashMap<>();
      String payload = "1' -- comment";
      params.put("C_BPartner_ID", payload);
      String clause = "C_BPartner_Location.C_BPartner_ID=@C_BPartner_ID@";
      SelectorQueryBuilder.HqlWithParams result = (SelectorQueryBuilder.HqlWithParams) invokeStatic(
          "substituteValidationParams", new Class<?>[]{ String.class, Map.class }, clause, params);

      assertEquals("C_BPartner_Location.C_BPartner_ID=:valParam0", result.getHql());
      assertEquals(payload, result.getParams().get("valParam0"));
      assertFalse(result.getHql().contains("--"));
    }

    @Test
    @DisplayName("boolean injection payload is safely bound as a named parameter")
    void booleanInjectionPayloadIsBound() throws Exception {
      Map<String, String> params = new HashMap<>();
      String payload = "' or 1=1 or ''='";
      params.put("IsSOTrx", payload);
      String clause = "C_DocType.IsSOTrx='@IsSOTrx@'";
      SelectorQueryBuilder.HqlWithParams result = (SelectorQueryBuilder.HqlWithParams) invokeStatic(
          "substituteValidationParams", new Class<?>[]{ String.class, Map.class }, clause, params);

      assertEquals("C_DocType.IsSOTrx=:valParam0", result.getHql());
      assertEquals(payload, result.getParams().get("valParam0"));
      assertFalse(result.getHql().contains("1=1"));
    }

    @Test
    @DisplayName("request context params cannot override reserved session keys case-insensitively")
    void disallowsReservedSessionKeyOverrides() throws Exception {
      Map<String, String> contextParams = new HashMap<>();
      contextParams.put("AD_Org_ID", "injected_org");
      contextParams.put("ad_client_id", "injected_client");
      contextParams.put("#User_Client", "'injected_user_client'");
      contextParams.put("user_org", "'injected_user_org'");
      contextParams.put("customParam", "validValue");

      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
        OBContext ctx = mock(OBContext.class, RETURNS_DEEP_STUBS);
        when(ctx.getCurrentOrganization().getId()).thenReturn("trusted_org");
        when(ctx.getCurrentClient().getId()).thenReturn("trusted_client");
        when(ctx.getReadableClients()).thenReturn(new String[]{"0"});
        when(ctx.getReadableOrganizations()).thenReturn(new String[]{"0"});
        ctxMock.when(OBContext::getOBContext).thenReturn(ctx);

        @SuppressWarnings("unchecked")
        Map<String, String> paramMap = (Map<String, String>) invokeStatic(
            "buildValidationParamMap", new Class<?>[]{ String.class, Map.class },
            "x = '@customParam@' AND y = @AD_Org_ID@", contextParams);

        assertEquals("trusted_org", paramMap.get("AD_Org_ID"));
        assertEquals("trusted_client", paramMap.get("AD_Client_ID"));
        assertEquals("'0'", paramMap.get("#User_Client"));
        assertEquals("'0'", paramMap.get("#User_Org"));
        assertEquals("validValue", paramMap.get("customParam"));
      }
    }

    @Test
    @DisplayName("isReservedSessionKey identifies reserved session keys with or without hash and casing")
    void isReservedSessionKeyCheck() throws Exception {
      assertTrue((Boolean) invokeStatic("isReservedSessionKey", new Class<?>[]{ String.class }, "AD_Org_ID"));
      assertTrue((Boolean) invokeStatic("isReservedSessionKey", new Class<?>[]{ String.class }, "ad_org_id"));
      assertTrue((Boolean) invokeStatic("isReservedSessionKey", new Class<?>[]{ String.class }, "#AD_Org_ID"));
      assertTrue((Boolean) invokeStatic("isReservedSessionKey", new Class<?>[]{ String.class }, "#User_Client"));
      assertTrue((Boolean) invokeStatic("isReservedSessionKey", new Class<?>[]{ String.class }, "User_Client"));
      assertTrue((Boolean) invokeStatic("isReservedSessionKey", new Class<?>[]{ String.class }, "UserClient"));
      assertTrue((Boolean) invokeStatic("isReservedSessionKey", new Class<?>[]{ String.class }, "#UserClient"));
      assertTrue((Boolean) invokeStatic("isReservedSessionKey", new Class<?>[]{ String.class }, "ad_client_id"));
      assertFalse((Boolean) invokeStatic("isReservedSessionKey", new Class<?>[]{ String.class }, "C_BPartner_ID"));
      assertFalse((Boolean) invokeStatic("isReservedSessionKey", new Class<?>[]{ String.class }, "IsSOTrx"));
      assertFalse((Boolean) invokeStatic("isReservedSessionKey", new Class<?>[]{ String.class }, (Object) null));
    }
  }
}
