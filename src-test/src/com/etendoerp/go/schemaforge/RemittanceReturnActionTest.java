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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceLine;
import org.openbravo.module.remittance.RemittanceLineCancel;
import org.openbravo.module.remittance.utility.RemittanceStatusUtil;

import com.etendoerp.go.schemaforge.RemittanceReturnState.LineState;
import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.go.schemaforge.util.NeoMessageTranslator;
import com.etendoerp.payment.removal.util.TransactionRemovalUtil;

/**
 * Unit tests for {@link RemittanceReturnAction}, the two return actions of the remittance header
 * (ETP-6247), and for {@link RemittanceReturnProcess#getPaymentMethod()}:
 * <ul>
 *   <li>the requests: {@code returnLines} that is not a POST is a 405, an unknown remittance a 404,
 *       the flag off a 403, a call without {@code lineIds} the contract's 422;</li>
 *   <li>{@code returnState}: the body shape;</li>
 *   <li>the date: invalid and future dates are refused, the default is today;</li>
 *   <li>the refusals: an unknown line, every reason code as a problem of the list, a payment shared
 *       with lines the request did not name (accepted when all of them are named);</li>
 *   <li>the run: the order of the steps, what the module is given, the answer; a thrown exception,
 *       a module that swallowed its error (payment not void, settlement row still there, no return
 *       row, no settlement row to begin with) is each a 422 with the transaction rolled back;</li>
 *   <li>concurrency: {@code returnLines} locks the remittance row BEFORE reading the line states,
 *       so two concurrent returns cannot both pass the check; {@code returnState} (a read) does
 *       not lock;</li>
 *   <li>a request whose line ids are all blank is a 422 before anything is read;</li>
 *   <li>the reason keys and their catalog entries.</li>
 * </ul>
 *
 * <p>The state, the provisioning, the bank transaction removal and the remittance module's return
 * are seams mocked statically or by construction; the action logic runs for real. {@code
 * RemittanceReturnStateTest} specifies the state itself and {@code
 * RemittanceReturnIntegrationTest} the real module run.
 *
 * <p>A class of its own: {@code RemittanceReturnAction} is a new production class; {@code
 * RemittanceHeaderHandlerTest} pins only the action list.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceReturnAction
 * @covers com.etendoerp.go.schemaforge.RemittanceReturnProcess
 */
class RemittanceReturnActionTest {

  private static final String REM_ID = "REM-1";
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBContext> contextStatic;
  private MockedStatic<NeoLanguage> language;
  private MockedStatic<NeoMessageTranslator> translator;
  private MockedStatic<NeoErrorResponses> errors;
  private MockedStatic<RemittanceReturnState> state;
  private MockedStatic<RemittanceFileReadiness> readiness;
  private MockedStatic<RemittanceSepaProvisioning> provisioning;
  private MockedStatic<TransactionRemovalUtil> removal;
  private MockedConstruction<RemittanceReturnProcess> module;

  private OBDal dal;
  private RemittanceHeaderHandler handler;
  private Remittance remittance;
  private FIN_PaymentMethod returnMethod;
  private FIN_Payment negative;
  /** What the module's return does when called; {@code null} to succeed. */
  private Throwable moduleFailure;

  private final Map<String, LineState> states = new LinkedHashMap<>();
  private final Map<String, FIN_Payment> payments = new LinkedHashMap<>();
  private final List<RemittanceLine> activeLines = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    dal = mock(OBDal.class);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    contextStatic = mockStatic(OBContext.class);
    language = mockStatic(NeoLanguage.class);
    language.when(NeoLanguage::requestCode).thenReturn("en_US");
    translator = mockStatic(NeoMessageTranslator.class);
    translator.when(() -> NeoMessageTranslator.safeParseTranslation(any()))
        .thenAnswer(inv -> inv.getArgument(0));
    errors = mockStatic(NeoErrorResponses.class, Mockito.CALLS_REAL_METHODS);
    errors.when(NeoErrorResponses::rollbackUnlessBatchOwned).then(inv -> null);
    state = mockStatic(RemittanceReturnState.class);
    readiness = mockStatic(RemittanceFileReadiness.class);
    provisioning = mockStatic(RemittanceSepaProvisioning.class);
    removal = mockStatic(TransactionRemovalUtil.class);

    Client client = mock(Client.class);
    remittance = mock(Remittance.class);
    when(remittance.getId()).thenReturn(REM_ID);
    when(remittance.getClient()).thenReturn(client);
    when(remittance.get(RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS)).thenReturn("CP");
    when(dal.get(Remittance.class, REM_ID)).thenReturn(remittance);

    returnMethod = mock(FIN_PaymentMethod.class);
    when(returnMethod.getId()).thenReturn("RM-1");
    when(dal.get(FIN_PaymentMethod.class, "RM-1")).thenReturn(returnMethod);
    provisioning.when(() -> RemittanceSepaProvisioning.ensureReturnPaymentMethod(client))
        .thenReturn(returnMethod);

    negative = mock(FIN_Payment.class);
    when(negative.getId()).thenReturn("NEG-1");
    when(negative.getDocumentNo()).thenReturn("1000099");

    state.when(() -> RemittanceReturnState.of(remittance)).thenReturn(states);
    state.when(() -> RemittanceReturnState.isCollection(remittance)).thenReturn(true);
    state.when(() -> RemittanceReturnState.hasReturns(remittance)).thenReturn(true);
    readiness.when(() -> RemittanceFileReadiness.activeLines(remittance)).thenReturn(activeLines);

    module = mockConstruction(RemittanceReturnProcess.class, (process, ctx) -> when(
        process.returnPayments(any(), anyString(), any(), any())).thenAnswer(inv -> {
          if (moduleFailure != null) {
            throw moduleFailure;
          }
          return negative;
        }));

    handler = spy(new RemittanceHeaderHandler());
    doReturn(true).when(handler).isFlagEnabled();
    doReturn(remittance).when(handler).loadRemittance(REM_ID);
    doReturn(TODAY).when(handler).today();
    doNothing().when(handler).lockRemittance(any());
  }

  @AfterEach
  void tearDown() {
    if (module != null) {
      module.close();
    }
    removal.close();
    provisioning.close();
    readiness.close();
    state.close();
    errors.close();
    translator.close();
    language.close();
    contextStatic.close();
    dalStatic.close();
    Mockito.framework().clearInlineMocks();
  }

  // ---------------------------------------------------------------- requests

  @Test
  void returnLinesThatIsNotAPostIsA405() throws Exception {
    NeoResponse response = handler.handle(context("GET", "returnLines", new JSONObject()));

    assertEquals(405, response.getHttpStatus());
    verify(handler, never()).loadRemittance(any());
  }

  @ParameterizedTest
  @ValueSource(strings = { "returnState", "returnLines" })
  void anUnknownRemittanceIsA404(String name) throws Exception {
    doReturn(null).when(handler).loadRemittance(REM_ID);

    NeoResponse response = handler.handle(context("POST", name, bodyFor(name)));

    assertEquals(404, response.getHttpStatus());
  }

  @ParameterizedTest
  @ValueSource(strings = { "returnState", "returnLines" })
  void everyActionAnswers403WhileTheFlagIsOff(String name) throws Exception {
    doReturn(false).when(handler).isFlagEnabled();

    NeoResponse response = handler.handle(context("POST", name, bodyFor(name)));

    assertEquals(403, response.getHttpStatus());
    verify(handler, never()).loadRemittance(any());
  }

  @Test
  void aCallWithoutLineIdsIsRefusedByTheContract() throws Exception {
    NeoResponse response = handler.handle(context("POST", "returnLines",
        new JSONObject().put("fieldValues", new JSONObject())));

    assertEquals(422, response.getHttpStatus());
    verify(handler, never()).loadRemittance(any());
    removal.verifyNoInteractions();
  }

  @Test
  void aFailureWhileLoadingIsA500WithTheTransactionRolledBack() throws Exception {
    doThrow(new IllegalStateException("boom")).when(handler).loadRemittance(REM_ID);

    NeoResponse response = handler.handle(context("POST", "returnState", new JSONObject()));

    assertEquals(500, response.getHttpStatus());
    errors.verify(NeoErrorResponses::rollbackUnlessBatchOwned);
  }

  @Test
  void theActionsServedAreExactlyTheTwoReturnOnes() {
    assertTrue(RemittanceReturnAction.serves("returnState"));
    assertTrue(RemittanceReturnAction.serves("returnLines"));
    assertFalse(RemittanceReturnAction.serves("confirm"));
    assertFalse(RemittanceReturnAction.serves(null));
  }

  /** B2: the row lock comes before the states are read, so a concurrent return waits and re-reads. */
  @Test
  void returnLinesLocksTheRemittanceBeforeReadingTheLineStates() throws Exception {
    settled("L1", "P1");
    boolean[] locked = { false };
    doAnswer(inv -> {
      // An AssertionError is not caught by the action: it fails the test from here.
      state.verify(() -> RemittanceReturnState.of(any()), never());
      locked[0] = true;
      return null;
    }).when(handler).lockRemittance(remittance);

    assertEquals(200, returnLines(null, "L1").getHttpStatus());

    assertTrue(locked[0], "the remittance was locked");
    InOrder order = inOrder(handler);
    order.verify(handler).loadRemittance(REM_ID);
    order.verify(handler).lockRemittance(remittance);
    state.verify(() -> RemittanceReturnState.of(remittance));
  }

  @Test
  void aLockThatFailsIsA500AndNothingIsReturned() throws Exception {
    settled("L1", "P1");
    doThrow(new IllegalStateException("lock timeout")).when(handler).lockRemittance(remittance);

    assertEquals(500, returnLines(null, "L1").getHttpStatus());

    state.verify(() -> RemittanceReturnState.of(any()), never());
    removal.verifyNoInteractions();
    assertTrue(module.constructed().isEmpty());
    errors.verify(NeoErrorResponses::rollbackUnlessBatchOwned);
  }

  @Test
  void returnStateIsAReadAndDoesNotLock() throws Exception {
    handler.handle(context("POST", "returnState", new JSONObject()));

    verify(handler, never()).lockRemittance(any());
  }

  /**
   * Nit: blank line ids are nothing to return. Through the action the contract already refuses
   * them (an array of non-blank strings); a direct caller of returnLines gets the explicit 422.
   * Neither reads a state nor touches anything.
   */
  @Test
  void lineIdsThatAreAllBlankAreA422BeforeAnythingIsRead() throws Exception {
    NeoResponse viaAction = returnLines(null, " ", "");
    NeoResponse direct = RemittanceReturnAction.returnLines(handler, remittance,
        new JSONObject().put("lineIds", new JSONArray(List.of(" ", ""))));

    assertEquals(422, viaAction.getHttpStatus());
    assertEquals(422, direct.getHttpStatus());
    assertEquals(RemittanceReturnAction.MSG_NO_LINES, messageKey(direct));
    state.verify(() -> RemittanceReturnState.of(any()), never());
    removal.verifyNoInteractions();
    assertTrue(module.constructed().isEmpty());
  }

  // ---------------------------------------------------------------- returnState

  @Test
  void returnStateAnswersTheCollectionTheReturnsAndEveryLine() throws Exception {
    states.put("L1", new LineState("L1", "P1", RemittanceReturnState.SETTLED, null, null,
        List.of("L1", "L2")));
    states.put("L2", new LineState("L2", null, RemittanceReturnState.RETURNED,
        RemittanceReturnState.ALREADY_RETURNED, "2026-11-09", List.of("L2")));
    states.put("L3", new LineState("L3", "P3", RemittanceReturnState.SETTLED,
        RemittanceReturnState.POSTED, null, List.of("L3")));

    NeoResponse response = handler.handle(context("GET", "returnState", null));

    assertEquals(200, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertTrue(body.getBoolean("collection"));
    assertTrue(body.getBoolean("hasReturns"));
    JSONObject lines = body.getJSONObject("lines");
    assertEquals(3, lines.length());

    JSONObject returnable = lines.getJSONObject("L1");
    assertEquals("SETTLED", returnable.getString("state"));
    assertTrue(returnable.getBoolean("returnable"));
    assertTrue(returnable.isNull("reasonCode"));
    assertTrue(returnable.isNull("returnDate"));
    assertEquals("P1", returnable.getString("paymentId"));
    assertEquals("[\"L1\",\"L2\"]", returnable.getJSONArray("paymentLineIds").toString());

    JSONObject returned = lines.getJSONObject("L2");
    assertEquals("RETURNED", returned.getString("state"));
    assertFalse(returned.getBoolean("returnable"));
    assertEquals("ALREADY_RETURNED", returned.getString("reasonCode"));
    assertEquals("2026-11-09", returned.getString("returnDate"));
    assertTrue(returned.isNull("paymentId"));

    assertEquals("POSTED", lines.getJSONObject("L3").getString("reasonCode"));
  }

  @Test
  void returnStateOfAPaymentRemittanceWithoutReturnsSaysSo() throws Exception {
    state.when(() -> RemittanceReturnState.isCollection(remittance)).thenReturn(false);
    state.when(() -> RemittanceReturnState.hasReturns(remittance)).thenReturn(false);

    JSONObject body = handler.handle(context("GET", "returnState", null)).getBody();

    assertFalse(body.getBoolean("collection"));
    assertFalse(body.getBoolean("hasReturns"));
    assertEquals(0, body.getJSONObject("lines").length());
  }

  // ---------------------------------------------------------------- the date

  @ParameterizedTest
  @ValueSource(strings = { "2026-13-45", "2026-02-30", "2026-11-17", "2027-01-01" })
  void anImpossibleOrFutureReturnDateIsRefusedBeforeAnythingIsTouched(String date)
      throws Exception {
    settled("L1", "P1");

    NeoResponse response = returnLines(date, "L1");

    assertEquals(422, response.getHttpStatus());
    assertEquals("ETGO_RemittanceReturnDateInvalid", messageKey(response));
    removal.verifyNoInteractions();
    assertEquals(0, module.constructed().size());
  }

  @ParameterizedTest
  @ValueSource(strings = { "not-a-date", "16/11/2026" })
  void aReturnDateThatIsNotYyyyMmDdIsRefusedByTheContract(String date) throws Exception {
    settled("L1", "P1");

    NeoResponse response = returnLines(date, "L1");

    assertEquals(422, response.getHttpStatus());
    verify(handler, never()).loadRemittance(any());
    removal.verifyNoInteractions();
  }

  @Test
  void todayIsAnAcceptedReturnDate() throws Exception {
    settled("L1", "P1");

    NeoResponse response = returnLines("2026-11-16", "L1");

    assertEquals(200, response.getHttpStatus());
    assertEquals("2026-11-16", dateGivenToTheModule());
  }

  @Test
  void thePastIsAnAcceptedReturnDate() throws Exception {
    settled("L1", "P1");

    assertEquals(200, returnLines("2026-10-01", "L1").getHttpStatus());
    assertEquals("2026-10-01", dateGivenToTheModule());
  }

  @Test
  void withoutADateTheReturnDateIsToday() throws Exception {
    settled("L1", "P1");

    assertEquals(200, returnLines(null, "L1").getHttpStatus());
    assertEquals("2026-11-16", dateGivenToTheModule());
  }

  // ---------------------------------------------------------------- refusals

  @Test
  void aLineThatIsNotOfTheRemittanceIsRefusedByItsId() throws Exception {
    settled("L1", "P1");

    NeoResponse response = returnLines(null, "L1", "OTHER");

    assertEquals(422, response.getHttpStatus());
    assertEquals("ETGO_RemittanceReturnLineNotFound", messageKey(response));
    assertTrue(response.getBody().getJSONObject("error").getString("message").contains("OTHER"));
    removal.verifyNoInteractions();
  }

  @ParameterizedTest
  @ValueSource(strings = { "NOT_PROCESSED", "NOT_SETTLED", "ALREADY_RETURNED",
      "NOT_A_COLLECTION", "POSTED", "RECONCILED" })
  void aLineThatCannotBeReturnedIsAProblemWithItsReason(String reason) throws Exception {
    states.put("L1", new LineState("L1", "P1", RemittanceReturnState.SETTLED, reason, null,
        List.of("L1")));
    activeLines.add(line("L1", "FV-100"));

    NeoResponse response = returnLines(null, "L1");

    assertEquals(422, response.getHttpStatus());
    assertEquals("ETGO_RemittanceReturnNotAllowed", messageKey(response));
    JSONArray problems = response.getBody().getJSONObject("error").getJSONArray("problems");
    assertEquals(1, problems.length());
    JSONObject problem = problems.getJSONObject(0);
    assertEquals("L1", problem.getString("lineId"));
    assertEquals(reason, problem.getString("reasonCode"));
    assertEquals("FV-100", problem.getString("documentNo"));
    removal.verifyNoInteractions();
    assertEquals(0, module.constructed().size());
  }

  @Test
  void theProblemMessageNamesTheInvoiceAndIsInTheRequestLanguage() throws Exception {
    states.put("L1", new LineState("L1", "P1", RemittanceReturnState.SETTLED,
        RemittanceReturnState.POSTED, null, List.of("L1")));
    activeLines.add(line("L1", "FV-100"));

    JSONObject problem = returnLines(null, "L1").getBody().getJSONObject("error")
        .getJSONArray("problems").getJSONObject(0);

    assertEquals("Invoice FV-100: its settlement or its bank transaction is already posted to "
        + "accounting. Undo the posting first.", problem.getString("message"));
  }

  @Test
  void everyLineThatCannotBeReturnedIsListedAndTheReturnableOnesAreNot() throws Exception {
    settled("L1", "P1");
    states.put("L2", new LineState("L2", "P2", RemittanceReturnState.SETTLED,
        RemittanceReturnState.RECONCILED, null, List.of("L2")));
    states.put("L3", new LineState("L3", null, RemittanceReturnState.PROCESSED,
        RemittanceReturnState.NOT_SETTLED, null, List.of("L3")));
    activeLines.add(line("L1", "FV-1"));
    activeLines.add(line("L2", "FV-2"));
    activeLines.add(line("L3", null));

    NeoResponse response = returnLines(null, "L1", "L2", "L3");

    JSONArray problems = response.getBody().getJSONObject("error").getJSONArray("problems");
    assertEquals(2, problems.length());
    assertEquals("L2", problems.getJSONObject(0).getString("lineId"));
    assertEquals("RECONCILED", problems.getJSONObject(0).getString("reasonCode"));
    assertEquals("L3", problems.getJSONObject(1).getString("lineId"));
    assertEquals("NOT_SETTLED", problems.getJSONObject(1).getString("reasonCode"));
    assertTrue(problems.getJSONObject(1).isNull("documentNo"), "a line without an invoice");
    assertTrue(response.getBody().getJSONObject("error").getString("message").contains("2"));
  }

  @Test
  void aSharedPaymentIsRefusedWhenOnlySomeOfItsLinesAreSent() throws Exception {
    shared("P1", "L1", "L2");
    activeLines.add(line("L1", "FV-1"));
    activeLines.add(line("L2", "FV-2"));

    NeoResponse response = returnLines(null, "L1");

    assertEquals(422, response.getHttpStatus());
    assertEquals("ETGO_RemittanceReturnNotAllowed", messageKey(response));
    JSONArray problems = response.getBody().getJSONObject("error").getJSONArray("problems");
    assertEquals(1, problems.length());
    assertEquals("SHARED_PAYMENT", problems.getJSONObject(0).getString("reasonCode"));
    assertEquals("L1", problems.getJSONObject(0).getString("lineId"));
    removal.verifyNoInteractions();
  }

  @Test
  void aSharedPaymentIsAcceptedWhenAllItsLinesAreSent() throws Exception {
    shared("P1", "L1", "L2");

    NeoResponse response = returnLines(null, "L1", "L2");

    assertEquals(200, response.getHttpStatus());
    assertEquals("[\"L1\",\"L2\"]", response.getBody().getJSONArray("returned").toString());
    JSONArray cancelIds = cancelIdsGivenToTheModule();
    assertEquals(1, cancelIds.length(), "one settlement row for the one shared payment");
  }

  // ---------------------------------------------------------------- the run

  @Test
  void aReturnRunsTheStepsInOrderAndAnswersTheNegativePayment() throws Exception {
    settled("L1", "P1");
    settled("L2", "P2");
    FIN_FinaccTransaction t1 = transaction("T1");
    FIN_FinaccTransaction t2 = transaction("T2");
    when(payments.get("P1").getFINFinaccTransactionList()).thenReturn(List.of(t1));
    when(payments.get("P2").getFINFinaccTransactionList()).thenReturn(List.of(t2));

    NeoResponse response = returnLines("2026-11-10", "L1", "L2");

    assertEquals(200, response.getHttpStatus(), String.valueOf(response.getBody()));
    JSONObject body = response.getBody();
    assertEquals("CP", body.getString("status"));
    assertEquals("[\"L1\",\"L2\"]", body.getJSONArray("returned").toString());
    assertEquals("NEG-1", body.getString("returnPaymentId"));
    assertEquals("1000099", body.getString("returnPaymentDocumentNo"));

    provisioning.verify(() -> RemittanceSepaProvisioning.ensureReturnPaymentMethod(
        remittance.getClient()));
    verify(dal, org.mockito.Mockito.times(2)).flush(); // before the removal, after the module
    removal.verify(() -> TransactionRemovalUtil.reactivateAndRemove("T1"));
    removal.verify(() -> TransactionRemovalUtil.reactivateAndRemove("T2"));
    JSONArray cancelIds = cancelIdsGivenToTheModule();
    assertEquals("[\"C-P1\",\"C-P2\"]", cancelIds.toString());
    RemittanceReturnProcess process = module.constructed().get(0);
    verify(process).returnPayments(any(JSONArray.class), eq("2026-11-10"), eq(remittance),
        eq(payments.get("P1")));
    errors.verify(NeoErrorResponses::rollbackUnlessBatchOwned, never());
  }

  @Test
  void aStatusThatIsNullIsAnsweredAsNull() throws Exception {
    settled("L1", "P1");
    when(remittance.get(RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS)).thenReturn(null);
    when(negative.getDocumentNo()).thenReturn(null);

    JSONObject body = returnLines(null, "L1").getBody();

    assertTrue(body.isNull("status"));
    assertTrue(body.isNull("returnPaymentDocumentNo"));
  }

  @Test
  void aPaymentWithNoBankTransactionIsReturnedWithoutRemovingAny() throws Exception {
    settled("L1", "P1");

    assertEquals(200, returnLines(null, "L1").getHttpStatus());
    removal.verifyNoInteractions();
  }

  // ---------------------------------------------------------------- failures

  @Test
  void aFailureRemovingTheBankTransactionRollsEverythingBack() throws Exception {
    settled("L1", "P1");
    FIN_FinaccTransaction t1 = transaction("T1");
    when(payments.get("P1").getFINFinaccTransactionList()).thenReturn(List.of(t1));
    removal.when(() -> TransactionRemovalUtil.reactivateAndRemove("T1"))
        .thenThrow(new IllegalStateException("cannot reactivate"));

    NeoResponse response = returnLines(null, "L1");

    assertFailed(response, "cannot reactivate");
    assertEquals(0, module.constructed().size(), "the module's return never ran");
  }

  @Test
  void aFailureInTheModulesReturnRollsEverythingBack() throws Exception {
    settled("L1", "P1");
    moduleFailure = new IllegalStateException("module said no");

    assertFailed(returnLines(null, "L1"), "module said no");
  }

  @Test
  void aMissingSettlementRowRollsEverythingBack() throws Exception {
    settled("L1", "P1");
    state.when(() -> RemittanceReturnState.settlementRows(remittance))
        .thenReturn(new LinkedHashMap<>());

    assertFailed(returnLines(null, "L1"), "No settlement row for payment P1");
    assertEquals(0, module.constructed().size());
  }

  @Test
  void aPaymentThatIsNotVoidAfterTheRunRollsEverythingBack() throws Exception {
    settled("L1", "P1");
    when(payments.get("P1").getStatus()).thenReturn("RPR");

    assertFailed(returnLines(null, "L1"), "did not complete");
  }

  @Test
  void aSettlementRowThatSurvivesTheRunRollsEverythingBack() throws Exception {
    settled("L1", "P1");
    RemittanceLineCancel cancel = cancel("P1");
    state.when(() -> RemittanceReturnState.settlementRows(remittance))
        .thenReturn(Map.of("P1", cancel), Map.of("P1", cancel));

    assertFailed(returnLines(null, "L1"), "did not complete");
  }

  @Test
  void aRunThatWroteNoReturnRowsRollsEverythingBack() throws Exception {
    settled("L1", "P1");
    state.when(() -> RemittanceReturnState.hasReturns(remittance)).thenReturn(false);

    assertFailed(returnLines(null, "L1"), "The return rows were not written");
  }

  // ---------------------------------------------------------------- the provisioning seam

  @Test
  void theModuleIsGivenTheReturnMethodOfTheClient() {
    module.close();
    module = null;
    FIN_PaymentMethod method = mock(FIN_PaymentMethod.class);

    RemittanceReturnProcess process = new RemittanceReturnProcess(method);

    assertSame(method, process.getPaymentMethod());
  }

  // ---------------------------------------------------------------- reason keys

  @ParameterizedTest
  @ValueSource(strings = { "NOT_PROCESSED:NotProcessed", "NOT_SETTLED:NotSettled",
      "ALREADY_RETURNED:AlreadyReturned", "NOT_A_COLLECTION:NotACollection", "POSTED:Posted",
      "RECONCILED:Reconciled", "SHARED_PAYMENT:SharedPayment" })
  void everyReasonCodeHasItsCatalogKeyInBothLanguages(String pair) {
    String[] parts = pair.split(":");

    String key = RemittanceReturnAction.reasonKey(parts[0]);

    assertEquals("ETGO_RemittanceReturnReason" + parts[1], key);
    for (String lang : new String[] { "en", "es" }) {
      ResourceBundle bundle = ResourceBundle.getBundle(
          "com.etendoerp.go.schemaforge.messages.neo_hints", new java.util.Locale(lang));
      assertTrue(bundle.containsKey(key), key + " missing in " + lang);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = { "ETGO_RemittanceReturnNotAllowed", "ETGO_RemittanceReturnLineNotFound",
      "ETGO_RemittanceReturnDateInvalid", "ETGO_RemittanceReturnFailed",
      "ETGO_RemittanceFileHasReturns", "ETGO_RemittanceReturnNoLines" })
  void everyRefusalKeyIsInTheCatalogInBothLanguages(String key) {
    for (String lang : new String[] { "en", "es" }) {
      ResourceBundle bundle = ResourceBundle.getBundle(
          "com.etendoerp.go.schemaforge.messages.neo_hints", new java.util.Locale(lang));
      assertTrue(bundle.containsKey(key), key + " missing in " + lang);
    }
  }

  @Test
  void theReasonCodesTheStateEmitsAreTheOnesTheCatalogKnows() {
    for (String code : new String[] { RemittanceReturnState.NOT_PROCESSED,
        RemittanceReturnState.NOT_SETTLED, RemittanceReturnState.ALREADY_RETURNED,
        RemittanceReturnState.NOT_A_COLLECTION, RemittanceReturnState.POSTED,
        RemittanceReturnState.RECONCILED, RemittanceReturnAction.SHARED_PAYMENT }) {
      String key = RemittanceReturnAction.reasonKey(code);
      assertTrue(ResourceBundle.getBundle("com.etendoerp.go.schemaforge.messages.neo_hints",
          java.util.Locale.ENGLISH).containsKey(key), key);
    }
  }

  // ---------------------------------------------------------------- helpers

  /** A returnable settled line with its own payment, its settlement row and its mocks. */
  private void settled(String lineId, String paymentId) {
    states.put(lineId, new LineState(lineId, paymentId, RemittanceReturnState.SETTLED, null, null,
        List.of(lineId)));
    payment(paymentId);
    registerCancel();
  }

  /** Several returnable lines that share one payment. */
  private void shared(String paymentId, String... lineIds) {
    for (String id : lineIds) {
      states.put(id, new LineState(id, paymentId, RemittanceReturnState.SETTLED, null, null,
          List.of(lineIds)));
    }
    payment(paymentId);
    registerCancel();
  }

  private FIN_Payment payment(String id) {
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getId()).thenReturn(id);
    when(payment.getDocumentNo()).thenReturn("PAY-" + id);
    when(payment.getStatus()).thenReturn(RemittanceReturnState.PAYMENT_VOID);
    when(payment.getFINFinaccTransactionList()).thenReturn(new ArrayList<>());
    payments.put(id, payment);
    when(dal.get(FIN_Payment.class, id)).thenReturn(payment);
    return payment;
  }

  /** The settlement rows the state answers: all of them before the run, none after it. */
  private void registerCancel() {
    Map<String, RemittanceLineCancel> before = new LinkedHashMap<>();
    for (String id : payments.keySet()) {
      before.put(id, cancel(id));
    }
    state.when(() -> RemittanceReturnState.settlementRows(remittance))
        .thenReturn(before, new LinkedHashMap<>());
  }

  private RemittanceLineCancel cancel(String paymentId) {
    RemittanceLineCancel cancel = mock(RemittanceLineCancel.class);
    when(cancel.getId()).thenReturn("C-" + paymentId);
    when(cancel.getPayment()).thenReturn(payments.get(paymentId));
    return cancel;
  }

  private static FIN_FinaccTransaction transaction(String id) {
    FIN_FinaccTransaction transaction = mock(FIN_FinaccTransaction.class);
    when(transaction.getId()).thenReturn(id);
    return transaction;
  }

  private static RemittanceLine line(String id, String invoiceNo) {
    RemittanceLine line = mock(RemittanceLine.class);
    when(line.getId()).thenReturn(id);
    if (invoiceNo != null) {
      FIN_PaymentScheduleDetail detail = mock(FIN_PaymentScheduleDetail.class);
      FIN_PaymentSchedule schedule = mock(FIN_PaymentSchedule.class);
      org.openbravo.model.common.invoice.Invoice real =
          mock(org.openbravo.model.common.invoice.Invoice.class);
      when(real.getDocumentNo()).thenReturn(invoiceNo);
      when(schedule.getInvoice()).thenReturn(real);
      when(detail.getInvoicePaymentSchedule()).thenReturn(schedule);
      when(line.getPaymentScheduleDetail()).thenReturn(detail);
    }
    return line;
  }

  private void assertFailed(NeoResponse response, String reason) throws Exception {
    assertEquals(422, response.getHttpStatus(), String.valueOf(response.getBody()));
    assertEquals("ETGO_RemittanceReturnFailed", messageKey(response));
    assertTrue(response.getBody().getJSONObject("error").getString("message").contains(reason),
        response.getBody().toString());
    errors.verify(NeoErrorResponses::rollbackUnlessBatchOwned);
  }

  private NeoResponse returnLines(String date, String... ids) throws Exception {
    JSONObject values = new JSONObject().put("lineIds", new JSONArray(List.of(ids)));
    if (date != null) {
      values.put("returnDate", date);
    }
    return handler.handle(context("POST", "returnLines",
        new JSONObject().put("fieldValues", values)));
  }

  private static JSONObject bodyFor(String action) throws Exception {
    return "returnLines".equals(action) ? lineIds("L1") : new JSONObject();
  }

  private static JSONObject lineIds(String... ids) throws Exception {
    return new JSONObject().put("fieldValues",
        new JSONObject().put("lineIds", new JSONArray(List.of(ids))));
  }

  private String dateGivenToTheModule() throws Exception {
    ArgumentCaptor<String> date = ArgumentCaptor.forClass(String.class);
    verify(module.constructed().get(0)).returnPayments(any(), date.capture(), any(), any());
    return date.getValue();
  }

  private JSONArray cancelIdsGivenToTheModule() throws Exception {
    ArgumentCaptor<JSONArray> ids = ArgumentCaptor.forClass(JSONArray.class);
    verify(module.constructed().get(0)).returnPayments(ids.capture(), anyString(), any(), any());
    return ids.getValue();
  }

  private static NeoContext context(String method, String name, JSONObject body) {
    return NeoContext.builder().specName("remittance").entityName("remittance")
        .httpMethod(method).endpointType(NeoEndpointType.ACTION).fieldName(name)
        .recordId(REM_ID).requestBody(body).build();
  }

  private static String messageKey(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getJSONArray("messageKeys").getString(0);
  }
}
