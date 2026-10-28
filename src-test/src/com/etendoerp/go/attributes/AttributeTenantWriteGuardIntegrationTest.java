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

package com.etendoerp.go.attributes;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.ETGOAttrDef;
import com.etendoerp.go.schemaforge.data.ETGOAttrDefCategory;
import com.etendoerp.go.schemaforge.data.ETGOProductAttrVal;

/**
 * ETP-5977, H3: a session of client A cannot update or delete an attribute record of client B. The gap is core's
 * {@code DefaultJsonDataService#remove}/{@code update}, which load by id without a client filter; the guard is a DAL
 * observer, so it is exercised here at the DAL, the layer every channel shares. Uses the client B rows of the POC
 * dataset (sql/10-dataset.sql) and never commits.
 *
 * @covers com.etendoerp.go.attributes.AttributeTenantWriteGuard
 */
public class AttributeTenantWriteGuardIntegrationTest extends WeldBaseTest {

  private static final String CLIENT_A = "23C59575B9CF467C9620760EB255B389";
  private static final String CLIENT_B = "4028E6C72959682B01295A070852010D";

  private String bValue;
  private String bDefinition;
  private String bScope;
  private String aValue;

  @Before
  public void asClientA() {
    OBContext.setOBContext("100", "42D0EEB1C66F497A90DD526DC597E6F0", CLIENT_A, "19404EAD144C49A0AF37D54377CF452D");
    OBContext.setAdminMode(false);
    bValue = one("select etgo_product_attr_val_id from etgo_product_attr_val where ad_client_id = '" + CLIENT_B + "' limit 1");
    bDefinition = one("select etgo_attr_def_id from etgo_attr_def where ad_client_id = '" + CLIENT_B + "' limit 1");
    bScope = one("select etgo_attr_def_category_id from etgo_attr_def_category where ad_client_id = '" + CLIENT_B + "' limit 1");
    aValue = one("select etgo_product_attr_val_id from etgo_product_attr_val where ad_client_id = '" + CLIENT_A + "' limit 1");
  }

  @After
  public void rollBack() {
    OBDal.getInstance().rollbackAndClose();
    while (OBContext.getOBContext() != null && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void deletingAValueOfAnotherClientIsRefused() {
    ETGOProductAttrVal v = OBDal.getInstance().get(ETGOProductAttrVal.class, bValue);
    assertRefused(() -> {
      OBDal.getInstance().remove(v);
      OBDal.getInstance().flush();
    });
  }

  @Test
  public void updatingAValueOfAnotherClientIsRefused() {
    ETGOProductAttrVal v = OBDal.getInstance().get(ETGOProductAttrVal.class, bValue);
    assertRefused(() -> {
      v.setActive(false);
      OBDal.getInstance().flush();
    });
  }

  @Test
  public void updatingADefinitionOfAnotherClientIsRefused() {
    ETGOAttrDef d = OBDal.getInstance().get(ETGOAttrDef.class, bDefinition);
    assertRefused(() -> {
      d.setName("renamed");
      OBDal.getInstance().flush();
    });
  }

  @Test
  public void deletingACategoryScopeOfAnotherClientIsRefused() {
    ETGOAttrDefCategory s = OBDal.getInstance().get(ETGOAttrDefCategory.class, bScope);
    assertRefused(() -> {
      OBDal.getInstance().remove(s);
      OBDal.getInstance().flush();
    });
  }

  @Test
  public void updatingAValueOfOwnClientIsAllowed() {
    ETGOProductAttrVal v = OBDal.getInstance().get(ETGOProductAttrVal.class, aValue);
    v.setActive(!v.isActive());
    OBDal.getInstance().flush(); // rolled back in @After
  }

  private static void assertRefused(Runnable action) {
    try {
      action.run();
      fail("a write on another client's record must be refused");
    } catch (RuntimeException e) {
      StringBuilder chain = new StringBuilder();
      for (Throwable t = e; t != null; t = t.getCause()) {
        chain.append(t.getMessage()).append(" | ");
      }
      assertTrue(chain.toString(), chain.toString().contains("Record not found"));
    }
  }

  private static String one(String sql) {
    return (String) ((NativeQuery<?>) OBDal.getInstance().getSession().createNativeQuery(sql)).uniqueResult();
  }
}
