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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.psd2.bank.integration.data.PisPayment;

/**
 * Covers the decision logic {@link PisDeferredPaymentService} adds for ETP-4895 — which Salt Edge
 * statuses produce a payment, how a rejected transfer is distinguished, and how the request
 * snapshot and the per-attempt bank reference are built.
 *
 * <p>These are the pieces that decide whether a payment is created at all, so they are exercised
 * directly rather than through the DAL-heavy entry points. Everything under test is pure: no
 * database, no Salt Edge. The private methods are reached by reflection, which is the only way to
 * pin this logic without widening the class's API purely for testing.
 */
class PisDeferredPaymentServiceTest {

  private static Object invokePrivate(String name, Class<?>[] types, Object... args)
      throws Exception {
    Method m = PisDeferredPaymentService.class.getDeclaredMethod(name, types);
    m.setAccessible(true);
    return m.invoke(null, args);
  }

  private static boolean requiresPayment(String status) throws Exception {
    return (boolean) invokePrivate("requiresPayment", new Class<?>[]{ String.class }, status);
  }

  @Nested
  @DisplayName("which statuses produce a payment")
  class RequiresPayment {

    @Test
    @DisplayName("every resolutive status produces a payment, failed included")
    void resolutiveStatusesProduceAPayment() throws Exception {
      // The ticket's contract: AUTHORIZED / EXECUTED / SETTLED create the payment, and FAILED
      // creates it too — flagged as an error — so a rejected attempt stays visible on the invoice
      // instead of vanishing.
      assertTrue(requiresPayment("authorized"));
      assertTrue(requiresPayment("executed"));
      assertTrue(requiresPayment("settled"));
      assertTrue(requiresPayment("failed"));
    }

    @Test
    @DisplayName("no payment exists while the transfer is still in flight")
    void intermediateStatusesProduceNothing() throws Exception {
      // Up to this point the user may still abandon the bank window, so nothing must be left
      // behind on the invoice. 'initiated_info_required' is the status that caused the original
      // bug: it is a perfectly normal in-flight state, not a failure.
      assertFalse(requiresPayment("requested"));
      assertFalse(requiresPayment("initiated"));
      assertFalse(requiresPayment("initiated_info_required"));
      assertFalse(requiresPayment("authorizing"));
    }

    @Test
    @DisplayName("an unknown status is treated as still in flight, never as resolved")
    void unknownStatusProducesNothing() throws Exception {
      // Defaulting an unrecognized status to "resolved" would register a payment for a transfer
      // that may never happen. Erring towards "not yet" is the safe direction.
      assertFalse(requiresPayment("some_future_saltedge_status"));
      assertFalse(requiresPayment(""));
      assertFalse(requiresPayment(null));
    }

    @Test
    @DisplayName("status matching ignores case")
    void statusMatchingIsCaseInsensitive() throws Exception {
      assertTrue(requiresPayment("AUTHORIZED"));
      assertTrue(requiresPayment("Executed"));
    }
  }

  @Nested
  @DisplayName("failed vs settled classification")
  class StatusClassification {

    private boolean isFailed(String status) throws Exception {
      return (boolean) invokePrivate("isFailedStatus", new Class<?>[]{ String.class }, status);
    }

    private boolean isSettled(String status) throws Exception {
      return (boolean) invokePrivate("isSettledStatus", new Class<?>[]{ String.class }, status);
    }

    @Test
    @DisplayName("only 'failed' marks the payment as rejected")
    void onlyFailedIsAFailure() throws Exception {
      assertTrue(isFailed("failed"));
      assertTrue(isFailed("FAILED"));
      assertFalse(isFailed("authorized"));
      assertFalse(isFailed("initiated_info_required"));
      assertFalse(isFailed(null));
    }

    @Test
    @DisplayName("only executed/settled book the bank transaction")
    void onlySettledStatusesBookTheTransaction() throws Exception {
      // 'authorized' creates the payment but the money has not landed, so no financial
      // transaction may be created for it yet.
      assertTrue(isSettled("executed"));
      assertTrue(isSettled("settled"));
      assertFalse(isSettled("authorized"));
      assertFalse(isSettled("failed"));
    }
  }

  @Nested
  @DisplayName("per-attempt bank reference")
  class EndToEndId {

    /** Runs the real nextEndToEndId with the DAL stubbed to report {@code existingAttempts}. */
    @SuppressWarnings("unchecked")
    private String buildReference(String documentNo, int existingAttempts) throws Exception {
      Invoice invoice = mock(Invoice.class);
      when(invoice.getDocumentNo()).thenReturn(documentNo);
      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(obDal);
        OBCriteria<PisPayment> crit = mock(OBCriteria.class);
        when(obDal.createCriteria(PisPayment.class)).thenReturn(crit);
        when(crit.add(any())).thenReturn(crit);
        when(crit.count()).thenReturn(existingAttempts);
        return (String) invokePrivate("nextEndToEndId", new Class<?>[]{ Invoice.class }, invoice);
      }
    }

    @Test
    @DisplayName("a retry never reuses the previous reference")
    void retryGetsADistinctReference() throws Exception {
      // End-to-end ids must be unique per debtor account: resubmitting the same one risks a
      // silent rejection at the bank or a false "already processed" match.
      String first = buildReference("10000236", 0);
      String second = buildReference("10000236", 1);
      assertEquals("10000236-1", first);
      assertEquals("10000236-2", second);
    }

    @Test
    @DisplayName("a long document number is truncated to the 35-char limit")
    void longDocumentNumberIsTruncated() throws Exception {
      // GenerateBankPayment rejects anything longer, so the suffix must survive truncation.
      String reference = buildReference("A".repeat(60), 0);
      assertEquals(35, reference.length());
      assertTrue(reference.endsWith("-1"));
    }
  }

  @Nested
  @DisplayName("request snapshot")
  class Intent {

    @Test
    @DisplayName("keeps the invoice, the direction and the original request verbatim")
    void snapshotCarriesWhatTheReplayNeeds() throws Exception {
      // The snapshot is what rebuilds the payment minutes later, when neither the modal nor the
      // HTTP request exist any more. Losing any of it means not knowing what to pay.
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("INV-1");
      JSONObject body = new JSONObject();
      body.put("scheduleId", "SCH-1");
      body.put("actual_payment", "24.20");
      body.put("fin_paymentmethod_id", "PM-1");

      JSONObject intent = (JSONObject) invokePrivate("buildIntent",
          new Class<?>[]{ Invoice.class, JSONObject.class, boolean.class }, invoice, body, false);

      assertEquals("INV-1", intent.getString("invoiceId"));
      assertFalse(intent.getBoolean("isReceipt"));
      JSONObject stored = intent.getJSONObject("body");
      assertEquals("SCH-1", stored.getString("scheduleId"));
      assertEquals("24.20", stored.getString("actual_payment"));
      assertEquals("PM-1", stored.getString("fin_paymentmethod_id"));
    }

    @Test
    @DisplayName("keeps the receipt direction for a sales invoice")
    void snapshotKeepsDirection() throws Exception {
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("INV-2");

      JSONObject intent = (JSONObject) invokePrivate("buildIntent",
          new Class<?>[]{ Invoice.class, JSONObject.class, boolean.class },
          invoice, new JSONObject(), true);

      assertTrue(intent.getBoolean("isReceipt"));
    }
  }

  @Nested
  @DisplayName("reading the replayed payment id")
  class ExtractPaymentId {

    private String extract(NeoResponse response) throws Exception {
      return (String) invokePrivate("extractPaymentId", new Class<?>[]{ NeoResponse.class },
          response);
    }

    @Test
    @DisplayName("digs the id out of the response envelope")
    void readsTheIdFromTheEnvelope() throws Exception {
      JSONObject data = new JSONObject();
      data.put("id", "PAY-1");
      JSONObject inner = new JSONObject();
      inner.put("data", data);
      JSONObject body = new JSONObject();
      body.put("response", inner);

      assertEquals("PAY-1", extract(new NeoResponse(201, body)));
    }

    @Test
    @DisplayName("returns null instead of throwing on an unexpected envelope")
    void toleratesAMalformedEnvelope() throws Exception {
      // A null id is handled by the caller as "the replay produced nothing", which is logged and
      // retried later — far better than an exception aborting the status refresh.
      assertNull(extract(null));
      assertNull(extract(new NeoResponse(500, null)));
      assertNull(extract(new NeoResponse(200, new JSONObject())));
    }
  }
}
