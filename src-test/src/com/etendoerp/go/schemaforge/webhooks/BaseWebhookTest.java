/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2025 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge.webhooks;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Base class for SchemaForge webhook tests. Provides common mock setup
 * for OBDal, OBContext, Client and Organization, as well as shared
 * parameter and response maps.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
abstract class BaseWebhookTest {

    @Mock
    protected OBDal obDal;
    @Mock
    protected OBContext obContext;
    @Mock
    protected Client client;
    @Mock
    protected Organization organization;

    protected MockedStatic<OBDal> obDalMock;
    protected MockedStatic<OBContext> obContextMock;

    protected Map<String, String> parameters;
    protected Map<String, String> responseVars;

    @BeforeEach
    void baseSetUp() {
        obDalMock = mockStatic(OBDal.class);
        obContextMock = mockStatic(OBContext.class);

        obDalMock.when(OBDal::getInstance).thenReturn(obDal);
        obContextMock.when(OBContext::getOBContext).thenReturn(obContext);

        when(obContext.getCurrentClient()).thenReturn(client);
        when(obContext.getCurrentOrganization()).thenReturn(organization);

        parameters = new HashMap<>();
        responseVars = new HashMap<>();
    }

    @AfterEach
    void baseTearDown() {
        obDalMock.close();
        obContextMock.close();
    }

    /**
     * Creates a mock OBCriteria for the given entity class, pre-configured
     * with chained add() and setMaxResults() returning itself.
     */
    @SuppressWarnings("unchecked")
    protected <T extends BaseOBObject> OBCriteria<T> mockCriteria(Class<T> entityClass) {
        OBCriteria<T> crit = mock(OBCriteria.class);
        when(obDal.createCriteria(entityClass)).thenReturn(crit);
        when(crit.add(any())).thenReturn(crit);
        when(crit.setMaxResults(anyInt())).thenReturn(crit);
        return crit;
    }
}
