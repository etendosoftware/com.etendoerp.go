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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

/**
 * The contact category of a purchase document: the unique {@code Proveedor} one of the tenant, the
 * System's only when the tenant has none, none when several share the scope; and the tax category
 * of a tax.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxConfirmLookups
 */
class InvoiceInboxConfirmLookupsTest {

  private MockedStatic<OBDal> dalStatic;
  private Session session;
  private NativeQuery<?> query;

  @BeforeEach
  @SuppressWarnings({ "unchecked", "rawtypes" })
  void setUp() {
    OBDal dal = mock(OBDal.class);
    session = mock(Session.class);
    query = mock(NativeQuery.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn((NativeQuery) query);
    when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any()))
        .thenReturn((NativeQuery) query);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
  }

  @AfterEach
  void tearDown() {
    dalStatic.close();
  }

  /** The categories answered for the tenant, then for System, in the order they are asked. */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private void categories(List<Object> tenant, List<Object> system) {
    when(((NativeQuery) query).list()).thenReturn(tenant, system);
  }

  @Test
  @DisplayName("one Proveedor in the tenant is the category; System is not asked")
  void theTenantsOwnWins() {
    categories(List.of("BPG-T"), List.of("BPG-0"));

    assertEquals("BPG-T", InvoiceInboxConfirmLookups.purchaseContactCategory("C1"));

    verify(query).setParameter("client", "C1");
    verify(query, never()).setParameter("client", "0");
    verify(query).setParameter("name", "proveedor");
  }

  @Test
  @DisplayName("System's is used only when the tenant has none")
  void systemOnlyWhenTheTenantHasNone() {
    categories(List.of(), List.of("BPG-0"));

    assertEquals("BPG-0", InvoiceInboxConfirmLookups.purchaseContactCategory("C1"));

    verify(query).setParameter("client", "0");
  }

  @Test
  @DisplayName("two in the tenant give none (never a guess), and System is not consulted to break the tie")
  void twoInTheTenantGiveNone() {
    categories(List.of("A", "B"), List.of("BPG-0"));

    assertNull(InvoiceInboxConfirmLookups.purchaseContactCategory("C1"));
    verify(query, never()).setParameter("client", "0");
  }

  @Test
  @DisplayName("none anywhere, or two in System, give none")
  void noneOrTwoInSystemGiveNone() {
    categories(List.of(), List.of());
    assertNull(InvoiceInboxConfirmLookups.purchaseContactCategory("C1"));

    categories(List.of(), List.of("A", "B"));
    assertNull(InvoiceInboxConfirmLookups.purchaseContactCategory("C1"));
  }

  @Test
  @DisplayName("a document of the System client asks only System")
  void theSystemClientAsksOnce() {
    categories(List.of(), List.of("never"));

    assertNull(InvoiceInboxConfirmLookups.purchaseContactCategory("0"));

    verify(session).createNativeQuery(anyString());
  }

  @Test
  @SuppressWarnings({ "unchecked", "rawtypes" })
  @DisplayName("the tax category of a tax is read by its id; no tax id, no query")
  void taxCategoryOf() {
    when(((NativeQuery) query).uniqueResult()).thenReturn("TC1");

    assertEquals("TC1", InvoiceInboxConfirmLookups.taxCategoryOf("T1"));
    assertNull(InvoiceInboxConfirmLookups.taxCategoryOf(null));

    verify(query).setParameter("id", "T1");
    verify(session).createNativeQuery("SELECT c_taxcategory_id FROM c_tax WHERE c_tax_id = :id");
  }
}
