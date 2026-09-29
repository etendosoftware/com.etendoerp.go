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

package com.etendoerp.go.schemaforge.email;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/** Alert delivery owns an independent settled transaction; no real provider is constructed. */
public class InternalAlertServiceTest {
  private static InternalAlertEvent event(InternalAlertEvent.Status status) {
    return new InternalAlertEvent("environment-provisioning", status, "attempt-1", "PRODUCTIVE",
        "client-1", "POOL", "committed", null);
  }
  private static InternalAlertConfig config(boolean enabled) {
    return new InternalAlertConfig(enabled, List.of("ops@example.test"),
        EnumSet.allOf(InternalAlertEvent.Status.class), "es_ES");
  }
  @Test public void disabledAndInvalidConfigurationNeverOpenTransactionOrSend() {
    TransactionalEmailService sender = mock(TransactionalEmailService.class);
    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class); MockedStatic<OBContext> context = mockStatic(OBContext.class)) {
      new InternalAlertService(sender, () -> config(false)).sendAfterTransaction(event(InternalAlertEvent.Status.OK));
      new InternalAlertService(sender, () -> { throw new IllegalArgumentException("malformed"); }).sendAfterTransaction(event(InternalAlertEvent.Status.ERROR));
      verifyNoInteractions(sender); dal.verifyNoInteractions(); context.verifyNoInteractions();
    }
  }
  @Test public void deliveryFailureRollsBackOnlyAlertTransactionAndRestoresContext() {
    TransactionalEmailService sender = mock(TransactionalEmailService.class);
    when(sender.send(eq(InternalAlertEmailContract.NAME), any())).thenThrow(new RuntimeException("provider down"));
    OBDal dal = mock(OBDal.class); OBContext previous = mock(OBContext.class);
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class); MockedStatic<OBContext> context = mockStatic(OBContext.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal); context.when(OBContext::getOBContext).thenReturn(previous);
      new InternalAlertService(sender, () -> config(true)).sendAfterTransaction(event(InternalAlertEvent.Status.ERROR));
      verify(dal).rollbackAndClose(); verify(dal, never()).commitAndClose();
      context.verify(OBContext::restorePreviousMode); context.verify(() -> OBContext.setOBContext(previous));
    }
  }
  @Test public void acceptedDeliveryFlushesAndCommitsIndependentAlertTransaction() {
    TransactionalEmailService sender = mock(TransactionalEmailService.class); OBDal dal = mock(OBDal.class);
    OBContext previous = mock(OBContext.class);
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class); MockedStatic<OBContext> context = mockStatic(OBContext.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal); context.when(OBContext::getOBContext).thenReturn(previous);
      new InternalAlertService(sender, () -> config(true)).sendAfterTransaction(event(InternalAlertEvent.Status.OK));
      verify(sender).send(eq(InternalAlertEmailContract.NAME), any()); verify(dal).flush(); verify(dal).commitAndClose();
      verify(dal, never()).rollbackAndClose(); context.verify(() -> OBContext.setOBContext(previous));
    }
  }
  @Test public void failedAlertRollbackIsContained() {
    TransactionalEmailService sender = mock(TransactionalEmailService.class); OBDal dal = mock(OBDal.class);
    when(sender.send(eq(InternalAlertEmailContract.NAME), any())).thenThrow(new RuntimeException("provider down"));
    doThrow(new RuntimeException("rollback down")).when(dal).rollbackAndClose();
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class); MockedStatic<OBContext> context = mockStatic(OBContext.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      new InternalAlertService(sender, () -> config(true)).sendAfterTransaction(event(InternalAlertEvent.Status.ERROR));
      verify(dal).rollbackAndClose(); context.verify(OBContext::restorePreviousMode);
    }
  }
}
