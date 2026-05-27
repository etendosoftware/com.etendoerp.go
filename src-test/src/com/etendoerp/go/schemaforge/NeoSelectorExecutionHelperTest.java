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

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBQuery;

import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/**
 * Unit tests for {@link NeoSelectorExecutionHelper}.
 *
 * All methods under test are package-private and static. Where they delegate to
 * {@link SelectorValidationResolver} or {@link SelectorOrgFilter}, those
 * dependencies are stubbed via {@code MockedStatic}.
 */
class NeoSelectorExecutionHelperTest {

  // ------------------------------------------------------------------ //
  // appendLiteralFilter
  // ------------------------------------------------------------------ //

  @Nested
  @DisplayName("appendLiteralFilter")
  class AppendLiteralFilterTests {

    @Test
    @DisplayName("blank filter is ignored")
    void blankFilterIsIgnored() {
      StringBuilder hql = new StringBuilder("existing");
      NeoSelectorExecutionHelper.appendLiteralFilter(hql, "");
      assertEquals("existing", hql.toString());
    }

    @Test
    @DisplayName("null filter is ignored")
    void nullFilterIsIgnored() {
      StringBuilder hql = new StringBuilder("existing");
      NeoSelectorExecutionHelper.appendLiteralFilter(hql, null);
      assertEquals("existing", hql.toString());
    }

    @Test
    @DisplayName("whitespace-only filter is ignored")
    void whitespaceFilterIsIgnored() {
      StringBuilder hql = new StringBuilder();
      NeoSelectorExecutionHelper.appendLiteralFilter(hql, "   ");
      assertEquals("", hql.toString());
    }

    @Test
    @DisplayName("non-blank filter appended to empty hql without AND")
    void nonBlankFilterAppendedToEmpty() {
      StringBuilder hql = new StringBuilder();
      NeoSelectorExecutionHelper.appendLiteralFilter(hql, "e.active = true");
      assertEquals("e.active = true", hql.toString());
    }

    @Test
    @DisplayName("non-blank filter appended with AND when hql is non-empty")
    void nonBlankFilterAppendedWithAnd() {
      StringBuilder hql = new StringBuilder("e.id IS NOT NULL");
      NeoSelectorExecutionHelper.appendLiteralFilter(hql, "e.active = true");
      assertEquals("e.id IS NOT NULL AND e.active = true", hql.toString());
    }
  }

  // ------------------------------------------------------------------ //
  // appendSimpleSearchFilter
  // ------------------------------------------------------------------ //

  @Nested
  @DisplayName("appendSimpleSearchFilter")
  class AppendSimpleSearchFilterTests {

    @Test
    @DisplayName("blank search is ignored")
    void blankSearchIsIgnored() {
      StringBuilder hql = new StringBuilder("existing");
      NeoSelectorExecutionHelper.appendSimpleSearchFilter(hql, "name", "");
      assertEquals("existing", hql.toString());
    }

    @Test
    @DisplayName("null search is ignored")
    void nullSearchIsIgnored() {
      StringBuilder hql = new StringBuilder();
      NeoSelectorExecutionHelper.appendSimpleSearchFilter(hql, "name", null);
      assertEquals("", hql.toString());
    }

    @Test
    @DisplayName("appends LIKE clause to empty hql")
    void appendsLikeClauseToEmpty() {
      StringBuilder hql = new StringBuilder();
      NeoSelectorExecutionHelper.appendSimpleSearchFilter(hql, "name", "test");
      assertEquals("lower(e.name) LIKE :search", hql.toString());
    }

    @Test
    @DisplayName("appends LIKE clause with AND when hql is non-empty")
    void appendsLikeClauseWithAnd() {
      StringBuilder hql = new StringBuilder("e.active = true");
      NeoSelectorExecutionHelper.appendSimpleSearchFilter(hql, "commercialName", "acme");
      assertEquals("e.active = true AND lower(e.commercialName) LIKE :search", hql.toString());
    }
  }

  // ------------------------------------------------------------------ //
  // buildSimpleWhereClause
  // ------------------------------------------------------------------ //

  @Nested
  @DisplayName("buildSimpleWhereClause")
  class BuildSimpleWhereClauseTests {

    @Test
    @DisplayName("empty hql returns 'as e'")
    void emptyHqlReturnsAsE() {
      assertEquals("as e", NeoSelectorExecutionHelper.buildSimpleWhereClause(new StringBuilder()));
    }

    @Test
    @DisplayName("non-empty hql returns 'as e where ...'")
    void nonEmptyHqlReturnsAsEWhere() {
      StringBuilder hql = new StringBuilder("e.active = true");
      assertEquals("as e where e.active = true",
          NeoSelectorExecutionHelper.buildSimpleWhereClause(hql));
    }
  }

  // ------------------------------------------------------------------ //
  // appendResolvedWhereClause
  // ------------------------------------------------------------------ //

  @Nested
  @DisplayName("appendResolvedWhereClause")
  class AppendResolvedWhereClauseTests {

    @Test
    @DisplayName("blank whereClause is ignored")
    void blankWhereClauseIsIgnored() {
      StringBuilder hql = new StringBuilder("existing");
      Map<String, Object> params = new HashMap<>();

      NeoSelectorExecutionHelper.appendResolvedWhereClause(hql, params, "");

      assertEquals("existing", hql.toString());
      assertTrue(params.isEmpty());
    }

    @Test
    @DisplayName("delegates to SelectorValidationResolver and appends result")
    void delegatesToResolverAndAppends() {
      Map<String, Object> resolvedParams = new HashMap<>();
      resolvedParams.put("p1", "v1");
      SelectorQueryBuilder.HqlWithParams resolved =
          new SelectorQueryBuilder.HqlWithParams("e.col = :p1", resolvedParams);

      try (MockedStatic<SelectorValidationResolver> resolver =
               mockStatic(SelectorValidationResolver.class)) {
        resolver.when(() -> SelectorValidationResolver.resolveObuiselParams("e.col = @param@"))
            .thenReturn(resolved);

        StringBuilder hql = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        NeoSelectorExecutionHelper.appendResolvedWhereClause(hql, params, "e.col = @param@");

        assertEquals("e.col = :p1", hql.toString());
        assertEquals("v1", params.get("p1"));
      }
    }
  }

  // ------------------------------------------------------------------ //
  // bindNamedParameters (OBQuery overload)
  // ------------------------------------------------------------------ //

  @Nested
  @DisplayName("bindNamedParameters (OBQuery)")
  class BindNamedParametersOBQueryTests {

    @Test
    @DisplayName("null params is a no-op")
    @SuppressWarnings("unchecked")
    void nullParamsNoOp() {
      OBQuery<?> query = mock(OBQuery.class);
      NeoSelectorExecutionHelper.bindNamedParameters(query, null);
      verifyNoInteractions(query);
    }

    @Test
    @DisplayName("empty params is a no-op")
    @SuppressWarnings("unchecked")
    void emptyParamsNoOp() {
      OBQuery<?> query = mock(OBQuery.class);
      NeoSelectorExecutionHelper.bindNamedParameters(query, Collections.emptyMap());
      verifyNoInteractions(query);
    }

    @Test
    @DisplayName("params are bound via setNamedParameter")
    @SuppressWarnings("unchecked")
    void paramsAreBound() {
      OBQuery<?> query = mock(OBQuery.class);
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("key1", "value1");
      params.put("key2", 42);

      NeoSelectorExecutionHelper.bindNamedParameters(query, params);

      verify(query).setNamedParameter("key1", "value1");
      verify(query).setNamedParameter("key2", 42);
    }
  }

  // ------------------------------------------------------------------ //
  // bindNamedParameters (hibernate Query overload)
  // ------------------------------------------------------------------ //

  @Nested
  @DisplayName("bindNamedParameters (hibernate Query)")
  class BindNamedParametersHibernateQueryTests {

    @Test
    @DisplayName("null params is a no-op")
    @SuppressWarnings("unchecked")
    void nullParamsNoOp() {
      org.hibernate.query.Query<?> query = mock(org.hibernate.query.Query.class);
      NeoSelectorExecutionHelper.bindNamedParameters(query, null);
      verifyNoInteractions(query);
    }

    @Test
    @DisplayName("empty params is a no-op")
    @SuppressWarnings("unchecked")
    void emptyParamsNoOp() {
      org.hibernate.query.Query<?> query = mock(org.hibernate.query.Query.class);
      NeoSelectorExecutionHelper.bindNamedParameters(query, Collections.emptyMap());
      verifyNoInteractions(query);
    }

    @Test
    @DisplayName("Collection values use setParameterList")
    @SuppressWarnings("unchecked")
    void collectionValuesUseSetParameterList() {
      org.hibernate.query.Query<?> query = mock(org.hibernate.query.Query.class);
      Collection<String> list = Arrays.asList("a", "b", "c");
      Map<String, Object> params = new HashMap<>();
      params.put("ids", list);

      NeoSelectorExecutionHelper.bindNamedParameters(query, params);

      verify(query).setParameterList(eq("ids"), eq(list));
      verify(query, never()).setParameter(anyString(), any());
    }

    @Test
    @DisplayName("non-Collection values use setParameter")
    @SuppressWarnings("unchecked")
    void nonCollectionValuesUseSetParameter() {
      org.hibernate.query.Query<?> query = mock(org.hibernate.query.Query.class);
      Map<String, Object> params = new HashMap<>();
      params.put("name", "test");

      NeoSelectorExecutionHelper.bindNamedParameters(query, params);

      verify(query).setParameter("name", "test");
      verify(query, never()).setParameterList(anyString(), any(Collection.class));
    }

    @Test
    @DisplayName("mixed params dispatch correctly")
    @SuppressWarnings("unchecked")
    void mixedParamsDispatchCorrectly() {
      org.hibernate.query.Query<?> query = mock(org.hibernate.query.Query.class);
      Collection<String> orgIds = Arrays.asList("org1", "org2");
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("orgIds", orgIds);
      params.put("active", true);

      NeoSelectorExecutionHelper.bindNamedParameters(query, params);

      verify(query).setParameterList(eq("orgIds"), eq(orgIds));
      verify(query).setParameter("active", true);
    }
  }
}
