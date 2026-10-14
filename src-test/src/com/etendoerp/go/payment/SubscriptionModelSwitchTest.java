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
package com.etendoerp.go.payment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.criterion.Criterion;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.domain.Preference;

/**
 * Unit tests for {@link SubscriptionModelSwitch}: the environment-wide answer to "does a new paid
 * purchase open an {@code ETGO_SUBSCRIPTION} row?". Absent, {@code N} and a failed read must all
 * mean OFF (the pre-ETP-5046 preference path); only {@code Y} on a System-level, unrestricted row
 * means ON.
 *
 * @covers com.etendoerp.go.payment.SubscriptionModelSwitch
 */
public class SubscriptionModelSwitchTest {

  private MockedStatic<OBDal> dalMock;
  private MockedStatic<OBContext> contextMock;
  private OBDal obDal;
  private final List<Criterion> restrictions = new ArrayList<>();

  @Before
  public void setUp() {
    obDal = mock(OBDal.class);
    dalMock = mockStatic(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(obDal);
    contextMock = mockStatic(OBContext.class);
  }

  @After
  public void tearDown() {
    contextMock.close();
    dalMock.close();
  }

  @SuppressWarnings("unchecked")
  private OBCriteria<Preference> criteriaReturning(Preference row) {
    OBCriteria<Preference> criteria = mock(OBCriteria.class);
    when(criteria.setFilterOnReadableClients(false)).thenReturn(criteria);
    when(criteria.setFilterOnReadableOrganization(false)).thenReturn(criteria);
    when(criteria.add(any())).thenAnswer(invocation -> {
      restrictions.add(invocation.getArgument(0));
      return criteria;
    });
    when(criteria.addOrder(any())).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    when(criteria.uniqueResult()).thenReturn(row);
    return criteria;
  }

  private static Preference rowWithValue(String value) {
    Preference row = mock(Preference.class);
    when(row.getSearchKey()).thenReturn(value);
    return row;
  }

  private void givenRow(Preference row) {
    doReturn(criteriaReturning(row)).when(obDal).createCriteria(Preference.class);
  }

  @Test
  public void anAbsentPreferenceMeansOff() {
    givenRow(null);

    assertFalse(new SubscriptionModelSwitch().isOn());
  }

  @Test
  public void valueNMeansOff() {
    givenRow(rowWithValue("N"));

    assertFalse(new SubscriptionModelSwitch().isOn());
  }

  @Test
  public void valueYMeansOn() {
    givenRow(rowWithValue("Y"));

    assertTrue(new SubscriptionModelSwitch().isOn());
  }

  @Test
  public void surroundingWhitespaceAroundYStillMeansOn() {
    givenRow(rowWithValue(" Y "));

    assertTrue(new SubscriptionModelSwitch().isOn());
  }

  @Test
  public void anyOtherValueMeansOff() {
    for (String value : new String[] { "y", "yes", "true", "1", "", null }) {
      restrictions.clear();
      givenRow(rowWithValue(value));

      assertFalse("'" + value + "' must not switch the model on",
          new SubscriptionModelSwitch().isOn());
    }
  }

  @Test
  public void aFailedReadMeansOffAndNeverThrows() {
    // The purchase path must never fail on the switch: OFF is the pre-ETP-5046 path every
    // environment ran before the switch existed.
    when(obDal.createCriteria(Preference.class)).thenThrow(new IllegalStateException("no session"));

    assertFalse(new SubscriptionModelSwitch().isOn());
  }

  @Test
  public void readsOnlyTheSystemLevelUnrestrictedListPreference() {
    // A row scoped to a tenant, an org, a role, a user or a window is not an environment-wide
    // decision. ETGO_TenantPlan is the cautionary tale: client-0 rows that are ABOUT one tenant
    // through VISIBLEAT_CLIENT_ID, so AD_CLIENT_ID = '0' alone is not enough.
    givenRow(rowWithValue("Y"));

    new SubscriptionModelSwitch().isOn();

    String applied = restrictions.toString();
    assertContains(applied, "propertyList=true");
    assertContains(applied, "property=" + SubscriptionModelSwitch.PREFERENCE_PROPERTY);
    assertContains(applied, "active=true");
    assertContains(applied, "client.id=0");
    assertContains(applied, "visibleAtClient is null or visibleAtClient.id=0");
    assertContains(applied, "visibleAtOrganization is null or visibleAtOrganization.id=0");
    assertContains(applied, "visibleAtRole is null");
    assertContains(applied, "userContact is null");
    assertContains(applied, "window is null");
  }

  @Test
  public void isReadOnEveryCallSoAFlipNeedsNoRestart() {
    OBCriteria<Preference> off = criteriaReturning(rowWithValue("N"));
    OBCriteria<Preference> on = criteriaReturning(rowWithValue("Y"));
    doReturn(off).doReturn(on).when(obDal).createCriteria(Preference.class);
    SubscriptionModelSwitch modelSwitch = new SubscriptionModelSwitch();

    assertFalse(modelSwitch.isOn());
    assertTrue(modelSwitch.isOn());
    verify(obDal, times(2)).createCriteria(Preference.class);
  }

  @Test
  public void theListValueIsTheOneShippedInAdRefList() {
    // Pinned against src-db/database/sourcedata/AD_REF_LIST.xml: a renamed constant would read a
    // key no operator can pick in the Preference window, and every environment would stay OFF.
    assertEquals("ETGO_SubscriptionModel", SubscriptionModelSwitch.PREFERENCE_PROPERTY);
    assertEquals("Y", SubscriptionModelSwitch.ON);
  }

  private static void assertContains(String applied, String expected) {
    assertTrue("expected restriction '" + expected + "' in " + applied,
        applied.contains(expected));
  }
}
