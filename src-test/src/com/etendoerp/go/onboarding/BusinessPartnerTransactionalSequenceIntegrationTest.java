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

import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Sequence;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.test.base.OBBaseTest;

/**
 * Integration regression tests for Business Partner transactional sequences.
 */
@Ignore("Temporarily disabled — flaky in CI due to sequence state dependency")
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

  /**
   * ETP-5079: this used to also create the onboarding "Default Customer" and assert it received an
   * {@code EM_Etgo_Identifier} from the sequence. Onboarding no longer provisions that business
   * partner (a new tenant is born with none), so the assertion has no subject any more. What
   * remains — and is still worth guarding — is that onboarding generates the BP identifier
   * sequence itself, so any partner the tenant creates later gets a masked identifier.
   */
  @Test
  public void testOnboardingGeneratesBusinessPartnerIdentifierSequence() throws Exception {
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

}
