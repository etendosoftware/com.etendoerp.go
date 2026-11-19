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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.utility.RemittanceStatusUtil;

import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.go.schemaforge.util.NeoMessageTranslator;

/**
 * Unit tests for {@link RemittanceStatusAction} (ETP-6247): «Enviada al banco» ({@code markSent})
 * and «Cobrada» ({@code markCollected}) of the remittance header.
 * <ul>
 *   <li>the requests: not a POST is a 405, an unknown remittance a 404, the flag off a 403;</li>
 *   <li>{@code markSent}: only a processed remittance still PR becomes SB; every other state is a
 *       422 and nothing is written;</li>
 *   <li>{@code markCollected}: allowed from PR and SB only; settles through the hook seam, verifies
 *       the outcome (CT/PT), flags the remittance settled and answers {@code {status, settledOn}};
 *       a failure of the settlement, or an outcome that is not CT/PT, is a 422 with the
 *       transaction rolled back.</li>
 * </ul>
 * The REM status util, the settlement and the request-context scope are mocked seams; {@code
 * RemittanceProcessHookImplTest} (automated.remittance) specifies the settlement itself.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceStatusAction
 */
class RemittanceStatusActionTest {

  private static final String REM_ID = "REM-1";
  private static final String STATUS = RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS;

  private MockedStatic<RemittanceStatusAction> action;
  private MockedStatic<NeoProcessService> processService;
  private MockedStatic<NeoErrorResponses> errors;
  private MockedStatic<NeoMessageTranslator> translator;
  private MockedStatic<NeoLanguage> language;
  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBContext> contextStatic;

  private OBDal dal;
  private RemittanceHeaderHandler handler;
  private Remittance remittance;

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    contextStatic = mockStatic(OBContext.class);
    language = mockStatic(NeoLanguage.class);
    language.when(NeoLanguage::requestCode).thenReturn("en_US");
    translator = mockStatic(NeoMessageTranslator.class);
    translator.when(() -> NeoMessageTranslator.safeParseTranslation(any()))
        .thenAnswer(inv -> inv.getArgument(0));
    // Real, so the 403 of a disabled flag is built; only the rollback is a stub.
    errors = mockStatic(NeoErrorResponses.class, Mockito.CALLS_REAL_METHODS);
    errors.when(NeoErrorResponses::rollbackUnlessBatchOwned).then(inv -> null);
    processService = mockStatic(NeoProcessService.class);
    processService.when(NeoProcessService::pushRequestContextVars)
        .thenReturn(mock(NeoProcessService.RequestContextScope.class));
    action = mockStatic(RemittanceStatusAction.class, Mockito.CALLS_REAL_METHODS);
    action.when(() -> RemittanceStatusAction.refresh(any())).then(inv -> null);
    action.when(() -> RemittanceStatusAction.settle(any())).then(inv -> null);

    remittance = remittance(true, RemittanceStatusUtil.PROCESSED);
    handler = spy(new RemittanceHeaderHandler());
    doReturn(true).when(handler).isFlagEnabled();
    doReturn(remittance).when(handler).loadRemittance(REM_ID);
  }

  @AfterEach
  void tearDown() {
    action.close();
    processService.close();
    errors.close();
    translator.close();
    language.close();
    contextStatic.close();
    dalStatic.close();
    Mockito.framework().clearInlineMocks();
  }

  // ---------------------------------------------------------------- requests

  @ParameterizedTest
  @ValueSource(strings = { "markSent", "markCollected" })
  void aGetIsA405(String name) throws Exception {
    assertEquals(405, handler.handle(context("GET", name)).getHttpStatus());
    verify(handler, never()).loadRemittance(any());
  }

  @ParameterizedTest
  @ValueSource(strings = { "markSent", "markCollected" })
  void anUnknownRemittanceIsA404(String name) throws Exception {
    doReturn(null).when(handler).loadRemittance(REM_ID);
    assertEquals(404, handler.handle(context("POST", name)).getHttpStatus());
  }

  @ParameterizedTest
  @ValueSource(strings = { "markSent", "markCollected" })
  void bothAnswer403WhileTheFlagIsOff(String name) throws Exception {
    doReturn(false).when(handler).isFlagEnabled();
    assertEquals(403, handler.handle(context("POST", name)).getHttpStatus());
  }

  @Test
  void theActionsAreDeclaredAndServed() {
    assertTrue(handler.actionContracts().get("markSent").isMutating());
    assertTrue(handler.actionContracts().get("markCollected").isMutating());
    assertTrue(handler.actionContracts().get("markSent").getParams().isEmpty());
    assertTrue(RemittanceStatusAction.serves("markSent"));
    assertTrue(RemittanceStatusAction.serves("markCollected"));
    assertFalse(RemittanceStatusAction.serves("confirm"));
  }

  // ---------------------------------------------------------------- markSent

  @Test
  void aProcessedRemittanceIsMarkedSent() throws Exception {
    action.when(() -> RemittanceStatusAction.markSentToBank(remittance)).thenReturn(true);

    NeoResponse response = handler.handle(context("POST", "markSent"));

    assertEquals(200, response.getHttpStatus());
    assertEquals("SB", response.getBody().getString("status"));
  }

  @Test
  void anUnprocessedRemittanceCannotBeMarkedSent() throws Exception {
    remittance = remittance(false, "PE");
    doReturn(remittance).when(handler).loadRemittance(REM_ID);

    NeoResponse response = handler.handle(context("POST", "markSent"));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceStatusAction.MSG_SENT_NOT_ALLOWED, messageKey(response));
    action.verify(() -> RemittanceStatusAction.markSentToBank(any()), never());
  }

  @Test
  void aRemittanceNotInPrIsRefusedAndNothingIsWritten() throws Exception {
    action.when(() -> RemittanceStatusAction.markSentToBank(remittance)).thenReturn(false);

    NeoResponse response = handler.handle(context("POST", "markSent"));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceStatusAction.MSG_SENT_NOT_ALLOWED, messageKey(response));
  }

  // ---------------------------------------------------------------- markCollected

  @ParameterizedTest
  @ValueSource(strings = { "PR", "SB" })
  void aProcessedOrSentRemittanceIsSettledOnItsDueDate(String from) throws Exception {
    Date due = new Date(1_700_000_000_000L);
    remittance = remittance(true, from);
    when(remittance.getDueDate()).thenReturn(due);
    doReturn(remittance).when(handler).loadRemittance(REM_ID);
    Remittance settled = remittance(true, "CT");
    when(dal.get(Remittance.class, REM_ID)).thenReturn(settled);

    NeoResponse response = handler.handle(context("POST", "markCollected"));

    assertEquals(200, response.getHttpStatus(), String.valueOf(response.getBody()));
    assertEquals("CT", response.getBody().getString("status"));
    assertEquals(new java.text.SimpleDateFormat("yyyy-MM-dd").format(due),
        response.getBody().getString("settledOn"));
    action.verify(() -> RemittanceStatusAction.settle(remittance));
    verify(settled).setETARAutomaticprocess(true);
    verify(dal).save(settled);
    errors.verify(NeoErrorResponses::rollbackUnlessBatchOwned, never());
  }

  @ParameterizedTest
  @ValueSource(strings = { "PE", "CT", "PT", "CP", "PP", "AN" })
  void anyOtherStatusCannotBeMarkedCollected(String from) throws Exception {
    remittance = remittance(true, from);
    doReturn(remittance).when(handler).loadRemittance(REM_ID);

    NeoResponse response = handler.handle(context("POST", "markCollected"));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceStatusAction.MSG_COLLECTED_NOT_ALLOWED, messageKey(response));
    action.verify(() -> RemittanceStatusAction.settle(any()), never());
  }

  @Test
  void anUnprocessedRemittanceCannotBeMarkedCollected() throws Exception {
    remittance = remittance(false, "PE");
    doReturn(remittance).when(handler).loadRemittance(REM_ID);

    NeoResponse response = handler.handle(context("POST", "markCollected"));

    assertEquals(RemittanceStatusAction.MSG_COLLECTED_NOT_ALLOWED, messageKey(response));
    action.verify(() -> RemittanceStatusAction.settle(any()), never());
  }

  @Test
  void aFailedSettlementIsA422AndRollsEverythingBack() throws Exception {
    action.when(() -> RemittanceStatusAction.settle(remittance))
        .thenThrow(new IllegalStateException("REM_TransactionExists"));

    NeoResponse response = handler.handle(context("POST", "markCollected"));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceStatusAction.MSG_COLLECTED_FAILED, messageKey(response));
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("REM_TransactionExists"));
    errors.verify(NeoErrorResponses::rollbackUnlessBatchOwned);
  }

  /** The module swallows some of its own errors: an outcome that is not CT/PT is a failure. */
  @Test
  void anOutcomeThatIsNotFullySettledIsA422AndRollsBack() throws Exception {
    Remittance partial = remittance(true, "CP");
    when(dal.get(Remittance.class, REM_ID)).thenReturn(partial);

    NeoResponse response = handler.handle(context("POST", "markCollected"));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceStatusAction.MSG_COLLECTED_FAILED, messageKey(response));
    verify(partial, never()).setETARAutomaticprocess(true);
    errors.verify(NeoErrorResponses::rollbackUnlessBatchOwned);
  }

  // ---------------------------------------------------------------- helpers

  private static Remittance remittance(boolean processed, String status) {
    Remittance mock = mock(Remittance.class);
    when(mock.getId()).thenReturn(REM_ID);
    when(mock.isProcessed()).thenReturn(processed);
    when(mock.get(STATUS)).thenReturn(status);
    return mock;
  }

  private static NeoContext context(String method, String name) {
    return NeoContext.builder().specName("remittance").entityName("remittance")
        .httpMethod(method).endpointType(NeoEndpointType.ACTION).fieldName(name)
        .recordId(REM_ID).requestBody(new JSONObject()).build();
  }

  private static String messageKey(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getJSONArray("messageKeys").getString(0);
  }
}
