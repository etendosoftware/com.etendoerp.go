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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceType;
import org.openbravo.module.remittance.utility.RemittanceStatusUtil;

/**
 * Real-database run of the SEPA remittance provisioning of a financial account created and changed
 * through NEO ({@code financial-account/account}, the real REST dispatch with its
 * {@code @NeoExtension} {@link FinancialAccountHandler}):
 * <ul>
 *   <li>a Bank account created with the {@code sepa-remittances} flag on is linked to both
 *       remittance payment methods, and each link passes what Classic's
 *       {@code REMRemittanceProcess#checkPMSetup} reads (the method's direction allowed, deferred,
 *       executed automatically through a process); a Cash account created the same way gets
 *       none;</li>
 *   <li>a Bank account holding a real REM remittance in {@code PE} refuses a PATCH to type Cash
 *       with 422 {@code ETGO_RemittanceAccountTypeChangeBlocked}, and accepts it once that
 *       remittance is voided ({@code AN}); the accepted change deactivates only the remittance
 *       links (kept, inactive; the account's other payment methods stay active) and becoming Bank
 *       again does not reactivate them;</li>
 *   <li>the same change through MCP {@code etendo_update} deactivates the remittance links
 *       too;</li>
 *   <li>a type change the generic write refuses (no or a stale {@code updated}) leaves no Bank
 *       account with deactivated remittance links. That case runs as a single request, without
 *       the caller-owned transaction, since the refusal path is what is under test; it writes
 *       nothing the generic CRUD would commit.</li>
 * </ul>
 *
 * <p>Nothing in a mock shows these: the links are written by the real provisioning against the
 * real constraints and the DAL observer, and the open-remittance count runs on the real REM table.
 *
 * <p><b>Rollback.</b> The writes run under a caller-owned transaction
 * ({@link BatchService#beginCallerOwnedTransaction}, what a batch does), so the generic CRUD never
 * commits; every test rolls back in {@link #rollBack()}. The flag is the only stub:
 * {@link SepaRemittancesFlag#isEnabled()} answers {@code true} through a static mock on this
 * thread, the whole NEO path being real. Runs on {@link WeldBaseTest}: NEO resolves its handlers
 * through Weld.
 *
 * <p>A class of its own: no NEO financial-account integration test exists, and
 * {@code RemittanceSepaProvisioningIdempotencyIntegrationTest} calls the provisioning utility
 * directly, without NEO, on {@code OBBaseTest}.
 *
 * @covers com.etendoerp.go.schemaforge.FinancialAccountRemittanceSupport
 * @covers com.etendoerp.go.schemaforge.FinancialAccountHandler
 * @covers com.etendoerp.go.schemaforge.RemittanceSepaProvisioning
 */
public class FinancialAccountRemittanceProvisioningIntegrationTest extends WeldBaseTest {

  private static final String SPEC = "financial-account";
  private static final String ENTITY = "account";
  private static final String EURO_ID = "102";
  private static final String SPAIN_ID = "106";
  private static final String VALID_ES_IBAN = "ES9121000418450200051332";
  private static final String NAME_PREFIX = "SF remittance IT ";

  private MockedStatic<SepaRemittancesFlag> flag;

  /**
   * The generic CRUD's JSON service singleton reads the DAL servlet context, which a test JVM does
   * not have; OBBaseTest installs its mock one around each case.
   */
  @Override
  protected boolean shouldMockServletContext() {
    return true;
  }

  @Before
  public void openTheRequest() {
    setTestAdminContext();
    // These direct handler calls bypass NeoServlet, which opens this mode after authentication.
    OBContext.setAdminMode();
    BatchService.beginCallerOwnedTransaction();
    flag = Mockito.mockStatic(SepaRemittancesFlag.class, Mockito.CALLS_REAL_METHODS);
    flag.when(SepaRemittancesFlag::isEnabled).thenReturn(true);
  }

  @After
  public void rollBack() {
    try {
      flag.close();
      BatchService.endCallerOwnedTransaction();
      OBDal.getInstance().rollbackAndClose();
    } finally {
      while (OBContext.getOBContext() != null
          && OBContext.getOBContext().isInAdministratorMode()) {
        OBContext.restorePreviousMode();
      }
    }
  }

  @Test
  public void aBankAccountCreatedWithTheFlagOnGetsLinksThatPassClassicsCheckAndCashGetsNone()
      throws Exception {
    FIN_FinancialAccount bank = createAccount("B", VALID_ES_IBAN);
    FIN_FinancialAccount cash = createAccount("C", null);
    Client client = bank.getClient();

    for (String scheme : List.of(RemittanceSepaProvisioning.SCHEME_CORE,
        RemittanceSepaProvisioning.SCHEME_SCT)) {
      RemittanceType type = RemittanceSepaProvisioning.findType(client, scheme);
      assertNotNull("the create provisioned the " + scheme + " type", type);
      FinAccPaymentMethod link = linkOf(bank, type);
      assertNotNull("the Bank account is linked to the " + scheme + " method", link);
      assertTrue(Boolean.TRUE.equals(link.isActive()));
      boolean collection = Boolean.TRUE.equals(type.getPaymentMethod().isPayinAllow());
      assertFalse(scheme + " link refused by checkPMSetup", wrongForClassic(link, collection));
      assertEquals("the Cash account is not linked to the " + scheme + " method", null,
          linkOf(cash, type));
    }
    assertTrue(RemittanceSepaProvisioning.hasRemittanceMethodLink(bank));
    assertFalse(RemittanceSepaProvisioning.hasRemittanceMethodLink(cash));
  }

  @Test
  public void anOpenRemittanceBlocksLeavingBankAndAVoidedOneDoesNot() throws Exception {
    FIN_FinancialAccount bank = createAccount("B", VALID_ES_IBAN);
    FinAccPaymentMethod other = linkToAnotherMethod(bank);
    Remittance remittance = pendingRemittance(bank);
    assertEquals(1L, RemittanceSepaProvisioning.countOpenRemittances(bank));

    NeoResponse blocked = patchType(bank, "C");

    assertEquals(String.valueOf(blocked.getBody()), 422, blocked.getHttpStatus());
    assertEquals(FinancialAccountRemittanceSupport.MSG_TYPE_CHANGE_BLOCKED, blocked.getBody()
        .getJSONObject("error").getJSONArray("messageKeys").getString(0));
    OBDal.getInstance().refresh(bank);
    assertEquals("refused: still Bank", "B", bank.getType());

    remittance.set(RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS, RemittanceStatusUtil.VOIDED);
    OBDal.getInstance().save(remittance);
    OBDal.getInstance().flush();
    assertEquals(0L, RemittanceSepaProvisioning.countOpenRemittances(bank));

    NeoResponse allowed = patchType(bank, "C");

    assertTrue(String.valueOf(allowed.getBody()), allowed.getHttpStatus() < 300);
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(bank);
    assertEquals("C", bank.getType());
    // W1: leaving Bank deactivates the remittance-method links in the same update; they are kept.
    assertFalse("the links were deactivated",
        RemittanceSepaProvisioning.hasRemittanceMethodLink(bank));
    for (String scheme : List.of(RemittanceSepaProvisioning.SCHEME_CORE,
        RemittanceSepaProvisioning.SCHEME_SCT)) {
      FinAccPaymentMethod link = linkOf(bank,
          RemittanceSepaProvisioning.findType(bank.getClient(), scheme));
      assertNotNull(scheme + " link kept", link);
      OBDal.getInstance().refresh(link);
      assertFalse(scheme + " link inactive", Boolean.TRUE.equals(link.isActive()));
    }
    List<FinAccPaymentMethod> others = nonRemittanceLinks(bank);
    List<String> otherIds = new java.util.ArrayList<>();
    others.forEach(l -> otherIds.add(l.getId()));
    assertTrue("the account keeps its other link " + other.getId() + ": " + otherIds
        + " / all: " + allLinks(bank), otherIds.contains(other.getId()));
    for (FinAccPaymentMethod each : others) {
      OBDal.getInstance().refresh(each);
      assertTrue("only the remittance links are deactivated: "
          + each.getPaymentMethod().getName(), Boolean.TRUE.equals(each.isActive()));
    }

    NeoResponse backToBank = patchType(bank, "B");

    assertTrue(String.valueOf(backToBank.getBody()), backToBank.getHttpStatus() < 300);
    OBDal.getInstance().flush();
    assertFalse("becoming Bank again does not reactivate them",
        RemittanceSepaProvisioning.hasRemittanceMethodLink(bank));
  }

  /**
   * The link deactivation of a type change belongs to the update that changes the type: when the
   * generic write then refuses the update (here the missing optimistic-concurrency token, a 400,
   * and a stale one, a 409), the account must not be left a Bank account with its remittance links
   * deactivated. Outside a batch nothing rolls back after such an answer, and the request ends with
   * a commit (DalRequestFilter), so whatever the pre-hook flushed stays.
   */
  @Test
  public void aRefusedTypeChangeLeavesTheRemittanceLinksActive() throws Exception {
    FIN_FinancialAccount bank = createAccount("B", VALID_ES_IBAN);
    assertTrue(RemittanceSepaProvisioning.hasRemittanceMethodLink(bank));
    String stale = String.valueOf(NeoRestTestClient.readUpdated(SPEC, ENTITY, bank.getId()))
        .replaceFirst("^\\d{4}", "2001");
    // A single request, as the SPA or an agent sends it: no batch owns the transaction.
    BatchService.endCallerOwnedTransaction();
    try {
      NeoResponse missing = NeoRestTestClient.send(SPEC, ENTITY, "PATCH", bank.getId(),
          new JSONObject().put("type", "C"));
      NeoResponse conflict = NeoRestTestClient.send(SPEC, ENTITY, "PATCH", bank.getId(),
          new JSONObject().put("type", "C").put("updated", stale));

      assertEquals(String.valueOf(missing.getBody()), 400, missing.getHttpStatus());
      assertTrue(String.valueOf(conflict.getBody()), conflict.getHttpStatus() >= 400);
    } finally {
      BatchService.beginCallerOwnedTransaction();
    }
    assertEquals("no Bank account is left with its remittance links deactivated", 0L,
        bankAccountsWithInactiveRemittanceLinks(bank.getName()));
  }

  /**
   * The same type change made by an agent through MCP {@code etendo_update}: the update succeeds
   * and the post-hook deactivates the remittance links, as it does on REST (the flag the pre-hook
   * leaves on the context must reach the post-hook on this channel too).
   */
  @Test
  public void aTypeChangeThroughMcpDeactivatesTheRemittanceLinks() throws Exception {
    FIN_FinancialAccount bank = createAccount("B", VALID_ES_IBAN);
    FinAccPaymentMethod other = linkToAnotherMethod(bank);
    assertTrue(RemittanceSepaProvisioning.hasRemittanceMethodLink(bank));

    JSONObject result = com.etendoerp.go.mcp.McpToolRouterTestAccess.handleUpdate(SPEC,
        new JSONObject().put("entity", ENTITY).put("id", bank.getId())
            .put("fields", new JSONObject().put("type", "C"))
            .put("updated", NeoRestTestClient.readUpdated(SPEC, ENTITY, bank.getId())));

    assertFalse(result.toString(), result.optBoolean("isError", false));
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(bank);
    assertEquals("C", bank.getType());
    assertFalse("the remittance links were deactivated",
        RemittanceSepaProvisioning.hasRemittanceMethodLink(bank));
    OBDal.getInstance().refresh(other);
    assertTrue("the other payment method stays active", Boolean.TRUE.equals(other.isActive()));
  }

  // ---------------------------------------------------------------- helpers

  /**
   * Classic's {@code REMRemittanceProcess#getErrorResult} for one direction: the link must allow
   * it, be deferred and execute automatically ({@code A}) through a process.
   */
  private static boolean wrongForClassic(FinAccPaymentMethod link, boolean collection) {
    if (collection) {
      return !Boolean.TRUE.equals(link.isPayinAllow())
          || !Boolean.TRUE.equals(link.isPayinDeferred())
          || link.getPayinExecutionProcess() == null
          || !StringUtils.equals("A", link.getPayinExecutionType());
    }
    return !Boolean.TRUE.equals(link.isPayoutAllow())
        || !Boolean.TRUE.equals(link.isPayoutDeferred())
        || link.getPayoutExecutionProcess() == null
        || !StringUtils.equals("A", link.getPayoutExecutionType());
  }

  private static FinAccPaymentMethod linkOf(FIN_FinancialAccount account, RemittanceType type) {
    OBCriteria<FinAccPaymentMethod> criteria =
        OBDal.getInstance().createCriteria(FinAccPaymentMethod.class);
    criteria.add(Restrictions.eq(FinAccPaymentMethod.PROPERTY_ACCOUNT, account));
    criteria.add(Restrictions.eq(FinAccPaymentMethod.PROPERTY_PAYMENTMETHOD,
        type.getPaymentMethod()));
    criteria.setFilterOnReadableOrganization(false);
    criteria.setFilterOnActive(false);
    criteria.setMaxResults(1);
    return (FinAccPaymentMethod) criteria.uniqueResult();
  }

  /** Creates an account of {@code type} through NEO and reads it back by its unique name. */
  private FIN_FinancialAccount createAccount(String type, String iban) throws Exception {
    String name = NAME_PREFIX + type + " " + System.nanoTime();
    JSONObject body = new JSONObject().put("name", name).put("currency", EURO_ID)
        .put("country", SPAIN_ID).put("type", type);
    if (iban != null) {
      body.put("iBAN", iban);
    }

    NeoResponse created = NeoRestTestClient.send(SPEC, ENTITY, "POST", null, body);

    assertTrue(String.valueOf(created.getBody()), created.getHttpStatus() < 300);
    OBDal.getInstance().flush();
    OBCriteria<FIN_FinancialAccount> criteria =
        OBDal.getInstance().createCriteria(FIN_FinancialAccount.class);
    criteria.add(Restrictions.eq(FIN_FinancialAccount.PROPERTY_NAME, name));
    criteria.setFilterOnReadableOrganization(false);
    FIN_FinancialAccount account = (FIN_FinancialAccount) criteria.uniqueResult();
    assertNotNull("the account " + name + " was created", account);
    assertEquals(type, account.getType());
    return account;
  }

  private NeoResponse patchType(FIN_FinancialAccount account, String type) throws Exception {
    return NeoRestTestClient.patch(SPEC, ENTITY, account.getId(),
        new JSONObject().put("type", type));
  }

  /** A saved, unprocessed SEPA collection remittance in {@code PE} on {@code account}. */
  private static Remittance pendingRemittance(FIN_FinancialAccount account) {
    RemittanceType type = RemittanceSepaProvisioning.findType(account.getClient(),
        RemittanceSepaProvisioning.SCHEME_CORE);
    assertNotNull("the create provisioned the CORE type", type);
    DocumentType docType = (DocumentType) OBDal.getInstance().getSession().createQuery(
        "from DocumentType as d where d.client.id = :client and d.documentCategory = 'REM_REM'"
            + " and d.active = true")
        .setParameter("client", account.getClient().getId())
        .setMaxResults(1)
        .uniqueResult();
    assertNotNull("the create provisioned the remittance document type", docType);
    Remittance remittance = OBProvider.getInstance().get(Remittance.class);
    remittance.setClient(account.getClient());
    remittance.setOrganization(account.getOrganization());
    remittance.setFinancialAccount(account);
    remittance.setRemittanceType(type);
    remittance.setDocumentType(docType);
    remittance.setDocumentNo("SF-TYPECHANGE-IT");
    remittance.setName("SF type change IT");
    remittance.setTransactionDate(day(LocalDate.now()));
    remittance.setDueDate(day(LocalDate.now().plusDays(30)));
    remittance.setProcessed(false);
    remittance.set(RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS, RemittanceStatusUtil.PENDING);
    OBDal.getInstance().save(remittance);
    OBDal.getInstance().flush();
    return remittance;
  }

  /**
   * Links {@code account} to a payment method of its client that is not a remittance method, as a
   * user adding one in the account's payment methods would.
   */
  private static FinAccPaymentMethod linkToAnotherMethod(FIN_FinancialAccount account) {
    org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod method =
        OBDal.getInstance().getSession().createQuery(
            "from FIN_PaymentMethod as m where m.client.id = :client and m.active = true"
                + " and not exists (select 1 from "
                + org.openbravo.module.remittance.RemittanceTypeParameter.ENTITY_NAME + " as p"
                + "   where p.remittanceType.paymentMethod.id = m.id and p.name = :marker)"
                + " order by m.name",
            org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod.class)
            .setParameter("client", account.getClient().getId())
            .setParameter("marker", RemittanceSepaProvisioning.PARAM_SCHEME)
            .setMaxResults(1)
            .uniqueResult();
    assertNotNull("the sample client has a payment method", method);
    FinAccPaymentMethod link = OBProvider.getInstance().get(FinAccPaymentMethod.class);
    link.setClient(account.getClient());
    link.setOrganization(account.getOrganization());
    link.setAccount(account);
    link.setPaymentMethod(method);
    link.setDefault(false);
    OBDal.getInstance().save(link);
    OBDal.getInstance().flush();
    return link;
  }

  /** Every link of the account, for a failure message: id, method and active flag. */
  private static String allLinks(FIN_FinancialAccount account) {
    return String.valueOf(OBDal.getInstance().getSession().createNativeQuery(
        "select fin_finacc_paymentmethod_id || ':' || fin_paymentmethod_id || ':' || isactive"
            + " from fin_finacc_paymentmethod where fin_financial_account_id = :account")
        .setParameter("account", account.getId()).list());
  }

  /** The account's links to methods that are not remittance methods. */
  private static List<FinAccPaymentMethod> nonRemittanceLinks(FIN_FinancialAccount account) {
    return OBDal.getInstance().getSession().createQuery(
        "select l from " + FinAccPaymentMethod.ENTITY_NAME + " as l where l.account.id = :account"
            + " and not exists (select 1 from "
            + org.openbravo.module.remittance.RemittanceTypeParameter.ENTITY_NAME + " as p"
            + "   where p.remittanceType.paymentMethod.id = l.paymentMethod.id"
            + "   and p.name = :marker)", FinAccPaymentMethod.class)
        .setParameter("account", account.getId())
        .setParameter("marker", RemittanceSepaProvisioning.PARAM_SCHEME)
        .list();
  }

  /**
   * Bank accounts named {@code name} that have an inactive link to a remittance method, read on the
   * current connection (after a rollback, on a fresh session).
   */
  private static long bankAccountsWithInactiveRemittanceLinks(String name) {
    Object count = OBDal.getInstance().getSession().createNativeQuery(
        "select count(*) from fin_financial_account a"
            + " join fin_finacc_paymentmethod l"
            + "   on l.fin_financial_account_id = a.fin_financial_account_id"
            + " where a.name = :name and a.type = 'B' and l.isactive = 'N'"
            + " and exists (select 1 from rem_remittance_type t"
            + "   join rem_remittance_type_param p"
            + "     on p.rem_remittance_type_id = t.rem_remittance_type_id"
            + "   where t.fin_paymentmethod_id = l.fin_paymentmethod_id and p.name = :marker)")
        .setParameter("name", name)
        .setParameter("marker", RemittanceSepaProvisioning.PARAM_SCHEME)
        .uniqueResult();
    return ((Number) count).longValue();
  }

  private static Date day(LocalDate date) {
    return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
  }
}
