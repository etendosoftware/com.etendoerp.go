/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.hubspot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.hubspot.HubSpotOutboxStore.OpenDemo;
import com.etendoerp.go.hubspot.HubSpotOutboxStore.PaidCheckout;
import com.etendoerp.go.hubspot.HubSpotOutboxStore.PendingEvent;

/**
 * The HubSpot outbox against a real database: the SQL itself is what is under test.
 *
 * <ul>
 *   <li>One row per account and event type, enforced by {@code ON CONFLICT DO NOTHING} on the
 *       unique index, so an event reported or detected twice is recorded once.</li>
 *   <li>The reads the sync detects events with: open demos, a first entry, a paid checkout.</li>
 *   <li>The status transitions a send leaves behind.</li>
 *   <li>The business-side writes of {@link HubSpotOutbox}, in plain JDBC inside a savepoint.</li>
 * </ul>
 *
 * <p>The store's writes join the caller's transaction, so each test commits what it needs to read
 * back and {@link #cleanUp()} deletes every fixture, marked by {@link #MARKER} in the account
 * e-mail.
 *
 * @covers com.etendoerp.go.hubspot.DalHubSpotOutboxStore
 * @covers com.etendoerp.go.hubspot.HubSpotOutbox
 */
public class DalHubSpotOutboxStoreIntegrationTest extends OBBaseTest {

  private static final String MARKER = "hubspot-outbox-it-";
  private static final String ZERO = "0";

  private final DalHubSpotOutboxStore store = new DalHubSpotOutboxStore();

  @After
  public void cleanUp() {
    try {
      OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
      String accounts = "(select etgo_account_id from etgo_account where email like '" + MARKER
          + "%')";
      execute("delete from etgo_hubspot_outbox where etgo_account_id in " + accounts);
      execute("delete from etgo_go_session where etgo_account_id in " + accounts);
      execute("delete from etgo_checkout_request where etgo_account_id in " + accounts);
      execute("delete from etgo_account where email like '" + MARKER + "%'");
      OBDal.getInstance().commitAndClose();
    } finally {
      OBDal.getInstance().rollbackAndClose();
      OBContext.setOBContext((OBContext) null);
    }
  }

  @Test
  public void anEventIsRecordedOncePerAccountAndType() {
    String account = createAccount("Ana García López", null);

    assertTrue(store.enqueue(account, HubSpotOutboxStore.ACCOUNT_CREATED, null, null));
    assertFalse("a second report of the same event adds nothing",
        store.enqueue(account, HubSpotOutboxStore.ACCOUNT_CREATED, null, null));
    assertTrue("another event type of the same account is its own row",
        store.enqueue(account, HubSpotOutboxStore.DEMO_READY, "C1", "{\"companyName\":\"X\"}"));
    store.commit();

    List<PendingEvent> pending = pendingOf(account);
    assertEquals(2, pending.size());
    PendingEvent first = pending.get(0);
    assertEquals(HubSpotOutboxStore.ACCOUNT_CREATED, first.eventType());
    assertEquals("the pending row carries the account it is about", "Ana García López",
        first.name());
    assertTrue(first.email().startsWith(MARKER));
    assertEquals(0, first.attemptCount());
    assertNull(first.nextAttemptAt());
    assertEquals("C1", pending.get(1).environmentId());
  }

  @Test
  public void aSendLeavesItsOutcomeOnTheRow() {
    String account = createAccount("Bruno", null);
    store.enqueue(account, HubSpotOutboxStore.DEMO_READY, "C1", null);
    store.enqueue(account, HubSpotOutboxStore.PAID, "C1", null);
    store.commit();
    List<PendingEvent> pending = pendingOf(account);
    Instant next = Instant.now().plus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);

    store.markRetry(pending.get(1).id(), 3, next, "HTTP 503");
    store.commit();
    PendingEvent retried = pendingOf(account).get(1);
    assertEquals(3, retried.attemptCount());
    assertEquals(next, retried.nextAttemptAt());

    assertNull("no deal before the demo event is sent", store.findDealId(account));
    store.markSent(pending.get(0).id(), "deal-77");
    store.markFailed(pending.get(1).id(), 4, "x".repeat(DalHubSpotOutboxStore.MAX_ERROR_LENGTH + 50));
    store.commit();

    assertEquals("deal-77", store.findDealId(account));
    assertEquals(HubSpotOutboxStore.STATUS_SENT,
        store.findStatus(account, HubSpotOutboxStore.DEMO_READY));
    assertEquals(HubSpotOutboxStore.STATUS_FAILED,
        store.findStatus(account, HubSpotOutboxStore.PAID));
    assertNull(store.findStatus(account, HubSpotOutboxStore.DEMO_EXPIRED));
    assertTrue("sent and failed rows are no longer pending", pendingOf(account).isEmpty());
    assertEquals("a huge error is cut, not rejected",
        String.valueOf(DalHubSpotOutboxStore.MAX_ERROR_LENGTH),
        scalar("select length(last_error) from etgo_hubspot_outbox where etgo_account_id = '"
            + account + "' and event_type = 'PAID'"));
  }

  @Test
  public void aSkippedRowKeepsItsReason() {
    String account = createAccount("Carla", null);
    store.enqueue(account, HubSpotOutboxStore.DEMO_EXPIRED, "C1", null);
    store.commit();

    store.markSkipped(pendingOf(account).get(0).id(), "no deal");
    store.commit();

    assertEquals(HubSpotOutboxStore.STATUS_SKIPPED,
        store.findStatus(account, HubSpotOutboxStore.DEMO_EXPIRED));
    assertEquals("no deal", scalar("select last_error from etgo_hubspot_outbox"
        + " where etgo_account_id = '" + account + "'"));
  }

  @Test
  public void aDemoStaysOpenUntilItIsPaidOrExpired() {
    String account = createAccount("Dora", null);
    store.enqueue(account, HubSpotOutboxStore.DEMO_READY, TEST_CLIENT_ID, null);
    store.commit();

    Optional<OpenDemo> open = openDemoOf(account);
    assertTrue(open.isPresent());
    assertEquals(TEST_CLIENT_ID, open.get().environmentId());
    assertFalse(open.get().entered());
    assertTrue(open.get().readyAt() != null);

    store.enqueue(account, HubSpotOutboxStore.DEMO_ENTERED, TEST_CLIENT_ID, null);
    store.commit();
    assertTrue("an entry already detected is reported as such", openDemoOf(account).get().entered());

    store.enqueue(account, HubSpotOutboxStore.DEMO_EXPIRED, TEST_CLIENT_ID, null);
    store.commit();
    assertFalse("a closed deal is no longer inspected", openDemoOf(account).isPresent());
  }

  @Test
  public void aSessionOnTheDemoIsAnEntry() {
    String account = createAccount("Eva", null);

    assertFalse("an environment nobody entered", store.hasEnteredEnvironment(
        UUID.randomUUID().toString().replace("-", "").toUpperCase()));

    execute("insert into etgo_go_session (etgo_go_session_id, ad_client_id, ad_org_id, created,"
        + " createdby, updated, updatedby, etgo_account_id, session_token_hash, csrf_token,"
        + " auth_method, ctx_client_id, expires_at, absolute_expires_at) values (get_uuid(), '0',"
        + " '0', now(), '0', now(), '0', '" + account + "', get_uuid(), get_uuid(), 'password', '"
        + TEST_CLIENT_ID + "', now(), now())");
    store.commit();

    assertTrue(store.hasEnteredEnvironment(TEST_CLIENT_ID));
  }

  @Test
  public void onlyAPaymentAfterTheDemoCounts() {
    String account = createAccount("Fede", null);
    Instant beforePayment = Instant.now().minus(1, ChronoUnit.HOURS);
    assertFalse(store.findPaidCheckout(account, beforePayment).isPresent());

    execute("insert into etgo_checkout_request (etgo_checkout_request_id, ad_client_id, ad_org_id,"
        + " created, createdby, updated, updatedby, request_id, etgo_account_id, account_email,"
        + " client_name, checkout_status, provisioning_attempts, creating_at,"
        + " demo_selection_recorded, transfer_products, transfer_contacts, paid_at, etgo_plan_id)"
        + " select get_uuid(), '0', '0', now(), '0', now(), '0', '" + MARKER + "'||get_uuid(), '"
        + account + "', email, 'X', 'PAID', 0, now(), 'N', 'N', 'N', now(), null"
        + " from etgo_account where etgo_account_id = '" + account + "'");
    store.commit();

    Optional<PaidCheckout> paid = store.findPaidCheckout(account, beforePayment);
    assertTrue(paid.isPresent());
    assertNull("a checkout without a plan names none", paid.get().planKey());
    assertFalse("a payment before the demo is not this demo's",
        store.findPaidCheckout(account, Instant.now().plus(1, ChronoUnit.HOURS)).isPresent());
  }

  @Test
  public void aNewAccountIsReportedInTheCallersTransaction() {
    String account = createAccount("Gala", null);
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);

    HubSpotOutbox.accountCreated(
        OBDal.getInstance().get(com.etendoerp.go.schemaforge.data.Account.class, account));
    OBDal.getInstance().rollbackAndClose();
    assertNull("rolled back with the caller", store.findStatus(account,
        HubSpotOutboxStore.ACCOUNT_CREATED));

    HubSpotOutbox.accountCreated(
        OBDal.getInstance().get(com.etendoerp.go.schemaforge.data.Account.class, account));
    OBDal.getInstance().commitAndClose();
    assertEquals(HubSpotOutboxStore.STATUS_PENDING,
        store.findStatus(account, HubSpotOutboxStore.ACCOUNT_CREATED));
  }

  @Test
  public void aReadyDemoIsReportedForItsSelfServeOwnerWithTheCountryName() throws Exception {
    String email = MARKER + "owner-" + UUID.randomUUID() + "@example.test";
    String account = createAccount("Hugo", email);
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);

    HubSpotOutbox.demoReady(email.toUpperCase(), "C9", "Empresa SL", "es");
    OBDal.getInstance().commitAndClose();

    PendingEvent event = pendingOf(account).get(0);
    assertEquals(HubSpotOutboxStore.DEMO_READY, event.eventType());
    assertEquals("C9", event.environmentId());
    JSONObject payload = new JSONObject(event.payload());
    assertEquals("Empresa SL", payload.getString(HubSpotOutbox.FIELD_COMPANY_NAME));
    assertEquals("Spain", payload.getString(HubSpotOutbox.FIELD_COUNTRY));
  }

  @Test
  public void aSignupApiAccountIsLeftOut() {
    String email = MARKER + "api-" + UUID.randomUUID() + "@example.test";
    String account = createAccount("Iris", email);
    execute("update etgo_account set signup_channel = 'agent' where etgo_account_id = '"
        + account + "'");
    store.commit();
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);

    HubSpotOutbox.demoReady(email, "C9", "Empresa SL", "ES");
    OBDal.getInstance().commitAndClose();

    assertNull(store.findStatus(account, HubSpotOutboxStore.DEMO_READY));
  }

  @Test
  public void aFailedReportLeavesTheCallersTransactionUsable() {
    String account = createAccount("Juan", null);
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    execute("update etgo_account set name = 'Juan Changed' where etgo_account_id = '" + account
        + "'");

    // An event type longer than the column: the insert fails inside the savepoint.
    HubSpotOutbox.write("an oversized event", connection -> HubSpotOutbox.enqueue(connection,
        account, "X".repeat(200), null, null));
    OBDal.getInstance().commitAndClose();

    assertEquals("the caller's own write still commits", "Juan Changed",
        scalar("select name from etgo_account where etgo_account_id = '" + account + "'"));
  }

  private String createAccount(String name, String email) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    String address = email != null ? email
        : MARKER + UUID.randomUUID().toString().replace("-", "") + "@example.test";
    String id = UUID.randomUUID().toString().replace("-", "").toUpperCase();
    execute("insert into etgo_account (etgo_account_id, ad_client_id, ad_org_id, isactive,"
        + " created, createdby, updated, updatedby, email, name, status) values ('" + id
        + "', '0', '0', 'Y', now(), '0', now(), '0', '" + address + "', '" + name
        + "', 'active')");
    OBDal.getInstance().commitAndClose();
    return id;
  }

  private List<PendingEvent> pendingOf(String account) {
    return store.findPending(1000).stream().filter(e -> e.accountId().equals(account)).toList();
  }

  private Optional<OpenDemo> openDemoOf(String account) {
    return store.findOpenDemos().stream().filter(d -> d.accountId().equals(account)).findFirst();
  }

  private static void execute(String sql) {
    OBDal.getInstance().getSession().createNativeQuery(sql).executeUpdate();
  }

  private static String scalar(String sql) {
    Object value = OBDal.getInstance().getSession().createNativeQuery(sql).getSingleResult();
    return value == null ? null : value.toString();
  }
}
