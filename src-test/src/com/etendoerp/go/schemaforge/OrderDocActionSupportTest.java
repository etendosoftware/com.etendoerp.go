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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.process.ProcessInstance;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.order.Order;
import org.openbravo.service.db.CallProcess;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * ETP-5528 — unit tests for {@link OrderDocActionSupport#runDocAction}, the server-side
 * {@code C_Order_Post} call the quotation conversion uses to leave the new order in Draft.
 */
class OrderDocActionSupportTest {

  private static final String C_ORDER_POST = "104";

  private MockedStatic<NeoAccessHelper> accessMock;
  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<CallProcess> callProcessMock;

  private OBDal dal;
  private CallProcess callProcess;
  private Process process;
  private ProcessInstance pInstance;
  private Order order;

  @BeforeEach
  void setUp() {
    accessMock = mockStatic(NeoAccessHelper.class);
    obDalMock = mockStatic(OBDal.class);
    callProcessMock = mockStatic(CallProcess.class);

    dal = mock(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    when(dal.getSession()).thenReturn(mock(Session.class));
    process = mock(Process.class);
    when(dal.get(Process.class, C_ORDER_POST)).thenReturn(process);

    callProcess = mock(CallProcess.class);
    callProcessMock.when(CallProcess::getInstance).thenReturn(callProcess);
    pInstance = mock(ProcessInstance.class);
    when(callProcess.call(eq(process), anyString(), isNull())).thenReturn(pInstance);

    order = mock(Order.class);
    when(order.getId()).thenReturn("order-5528");
  }

  @AfterEach
  void tearDown() {
    callProcessMock.close();
    obDalMock.close();
    accessMock.close();
  }

  private void grantAccess(boolean granted) {
    accessMock.when(() -> NeoAccessHelper.hasProcessAccess(C_ORDER_POST)).thenReturn(granted);
  }

  @Test
  @DisplayName("no access to C_Order_Post: false, and the procedure is never called")
  void noProcessAccessNeverCallsProcedure() throws Exception {
    grantAccess(false);

    assertFalse(OrderDocActionSupport.runDocAction(order, "RE"));

    verify(order, never()).setDocumentAction(anyString());
    verify(callProcess, never()).call(any(Process.class), anyString(), any());
    verify(dal, never()).flush();
  }

  @ParameterizedTest(name = "result {0}")
  @NullSource
  @ValueSource(longs = {0L})
  @DisplayName("a procedure result other than 1 is reported as false, not thrown")
  void failedResultReturnsFalse(Long result) throws Exception {
    grantAccess(true);
    when(pInstance.getResult()).thenReturn(result);

    assertFalse(OrderDocActionSupport.runDocAction(order, "RE"));
  }

  @Test
  @DisplayName("success: DocAction set to RE and flushed before the procedure runs, then true")
  void successSetsDocActionBeforeCallingProcedure() throws Exception {
    grantAccess(true);
    when(pInstance.getResult()).thenReturn(1L);

    assertTrue(OrderDocActionSupport.runDocAction(order, OrderDocActionSupport.DOC_ACTION_REACTIVATE));

    InOrder inOrder = inOrder(order, dal, callProcess);
    inOrder.verify(order).setDocumentAction("RE");
    inOrder.verify(dal).flush();
    inOrder.verify(callProcess).call(process, "order-5528", null);
    inOrder.verify(dal).refresh(order);
  }
}
