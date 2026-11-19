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

/**
 * Real-database acceptance of the REM trigger {@code REM_REMITTANCE_TRG} as ETP-6247 extended it
 * (the SEPA IBAN, creditor identifier, direct debit scheme and financed flag of a remittance),
 * inside the test transaction, rolled back after each test. GO reads those copies
 * ({@link RemittanceIban}, {@link RemittanceCreditorId}, {@link RemittanceSepaOptions}), so this
 * pins what they find there:
 * <ul>
 *   <li>defaults: a collection (a pay-in type) is {@code CORE}, a payment has no scheme, nobody is
 *       financed;</li>
 *   <li>insert: the account's IBAN and, for a collection only, its creditor identifier are copied
 *       when not provided; a provided value is respected;</li>
 *   <li>update while not processed: the copies are made again when the account changes or the type
 *       switches between collection and payment, and not on any other type change (an IBAN typed
 *       by hand survives a collection-to-collection change);</li>
 *   <li>once processed: the IBAN, the identifier, the scheme and the financed flag are frozen;</li>
 *   <li>the scheme is only {@code CORE} or {@code B2B}.</li>
 * </ul>
 *
 * <p>Data: GOClient's remittance types (several collection types, one SEPA payment type) and two of
 * its bank accounts with an IBAN. A class of its own: the behavior belongs to the database, which
 * none of the mocked remittance tests runs.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceIban
 * @covers com.etendoerp.go.schemaforge.RemittanceCreditorId
 * @covers com.etendoerp.go.schemaforge.RemittanceSepaOptions
 */
public class RemittanceTriggerIntegrationTest extends WeldBaseTest {

  private static final String GO_CLIENT_ID = "802509E12436405C86BA1FD5B1DF508C";
  private static final String GO_ORG_ID = "61849243BE89460EB70866880A545D50";
  private static final String GO_ADMIN_ROLE_ID = "9B8D736190724807AB256DC95F20EC5E";

  /** «Cuenta Remesa Tests», IBAN ES91 2100 0418 4502 0005 1332. */
  private static final String ACCOUNT_A_ID = "1728F0AC26EF478287FE8F9210052AC8";
  private static final String ACCOUNT_A_IBAN = "ES9121000418450200051332";
  private static final String ACCOUNT_A_CREDITOR_ID = "ES39001B87654321";
  /** The BBVA sandbox account, IBAN ES84 0182 6450 0002 0150 0191. */
  private static final String ACCOUNT_B_ID = "23341D8933D9408E9BB048B2930C2CFB";
  private static final String ACCOUNT_B_IBAN = "ES8401826450000201500191";
  private static final String ACCOUNT_B_CREDITOR_ID = "ES97000B12345678";

  /** Two collection types (pay-in) and a payment type (pay-out only). */
  private static final String COLLECTION_TYPE_ID = "A0AA4E4F8FA94E6CB3C4E9F306979447";
  private static final String OTHER_COLLECTION_TYPE_ID = "19EC581FA02748CEB1C7953075815482";
  private static final String PAYMENT_TYPE_ID = "F3A00FA0887246D88F53B164957D985D";

  private static final String TYPED_IBAN = "ES6000491500051234567892";
  private static final String TYPED_CREDITOR_ID = "ES86000A39200019";

  private int sequence;

  @Override
  protected boolean shouldMockServletContext() {
    return true;
  }

  @Before
  public void openTheRequest() {
    setTestAdminContext();
    SessionHandler.getInstance().setDoRollback(true);
    OBContext.setAdminMode(true);
    OBContext.setOBContext("100", GO_ADMIN_ROLE_ID, GO_CLIENT_ID, GO_ORG_ID);
    OBContext.setAdminMode(true);
    account(ACCOUNT_A_ID, ACCOUNT_A_CREDITOR_ID);
    account(ACCOUNT_B_ID, ACCOUNT_B_CREDITOR_ID);
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
  public void aCollectionGetsCoreNotFinancedAndTheAccountsIbanAndCreditorId() {
    Remittance remittance = insert(COLLECTION_TYPE_ID, ACCOUNT_A_ID, r -> { });

    assertEquals("CORE", remittance.getDirectDebitScheme());
    assertFalse(remittance.isFinanced());
    assertEquals(ACCOUNT_A_IBAN, remittance.getIBAN());
    assertEquals(ACCOUNT_A_CREDITOR_ID, remittance.getCreditorIdentifier());
  }

  @Test
  public void aPaymentHasNoSchemeNorCreditorIdButTheAccountsIban() {
    Remittance remittance = insert(PAYMENT_TYPE_ID, ACCOUNT_A_ID, r -> { });

    assertNull(remittance.getDirectDebitScheme());
    assertNull("a payment has no creditor identifier", remittance.getCreditorIdentifier());
    assertFalse(remittance.isFinanced());
    assertEquals(ACCOUNT_A_IBAN, remittance.getIBAN());
  }

  @Test
  public void aProvidedIbanCreditorIdAndSchemeAreRespectedOnInsert() {
    Remittance remittance = insert(COLLECTION_TYPE_ID, ACCOUNT_A_ID, r -> {
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

  @Test
  public void anAccountWithoutCreditorIdLeavesTheCopyEmpty() {
    FIN_FinancialAccount account = account(ACCOUNT_A_ID, null);

    Remittance remittance = insert(COLLECTION_TYPE_ID, account.getId(), r -> { });

    assertNull(remittance.getCreditorIdentifier());
    assertEquals(ACCOUNT_A_IBAN, remittance.getIBAN());
  }

  // ---------------------------------------------------------------- update while not processed

  @Test
  public void changingTheAccountRecopiesTheIbanAndTheCreditorIdEvenOverTypedOnes() {
    Remittance remittance = insert(COLLECTION_TYPE_ID, ACCOUNT_A_ID, r -> {
      r.setIBAN(TYPED_IBAN);
      r.setCreditorIdentifier(TYPED_CREDITOR_ID);
    });

    remittance.setFinancialAccount(OBDal.getInstance().get(FIN_FinancialAccount.class,
        ACCOUNT_B_ID));
    update(remittance);

    assertEquals(ACCOUNT_B_IBAN, remittance.getIBAN());
    assertEquals(ACCOUNT_B_CREDITOR_ID, remittance.getCreditorIdentifier());
  }

  @Test
  public void aCollectionToCollectionTypeChangeKeepsTheTypedValues() {
    Remittance remittance = insert(COLLECTION_TYPE_ID, ACCOUNT_A_ID, r -> {
      r.setIBAN(TYPED_IBAN);
      r.setCreditorIdentifier(TYPED_CREDITOR_ID);
    });

    remittance.setRemittanceType(OBDal.getInstance().get(RemittanceType.class,
        OTHER_COLLECTION_TYPE_ID));
    update(remittance);

    assertEquals("an IBAN typed by hand survives", TYPED_IBAN, remittance.getIBAN());
    assertEquals(TYPED_CREDITOR_ID, remittance.getCreditorIdentifier());
  }

  @Test
  public void switchingFromCollectionToPaymentRecopiesTheIbanAndDropsTheCreditorId() {
    Remittance remittance = insert(COLLECTION_TYPE_ID, ACCOUNT_A_ID, r -> {
      r.setIBAN(TYPED_IBAN);
      r.setCreditorIdentifier(TYPED_CREDITOR_ID);
    });

    remittance.setRemittanceType(OBDal.getInstance().get(RemittanceType.class, PAYMENT_TYPE_ID));
    update(remittance);

    assertEquals("the account's IBAN replaces the typed one", ACCOUNT_A_IBAN,
        remittance.getIBAN());
    assertNull("a payment has no creditor identifier", remittance.getCreditorIdentifier());
  }

  @Test
  public void switchingFromPaymentToCollectionCopiesTheAccountsCreditorId() {
    Remittance remittance = insert(PAYMENT_TYPE_ID, ACCOUNT_A_ID, r -> r.setIBAN(TYPED_IBAN));
    assertNull(remittance.getCreditorIdentifier());

    remittance.setRemittanceType(OBDal.getInstance().get(RemittanceType.class,
        COLLECTION_TYPE_ID));
    update(remittance);

    assertEquals(ACCOUNT_A_CREDITOR_ID, remittance.getCreditorIdentifier());
    assertEquals(ACCOUNT_A_IBAN, remittance.getIBAN());
  }

  // ---------------------------------------------------------------- processed

  @Test
  public void aProcessedRemittanceFreezesItsSepaValues() throws Exception {
    Remittance remittance = insert(COLLECTION_TYPE_ID, ACCOUNT_A_ID, r -> { });
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
        + " where rem_remittance_id = ?", ACCOUNT_B_ID, id);
    // Nothing else of the SEPA values changed, so an update that leaves them is accepted.
    execute("update rem_remittance set name = ? where rem_remittance_id = ?", "renamed", id);
    OBDal.getInstance().refresh(remittance);
    assertEquals(ACCOUNT_A_IBAN, remittance.getIBAN());
    assertEquals(ACCOUNT_A_CREDITOR_ID, remittance.getCreditorIdentifier());
    assertEquals("CORE", remittance.getDirectDebitScheme());
    assertEquals("renamed", remittance.getName());
  }

  @Test
  public void aProcessedRemittanceIsNotRecopiedWhenTheTypeSwitches() throws Exception {
    Remittance remittance = insert(COLLECTION_TYPE_ID, ACCOUNT_A_ID, r -> { });
    String id = remittance.getId();
    execute("update rem_remittance set processed = 'Y' where rem_remittance_id = ?", id);

    assertRefused("update rem_remittance set rem_remittance_type_id = ?"
        + " where rem_remittance_id = ?", PAYMENT_TYPE_ID, id);
    OBDal.getInstance().refresh(remittance);
    assertEquals(COLLECTION_TYPE_ID, remittance.getRemittanceType().getId());
    assertEquals(ACCOUNT_A_CREDITOR_ID, remittance.getCreditorIdentifier());
  }

  // ---------------------------------------------------------------- scheme check

  @Test
  public void theSchemeOnlyAcceptsCoreAndB2b() throws Exception {
    Remittance remittance = insert(COLLECTION_TYPE_ID, ACCOUNT_A_ID, r -> { });
    String id = remittance.getId();

    assertRefused("update rem_remittance set dd_scheme = ? where rem_remittance_id = ?",
        "XXX", id, "rem_remittance_ddscheme_chk");
    execute("update rem_remittance set dd_scheme = ? where rem_remittance_id = ?", "B2B", id);
    OBDal.getInstance().refresh(remittance);
    assertEquals("B2B", remittance.getDirectDebitScheme());
  }

  // ---------------------------------------------------------------- fixtures

  private static FIN_FinancialAccount account(String id, String creditorId) {
    FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class, id);
    assertNotNull("the bank account " + id, account);
    account.set(RemittanceCreditorId.PROPERTY, creditorId);
    OBDal.getInstance().save(account);
    OBDal.getInstance().flush();
    return account;
  }

  /** An unprocessed remittance without lines, inserted and read back with the trigger's values. */
  private Remittance insert(String typeId, String accountId, Consumer<Remittance> customize) {
    Client client = OBDal.getInstance().get(Client.class, GO_CLIENT_ID);
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
