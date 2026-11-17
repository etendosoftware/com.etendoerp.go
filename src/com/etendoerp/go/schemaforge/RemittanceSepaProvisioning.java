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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Sequence;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.gl.GLCategory;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.model.financialmgmt.payment.PaymentExecutionProcess;
import org.openbravo.model.financialmgmt.payment.PaymentExecutionProcessParameter;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceType;
import org.openbravo.module.remittance.RemittanceTypeParameter;

/**
 * ETP-6148 — the per-client data SEPA remittances need, created lazily the first time a Bank
 * financial account of the client is created while the {@code sepa-remittances} flag is on, and
 * the links of that account to the two remittance payment methods ({@link #provisionAccount}).
 *
 * <p><b>What a client gets</b> ({@link #ensureClient}), all in organization {@code 0} and all
 * idempotent — a second call, or a second account, reuses what exists and never overwrites it:
 * <ul>
 *   <li>the {@code FIN_Pay_Exec_Process} «In Remittance» with its two parameters, copied from the
 *       REM reference data ({@code Remittance_Types.xml}); found by its Java class, never by name;</li>
 *   <li>an {@code AD_Sequence} and a {@code C_DocType} with category {@code REM_REM}, copied from
 *       REM's {@code Document_Types.xml}; found by the category;</li>
 *   <li>two {@code FIN_PaymentMethod} — collection by SEPA direct debit (CORE) and payment by SEPA
 *       credit transfer (SCT) — with the flags of the OBSEPA templates
 *       ({@code Core_Direct_Debit_Scheme.xml}, {@code Credit_Transfer_Scheme.xml}): one direction
 *       only, automatic execution through «In Remittance», deferred, no multicurrency;</li>
 *   <li>one {@code REM_Remittance_Type} per method, with no Java class, no posting and no
 *       parameters other than this class's own: the marker {@value #PARAM_SCHEME} (value
 *       {@value #SCHEME_CORE} or {@value #SCHEME_SCT}) and, on the CORE type, the
 *       {@value #PARAM_CREDITOR_BUSINESS_CODE} the SEPA XML reads (default
 *       {@value #DEFAULT_CREDITOR_BUSINESS_CODE}). The team that generates the XML completes the
 *       Java class and the rest of the parameters here.</li>
 * </ul>
 *
 * <p><b>The marker.</b> No column identifies a remittance method or type: a payment method is a
 * remittance method when a remittance type carrying the {@value #PARAM_SCHEME} parameter points at
 * it. That is what the idempotent lookup, {@link RemittanceMethodLinkObserver} and the remittance
 * selectors read. Names are never used to find anything; they only label the rows, in the client's
 * language.
 *
 * <p><b>The links</b> ({@link #ensureAccountLinks}) are created by {@link #createRemittanceLink}, not
 * by {@code FinancialAccountSupport#createLink}: Classic's {@code REMRemittanceProcess#checkPMSetup}
 * refuses to process a remittance unless the account's link allows the remittance's direction, is
 * deferred, and executes automatically through a process — none of which the generic link sets.
 * Links are never removed. They are deactivated once, when the account stops being a Bank account
 * ({@link #deactivateAccountLinks}, called by {@link FinancialAccountRemittanceSupport}), and are
 * not reactivated if it becomes a Bank account again.
 *
 * <p><b>Concurrency.</b> {@link #ensureClient} takes a per-client advisory lock first, so two
 * first-time provisionings of the same client run one after the other and the second reuses what
 * the first committed. A remaining unique conflict (a writer outside this class inserting a
 * document type with the same name at the same instant) is answered 409 by
 * {@link FinancialAccountRemittanceSupport}, never 500.
 *
 * <p>Callers run in admin mode. Plain static utility; the DAL is the only collaborator.
 */
public final class RemittanceSepaProvisioning {

  private static final Logger log = LogManager.getLogger(RemittanceSepaProvisioning.class);

  /** Name of the {@code REM_Remittance_Type_Param} that marks a provisioned remittance type. */
  public static final String PARAM_SCHEME = "ETGO_SEPA_SCHEME";
  /** HQL parameter the {@link #activeRemittanceLinkExists} fragment binds the marker name to. */
  public static final String PARAM_MARKER = "marker";
  /** HQL parameter names shared by the lookups of this class. */
  private static final String Q_CLIENT = "client";
  private static final String Q_ACCOUNT = "account";

  /** Marker value of the SEPA direct debit (collection) type. */
  public static final String SCHEME_CORE = "CORE";
  /** Marker value of the SEPA credit transfer (payment) type. */
  public static final String SCHEME_SCT = "SCT";
  /** Name of the OBSEPAD parameter with the creditor business code (positions 4-6 of the CI). */
  public static final String PARAM_CREDITOR_BUSINESS_CODE = "Creditor_Business_Code";
  /** Default creditor business code; Classic's templates ship {@code ZZZ}, Spanish banks use 000. */
  public static final String DEFAULT_CREDITOR_BUSINESS_CODE = SepaCreditorId.DEFAULT_SUFFIX;

  /**
   * Values of {@code REM_REMITTANCE.REMITTANCE_STATUS} (REM list {@code REM_RemittanceStatus},
   * maintained by REM's own processes through {@code RemittanceStatusUtil}) that keep a remittance
   * open: pending, processed, partially collected, partially paid. Literals on purpose: the REM
   * constants are not a compile-time dependency of this module.
   */
  static final List<String> OPEN_STATUSES = Collections.unmodifiableList(
      Arrays.asList("PE", "PR", "CP", "PP"));

  /** DAL property of the remittance module's {@code REM_REMITTANCE.REMITTANCE_STATUS} (ETP-6148). */
  static final String PROPERTY_REMITTANCE_STATUS = "remittanceStatus";

  static final String IN_REMITTANCE_CLASS =
      "org.openbravo.module.remittance.executionprocess.InRemittance";
  static final String DOC_CATEGORY_REMITTANCE = "REM_REM";
  /** {@code AD_Table_ID} of {@code REM_Remittance}. */
  static final String REMITTANCE_TABLE_ID = "FF8080812E6C2859012E6D0271E1009D";

  private static final String ORG_STAR = "0";
  private static final String EXECUTION_AUTOMATIC = "A";
  private static final String EXECUTION_MANUAL = "M";
  private static final String PARAM_TYPE_IN = "IN";
  private static final String INPUT_CHECK = "CHECK";
  private static final long SEQUENCE_START = 1000000L;
  private static final List<String> SCHEMES = Arrays.asList(SCHEME_CORE, SCHEME_SCT);
  private static final int MAX_NAME_ATTEMPTS = 20;

  private RemittanceSepaProvisioning() {
  }

  // ---------------------------------------------------------------------------
  // Client provisioning
  // ---------------------------------------------------------------------------

  /**
   * Creates whatever of the client's remittance setup is missing, and flushes. Idempotent.
   *
   * @param client the tenant
   */
  public static void ensureClient(Client client) {
    lockClient(client);
    Organization star = OBDal.getInstance().getProxy(Organization.class, ORG_STAR);
    boolean spanish = isSpanish(client);
    PaymentExecutionProcess process = ensureExecutionProcess(client, star);
    ensureDocumentType(client, star, spanish);
    for (String scheme : SCHEMES) {
      ensureType(client, star, scheme, process, spanish);
    }
    OBDal.getInstance().flush();
  }

  /**
   * Serializes the provisioning of one client: a PostgreSQL transaction-level advisory lock keyed on
   * the client, released at commit or rollback. Two first-time enables of the same client (two
   * accounts saved at once) would otherwise both see nothing, both insert, and the second would
   * either duplicate the methods and types or hit {@code c_doctype_name}. With the lock the second
   * waits for the first to commit and its lookups then find the committed rows. {@code
   * com.etendoerp.go} targets PostgreSQL only.
   */
  private static void lockClient(Client client) {
    OBDal.getInstance().getSession()
        .createNativeQuery("select 1 from (select pg_advisory_xact_lock(hashtext(:key))) as l")
        .setParameter("key", "etgo-remittance-provisioning:" + client.getId())
        .getSingleResult();
  }

  private static PaymentExecutionProcess ensureExecutionProcess(Client client, Organization star) {
    OBQuery<PaymentExecutionProcess> query = OBDal.getInstance().createQuery(
        PaymentExecutionProcess.class,
        "as p where p.client.id = :client and p.javaClassName = :className order by p.creationDate");
    query.setNamedParameter(Q_CLIENT, client.getId());
    query.setNamedParameter("className", IN_REMITTANCE_CLASS);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setFilterOnActive(false);
    query.setMaxResult(1);
    PaymentExecutionProcess existing = query.uniqueResult();
    if (existing != null) {
      return existing;
    }
    PaymentExecutionProcess process = OBProvider.getInstance().get(PaymentExecutionProcess.class);
    process.setNewOBObject(true);
    process.setClient(client);
    process.setOrganization(star);
    process.setName("In Remittance");
    process.setDescription("It creates remittances for selected payments.");
    process.setJavaClassName(IN_REMITTANCE_CLASS);
    OBDal.getInstance().save(process);
    saveExecutionParameter(process, "Include in existing remittance or create a new one", 10L, "Y",
        "IsInRemittance");
    saveExecutionParameter(process, "Write off total amount", 20L, "N", "IsWriteOff");
    return process;
  }

  private static void saveExecutionParameter(PaymentExecutionProcess process, String name,
      long sequence, String defaultFlag, String searchKey) {
    PaymentExecutionProcessParameter parameter =
        OBProvider.getInstance().get(PaymentExecutionProcessParameter.class);
    parameter.setNewOBObject(true);
    parameter.setClient(process.getClient());
    parameter.setOrganization(process.getOrganization());
    parameter.setPaymentExecutionProcess(process);
    parameter.setName(name);
    parameter.setSequenceNumber(sequence);
    parameter.setInputType(INPUT_CHECK);
    parameter.setMandatory(true);
    parameter.setDefaultValueForFlag(defaultFlag);
    parameter.setParameterType(PARAM_TYPE_IN);
    parameter.setSearchKey(searchKey);
    OBDal.getInstance().save(parameter);
  }

  private static void ensureDocumentType(Client client, Organization star, boolean spanish) {
    // Only an ACTIVE REM_REM document type counts: an inactive one cannot number remittances, and
    // the AD default of REM_Remittance.C_DocType_ID only offers active ones.
    OBQuery<DocumentType> query = OBDal.getInstance().createQuery(DocumentType.class,
        "as d where d.client.id = :client and d.documentCategory = :category and d.active = true");
    query.setNamedParameter(Q_CLIENT, client.getId());
    query.setNamedParameter("category", DOC_CATEGORY_REMITTANCE);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    if (query.uniqueResult() != null) {
      return;
    }
    GLCategory category = defaultGlCategory(client);
    if (category == null) {
      // C_DocType.GL_Category_ID is mandatory. Without one the remittance window cannot number its
      // documents, but the account setting itself must not fail for it.
      log.warn("No GL category in client {}: the REM_REM document type was not created",
          client.getId());
      return;
    }
    Sequence sequence = OBProvider.getInstance().get(Sequence.class);
    sequence.setNewOBObject(true);
    sequence.setClient(client);
    sequence.setOrganization(star);
    sequence.setName("REM_Remittance Sequence");
    sequence.setDescription("Sequence for remittances");
    sequence.setAutoNumbering(true);
    sequence.setIncrementBy(1L);
    sequence.setStartingNo(SEQUENCE_START);
    sequence.setNextAssignedNumber(SEQUENCE_START);
    OBDal.getInstance().save(sequence);

    String name = freeDocumentTypeName(client, star, spanish ? "Remesa" : "Remittance");
    DocumentType docType = OBProvider.getInstance().get(DocumentType.class);
    docType.setNewOBObject(true);
    docType.setClient(client);
    docType.setOrganization(star);
    docType.setName(name);
    docType.setPrintText(name);
    docType.setDocumentCategory(DOC_CATEGORY_REMITTANCE);
    docType.setSalesTransaction(false);
    docType.setSequencedDocument(true);
    docType.setDocumentSequence(sequence);
    docType.setGLCategory(category);
    docType.setDefault(false);
    docType.setNumberOfCopies(1L);
    docType.setTable(OBDal.getInstance().getProxy(Table.class, REMITTANCE_TABLE_ID));
    docType.setFilterByOrganization(false);
    docType.setExpense(false);
    docType.setReversal(false);
    docType.setReturn(false);
    OBDal.getInstance().save(docType);
  }

  /**
   * {@code base}, or the first of {@code base (SEPA)}, {@code base (SEPA 2)}, … that no document type
   * of the client in organization {@code 0} uses: {@code c_doctype_name} is unique on (client, org,
   * name), and a client may already have a «Remesa» of another category, or an inactive REM_REM.
   */
  static String freeDocumentTypeName(Client client, Organization star, String base) {
    for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
      String candidate = disambiguatedName(base, attempt);
      if (!documentTypeNameTaken(client, star, candidate)) {
        return candidate;
      }
    }
    return base + " (SEPA " + System.currentTimeMillis() + ")";
  }

  static String disambiguatedName(String base, int attempt) {
    if (attempt == 0) {
      return base;
    }
    return attempt == 1 ? base + " (SEPA)" : base + " (SEPA " + attempt + ")";
  }

  private static boolean documentTypeNameTaken(Client client, Organization star, String name) {
    OBQuery<DocumentType> query = OBDal.getInstance().createQuery(DocumentType.class,
        "as d where d.client.id = :client and d.organization.id = :org and d.name = :name");
    query.setNamedParameter(Q_CLIENT, client.getId());
    query.setNamedParameter("org", star.getId());
    query.setNamedParameter("name", name);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setFilterOnActive(false);
    query.setMaxResult(1);
    return query.uniqueResult() != null;
  }

  private static GLCategory defaultGlCategory(Client client) {
    OBQuery<GLCategory> query = OBDal.getInstance().createQuery(GLCategory.class,
        "as g where g.client.id = :client order by g.default desc, g.name");
    query.setNamedParameter(Q_CLIENT, client.getId());
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    return query.uniqueResult();
  }

  private static void ensureType(Client client, Organization star, String scheme,
      PaymentExecutionProcess process, boolean spanish) {
    RemittanceType type = findType(client, scheme);
    if (type == null) {
      FIN_PaymentMethod method = createMethod(client, star, scheme, process, spanish);
      type = OBProvider.getInstance().get(RemittanceType.class);
      type.setNewOBObject(true);
      type.setClient(client);
      type.setOrganization(star);
      type.setName(method.getName());
      type.setRemitForDiscount(false);
      type.setPaymentMethod(method);
      type.setPostingAllowed(false);
      OBDal.getInstance().save(type);
      saveTypeParameter(type, PARAM_SCHEME, scheme);
    }
    if (SCHEME_CORE.equals(scheme) && findTypeParameter(type, PARAM_CREDITOR_BUSINESS_CODE) == null) {
      saveTypeParameter(type, PARAM_CREDITOR_BUSINESS_CODE, DEFAULT_CREDITOR_BUSINESS_CODE);
    }
  }

  private static FIN_PaymentMethod createMethod(Client client, Organization star, String scheme,
      PaymentExecutionProcess process, boolean spanish) {
    boolean collection = SCHEME_CORE.equals(scheme);
    FIN_PaymentMethod method = OBProvider.getInstance().get(FIN_PaymentMethod.class);
    method.setNewOBObject(true);
    method.setClient(client);
    method.setOrganization(star);
    method.setName(methodName(collection, spanish));
    method.setAutomaticReceipt(false);
    method.setAutomaticPayment(false);
    method.setAutomaticDeposit(false);
    method.setAutomaticWithdrawn(false);
    method.setPayinAllow(collection);
    method.setPayoutAllow(!collection);
    method.setPayinExecutionType(collection ? EXECUTION_AUTOMATIC : EXECUTION_MANUAL);
    method.setPayoutExecutionType(collection ? EXECUTION_MANUAL : EXECUTION_AUTOMATIC);
    method.setPayinExecutionProcess(collection ? process : null);
    method.setPayoutExecutionProcess(collection ? null : process);
    method.setPayinDeferred(collection);
    method.setPayoutDeferred(!collection);
    method.setPayinIsMulticurrency(false);
    method.setPayoutIsMulticurrency(false);
    OBDal.getInstance().save(method);
    return method;
  }

  static String methodName(boolean collection, boolean spanish) {
    if (spanish) {
      return collection ? "Remesa de cobro (adeudo SEPA)" : "Remesa de pago (transferencia SEPA)";
    }
    return collection ? "Collection remittance (SEPA direct debit)"
        : "Payment remittance (SEPA credit transfer)";
  }

  private static boolean isSpanish(Client client) {
    String language = client.getLanguage() != null ? client.getLanguage().getLanguage() : null;
    return language == null || language.toLowerCase(Locale.ROOT).startsWith("es");
  }

  private static void saveTypeParameter(RemittanceType type, String name, String value) {
    RemittanceTypeParameter parameter = OBProvider.getInstance().get(RemittanceTypeParameter.class);
    parameter.setNewOBObject(true);
    parameter.setClient(type.getClient());
    parameter.setOrganization(type.getOrganization());
    parameter.setRemittanceType(type);
    parameter.setName(name);
    parameter.setValue(value);
    OBDal.getInstance().save(parameter);
  }

  private static RemittanceTypeParameter findTypeParameter(RemittanceType type, String name) {
    OBQuery<RemittanceTypeParameter> query = OBDal.getInstance().createQuery(
        RemittanceTypeParameter.class, "as p where p.remittanceType.id = :type and p.name = :name");
    query.setNamedParameter("type", type.getId());
    query.setNamedParameter("name", name);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setFilterOnActive(false);
    query.setMaxResult(1);
    return query.uniqueResult();
  }

  // ---------------------------------------------------------------------------
  // Lookups by marker
  // ---------------------------------------------------------------------------

  /**
   * The provisioned remittance type of {@code scheme} in {@code client}, found by the
   * {@value #PARAM_SCHEME} marker.
   *
   * @param client the tenant
   * @param scheme {@value #SCHEME_CORE} or {@value #SCHEME_SCT}
   * @return the type, or {@code null} when the client was never provisioned
   */
  public static RemittanceType findType(Client client, String scheme) {
    OBQuery<RemittanceTypeParameter> query = OBDal.getInstance().createQuery(
        RemittanceTypeParameter.class,
        "as p where p.name = :name and p.value = :scheme and p.remittanceType.client.id = :client "
            + "order by p.creationDate");
    query.setNamedParameter("name", PARAM_SCHEME);
    query.setNamedParameter("scheme", scheme);
    query.setNamedParameter(Q_CLIENT, client.getId());
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setFilterOnActive(false);
    query.setMaxResult(1);
    RemittanceTypeParameter marker = query.uniqueResult();
    return marker != null ? marker.getRemittanceType() : null;
  }

  /**
   * Whether {@code method} is a remittance payment method: a remittance type carrying the
   * {@value #PARAM_SCHEME} marker points at it.
   *
   * @param method the payment method, may be {@code null}
   * @return {@code true} for a provisioned remittance method
   */
  public static boolean isRemittanceMethod(FIN_PaymentMethod method) {
    if (method == null || method.getId() == null) {
      return false;
    }
    OBQuery<RemittanceTypeParameter> query = OBDal.getInstance().createQuery(
        RemittanceTypeParameter.class,
        "as p where p.name = :name and p.remittanceType.paymentMethod.id = :method");
    query.setNamedParameter("name", PARAM_SCHEME);
    query.setNamedParameter("method", method.getId());
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setFilterOnActive(false);
    query.setMaxResult(1);
    return query.uniqueResult() != null;
  }

  /**
   * The creditor business code (positions 4-6 of the SEPA creditor identifier) the client's SEPA
   * files carry: the {@value #PARAM_CREDITOR_BUSINESS_CODE} parameter of the client's provisioned
   * collection type ({@value #SCHEME_CORE}, found by the {@value #PARAM_SCHEME} marker) — the
   * parameter Classic's {@code CoreDirectDebit} reads from the remittance's type when it writes the
   * file. Raw, as stored; the caller applies Classic's normalization and default.
   *
   * @param client the tenant (the account's client)
   * @return the stored value, or {@code null} when the client has no provisioned collection type
   *     or the type has no such parameter
   */
  public static String creditorBusinessCode(Client client) {
    RemittanceType type = client != null ? findType(client, SCHEME_CORE) : null;
    RemittanceTypeParameter parameter = type != null
        ? findTypeParameter(type, PARAM_CREDITOR_BUSINESS_CODE)
        : null;
    return parameter != null ? parameter.getValue() : null;
  }

  /**
   * Whether {@code type} is a provisioned remittance type (carries the {@value #PARAM_SCHEME}
   * marker).
   *
   * @param type the remittance type
   * @return {@code true} when provisioned by this class
   */
  public static boolean isProvisionedType(RemittanceType type) {
    return type != null && findTypeParameter(type, PARAM_SCHEME) != null;
  }

  /**
   * Whether {@code account} has an active link to {@code method}.
   *
   * @param account the financial account
   * @param method  the payment method
   * @return {@code true} when an active {@code FIN_FinAcc_PaymentMethod} joins them
   */
  public static boolean hasActiveLink(FIN_FinancialAccount account, FIN_PaymentMethod method) {
    FinAccPaymentMethod link = findLink(account, method);
    return link != null && Boolean.TRUE.equals(link.isActive());
  }

  /**
   * Open remittances (REM status {@code PE}/{@code PR}/{@code CP}/{@code PP}) of a provisioned type
   * on {@code account}. The remittance module's column is NOT NULL with default {@code PE}; a
   * remittance that existed before the column keeps {@code PE} until one of REM's transitions
   * refreshes it (it is not backfilled), so an old processed remittance counts as open.
   *
   * @param account the financial account
   * @return the count
   */
  public static long countOpenRemittances(FIN_FinancialAccount account) {
    String hql = "select count(r.id) from " + Remittance.ENTITY_NAME + " as r"
        + " where r.financialAccount.id = :account and r.active = true"
        + " and r." + PROPERTY_REMITTANCE_STATUS + " in (:open)"
        + " and exists (select 1 from " + RemittanceTypeParameter.ENTITY_NAME + " as p"
        + "   where p.remittanceType.id = r.remittanceType.id and p.name = :marker)";
    Long count = OBDal.getInstance().getSession().createQuery(hql, Long.class)
        .setParameter(Q_ACCOUNT, account.getId())
        .setParameterList("open", OPEN_STATUSES)
        .setParameter(PARAM_MARKER, PARAM_SCHEME)
        .uniqueResult();
    return count != null ? count : 0L;
  }

  // ---------------------------------------------------------------------------
  // Account links
  // ---------------------------------------------------------------------------

  /**
   * Provisions a Bank account for remittances: the client setup ({@link #ensureClient}) and the
   * account's links to the two remittance methods ({@link #ensureAccountLinks}). Idempotent.
   * Callers decide whether it applies (Bank account, flag on); this method does not check either.
   *
   * @param account the financial account, already flushed
   */
  public static void provisionAccount(FIN_FinancialAccount account) {
    ensureClient(account.getClient());
    ensureAccountLinks(account);
  }

  /**
   * Creates or reactivates the account's links to the two remittance methods, with the remittance
   * setup re-applied, and flushes so {@link RemittanceMethodLinkObserver} refuses here rather than
   * at commit. A client never provisioned (no remittance types) is logged and left unlinked.
   *
   * @param account the financial account
   */
  public static void ensureAccountLinks(FIN_FinancialAccount account) {
    boolean changed = false;
    for (String scheme : SCHEMES) {
      RemittanceType type = findType(account.getClient(), scheme);
      if (type == null || type.getPaymentMethod() == null) {
        log.warn("Remittance type {} missing for client {}: account {} not linked", scheme,
            account.getClient().getId(), account.getId());
        continue;
      }
      FIN_PaymentMethod method = type.getPaymentMethod();
      FinAccPaymentMethod link = findLink(account, method);
      if (link == null) {
        createRemittanceLink(account, method);
      } else {
        applyRemittanceSetup(link, method);
        link.setActive(true);
        OBDal.getInstance().save(link);
      }
      changed = true;
    }
    if (changed) {
      OBDal.getInstance().flush();
    }
  }

  /**
   * Whether {@code account} has an active link to at least one provisioned remittance method — what
   * makes it eligible for a remittance (offered by the account selector), together with being a
   * Bank account; saving a remittance also needs a valid IBAN.
   *
   * @param account the financial account
   * @return {@code true} when an active {@code FIN_FinAcc_PaymentMethod} joins it to a method a
   *     marked remittance type points at
   */
  public static boolean hasRemittanceMethodLink(FIN_FinancialAccount account) {
    if (account == null || StringUtils.isBlank(account.getId())) {
      return false;
    }
    String hql = "select count(a.id) from " + FIN_FinancialAccount.ENTITY_NAME + " as a"
        + " where a.id = :account and " + activeRemittanceLinkExists("a");
    Long count = OBDal.getInstance().getSession().createQuery(hql, Long.class)
        .setParameter(Q_ACCOUNT, account.getId())
        .setParameter(PARAM_MARKER, PARAM_SCHEME)
        .uniqueResult();
    return count != null && count > 0;
  }

  /**
   * The HQL condition behind {@link #hasRemittanceMethodLink}, for a query that filters many
   * accounts at once (the remittance window's account selector): true when the account aliased
   * {@code accountAlias} has an active {@code FIN_FinAcc_PaymentMethod} to a method a marked
   * remittance type points at. The caller binds {@value #PARAM_MARKER} to {@link #PARAM_SCHEME}.
   * One definition, so the selector and the write check cannot disagree on what a remittance
   * account is.
   *
   * @param accountAlias the HQL alias of the {@code FIN_Financial_Account} in the outer query
   * @return the {@code exists (…)} condition
   */
  public static String activeRemittanceLinkExists(String accountAlias) {
    return "exists (select 1 from " + FinAccPaymentMethod.ENTITY_NAME + " as rl, "
        + RemittanceTypeParameter.ENTITY_NAME + " as rp"
        + " where rl.account.id = " + accountAlias + ".id and rl.active = true"
        + " and rp.remittanceType.paymentMethod.id = rl.paymentMethod.id"
        + " and rp.name = :" + PARAM_MARKER + ")";
  }

  /**
   * Deactivates the account's links to the remittance payment methods (the methods a remittance
   * type carrying the {@value #PARAM_SCHEME} marker points at), and flushes. Called when a Bank
   * account becomes Cash or Card with no open remittance: a remittance method on such an account
   * is refused by {@link RemittanceMethodLinkObserver}, and Classic would otherwise keep offering
   * it in the account's payment methods. The links are kept, inactive; becoming a Bank account
   * again does not reactivate them. Every other link of the account is left alone.
   *
   * @param account the financial account
   * @return how many links were deactivated
   */
  public static int deactivateAccountLinks(FIN_FinancialAccount account) {
    if (account == null || StringUtils.isBlank(account.getId())) {
      return 0;
    }
    String hql = "select l from " + FinAccPaymentMethod.ENTITY_NAME + " as l"
        + " where l.account.id = :account and l.active = true"
        + " and exists (select 1 from " + RemittanceTypeParameter.ENTITY_NAME + " as p"
        + "   where p.remittanceType.paymentMethod.id = l.paymentMethod.id and p.name = :"
        + PARAM_MARKER + ")";
    List<FinAccPaymentMethod> links = OBDal.getInstance().getSession()
        .createQuery(hql, FinAccPaymentMethod.class)
        .setParameter(Q_ACCOUNT, account.getId())
        .setParameter(PARAM_MARKER, PARAM_SCHEME)
        .list();
    for (FinAccPaymentMethod link : links) {
      link.setActive(false);
      OBDal.getInstance().save(link);
    }
    if (!links.isEmpty()) {
      OBDal.getInstance().flush();
    }
    return links.size();
  }

  /**
   * Creates the link of {@code account} to a remittance {@code method} that Classic's
   * {@code REMRemittanceProcess#checkPMSetup} accepts: only the method's own direction allowed,
   * executed automatically through the method's execution process, deferred, no multicurrency, not
   * the default method of the account.
   *
   * @param account the financial account
   * @param method  the remittance payment method
   * @return the saved link
   */
  static FinAccPaymentMethod createRemittanceLink(FIN_FinancialAccount account,
      FIN_PaymentMethod method) {
    FinAccPaymentMethod link = OBProvider.getInstance().get(FinAccPaymentMethod.class);
    link.setNewOBObject(true);
    link.setClient(account.getClient());
    link.setOrganization(account.getOrganization());
    link.setAccount(account);
    link.setPaymentMethod(method);
    link.setDefault(false);
    link.setActive(true);
    applyRemittanceSetup(link, method);
    OBDal.getInstance().save(link);
    return link;
  }

  private static void applyRemittanceSetup(FinAccPaymentMethod link, FIN_PaymentMethod method) {
    boolean in = Boolean.TRUE.equals(method.isPayinAllow());
    boolean out = Boolean.TRUE.equals(method.isPayoutAllow());
    link.setPayinAllow(in);
    link.setPayoutAllow(out);
    link.setPayinExecutionType(in ? EXECUTION_AUTOMATIC : EXECUTION_MANUAL);
    link.setPayoutExecutionType(out ? EXECUTION_AUTOMATIC : EXECUTION_MANUAL);
    link.setPayinExecutionProcess(in ? method.getPayinExecutionProcess() : null);
    link.setPayoutExecutionProcess(out ? method.getPayoutExecutionProcess() : null);
    link.setPayinDeferred(in);
    link.setPayoutDeferred(out);
    link.setPayinIsMulticurrency(false);
    link.setPayoutIsMulticurrency(false);
    link.setAutomaticReceipt(false);
    link.setAutomaticPayment(false);
    link.setAutomaticDeposit(false);
    link.setAutomaticWithdrawn(false);
    link.setUponReceiptUse(method.getUponReceiptUse());
    link.setUponDepositUse(method.getUponDepositUse());
    link.setINUponClearingUse(method.getINUponClearingUse());
    link.setUponPaymentUse(method.getUponPaymentUse());
    link.setUponWithdrawalUse(method.getUponWithdrawalUse());
    link.setOUTUponClearingUse(method.getOUTUponClearingUse());
  }

  private static FinAccPaymentMethod findLink(FIN_FinancialAccount account,
      FIN_PaymentMethod method) {
    if (account == null || method == null || StringUtils.isBlank(account.getId())) {
      return null;
    }
    OBQuery<FinAccPaymentMethod> query = OBDal.getInstance().createQuery(FinAccPaymentMethod.class,
        "as l where l.account.id = :account and l.paymentMethod.id = :method");
    query.setNamedParameter(Q_ACCOUNT, account.getId());
    query.setNamedParameter("method", method.getId());
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setFilterOnActive(false);
    query.setMaxResult(1);
    return query.uniqueResult();
  }
}
