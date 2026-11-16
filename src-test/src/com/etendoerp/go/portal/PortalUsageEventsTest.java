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


package com.etendoerp.go.portal;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mockStatic;

import java.util.Date;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.supportaccess.SupportAccessGuard;
import com.etendoerp.go.usageevents.UsageEvent;
import com.etendoerp.go.usageevents.UsageEventRecorder;
import com.etendoerp.go.usageevents.UsageEventTypes;

/**
 * Unit tests for {@link PortalUsageEvents}: what the portal's KPI events carry. KPI semantics are
 * the PO's and deliberately not asserted here; only that each event names its send.
 *
 * @covers com.etendoerp.go.portal.PortalUsageEvents
 */
public class PortalUsageEventsTest {

  @Test
  public void sentCarriesTheSendLogTheRecipientCountAndTheRootLink() throws Exception {
    PortalLink link = new PortalLink("LINK-2", "CLIENT-1", "Acme", "ORG-1", "BP-1",
        "Customer SL", PortalDocumentKind.ORDER, "ORD-1", PortalLink.ORIGIN_SEND, null, "LINK-2",
        "sales-order-send", "a@customer.test", null, "es_ES", new Date(0L), new Date(1L), true,
        false);

    UsageEvent event;
    try (MockedStatic<UsageEventRecorder> recorder = mockStatic(UsageEventRecorder.class);
        MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<SupportAccessGuard> support = mockStatic(SupportAccessGuard.class)) {
      PortalUsageEvents.sent(link, "LOG-7", 3);

      ArgumentCaptor<UsageEvent> submitted = ArgumentCaptor.forClass(UsageEvent.class);
      recorder.verify(() -> UsageEventRecorder.submit(submitted.capture()));
      event = submitted.getValue();
    }

    assertEquals(UsageEventTypes.PORTAL_LINK_SENT, event.eventType());
    assertEquals("one send is one root link", "LINK-2", event.sessionKey());
    assertEquals("CLIENT-1", event.clientId());
    JSONObject properties = new JSONObject(event.properties());
    assertEquals("LOG-7", properties.getString("sendLogId"));
    assertEquals(3, properties.getInt("recipientCount"));
    assertEquals("sales-order-send", properties.getString("contractName"));
  }
}
