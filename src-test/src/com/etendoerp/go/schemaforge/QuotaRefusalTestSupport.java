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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

import java.util.Collection;
import java.util.Date;

import org.codehaus.jettison.json.JSONObject;
import org.mockito.MockedStatic;
import org.openbravo.base.structure.BaseOBObject;

import com.etendoerp.go.usage.quota.QuotaDefinition;
import com.etendoerp.go.usage.quota.QuotaEvaluation;
import com.etendoerp.go.usage.quota.QuotaPeriod;
import com.etendoerp.go.usage.quota.QuotaVerdict;

/**
 * ETP-5051 — shared fixture for the creators wired to {@link NeoQuotaCreateCheck}: a static mock
 * under which every create check refuses, and the assertion on the CRUD gate's refusal envelope.
 *
 * <p>Each creator's own test class asserts what matters for that creator — that it answers the
 * refusal, rolls back, and runs nothing irreversible after the check. What the check itself decides
 * is pinned in {@code NeoQuotaCreateCheckTest}.
 *
 * @covers com.etendoerp.go.schemaforge.NeoQuotaCreateCheck
 */
final class QuotaRefusalTestSupport {

  static final String RESOURCE_KEY = "CREATED_DOCUMENTS";

  private QuotaRefusalTestSupport() {
  }

  /** A {@code block} quota of 10 documents, all consumed: the refusal a creator must answer. */
  static NeoQuotaCreateCheck.Refused refusal() {
    QuotaDefinition quota = QuotaDefinition.builder().resourceId("RES-DOC")
        .resourceKey(RESOURCE_KEY).resourceName("Documents").unitLabel("Doc")
        .enforcementMode("block").includedQty(10L).build();
    Date start = new Date(1_790_000_000_000L);
    Date end = new Date(1_792_600_000_000L);
    QuotaEvaluation atLimit = new QuotaEvaluation(quota, "month",
        QuotaPeriod.current(start, end, false, new Date(1_791_000_000_000L)), 10L,
        QuotaVerdict.DENY);
    return new NeoQuotaCreateCheck.Refused(NeoQuotaWriteGate.refusalResponse(atLimit), null);
  }

  /**
   * Every create check refuses: the document variant, the rows variant and the id variant. Close
   * it (try-with-resources) at the end of the case.
   */
  @SuppressWarnings("unchecked")
  static MockedStatic<NeoQuotaCreateCheck> everyCreateRefused() {
    MockedStatic<NeoQuotaCreateCheck> check = mockStatic(NeoQuotaCreateCheck.class);
    check.when(() -> NeoQuotaCreateCheck.requireAllowedDocument(any(BaseOBObject.class)))
        .thenThrow(refusal());
    check.when(() -> NeoQuotaCreateCheck.requireAllowed(anyString(), any(Collection.class)))
        .thenThrow(refusal());
    check.when(() -> NeoQuotaCreateCheck.requireAllowed(any(Collection.class)))
        .thenThrow(refusal());
    return check;
  }

  /** The response is the CRUD gate's 403 {@code QUOTA_EXCEEDED} with the {@code quota} body. */
  static void assertQuotaExceeded(NeoResponse response) throws Exception {
    assertNotNull("the action must answer the refusal", response);
    assertEquals(403, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals("QUOTA_EXCEEDED", error.getString("code"));
    assertEquals(RESOURCE_KEY, error.getJSONObject("quota").getString("resourceKey"));
  }
}
