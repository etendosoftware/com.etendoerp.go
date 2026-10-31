/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.classic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;

import com.etendoerp.go.payment.PlanChangeRefusedException;
import com.etendoerp.go.payment.PlanChangeResult;
import com.etendoerp.go.payment.PlanChangeService;
import com.etendoerp.go.payment.PlanChangeTiming;
import com.etendoerp.go.payment.StripeTransportException;
import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * ETP-5053 — the operator's Change Plan button goes through the same service as the customer and
 * words every outcome with a message, never a stack trace.
 *
 * @covers com.etendoerp.go.payment.classic.SubscriptionPlanChangeActionHandler
 */
class SubscriptionPlanChangeActionHandlerTest {

  private static final String ROW_ID = "7A1F2B3C4D5E4F60A1B2C3D4E5F60718";
  private static final String PLAN_ID = "0D3C1D0C0A6B4E6C9E5E5B7C2A1F9D11";

  private final PlanChangeService service = mock(PlanChangeService.class);
  private final OBDal obDal = mock(OBDal.class);
  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<OBMessageUtils> messagesMock;
  private Subscription row;
  private Plan pro;

  private final SubscriptionPlanChangeActionHandler handler =
      new SubscriptionPlanChangeActionHandler(() -> service) {
        @Override
        protected java.time.ZoneId zone() {
          return ZoneOffset.UTC;
        }
      };

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);
    messagesMock = mockStatic(OBMessageUtils.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    messagesMock.when(() -> OBMessageUtils.getI18NMessage(anyString(), any()))
        .thenAnswer(invocation -> {
          String key = invocation.getArgument(0);
          String[] params = invocation.getArgument(1);
          return key + (params == null ? "" : ":" + String.join(",", params));
        });
    row = mock(Subscription.class);
    pro = mock(Plan.class);
    when(pro.getSearchKey()).thenReturn("pro");
    when(pro.getName()).thenReturn("Pro");
    when(obDal.get(Subscription.class, ROW_ID)).thenReturn(row);
    when(obDal.get(Plan.class, PLAN_ID)).thenReturn(pro);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextMock.close();
    messagesMock.close();
  }

  private static String request(String rowId, String planId) {
    return "{\"inpKeyName\":\"inpetgoSubscriptionId\",\"inpetgoSubscriptionId\":"
        + (rowId == null ? "null" : "\"" + rowId + "\"") + ",\"_params\":{\"target_plan_id\":"
        + (planId == null ? "null" : "\"" + planId + "\"") + "}}";
  }

  @Test
  void appliesAnUpgradeAndSaysSo() throws Exception {
    when(service.requestChange(row, "pro")).thenReturn(new PlanChangeResultFixture()
        .immediate(pro, Instant.parse("2026-10-31T10:00:00Z")));

    JSONObject response = handler.doExecute(Map.of(), request(ROW_ID, PLAN_ID));

    assertTrue(response.toString().contains("ETGO_PlanChangeApplied:Pro"), response.toString());
    assertTrue(response.toString().contains("success"), response.toString());
    verify(obDal).commitAndClose();
  }

  @Test
  void schedulesADowngradeWithItsDate() throws Exception {
    when(service.requestChange(row, "pro")).thenReturn(new PlanChangeResultFixture()
        .scheduled(pro, Instant.parse("2026-12-01T00:00:00Z"), false));

    JSONObject response = handler.doExecute(Map.of(), request(ROW_ID, PLAN_ID));

    assertTrue(response.toString().contains("ETGO_PlanChangeScheduled:Pro,"),
        response.toString());
    verify(obDal).commitAndClose();
  }

  @Test
  void reportsAnAlreadyPendingDowngradeAsSuch() throws Exception {
    when(service.requestChange(row, "pro")).thenReturn(new PlanChangeResultFixture()
        .scheduled(pro, Instant.parse("2026-12-01T00:00:00Z"), true));

    JSONObject response = handler.doExecute(Map.of(), request(ROW_ID, PLAN_ID));

    assertTrue(response.toString().contains("ETGO_PlanChangeAlreadyPending:Pro,"),
        response.toString());
  }

  @Test
  void aRefusalIsWordedWithItsCodeAndRolledBack() throws Exception {
    when(service.requestChange(row, "pro")).thenThrow(new PlanChangeRefusedException(
        PlanChangeRefusedException.PLAN_CHANGE_NOT_ALLOWED, "not offered"));

    JSONObject response = handler.doExecute(Map.of(), request(ROW_ID, PLAN_ID));

    assertTrue(response.toString().contains(
        "ETGO_PlanChangeRefused:PLAN_CHANGE_NOT_ALLOWED,not offered"), response.toString());
    assertTrue(response.toString().contains("error"), response.toString());
    verify(obDal).rollbackAndClose();
    verify(obDal, never()).commitAndClose();
  }

  @Test
  void aProviderThatDoesNotAnswerIsWordedAndRolledBack() throws Exception {
    when(service.requestChange(row, "pro")).thenThrow(new StripeTransportException("timeout"));

    JSONObject response = handler.doExecute(Map.of(), request(ROW_ID, PLAN_ID));

    assertTrue(response.toString().contains("ETGO_PlanChangeProviderDown"), response.toString());
    verify(obDal).rollbackAndClose();
  }

  @Test
  void aMissingTargetPlanOrAClosedRowIsRefusedBeforeTheService() throws Exception {
    JSONObject noPlan = handler.doExecute(Map.of(), request(ROW_ID, null));
    assertTrue(noPlan.toString().contains("ETGO_PlanChangeNoPlan"), noPlan.toString());

    when(row.getEndDate()).thenReturn(new Date());
    JSONObject closed = handler.doExecute(Map.of(), request(ROW_ID, PLAN_ID));
    assertTrue(closed.toString().contains("ETGO_PlanChangeRowClosed"), closed.toString());

    verify(service, never()).requestChange(any(Subscription.class), anyString());
  }

  @Test
  void theSelectedRowIsReadFromTheKeyProperty() throws Exception {
    assertEquals(ROW_ID, SubscriptionPlanChangeActionHandler.selectedRowId(
        new JSONObject(request(ROW_ID, PLAN_ID))));
    assertEquals(null, SubscriptionPlanChangeActionHandler.selectedRowId(
        new JSONObject(request(null, PLAN_ID))));
    assertEquals(null, SubscriptionPlanChangeActionHandler.selectedRowId(new JSONObject("{}")));
  }

  /** Builds results through the package-private constructor of {@link PlanChangeResult}. */
  private static final class PlanChangeResultFixture {
    PlanChangeResult immediate(Plan plan, Instant at) throws Exception {
      return construct(PlanChangeTiming.IMMEDIATE, plan, at, false);
    }

    PlanChangeResult scheduled(Plan plan, Instant at, boolean pending) throws Exception {
      return construct(PlanChangeTiming.PERIOD_END, plan, at, pending);
    }

    private static PlanChangeResult construct(PlanChangeTiming timing, Plan plan, Instant at,
        boolean pending) throws Exception {
      java.lang.reflect.Constructor<PlanChangeResult> constructor =
          PlanChangeResult.class.getDeclaredConstructor(PlanChangeTiming.class, Plan.class,
              Instant.class, boolean.class);
      constructor.setAccessible(true);
      return constructor.newInstance(timing, plan, at, pending);
    }
  }
}
