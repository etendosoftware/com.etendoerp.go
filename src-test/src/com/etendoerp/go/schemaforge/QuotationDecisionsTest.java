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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.RejectReason;

/**
 * Unit tests for {@link QuotationDecisions}, the quotation decision writes the app's reject action
 * and the customer portal's confirmed decisions share.
 *
 * @covers com.etendoerp.go.schemaforge.QuotationDecisions
 */
public class QuotationDecisionsTest {

  @Test
  public void onlyAQuotationUnderEvaluationCanBeDecided() {
    for (String status : Arrays.asList("DR", "CO", "CJ", "CA", "ETGO_CI", "ETGO_AC", "VO",
        null)) {
      Order quotation = mock(Order.class);
      when(quotation.getDocumentStatus()).thenReturn(status);

      assertFalse(status, QuotationDecisions.isDecidable(quotation));
    }
    Order underEvaluation = mock(Order.class);
    when(underEvaluation.getDocumentStatus()).thenReturn("UE");
    assertTrue(QuotationDecisions.isDecidable(underEvaluation));
    assertFalse(QuotationDecisions.isDecidable(null));
  }

  @Test
  public void rejectClosesTheQuotationAsRejectedAndKeepsAMissingReasonUnset() {
    RejectReason reason = mock(RejectReason.class);
    Order withReason = mock(Order.class);
    Order withoutReason = mock(Order.class);
    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);

      QuotationDecisions.reject(withReason, reason);
      QuotationDecisions.reject(withoutReason, null);
    }

    for (Order quotation : Arrays.asList(withReason, withoutReason)) {
      verify(quotation).setDocumentStatus("CJ");
      verify(quotation).setProcessed(true);
    }
    verify(withReason).setRejectReason(reason);
    // The portal has no tenant reason: it must not clear one the seller may set later.
    verify(withoutReason, never()).setRejectReason(any());
    InOrder persisted = inOrder(dal);
    persisted.verify(dal).save(withReason);
    persisted.verify(dal).flush();
  }

  @Test
  public void acceptMarksTheQuotationAcceptedAndProcessed() {
    Order quotation = mock(Order.class);
    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);

      QuotationDecisions.accept(quotation);
    }

    verify(quotation).setDocumentStatus("ETGO_AC");
    verify(quotation).setProcessed(true);
    verify(quotation, never()).setRejectReason(any());
    verify(dal).save(quotation);
    verify(dal).flush();
  }
}
