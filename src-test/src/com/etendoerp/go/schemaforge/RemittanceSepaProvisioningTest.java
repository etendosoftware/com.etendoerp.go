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
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.ad.utility.Sequence;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.gl.GLCategory;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.model.financialmgmt.payment.PaymentExecutionProcess;
import org.openbravo.model.financialmgmt.payment.PaymentExecutionProcessParameter;
import org.openbravo.module.remittance.RemittanceType;
import org.openbravo.module.remittance.RemittanceTypeParameter;

/**
 * Unit tests for {@link RemittanceSepaProvisioning}: what a client gets the first time, that a
 * second run creates nothing, that every lookup goes by the {@code ETGO_SEPA_SCHEME} marker (or a
 * Java class / document category) and never by a name, and that the account links satisfy the
 * conditions of Classic's {@code REMRemittanceProcess#checkPMSetup}.
 *
 * <p>The DAL is mocked: {@link OBDal#createQuery} answers a recording {@link OBQuery} whose
 * {@code uniqueResult} is decided by {@link #resolver} from the entity, the HQL and the named
 * parameters; {@link OBProvider#get} hands out a fresh mock per entity, collected in
 * {@link #created} so the test can inspect what was built.
 *
 * <p>{@code checkPMSetup} (REM {@code REMRemittanceProcess#getErrorResult}) refuses a remittance of
 * direction X unless the account link has X allowed, X deferred, an X execution process and X
 * execution type {@code A}; the link tests pin exactly those setters.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceSepaProvisioning
 */
class RemittanceSepaProvisioningTest {

  private static final String CLIENT_ID = "C1";
  private static final String MARKER = RemittanceSepaProvisioning.PARAM_SCHEME;

  /** One {@code createQuery} call: its entity, HQL and the named parameters it was bound with. */
  private static final class Recorded {
    final Class<?> entity;
    final String hql;
    final Map<String, Object> params = new HashMap<>();

    Recorded(Class<?> entity, String hql) {
      this.entity = entity;
      this.hql = hql;
    }
  }

  @FunctionalInterface
  private interface Resolver {
    Object resolve(Recorded query);
  }

  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBProvider> providerStatic;
  private OBDal dal;
  private Session session;
  private Organization star;
  private Table remittanceTable;
  private GLCategory glCategory;
  private Client client;

  private final List<Recorded> queries = new ArrayList<>();
  private final Map<Class<?>, List<Object>> created = new HashMap<>();
  private Resolver resolver;

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    session = mock(Session.class);
    star = mock(Organization.class);
    remittanceTable = mock(Table.class);
    glCategory = mock(GLCategory.class);
    client = clientWithLanguage("es_ES");
    when(dal.getProxy(Organization.class, "0")).thenReturn(star);
    when(dal.getProxy(Table.class, RemittanceSepaProvisioning.REMITTANCE_TABLE_ID))
        .thenReturn(remittanceTable);
    when(dal.getSession()).thenReturn(session);
    // The per-client advisory lock (W4): a native query whose result is ignored.
    when(session.createNativeQuery(anyString())).thenReturn(mock(NativeQuery.class, RETURNS_SELF));
    when(dal.createQuery(any(Class.class), anyString())).thenAnswer(
        inv -> recordingQuery(inv.getArgument(0), inv.getArgument(1)));
    resolver = q -> q.entity == GLCategory.class ? glCategory : null;

    OBProvider provider = mock(OBProvider.class);
    when(provider.get(any(Class.class))).thenAnswer(inv -> {
      Class<?> type = inv.getArgument(0);
      Object instance = mock(type);
      // Only DAL entities are what provisioning builds; the logging stack asks the provider for
      // its own configuration objects (OBConfigFileProvider) when a warning is written.
      if (org.openbravo.base.structure.BaseOBObject.class.isAssignableFrom(type)) {
        created.computeIfAbsent(type, k -> new ArrayList<>()).add(instance);
      }
      return instance;
    });

    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    providerStatic = mockStatic(OBProvider.class);
    providerStatic.when(OBProvider::getInstance).thenReturn(provider);
  }

  @AfterEach
  void tearDown() {
    dalStatic.close();
    providerStatic.close();
    Mockito.framework().clearInlineMocks();
  }

  // ---------------------------------------------------------------- ensureClient: first run

  @Test
  void aFreshClientGetsTheInRemittanceExecutionProcessWithItsTwoParameters() {
    RemittanceSepaProvisioning.ensureClient(client);

    PaymentExecutionProcess process = only(PaymentExecutionProcess.class);
    verify(process).setClient(client);
    verify(process).setOrganization(star);
    verify(process).setJavaClassName(RemittanceSepaProvisioning.IN_REMITTANCE_CLASS);
    verify(dal).save(process);

    List<PaymentExecutionProcessParameter> params = all(PaymentExecutionProcessParameter.class);
    assertEquals(2, params.size());
    verify(params.get(0)).setSearchKey("IsInRemittance");
    verify(params.get(0)).setDefaultValueForFlag("Y");
    verify(params.get(0)).setPaymentExecutionProcess(process);
    verify(params.get(1)).setSearchKey("IsWriteOff");
    verify(params.get(1)).setDefaultValueForFlag("N");
    verify(params.get(1)).setPaymentExecutionProcess(process);
  }

  @Test
  void aFreshClientGetsTheRemittanceDocumentTypeAndItsSequence() {
    RemittanceSepaProvisioning.ensureClient(client);

    Sequence sequence = only(Sequence.class);
    verify(sequence).setClient(client);
    verify(sequence).setAutoNumbering(true);
    DocumentType docType = only(DocumentType.class);
    verify(docType).setDocumentCategory(RemittanceSepaProvisioning.DOC_CATEGORY_REMITTANCE);
    verify(docType).setDocumentSequence(sequence);
    verify(docType).setSequencedDocument(true);
    verify(docType).setGLCategory(glCategory);
    verify(docType).setTable(remittanceTable);
    verify(docType).setOrganization(star);
    verify(docType).setName("Remesa");
    verify(dal).save(sequence);
    verify(dal).save(docType);
  }

  @Test
  void aFreshClientGetsTwoPaymentMethodsWithTheObsepaTemplateFlags() {
    RemittanceSepaProvisioning.ensureClient(client);

    PaymentExecutionProcess process = only(PaymentExecutionProcess.class);
    List<FIN_PaymentMethod> methods = all(FIN_PaymentMethod.class);
    assertEquals(2, methods.size());

    FIN_PaymentMethod core = methods.get(0);
    verify(core).setName("Remesa de cobro (adeudo SEPA)");
    verify(core).setPayinAllow(true);
    verify(core).setPayoutAllow(false);
    verify(core).setPayinExecutionType("A");
    verify(core).setPayoutExecutionType("M");
    verify(core).setPayinExecutionProcess(process);
    verify(core).setPayoutExecutionProcess(null);
    verify(core).setPayinDeferred(true);
    verify(core).setPayoutDeferred(false);
    verify(core).setPayinIsMulticurrency(false);
    verify(core).setPayoutIsMulticurrency(false);
    verify(core).setAutomaticReceipt(false);

    FIN_PaymentMethod sct = methods.get(1);
    verify(sct).setName("Remesa de pago (transferencia SEPA)");
    verify(sct).setPayinAllow(false);
    verify(sct).setPayoutAllow(true);
    verify(sct).setPayinExecutionType("M");
    verify(sct).setPayoutExecutionType("A");
    verify(sct).setPayinExecutionProcess(null);
    verify(sct).setPayoutExecutionProcess(process);
    verify(sct).setPayinDeferred(false);
    verify(sct).setPayoutDeferred(true);
    verify(sct).setPayinIsMulticurrency(false);
    verify(sct).setPayoutIsMulticurrency(false);
  }

  @Test
  void aFreshClientGetsTwoTypesMarkedWithTheirSchemeAndTheCoreCreditorBusinessCode() {
    RemittanceSepaProvisioning.ensureClient(client);

    List<FIN_PaymentMethod> methods = all(FIN_PaymentMethod.class);
    List<RemittanceType> types = all(RemittanceType.class);
    assertEquals(2, types.size());
    verify(types.get(0)).setPaymentMethod(methods.get(0));
    verify(types.get(1)).setPaymentMethod(methods.get(1));
    verify(types.get(0)).setPostingAllowed(false);

    List<RemittanceTypeParameter> params = all(RemittanceTypeParameter.class);
    assertEquals(3, params.size(), "CORE marker, CORE creditor business code, SCT marker");
    verify(params.get(0)).setRemittanceType(types.get(0));
    verify(params.get(0)).setName(MARKER);
    verify(params.get(0)).setValue(RemittanceSepaProvisioning.SCHEME_CORE);
    verify(params.get(1)).setRemittanceType(types.get(0));
    verify(params.get(1)).setName(RemittanceSepaProvisioning.PARAM_CREDITOR_BUSINESS_CODE);
    verify(params.get(1)).setValue("000");
    verify(params.get(2)).setRemittanceType(types.get(1));
    verify(params.get(2)).setName(MARKER);
    verify(params.get(2)).setValue(RemittanceSepaProvisioning.SCHEME_SCT);
    verify(dal).flush();
  }

  @Test
  void aClientInAnotherLanguageGetsEnglishNames() {
    RemittanceSepaProvisioning.ensureClient(clientWithLanguage("en_US"));

    verify(only(DocumentType.class)).setName("Remittance");
    List<FIN_PaymentMethod> methods = all(FIN_PaymentMethod.class);
    verify(methods.get(0)).setName("Collection remittance (SEPA direct debit)");
    verify(methods.get(1)).setName("Payment remittance (SEPA credit transfer)");
  }

  @Test
  void methodNameCoversEveryLanguageAndDirection() {
    assertEquals("Remesa de cobro (adeudo SEPA)",
        RemittanceSepaProvisioning.methodName(true, true));
    assertEquals("Remesa de pago (transferencia SEPA)",
        RemittanceSepaProvisioning.methodName(false, true));
    assertEquals("Collection remittance (SEPA direct debit)",
        RemittanceSepaProvisioning.methodName(true, false));
    assertEquals("Payment remittance (SEPA credit transfer)",
        RemittanceSepaProvisioning.methodName(false, false));
  }

  @Test
  void aClientWithoutAGlCategoryStillGetsItsTypesButNoDocumentType() {
    resolver = q -> null;

    RemittanceSepaProvisioning.ensureClient(client);

    assertTrue(all(DocumentType.class).isEmpty());
    assertTrue(all(Sequence.class).isEmpty());
    assertEquals(2, all(RemittanceType.class).size());
    verify(dal).flush();
  }

  // ---------------------------------------------------------------- ensureClient: idempotency

  @Test
  void aProvisionedClientGetsNothingNew() {
    PaymentExecutionProcess existingProcess = mock(PaymentExecutionProcess.class);
    RemittanceType coreType = mock(RemittanceType.class);
    RemittanceType sctType = mock(RemittanceType.class);
    resolver = q -> {
      if (q.entity == PaymentExecutionProcess.class) {
        return existingProcess;
      }
      if (q.entity == DocumentType.class) {
        return mock(DocumentType.class);
      }
      if (q.entity == RemittanceTypeParameter.class) {
        Object scheme = q.params.get("scheme");
        if (scheme != null) {
          return marker(RemittanceSepaProvisioning.SCHEME_CORE.equals(scheme) ? coreType : sctType);
        }
        return mock(RemittanceTypeParameter.class); // the creditor business code exists
      }
      return null;
    };

    RemittanceSepaProvisioning.ensureClient(client);

    assertTrue(created.isEmpty(), "nothing created on a second run: " + created.keySet());
    verify(dal, never()).save(any());
    verify(dal).flush();
  }

  @Test
  void aCoreTypeMissingItsCreditorBusinessCodeGetsOnlyThatParameter() {
    RemittanceType coreType = mock(RemittanceType.class);
    when(coreType.getId()).thenReturn("CORE-TYPE");
    RemittanceType sctType = mock(RemittanceType.class);
    resolver = q -> {
      if (q.entity == PaymentExecutionProcess.class || q.entity == DocumentType.class) {
        return mock(q.entity);
      }
      if (q.entity == RemittanceTypeParameter.class && q.params.containsKey("scheme")) {
        return marker(RemittanceSepaProvisioning.SCHEME_CORE.equals(q.params.get("scheme"))
            ? coreType : sctType);
      }
      return null;
    };

    RemittanceSepaProvisioning.ensureClient(client);

    assertTrue(all(FIN_PaymentMethod.class).isEmpty());
    assertTrue(all(RemittanceType.class).isEmpty());
    RemittanceTypeParameter param = only(RemittanceTypeParameter.class);
    verify(param).setRemittanceType(coreType);
    verify(param).setName(RemittanceSepaProvisioning.PARAM_CREDITOR_BUSINESS_CODE);
    verify(param).setValue("000");
  }

  @Test
  void everyLookupGoesByMarkerJavaClassOrCategoryAndNeverByName() {
    RemittanceSepaProvisioning.ensureClient(client);

    Recorded process = first(PaymentExecutionProcess.class);
    assertTrue(process.hql.contains("p.javaClassName = :className"), process.hql);
    assertEquals(RemittanceSepaProvisioning.IN_REMITTANCE_CLASS, process.params.get("className"));
    assertEquals(CLIENT_ID, process.params.get("client"));

    Recorded docType = first(DocumentType.class);
    assertEquals(RemittanceSepaProvisioning.DOC_CATEGORY_REMITTANCE, docType.params.get("category"));

    List<Recorded> typeLookups = queries.stream()
        .filter(q -> q.entity == RemittanceTypeParameter.class && q.params.containsKey("scheme"))
        .collect(Collectors.toList());
    assertEquals(2, typeLookups.size());
    for (Recorded lookup : typeLookups) {
      assertEquals(MARKER, lookup.params.get("name"));
      assertEquals(CLIENT_ID, lookup.params.get("client"));
    }
    assertEquals(Arrays.asList("CORE", "SCT"),
        typeLookups.stream().map(q -> q.params.get("scheme")).collect(Collectors.toList()));

    List<String> labels = Arrays.asList("In Remittance", "Remesa", "Remittance",
        RemittanceSepaProvisioning.methodName(true, true),
        RemittanceSepaProvisioning.methodName(false, true));
    for (Recorded query : queries) {
      if (isDocumentTypeNameProbe(query)) {
        // The c_doctype_name collision probe binds the candidate name on purpose: it decides the
        // name a NEW document type gets, it never identifies a provisioned row (that is by category).
        continue;
      }
      for (Object value : query.params.values()) {
        assertFalse(labels.contains(value), "a lookup bound a display name: " + query.hql);
      }
    }
  }

  private static boolean isDocumentTypeNameProbe(Recorded query) {
    return query.entity == DocumentType.class && query.params.containsKey("name")
        && !query.params.containsKey("category");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ensureClientTakesThePerClientAdvisoryLockBeforeItsFirstLookup() {
    NativeQuery<Object> lock = mock(NativeQuery.class, RETURNS_SELF);
    int[] lookupsBeforeLock = { -1 };
    when(session.createNativeQuery(anyString())).thenAnswer(inv -> {
      lookupsBeforeLock[0] = queries.size();
      return lock;
    });

    RemittanceSepaProvisioning.ensureClient(client);

    org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(session).createNativeQuery(sql.capture());
    assertTrue(sql.getValue().contains("pg_advisory_xact_lock(hashtext(:key))"), sql.getValue());
    verify(lock).setParameter("key", "etgo-remittance-provisioning:" + CLIENT_ID);
    verify(lock).getSingleResult();
    assertEquals(0, lookupsBeforeLock[0], "the lock is taken before any lookup");
    assertFalse(queries.isEmpty(), "the lookups run after it");
  }

  @Test
  void theRemittanceDocumentTypeLookupCountsActiveRowsOnly() {
    RemittanceSepaProvisioning.ensureClient(client);

    Recorded lookup = queries.stream()
        .filter(q -> q.entity == DocumentType.class && q.params.containsKey("category"))
        .findFirst().orElseThrow(() -> new AssertionError("no REM_REM lookup"));
    assertTrue(lookup.hql.contains("d.active = true"), lookup.hql);
  }

  @Test
  void disambiguatedNameAppendsSepaThenTheAttemptNumber() {
    assertEquals("Remesa", RemittanceSepaProvisioning.disambiguatedName("Remesa", 0));
    assertEquals("Remesa (SEPA)", RemittanceSepaProvisioning.disambiguatedName("Remesa", 1));
    assertEquals("Remesa (SEPA 2)", RemittanceSepaProvisioning.disambiguatedName("Remesa", 2));
  }

  // ---------------------------------------------------------------- createRemittanceLink

  @Test
  void aCollectionLinkAllowsOnlyPayInExecutedAutomaticallyDeferredAndNotDefault() {
    FIN_FinancialAccount account = account("ACC");
    PaymentExecutionProcess process = mock(PaymentExecutionProcess.class);
    FIN_PaymentMethod method = remittanceMethod(true, process);

    FinAccPaymentMethod link = RemittanceSepaProvisioning.createRemittanceLink(account, method);

    assertSame(only(FinAccPaymentMethod.class), link);
    verify(link).setAccount(account);
    verify(link).setPaymentMethod(method);
    verify(link).setDefault(false);
    verify(link).setActive(true);
    // checkPMSetup, direction IN: allowed, deferred, an execution process, execution type A.
    verify(link).setPayinAllow(true);
    verify(link).setPayinDeferred(true);
    verify(link).setPayinExecutionProcess(process);
    verify(link).setPayinExecutionType("A");
    // The other direction is not allowed and has no process.
    verify(link).setPayoutAllow(false);
    verify(link).setPayoutExecutionType("M");
    verify(link).setPayoutExecutionProcess(null);
    verify(link).setPayoutDeferred(false);
    verify(link).setPayinIsMulticurrency(false);
    verify(link).setPayoutIsMulticurrency(false);
    verify(dal).save(link);
  }

  @Test
  void aPaymentLinkAllowsOnlyPayOutExecutedAutomaticallyAndDeferred() {
    PaymentExecutionProcess process = mock(PaymentExecutionProcess.class);
    FIN_PaymentMethod method = remittanceMethod(false, process);

    FinAccPaymentMethod link = RemittanceSepaProvisioning.createRemittanceLink(account("ACC"),
        method);

    verify(link).setPayoutAllow(true);
    verify(link).setPayoutDeferred(true);
    verify(link).setPayoutExecutionProcess(process);
    verify(link).setPayoutExecutionType("A");
    verify(link).setPayinAllow(false);
    verify(link).setPayinExecutionType("M");
    verify(link).setPayinExecutionProcess(null);
    verify(link).setDefault(false);
  }

  // ---------------------------------------------------------------- ensureAccountLinks

  @Test
  void enablingCreatesAMissingLinkPerProvisionedMethodAndFlushes() {
    FIN_FinancialAccount account = account("ACC");
    FIN_PaymentMethod core = remittanceMethod(true, mock(PaymentExecutionProcess.class));
    FIN_PaymentMethod sct = remittanceMethod(false, mock(PaymentExecutionProcess.class));
    resolveTypes(core, sct, null, null);

    RemittanceSepaProvisioning.ensureAccountLinks(account, true);

    List<FinAccPaymentMethod> links = all(FinAccPaymentMethod.class);
    assertEquals(2, links.size());
    verify(links.get(0)).setPaymentMethod(core);
    verify(links.get(1)).setPaymentMethod(sct);
    verify(dal).flush();
  }

  @Test
  void enablingReactivatesAnInactiveLinkAndReappliesTheSetup() {
    FIN_PaymentMethod core = remittanceMethod(true, mock(PaymentExecutionProcess.class));
    FIN_PaymentMethod sct = remittanceMethod(false, mock(PaymentExecutionProcess.class));
    FinAccPaymentMethod coreLink = link(false);
    FinAccPaymentMethod sctLink = link(false);
    resolveTypes(core, sct, coreLink, sctLink);

    RemittanceSepaProvisioning.ensureAccountLinks(account("ACC"), true);

    assertTrue(all(FinAccPaymentMethod.class).isEmpty(), "an existing link is reused");
    verify(coreLink).setActive(true);
    verify(coreLink).setPayinAllow(true);
    verify(coreLink).setPayinExecutionType("A");
    verify(coreLink).setPayinDeferred(true);
    verify(sctLink).setActive(true);
    verify(sctLink).setPayoutAllow(true);
    verify(dal).save(coreLink);
    verify(dal).save(sctLink);
    verify(dal).flush();
  }

  @Test
  void disablingSetsAnActiveLinkInactiveAndNeverDeletesIt() {
    FIN_PaymentMethod core = remittanceMethod(true, mock(PaymentExecutionProcess.class));
    FIN_PaymentMethod sct = remittanceMethod(false, mock(PaymentExecutionProcess.class));
    FinAccPaymentMethod coreLink = link(true);
    FinAccPaymentMethod sctLink = link(false);
    resolveTypes(core, sct, coreLink, sctLink);

    RemittanceSepaProvisioning.ensureAccountLinks(account("ACC"), false);

    verify(coreLink).setActive(false);
    verify(dal).save(coreLink);
    verify(sctLink, never()).setActive(any());
    verify(dal, never()).save(sctLink);
    verify(dal, never()).remove(any());
    verify(dal).flush();
  }

  @Test
  void disablingAnAccountWithoutLinksTouchesNothing() {
    resolveTypes(remittanceMethod(true, null), remittanceMethod(false, null), null, null);

    RemittanceSepaProvisioning.ensureAccountLinks(account("ACC"), false);

    verify(dal, never()).save(any());
    verify(dal, never()).flush();
  }

  @Test
  void enablingAnUnprovisionedClientLinksNothing() {
    RemittanceSepaProvisioning.ensureAccountLinks(account("ACC"), true);

    assertTrue(created.isEmpty(), "created: " + created.keySet());
    verify(dal, never()).flush();
  }

  // ---------------------------------------------------------------- lookups

  @Test
  void isRemittanceMethodAsksForTheMarkerOnATypePointingAtTheMethod() {
    FIN_PaymentMethod method = mock(FIN_PaymentMethod.class);
    when(method.getId()).thenReturn("PM1");
    RemittanceTypeParameter found = mock(RemittanceTypeParameter.class);
    resolver = q -> found;

    assertTrue(RemittanceSepaProvisioning.isRemittanceMethod(method));

    Recorded query = first(RemittanceTypeParameter.class);
    assertEquals(MARKER, query.params.get("name"));
    assertEquals("PM1", query.params.get("method"));
    assertTrue(query.hql.contains("p.remittanceType.paymentMethod.id = :method"), query.hql);
  }

  @Test
  void isRemittanceMethodIsFalseWithoutAMarkerOrAMethod() {
    FIN_PaymentMethod method = mock(FIN_PaymentMethod.class);
    when(method.getId()).thenReturn("PM1");
    resolver = q -> null;

    assertFalse(RemittanceSepaProvisioning.isRemittanceMethod(method));
    assertFalse(RemittanceSepaProvisioning.isRemittanceMethod(null));
    assertFalse(RemittanceSepaProvisioning.isRemittanceMethod(mock(FIN_PaymentMethod.class)));
  }

  @Test
  void isProvisionedTypeReadsTheMarkerParameter() {
    RemittanceType type = mock(RemittanceType.class);
    when(type.getId()).thenReturn("T1");
    RemittanceTypeParameter found = mock(RemittanceTypeParameter.class);
    resolver = q -> MARKER.equals(q.params.get("name")) && "T1".equals(q.params.get("type"))
        ? found : null;

    assertTrue(RemittanceSepaProvisioning.isProvisionedType(type));
    assertFalse(RemittanceSepaProvisioning.isProvisionedType(null));
    resolver = q -> null;
    assertFalse(RemittanceSepaProvisioning.isProvisionedType(type));
  }

  @Test
  void hasActiveLinkIsTrueOnlyForAnActiveLink() {
    FIN_FinancialAccount account = account("ACC");
    FIN_PaymentMethod method = mock(FIN_PaymentMethod.class);
    when(method.getId()).thenReturn("PM1");

    FinAccPaymentMethod active = link(true);
    FinAccPaymentMethod inactive = link(false);
    resolver = q -> active;
    assertTrue(RemittanceSepaProvisioning.hasActiveLink(account, method));
    resolver = q -> inactive;
    assertFalse(RemittanceSepaProvisioning.hasActiveLink(account, method));
    resolver = q -> null;
    assertFalse(RemittanceSepaProvisioning.hasActiveLink(account, method));
    assertFalse(RemittanceSepaProvisioning.hasActiveLink(account(null), method));
  }

  @Test
  @SuppressWarnings("unchecked")
  void countOpenRemittancesCountsTheOpenStatusesOfMarkedTypesOnTheAccount() {
    Query<Long> query = mock(Query.class, RETURNS_SELF);
    when(query.uniqueResult()).thenReturn(4L);
    when(session.createQuery(anyString(), eq(Long.class))).thenReturn(query);

    assertEquals(4L, RemittanceSepaProvisioning.countOpenRemittances(account("ACC")));

    verify(query).setParameter("account", "ACC");
    verify(query).setParameterList("open", Arrays.asList("PE", "PR", "CP", "PP"));
    verify(query).setParameter("marker", MARKER);
    org.mockito.ArgumentCaptor<String> hql = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(session).createQuery(hql.capture(), eq(Long.class));
    // The status is REM's own REM_REMITTANCE.REMITTANCE_STATUS, never null: the column defaults to
    // PE and REM's transitions keep it current through RemittanceStatusUtil. Remittances that
    // existed before the column are not backfilled and stay PE (open). So the open set alone
    // decides: no null/unprocessed branch.
    assertTrue(hql.getValue().contains("r.remittanceStatus in (:open)"), hql.getValue());
    assertFalse(hql.getValue().contains("processed"), hql.getValue());
    assertFalse(hql.getValue().contains("eTGOStatus"), hql.getValue());
  }

  @Test
  @SuppressWarnings("unchecked")
  void countOpenRemittancesAnswersZeroForANullCount() {
    Query<Long> query = mock(Query.class, RETURNS_SELF);
    when(query.uniqueResult()).thenReturn(null);
    when(session.createQuery(anyString(), eq(Long.class))).thenReturn(query);

    assertEquals(0L, RemittanceSepaProvisioning.countOpenRemittances(account("ACC")));
  }

  // ---------------------------------------------------------------- helpers

  @SuppressWarnings("unchecked")
  private OBQuery<org.openbravo.base.structure.BaseOBObject> recordingQuery(Class<?> entity, String hql) {
    Recorded recorded = new Recorded(entity, hql);
    queries.add(recorded);
    OBQuery<org.openbravo.base.structure.BaseOBObject> query = mock(OBQuery.class, RETURNS_SELF);
    when(query.setNamedParameter(anyString(), any())).thenAnswer(inv -> {
      recorded.params.put(inv.getArgument(0), inv.getArgument(1));
      return query;
    });
    when(query.uniqueResult()).thenAnswer(inv -> resolver.resolve(recorded));
    return query;
  }

  /**
   * Answers the two marker lookups with types pointing at {@code core} / {@code sct}, and the link
   * lookups with {@code coreLink} / {@code sctLink} by method id.
   */
  private void resolveTypes(FIN_PaymentMethod core, FIN_PaymentMethod sct,
      FinAccPaymentMethod coreLink, FinAccPaymentMethod sctLink) {
    when(core.getId()).thenReturn("PM-CORE");
    when(sct.getId()).thenReturn("PM-SCT");
    RemittanceType coreType = mock(RemittanceType.class);
    when(coreType.getPaymentMethod()).thenReturn(core);
    RemittanceType sctType = mock(RemittanceType.class);
    when(sctType.getPaymentMethod()).thenReturn(sct);
    RemittanceTypeParameter coreMarker = marker(coreType);
    RemittanceTypeParameter sctMarker = marker(sctType);
    resolver = q -> {
      if (q.entity == RemittanceTypeParameter.class) {
        return RemittanceSepaProvisioning.SCHEME_CORE.equals(q.params.get("scheme")) ? coreMarker
            : sctMarker;
      }
      if (q.entity == FinAccPaymentMethod.class) {
        return "PM-CORE".equals(q.params.get("method")) ? coreLink : sctLink;
      }
      return null;
    };
  }

  private static RemittanceTypeParameter marker(RemittanceType type) {
    RemittanceTypeParameter marker = mock(RemittanceTypeParameter.class);
    when(marker.getRemittanceType()).thenReturn(type);
    return marker;
  }

  private static FinAccPaymentMethod link(boolean active) {
    FinAccPaymentMethod link = mock(FinAccPaymentMethod.class);
    when(link.isActive()).thenReturn(active);
    return link;
  }

  private static FIN_PaymentMethod remittanceMethod(boolean collection,
      PaymentExecutionProcess process) {
    FIN_PaymentMethod method = mock(FIN_PaymentMethod.class);
    when(method.isPayinAllow()).thenReturn(collection);
    when(method.isPayoutAllow()).thenReturn(!collection);
    when(method.getPayinExecutionProcess()).thenReturn(collection ? process : null);
    when(method.getPayoutExecutionProcess()).thenReturn(collection ? null : process);
    return method;
  }

  private FIN_FinancialAccount account(String id) {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(id);
    when(account.getClient()).thenReturn(client);
    when(account.getOrganization()).thenReturn(mock(Organization.class));
    return account;
  }

  private static Client clientWithLanguage(String code) {
    Language language = mock(Language.class);
    when(language.getLanguage()).thenReturn(code);
    Client c = mock(Client.class);
    when(c.getId()).thenReturn(CLIENT_ID);
    when(c.getLanguage()).thenReturn(language);
    return c;
  }

  @SuppressWarnings("unchecked")
  private <T> List<T> all(Class<T> type) {
    return (List<T>) (List<?>) created.getOrDefault(type, new ArrayList<>());
  }

  private <T> T only(Class<T> type) {
    List<T> instances = all(type);
    assertEquals(1, instances.size(), "instances of " + type.getSimpleName());
    return instances.get(0);
  }

  private Recorded first(Class<?> entity) {
    return queries.stream().filter(q -> q.entity == entity).findFirst()
        .orElseThrow(() -> new AssertionError("no query on " + entity.getSimpleName()));
  }
}
