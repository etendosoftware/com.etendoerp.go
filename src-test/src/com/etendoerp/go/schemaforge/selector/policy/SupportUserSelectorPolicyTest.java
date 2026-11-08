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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge.selector.policy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.data.FieldProvider;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.supportaccess.SupportAccessGuard;

/**
 * ETP-5351 (T6) — the tenant's "Soporte Etendo" user is never offered by an {@code AD_User}
 * selector: the HQL route ({@link SupportUserSelectorPolicy}, wired in {@link NeoSelectorPolicy})
 * and the SQL-rule combo route ({@link ComboRowSelectorPolicy}). No database.
 */
class SupportUserSelectorPolicyTest {

  private static final String CLIENT = "4028E6C72959682B01295A070852010D";
  private static final String SUPPORT_USER = SupportAccessGuard.supportUserIdFor(CLIENT);
  private static final String ENTITY_USER = "ADUser";
  private static final String FIELD_ID = "ID";

  private MockedStatic<OBContext> obContextMock;

  @BeforeEach
  void setUp() {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT);
    OBContext context = mock(OBContext.class);
    when(context.getCurrentClient()).thenReturn(client);
    obContextMock = mockStatic(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(context);
  }

  @AfterEach
  void tearDown() {
    obContextMock.close();
  }

  private static FieldProvider row(String id) {
    FieldProvider row = mock(FieldProvider.class);
    when(row.getField(FIELD_ID)).thenReturn(id);
    return row;
  }

  @Test
  void appliesToUserSelectorsOnly() {
    SupportUserSelectorPolicy policy = new SupportUserSelectorPolicy();

    assertTrue(policy.supports(ENTITY_USER));
    assertFalse(policy.supports("BusinessPartner"));
  }

  @Test
  void filterBindsTheSupportUserInsteadOfInliningIt() {
    String filter = new SupportUserSelectorPolicy()
        .resolveFilter(ENTITY_USER, Collections.emptyMap(), "u");

    assertEquals("u.id <> :etgoSupportUserId", filter);
    assertFalse(filter.contains(SUPPORT_USER));
  }

  @Test
  void filterIsSkippedWithoutAClient() {
    obContextMock.when(OBContext::getOBContext).thenReturn(null);

    assertNull(new SupportUserSelectorPolicy()
        .resolveFilter(ENTITY_USER, Collections.emptyMap(), "e"));
  }

  @Test
  void registryDispatchesUserSelectors() {
    assertEquals("e.id <> :etgoSupportUserId",
        NeoSelectorPolicy.resolveContextParamFilter(ENTITY_USER, Collections.emptyMap(), "e"));
  }

  @Test
  void comboRowsDropTheSupportUserWhateverTheColumn() {
    FieldProvider real = row("B0000000000000000000000000000001");
    FieldProvider support = row(SUPPORT_USER);

    FieldProvider[] filtered = ComboRowSelectorPolicy.filter("SalesRep_ID",
        new FieldProvider[] { real, support });

    assertArrayEquals(new FieldProvider[] { real }, filtered);
  }

  @Test
  void comboRowsAreUntouchedWithoutAClient() {
    obContextMock.when(OBContext::getOBContext).thenReturn(null);
    FieldProvider[] rows = { row(SUPPORT_USER) };

    assertSame(rows, ComboRowSelectorPolicy.filter("SalesRep_ID", rows));
  }
}
