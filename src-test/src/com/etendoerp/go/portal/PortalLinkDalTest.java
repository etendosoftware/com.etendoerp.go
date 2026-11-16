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

package com.etendoerp.go.portal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.structure.DynamicOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Unit tests for {@link PortalLinkDal}, the DAO boundary of {@code etgo_portal_link}.
 *
 * <p>Mockito only: {@code OBDal}, {@code ModelProvider} and {@code OBProvider} are mocked
 * statically and rows are a map-backed {@link BaseOBObject}, so what each method writes or asks
 * the database for can be read back directly.
 *
 * @covers com.etendoerp.go.portal.PortalLinkDal
 */
public class PortalLinkDalTest {

  private static final int RECIPIENTS_WIDTH = 2000;

  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<ModelProvider> modelProviderStatic;
  private MockedStatic<OBProvider> providerStatic;
  private OBDal obDal;
  private Entity entity;

  @Before
  @SuppressWarnings("unchecked")
  public void setUp() {
    obDal = mock(OBDal.class);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

    // The generated class the session maps ETGO_Portal_Link to.
    entity = mock(Entity.class);
    doReturn(MappedRow.class).when(entity).getMappingClass();
    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntity(PortalLinkDal.ENTITY)).thenReturn(entity);
    modelProviderStatic = mockStatic(ModelProvider.class);
    modelProviderStatic.when(ModelProvider::getInstance).thenReturn(modelProvider);
    OBProvider provider = mock(OBProvider.class);
    when(provider.get(MappedRow.class)).thenAnswer(inv -> new MappedRow());
    providerStatic = mockStatic(OBProvider.class);
    providerStatic.when(OBProvider::getInstance).thenReturn(provider);

    OBQuery<Table> tableQuery = mock(OBQuery.class);
    when(tableQuery.list()).thenReturn(Collections.<Table>emptyList());
    when(obDal.createQuery(eq(Table.class), anyString())).thenReturn(tableQuery);
  }

  @After
  public void tearDown() {
    providerStatic.close();
    modelProviderStatic.close();
    obDalStatic.close();
  }

  @Test
  public void insertPendingSavesAnInstanceOfTheMappedClassNeverADynamicObject() {
    // A DynamicOBObject saved under a mapped entity makes the next read of the same row in the
    // session fail with WrongClassException, which rolled back the whole send (link, history and
    // audit) while the customer already had the email.
    BaseOBObject saved = insert(Collections.singletonList("a@customer.test"),
        Collections.<String>emptyList());

    assertTrue("expected the mapped class, got " + saved.getClass(), saved instanceof MappedRow);
    assertFalse(saved instanceof DynamicOBObject);
    verify(obDal).flush();
  }

  @Test
  public void insertPendingStoresToAndCcAndLeavesTheLinkUnsent() {
    BaseOBObject saved = insert(Arrays.asList(" billing@customer.test ", "ap@customer.test"),
        Collections.singletonList("boss@customer.test"));

    assertEquals("billing@customer.test, ap@customer.test",
        saved.get(PortalLinkDal.PROP_RECIPIENTS_TO));
    assertEquals("boss@customer.test", saved.get(PortalLinkDal.PROP_RECIPIENTS_CC));
    assertEquals(Boolean.TRUE, saved.get(PortalLinkDal.PROP_ACTIVE));
    // Only a delivered email starts the lifetime (PortalLinkDal#confirm, from onDelivered).
    assertNull(saved.get(PortalLinkDal.PROP_SENT_AT));
    assertNull(saved.get(PortalLinkDal.PROP_EXPIRES_AT));
  }

  @Test
  public void confirmIsWhatStampsSentAtAndExpiresAt() {
    MappedRow row = new MappedRow();
    Date sentAt = new Date(1_000L);
    Date expiresAt = new Date(2_000L);

    PortalLinkDal.confirm(row, sentAt, expiresAt, " LOG-1 ");

    assertEquals(sentAt, row.get(PortalLinkDal.PROP_SENT_AT));
    assertEquals(expiresAt, row.get(PortalLinkDal.PROP_EXPIRES_AT));
    assertEquals("LOG-1", row.get(PortalLinkDal.PROP_EMAIL_SEND_LOG));
  }

  @Test
  public void recipientsKeepOnlyWholeAddressesWithinTheColumnWidth() {
    // 60-character addresses joined by ", ": 32 of them take 1982 characters, a 33rd would take
    // 2044. It is left out whole rather than cut, and so is everything after it.
    List<String> addresses = new ArrayList<>();
    for (int index = 0; index < 34; index++) {
      addresses.add(address(index));
    }
    addresses.add(33, "x@y.z");

    String stored = (String) insert(addresses, Collections.<String>emptyList())
        .get(PortalLinkDal.PROP_RECIPIENTS_TO);

    assertEquals(String.join(", ", addresses.subList(0, 32)), stored);
    assertTrue(stored.length() <= RECIPIENTS_WIDTH);
    for (String part : stored.split(", ")) {
      assertTrue("every stored address is a whole one: " + part, addresses.contains(part));
    }
  }

  @Test
  public void recipientsWithNoUsableAddressAreStoredAsNull() {
    BaseOBObject saved = insert(Arrays.asList(" ", null), Collections.<String>emptyList());

    assertNull(saved.get(PortalLinkDal.PROP_RECIPIENTS_TO));
    assertNull(saved.get(PortalLinkDal.PROP_RECIPIENTS_CC));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void renewalCountsIncludePendingRowsByCreationButNotDeactivatedOnes() {
    // The cool-down counts a renewal from the moment its row exists, so a second open of the same
    // link while the first email is still on its way counts it too. A failed attempt's row is
    // deactivated, and DAL's active filter (left on) keeps it out.
    OBQuery<BaseOBObject> query = mock(OBQuery.class);
    when(query.count()).thenReturn(2);
    ArgumentCaptor<String> where = ArgumentCaptor.forClass(String.class);
    when(obDal.createQuery(eq(PortalLinkDal.ENTITY), where.capture())).thenReturn(query);
    Date since = new Date(5_000L);

    assertEquals(2, PortalLinkDal.countRenewalsOf("LINK-1", since));
    assertEquals(2, PortalLinkDal.countRenewalsForBusinessPartner("CLIENT-1", "BP-1", since));

    for (String clause : where.getAllValues()) {
      assertTrue(clause, clause.contains("l." + PortalLinkDal.PROP_CREATION_DATE + " >= :since"));
      assertFalse("pending rows must count: " + clause,
          clause.contains(PortalLinkDal.PROP_SENT_AT));
    }
    assertTrue(where.getAllValues().get(0).contains("l." + PortalLinkDal.PROP_PARENT_LINK
        + " = :parentLinkId"));
    verify(query).setNamedParameter("parentLinkId", "LINK-1");
    verify(query).setNamedParameter("clientId", "CLIENT-1");
    verify(query).setNamedParameter("bpartnerId", "BP-1");
    verify(query, atLeastOnce()).setNamedParameter("since", since);
    verify(query, atLeastOnce()).setNamedParameter("origin", PortalLink.ORIGIN_RENEWAL);
    verify(query, never()).setFilterOnActive(false);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void countChildrenOfBindsTheOriginItIsGiven() {
    // The decision landing's daily limit counts DECISION children; the renewal cool-down keeps
    // counting RENEWAL ones through the same query.
    OBQuery<BaseOBObject> query = mock(OBQuery.class);
    when(query.count()).thenReturn(4);
    when(obDal.createQuery(eq(PortalLinkDal.ENTITY), anyString())).thenReturn(query);
    Date since = new Date(5_000L);

    assertEquals(4, PortalLinkDal.countChildrenOf("LINK-1", PortalLink.ORIGIN_DECISION, since));

    verify(query).setNamedParameter("origin", PortalLink.ORIGIN_DECISION);
    verify(query, never()).setNamedParameter("origin", PortalLink.ORIGIN_RENEWAL);
    verify(query).setNamedParameter("parentLinkId", "LINK-1");
    verify(query).setNamedParameter("since", since);
  }

  @Test
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public void lockForRenewalSelectsTheExpiredRowForUpdateWithItsIdBound() {
    // The W1 race fix: a second open of the same link waits on this row lock until the first
    // renewal's transaction commits, and then counts that renewal.
    Session session = mock(Session.class);
    NativeQuery nativeQuery = mock(NativeQuery.class);
    when(obDal.getSession()).thenReturn(session);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    doReturn(nativeQuery).when(session).createNativeQuery(sql.capture());
    doReturn(nativeQuery).when(nativeQuery).setParameter(anyString(), any());

    PortalLinkDal.lockForRenewal("LINK-1");

    assertTrue(sql.getValue(), sql.getValue().endsWith(" FOR UPDATE"));
    assertTrue(sql.getValue(),
        sql.getValue().contains("WHERE etgo_portal_link_id = :linkId"));
    verify(nativeQuery).setParameter("linkId", "LINK-1");
    verify(nativeQuery).list();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void deactivatePendingRenewalsSwitchesOffOnlyUnsentRenewalsOfThatLink() {
    MappedRow first = new MappedRow();
    first.set(PortalLinkDal.PROP_ACTIVE, Boolean.TRUE);
    MappedRow second = new MappedRow();
    second.set(PortalLinkDal.PROP_ACTIVE, Boolean.TRUE);
    OBQuery<BaseOBObject> query = mock(OBQuery.class);
    when(query.list()).thenReturn(Arrays.<BaseOBObject> asList(first, second));
    ArgumentCaptor<String> where = ArgumentCaptor.forClass(String.class);
    when(obDal.createQuery(eq(PortalLinkDal.ENTITY), where.capture())).thenReturn(query);

    PortalLinkDal.deactivatePendingRenewalsOf("LINK-1");

    // Sent renewals are working links and must stay active; only the unsent ones are switched off.
    assertTrue(where.getValue(), where.getValue().contains("l." + PortalLinkDal.PROP_SENT_AT
        + " is null"));
    assertTrue(where.getValue(), where.getValue().contains("l." + PortalLinkDal.PROP_PARENT_LINK
        + " = :parentLinkId"));
    verify(query).setNamedParameter("parentLinkId", "LINK-1");
    verify(query).setNamedParameter("origin", PortalLink.ORIGIN_RENEWAL);
    assertEquals(Boolean.FALSE, first.get(PortalLinkDal.PROP_ACTIVE));
    assertEquals(Boolean.FALSE, second.get(PortalLinkDal.PROP_ACTIVE));
    verify(obDal).save(first);
    verify(obDal).save(second);
    verify(obDal).flush();
  }

  private BaseOBObject insert(List<String> to, List<String> cc) {
    PortalLinkDal.NewLink link = new PortalLinkDal.NewLink();
    link.id = "LINK-1";
    link.client = mock(Client.class);
    link.organization = mock(Organization.class);
    link.businessPartner = mock(BusinessPartner.class);
    link.tokenHash = "hash";
    link.kind = PortalDocumentKind.INVOICE;
    link.recordId = "INV-1";
    link.origin = PortalLink.ORIGIN_SEND;
    link.rootLinkId = link.id;
    link.contractName = "sales-invoice-send";
    link.recipientsTo = to;
    link.recipientsCc = cc;
    link.language = "es_ES";

    PortalLinkDal.insertPending(link);

    ArgumentCaptor<BaseOBObject> saved = ArgumentCaptor.forClass(BaseOBObject.class);
    verify(obDal, atLeastOnce()).save(saved.capture());
    assertEquals("the id is the minted one", "LINK-1", saved.getValue().getId());
    return saved.getValue();
  }

  private static String address(int index) {
    String local = String.format("customer%03d", index);
    StringBuilder padded = new StringBuilder(local);
    while (padded.length() < 60 - "@example.test".length()) {
      padded.append('x');
    }
    return padded + "@example.test";
  }

  /** Map-backed stand-in for the generated {@code ETGO_Portal_Link} class. */
  public static final class MappedRow extends BaseOBObject {
    private static final long serialVersionUID = 1L;
    private final Map<String, Object> values = new HashMap<>();

    @Override
    public String getEntityName() {
      return PortalLinkDal.ENTITY;
    }

    @Override
    public Object getId() {
      return values.get(ID);
    }

    @Override
    public Object get(String propName) {
      return values.get(propName);
    }

    @Override
    public void set(String propName, Object value) {
      values.put(propName, value);
    }
  }
}
