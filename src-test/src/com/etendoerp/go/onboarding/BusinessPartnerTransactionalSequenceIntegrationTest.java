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
package com.etendoerp.go.onboarding;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Sequence;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Category;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.test.base.OBBaseTest;

/**
 * Integration regression tests for Business Partner transactional sequences.
 */
public class BusinessPartnerTransactionalSequenceIntegrationTest extends OBBaseTest {

  private static final String BP_IDENTIFIER_COLUMN_ID = "294937FFC81749289BD9BB28E400D4B2";

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void testOnboardingGeneratesBusinessPartnerIdentifierSequenceBeforeDefaultCustomer()
      throws Exception {
    setTestUserContext();
    addReadWriteAccess(BusinessPartner.class);
    addReadWriteAccess(Sequence.class);

    new OnboardingSequenceGeneratorService().generateSequences(TEST_CLIENT_ID, TEST_ORG_ID,
        TEST_USER_ID, TEST_ROLE_ID);

    OBContext.setAdminMode(true);
    try {
      assertNotNull("Onboarding sequence generation must create the Business Partner identifier "
          + "sequence combination", findBusinessPartnerIdentifierSequence());
    } finally {
      OBContext.restorePreviousMode();
    }

    String customerId = new UniqueDefaultCustomerService().ensureDefaultCustomer(TEST_CLIENT_ID,
        TEST_ORG_ID, TEST_USER_ID, TEST_ROLE_ID);

    OBContext.setAdminMode(true);
    try {
      BusinessPartner businessPartner = OBDal.getInstance().get(BusinessPartner.class, customerId);
      assertNotNull("Onboarding default customer must exist after creation", businessPartner);
      String identifier = businessPartner.getEtgoIdentifier();
      assertNotNull("Onboarding default customer must receive EM_Etgo_Identifier", identifier);
      assertTrue("Onboarding default customer must use the transactional sequence mask",
          identifier.matches("\\d{7}"));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private Sequence findBusinessPartnerIdentifierSequence() {
    OBCriteria<Sequence> criteria = OBDal.getInstance().createCriteria(Sequence.class);
    criteria.add(Restrictions.eq(Sequence.PROPERTY_CLIENT,
        OBDal.getInstance().get(Client.class, TEST_CLIENT_ID)));
    criteria.add(Restrictions.eq(Sequence.PROPERTY_ORGANIZATION,
        OBDal.getInstance().get(Organization.class, TEST_ORG_ID)));
    criteria.add(Restrictions.eq(Sequence.PROPERTY_COLUMN,
        OBDal.getInstance().get(Column.class, BP_IDENTIFIER_COLUMN_ID)));
    criteria.setMaxResults(1);
    return (Sequence) criteria.uniqueResult();
  }

  private static final class UniqueDefaultCustomerService extends OnboardingDefaultCustomerService {
    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 20);

    @Override
    protected BusinessPartner findExistingDefaultCustomer(String clientId, String orgId) {
      return null;
    }

    @Override
    protected BusinessPartner createDefaultCustomer(Client client, Organization organization,
        Category bpGroup) {
      BusinessPartner customer = super.createDefaultCustomer(client, organization, bpGroup);
      customer.setSearchKey("ONBOARDING_BPSEQ_" + suffix);
      customer.setName("Onboarding BP Sequence " + suffix);
      return customer;
    }
  }
}
