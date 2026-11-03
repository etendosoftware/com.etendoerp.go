/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.email.contracts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.schemaforge.data.Invitation;
import com.etendoerp.go.schemaforge.email.EmailAuthorizationResult;
import com.etendoerp.go.schemaforge.email.EmailContractCommand;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;

/**
 * ETP-5819: through the NEO email endpoint the invitation id comes from the request, so another
 * tenant's invitation is not found; the server's own send, in the System context, still finds the
 * invitation it created.
 *
 * @covers com.etendoerp.go.schemaforge.email.contracts.CompanyInvitationEmailContract
 */
public class CompanyInvitationEmailContractTenantTest {

  private static final String TENANT = "TENANT-A";
  private static final String OTHER_TENANT = "TENANT-B";
  private static final String INVITATION_ID = "INVITATION-1";

  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBContext> contextStatic;
  private OBDal dal;

  @Before
  public void setUp() {
    dal = mock(OBDal.class);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    contextStatic = mockStatic(OBContext.class);
  }

  @After
  public void tearDown() {
    contextStatic.close();
    dalStatic.close();
  }

  @Test
  public void anotherTenantsInvitationIsNotFound() throws Exception {
    invitationOf(OTHER_TENANT);
    runningAs(TENANT);

    EmailAuthorizationResult result = authorize();

    assertFalse(result.isAllowed());
    assertEquals(404, result.getHttpStatus());
  }

  @Test
  public void theCallersOwnInvitationIsFound() throws Exception {
    invitationOf(TENANT);
    runningAs(TENANT);

    assertTrue(authorize().isAllowed());
  }

  /** The server's own send (TransactionalAuthEmailSender) switches to the System context. */
  @Test
  public void theSystemContextFindsAnyTenantsInvitation() throws Exception {
    invitationOf(OTHER_TENANT);
    runningAs("0");

    assertTrue(authorize().isAllowed());
  }

  private void invitationOf(String clientId) {
    Invitation invitation = mock(Invitation.class);
    Client client = client(clientId);
    when(invitation.getClient()).thenReturn(client);
    when(dal.get(Invitation.class, INVITATION_ID)).thenReturn(invitation);
  }

  private void runningAs(String clientId) {
    OBContext context = mock(OBContext.class);
    Client client = client(clientId);
    when(context.getCurrentClient()).thenReturn(client);
    when(context.getReadableClients()).thenReturn(new String[] { clientId, "0" });
    contextStatic.when(OBContext::getOBContext).thenReturn(context);
  }

  private static Client client(String id) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(id);
    return client;
  }

  private static EmailAuthorizationResult authorize() throws Exception {
    JSONObject body = new JSONObject()
        .put(EmailContractCommandSupport.FIELD_RECORD_ID, INVITATION_ID)
        .put(EmailContractCommandSupport.FIELD_LINK, "https://app.example.test/invite/abc");
    return new CompanyInvitationEmailContract()
        .authorize(new EmailContractCommand("company-invitation", body));
  }
}
