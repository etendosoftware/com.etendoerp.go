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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceType;
import org.openbravo.module.remittance.utility.RemittanceStatusUtil;

/**
 * Real-database acceptance of the REM trigger {@code REM_REMITTANCE_TRG} as ETP-6247 left it: it
 * only FREEZES the SEPA values (IBAN, creditor identifier, direct debit scheme, financed flag) once
 * the remittance is processed, inside the test transaction, rolled back after each test. It no
 * longer copies nor defaults anything: the Classic callout
 * {@code RemittanceSepaDefaultsCallout} and the GO write path ({@link RemittanceHeaderWrites}) do.
 * Pins:
 * <ul>
 *   <li>insert: nothing is copied or defaulted (a remittance without IBAN, creditor identifier or
 *       scheme keeps them empty; a provided value is kept; the scheme is only {@code CORE} or
 *       {@code B2B});</li>
 *   <li>update while not processed: a change of the account or the type re-copies nothing, so a
 *       value typed by hand survives;</li>
 *   <li>once processed: the IBAN, the identifier, the scheme and the financed flag are frozen,
 *       and nothing else REM writes on it (status, posting, reactivation) is refused, with or
 *       without SEPA values (a remittance from before they existed has them all NULL).</li>
 * </ul>
 *
 * <p>Data: the F&B sample client of every fresh install ({@link RemittanceSampleDataFixtures}),
 * provisioned for SEPA in the test transaction ({@link RemittanceSepaProvisioning#ensureClient}:
 * a collection type and a payment type), its EUR bank account given an IBAN and a second bank
 * account copied from it. Nothing depends on a hand-made tenant. A class of its own: the behavior
 * belongs to the database, which none of the mocked remittance tests runs.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceIban
 * @covers com.etendoerp.go.schemaforge.RemittanceCreditorId
 * @covers com.etendoerp.go.schemaforge.RemittanceSepaOptions
 */
public class RemittanceTriggerIntegrationTest extends WeldBaseTest {

  /** The F&B EUR bank account, given IBAN ES91 2100 0418 4502 0005 1332 here. */
  private static final String ACCOUNT_A_IBAN = "ES9121000418450200051332";
  private static final String ACCOUNT_A_CREDITOR_ID = "ES39001B87654321";
  /** A second bank account created here, IBAN ES84 0182 6450 0002 0150 0191. */
  private static final String ACCOUNT_B_IBAN = "ES8401826450000201500191";
  private static final String ACCOUNT_B_CREDITOR_ID = "ES97000B12345678";

  private static final String TYPED_IBAN = "ES6000491500051234567892";
  private static final String TYPED_CREDITOR_ID = "ES86000A39200019";

  private int sequence;

  private String accountAId;
  private String accountBId;
  /** The provisioned collection type (pay-in) and payment type (pay-out only). */
  private String collectionTypeId;
  private String paymentTypeId;

  @Override
  protected boolean shouldMockServletContext() {
    return true;
  }

  @Before
  public void openTheRequest() {
    setTestAdminContext();
    SessionHandler.getInstance().setDoRollback(true);
    OBContext.setAdminMode(true);
    RemittanceSampleDataFixtures.asSampleAdmin(RemittanceSampleDataFixtures.SPAIN_ORG_ID);
    Client client = RemittanceSampleDataFixtures.client();
    RemittanceSepaProvisioning.ensureClient(client);
    collectionTypeId = type(client, RemittanceSepaProvisioning.SCHEME_CORE);
    paymentTypeId = type(client, RemittanceSepaProvisioning.SCHEME_SCT);
    FIN_FinancialAccount accountA = RemittanceSampleDataFixtures.eurBankAccount(ACCOUNT_A_IBAN);
    accountAId = withCreditorId(accountA, ACCOUNT_A_CREDITOR_ID).getId();
    accountBId = withCreditorId(RemittanceSampleDataFixtures.anotherBankAccount(accountA,
        "SF TRG IT second account", ACCOUNT_B_IBAN), ACCOUNT_B_CREDITOR_ID).getId();
  }

  @After
  public void rollBack() {
    try {
      OBDal.getInstance().rollbackAndClose();
    } finally {
      while (OBContext.getOBContext() != null
          && OBContext.getOBContext().isInAdministratorMode()) {
        OBContext.restorePreviousMode();
      }
    }
  }

  // ---------------------------------------------------------------- insert

  @Test
  public void theTriggerNoLongerCopiesNorDefaultsAnythingOnInsert() {
    Remittance collection = insert(collectionTypeId, accountAId, r -> { });
    Remittance payment = insert(paymentTypeId, accountAId, r -> { });

    for (Remittance remittance : new Remittance[] { collection, payment }) {
      assertNull(remittance.getIBAN());
      assertNull(remittance.getCreditorIdentifier());
      assertNull(remittance.getDirectDebitScheme());
      assertFalse(remittance.isFinanced());
    }
    assertEquals("CORE", RemittanceSepaOptions.scheme(collection));
  }

  @Test
  public void aProvidedIbanCreditorIdAndSchemeAreKeptOnInsert() {
    Remittance remittance = insert(collectionTypeId, accountAId, r -> {
      r.setIBAN(TYPED_IBAN);
      r.setCreditorIdentifier(TYPED_CREDITOR_ID);
      r.setDirectDebitScheme("B2B");
      r.setFinanced(true);
    });

    assertEquals(TYPED_IBAN, remittance.getIBAN());
    assertEquals(TYPED_CREDITOR_ID, remittance.getCreditorIdentifier());
    assertEquals("B2B", remittance.getDirectDebitScheme());
    assertTrue(remittance.isFinanced());
  }

  // ---------------------------------------------------------------- update while not processed

  @Test
  public void changingTheAccountDoesNotOverwriteTheTypedIbanNorCreditorId() {
    Remittance remittance = insert(collectionTypeId, accountAId, r -> {
      r.setIBAN(TYPED_IBAN);
      r.setCreditorIdentifier(TYPED_CREDITOR_ID);
    });

    remittance.setFinancialAccount(OBDal.getInstance().get(FIN_FinancialAccount.class,
        accountBId));
    update(remittance);

    assertEquals("the callout and the GO write path propose the new account's IBAN, not the trigger",
        TYPED_IBAN, remittance.getIBAN());
    assertEquals(TYPED_CREDITOR_ID, remittance.getCreditorIdentifier());
  }

  @Test
  public void changingTheTypeDoesNotOverwriteTheTypedValues() {
    Remittance remittance = insert(collectionTypeId, accountAId, r -> {
      r.setIBAN(TYPED_IBAN);
      r.setCreditorIdentifier(TYPED_CREDITOR_ID);
    });

    remittance.setRemittanceType(OBDal.getInstance().get(RemittanceType.class, paymentTypeId));
    update(remittance);

    assertEquals(TYPED_IBAN, remittance.getIBAN());
    assertEquals("the trigger leaves the identifier as it is", TYPED_CREDITOR_ID,
        remittance.getCreditorIdentifier());
  }

  // ---------------------------------------------------------------- processed

  @Test
  public void aProcessedRemittanceFreezesItsSepaValues() throws Exception {
    Remittance remittance = insertWithSepaValues();
    String id = remittance.getId();
    execute("update rem_remittance set processed = 'Y' where rem_remittance_id = ?", id);

    assertRefused("update rem_remittance set iban = ? where rem_remittance_id = ?",
        TYPED_IBAN, id);
    assertRefused("update rem_remittance set iban = null where rem_remittance_id = ?", id);
    assertRefused("update rem_remittance set creditor_identifier = ? where rem_remittance_id = ?",
        TYPED_CREDITOR_ID, id);
    assertRefused("update rem_remittance set creditor_identifier = null"
        + " where rem_remittance_id = ?", id);
    assertRefused("update rem_remittance set dd_scheme = ? where rem_remittance_id = ?", "B2B", id);
    assertRefused("update rem_remittance set isfinanced = 'Y' where rem_remittance_id = ?", id);
    assertRefused("update rem_remittance set fin_financial_account_id = ?"
        + " where rem_remittance_id = ?", accountBId, id);
    // Nothing else of the SEPA values changed, so an update that leaves them is accepted.
    execute("update rem_remittance set name = ? where rem_remittance_id = ?", "renamed", id);
    OBDal.getInstance().refresh(remittance);
    assertEquals(ACCOUNT_A_IBAN, remittance.getIBAN());
    assertEquals(ACCOUNT_A_CREDITOR_ID, remittance.getCreditorIdentifier());
    assertEquals("CORE", remittance.getDirectDebitScheme());
    assertEquals("renamed", remittance.getName());
  }

  @Test
  public void aProcessedRemittanceRefusesATypeSwitch() throws Exception {
    Remittance remittance = insertWithSepaValues();
    String id = remittance.getId();
    execute("update rem_remittance set processed = 'Y' where rem_remittance_id = ?", id);

    assertRefused("update rem_remittance set rem_remittance_type_id = ?"
        + " where rem_remittance_id = ?", paymentTypeId, id);
    OBDal.getInstance().refresh(remittance);
    assertEquals(collectionTypeId, remittance.getRemittanceType().getId());
    assertEquals(ACCOUNT_A_CREDITOR_ID, remittance.getCreditorIdentifier());
  }

  /**
   * The freeze stops only the SEPA values: the updates REM itself makes on a processed remittance
   * (its status, posting, and the reactivation that sets {@code processed = 'N'}) are accepted, for
   * a remittance carrying the values and for one from before they existed (all of them NULL).
   */
  @Test
  public void aProcessedRemittanceAcceptsTheUpdatesRemMakesWithOrWithoutSepaValues()
      throws Exception {
    Remittance withValues = insertWithSepaValues();
    Remittance legacy = insert(collectionTypeId, accountAId, r -> { });

    for (Remittance remittance : new Remittance[] { withValues, legacy }) {
      String id = remittance.getId();
      execute("update rem_remittance set processed = 'Y', remittance_status = 'PR'"
          + " where rem_remittance_id = ?", id);
      for (String status : new String[] { "SB", "CT", "CP", "PR" }) {
        execute("update rem_remittance set remittance_status = ? where rem_remittance_id = ?",
            status, id);
      }
      execute("update rem_remittance set posted = 'Y' where rem_remittance_id = ?", id);
      execute("update rem_remittance set posted = 'N' where rem_remittance_id = ?", id);
      execute("update rem_remittance set processed = 'N', remittance_status = 'PE'"
          + " where rem_remittance_id = ?", id);

      OBDal.getInstance().refresh(remittance);
      assertFalse(remittance.isProcessed());
      assertEquals("PE", remittance.get(RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS));
    }
    assertEquals(ACCOUNT_A_IBAN, withValues.getIBAN());
    assertEquals(ACCOUNT_A_CREDITOR_ID, withValues.getCreditorIdentifier());
    assertEquals("CORE", withValues.getDirectDebitScheme());
    assertNull("nothing is filled in on the way", legacy.getIBAN());
    assertNull(legacy.getCreditorIdentifier());
    assertNull(legacy.getDirectDebitScheme());
    assertFalse(legacy.isFinanced());
  }

  // ---------------------------------------------------------------- scheme check

  @Test
  public void theSchemeOnlyAcceptsCoreAndB2b() throws Exception {
    Remittance remittance = insert(collectionTypeId, accountAId, r -> { });
    String id = remittance.getId();

    assertRefused("update rem_remittance set dd_scheme = ? where rem_remittance_id = ?",
        "XXX", id, "rem_remittance_ddscheme_chk");
    execute("update rem_remittance set dd_scheme = ? where rem_remittance_id = ?", "B2B", id);
    OBDal.getInstance().refresh(remittance);
    assertEquals("B2B", remittance.getDirectDebitScheme());
  }

  // ---------------------------------------------------------------- fixtures

  /** An unprocessed collection that already carries the account's SEPA values, as GO writes them. */
  private Remittance insertWithSepaValues() {
    return insert(collectionTypeId, accountAId, r -> {
      r.setIBAN(ACCOUNT_A_IBAN);
      r.setCreditorIdentifier(ACCOUNT_A_CREDITOR_ID);
      r.setDirectDebitScheme("CORE");
    });
  }

  private static String type(Client client, String scheme) {
    RemittanceType type = RemittanceSepaProvisioning.findType(client, scheme);
    assertNotNull("ensureClient provisions the " + scheme + " type", type);
    return type.getId();
  }

  private static FIN_FinancialAccount withCreditorId(FIN_FinancialAccount account,
      String creditorId) {
    account.set(RemittanceCreditorId.PROPERTY, creditorId);
    OBDal.getInstance().save(account);
    OBDal.getInstance().flush();
    return account;
  }

  /** An unprocessed remittance without lines, inserted and read back with the trigger's values. */
  private Remittance insert(String typeId, String accountId, Consumer<Remittance> customize) {
    Client client = RemittanceSampleDataFixtures.client();
    FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class, accountId);
    Remittance remittance = OBProvider.getInstance().get(Remittance.class);
    remittance.setClient(client);
    remittance.setOrganization(account.getOrganization());
    remittance.setFinancialAccount(account);
    remittance.setRemittanceType(OBDal.getInstance().get(RemittanceType.class, typeId));
    remittance.setDocumentType(remittanceDocumentType(client));
    remittance.setDocumentNo("SF-TRG-IT-" + (++sequence));
    remittance.setName("SF TRG IT");
    remittance.setTransactionDate(day(LocalDate.now()));
    remittance.setDueDate(day(LocalDate.now().plusDays(10)));
    remittance.setProcessed(false);
    customize.accept(remittance);
    OBDal.getInstance().save(remittance);
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(remittance);
    return remittance;
  }

  private static void update(Remittance remittance) {
    OBDal.getInstance().save(remittance);
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(remittance);
  }

  /** Runs a statement on the session's own connection, so it sees the flushed rows. */
  private static void execute(String sql, String... parameters) {
    OBDal.getInstance().getSession().doWork(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        for (int i = 0; i < parameters.length; i++) {
          statement.setString(i + 1, parameters[i]);
        }
        statement.executeUpdate();
      }
    });
  }

  /**
   * Asserts the database refuses {@code sql}; the last parameter may be the name of the constraint
   * the failure must mention. The transaction is kept usable through a savepoint.
   */
  private static void assertRefused(String sql, String... parameters) {
    boolean[] refused = {false};
    String[] message = {null};
    OBDal.getInstance().getSession().doWork(connection -> {
      Savepoint savepoint = connection.setSavepoint();
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        int placeholders = (int) sql.chars().filter(c -> c == '?').count();
        for (int i = 0; i < placeholders; i++) {
          statement.setString(i + 1, parameters[i]);
        }
        statement.executeUpdate();
      } catch (SQLException e) {
        refused[0] = true;
        message[0] = e.getMessage();
        rollbackTo(connection, savepoint);
      }
    });
    assertTrue("the database accepted: " + sql, refused[0]);
    int placeholders = (int) sql.chars().filter(c -> c == '?').count();
    if (parameters.length > placeholders) {
      assertTrue(message[0], message[0].toLowerCase().contains(parameters[placeholders]));
    } else {
      assertTrue(message[0], message[0].contains("Document processed/posted"));
    }
  }

  private static void rollbackTo(Connection connection, Savepoint savepoint) {
    try {
      connection.rollback(savepoint);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static DocumentType remittanceDocumentType(Client client) {
    DocumentType docType = (DocumentType) OBDal.getInstance().getSession().createQuery(
        "from DocumentType as d where d.client.id = :client and d.documentCategory = 'REM_REM'"
            + " and d.active = true")
        .setParameter("client", client.getId())
        .setMaxResults(1)
        .uniqueResult();
    assertNotNull("the remittance document type", docType);
    return docType;
  }

  private static Date day(LocalDate date) {
    return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
  }
}
