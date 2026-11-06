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

package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.util.NeoAccountingAccessPolicy;

/**
 * ETP-6087: a role without {@code showAccountingFields} never sees {@code posted} in a list, so
 * it may not filter or sort by it either — otherwise {@code filters:{"posted":"Y"}} splits the
 * rows by the very status the projection hides.
 *
 * @covers com.etendoerp.go.mcp.McpQuerySupport
 */
class McpQuerySupportAccountingTest {

  private MockedStatic<NeoAccountingAccessPolicy> policy;
  private SFEntity entity;

  @BeforeEach
  void setUp() {
    policy = mockStatic(NeoAccountingAccessPolicy.class, CALLS_REAL_METHODS);
    entity = mock(SFEntity.class);
    when(entity.getName()).thenReturn("Invoice");
  }

  @AfterEach
  void tearDown() {
    policy.close();
  }

  private void roleSeesAccounting(boolean sees) {
    policy.when(NeoAccountingAccessPolicy::currentRoleSeesAccounting).thenReturn(sees);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "posted", "Posted", "EM_Etblkp_Accountingstatus",
      "etblkpAccountingstatus" })
  void anAccountingFilterIsRefusedLikeAnExcludedField(String key) throws Exception {
    roleSeesAccounting(false);
    JSONObject filters = new JSONObject().put("documentNo", "1000").put(key, "Y");

    McpRoutingException refusal = assertThrows(McpRoutingException.class,
        () -> McpQuerySupport.refuseHiddenAccountingFields(filters, null, entity, "invoice"));

    assertEquals(McpConstants.ERROR_UNKNOWN_FILTER_FIELD, refusal.getErrorCode());
    assertEquals(key, refusal.getField());
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "posted", "-posted", "documentNo,posted desc", "invoice.posted" })
  void anAccountingSortIsRefused(String orderBy) {
    roleSeesAccounting(false);

    McpRoutingException refusal = assertThrows(McpRoutingException.class,
        () -> McpQuerySupport.refuseHiddenAccountingFields(null, orderBy, entity, "invoice"));

    assertEquals(McpConstants.ERROR_VALIDATION, refusal.getErrorCode());
    assertEquals("orderBy", refusal.getField());
  }

  @Test
  void aRoleWithTheCapabilityFiltersAndSortsByPosted() throws Exception {
    roleSeesAccounting(true);
    JSONObject filters = new JSONObject().put("posted", "Y");

    assertDoesNotThrow(
        () -> McpQuerySupport.refuseHiddenAccountingFields(filters, "-posted", entity, "invoice"));
  }

  @Test
  void otherFieldsNeverReadTheRoleFlag() throws Exception {
    JSONObject filters = new JSONObject().put("documentNo", "1000").put("postedBy", "x");

    assertDoesNotThrow(() -> McpQuerySupport.refuseHiddenAccountingFields(filters,
        "invoiceDate desc,postingDate", entity, "invoice"));
    policy.verify(NeoAccountingAccessPolicy::currentRoleSeesAccounting, never());
  }
}
