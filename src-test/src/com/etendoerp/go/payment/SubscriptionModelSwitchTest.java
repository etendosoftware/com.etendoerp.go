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
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.Order;
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
  private final List<Order> orders = new ArrayList<>();

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
    when(criteria.addOrder(any())).thenAnswer(invocation -> {
      orders.add(invocation.getArgument(0));
      return criteria;
    });
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
  public void aFailedReadStillRestoresTheCallersAdminMode() {
    // The paid onboarding calls this mid-flight; an unbalanced admin mode would leak into every
    // later step of the same request.
    when(obDal.createCriteria(Preference.class)).thenThrow(new IllegalStateException("no session"));

    new SubscriptionModelSwitch().isOn();

    contextMock.verify(() -> OBContext.setAdminMode(true));
    contextMock.verify(OBContext::restorePreviousMode);
  }

  @Test
  public void theNewestMatchingRowWins() {
    // An operator who adds a second row to flip the switch must see it take effect: newest first,
    // and only that one row is read.
    OBCriteria<Preference> criteria = criteriaReturning(rowWithValue("Y"));
    doReturn(criteria).when(obDal).createCriteria(Preference.class);

    new SubscriptionModelSwitch().isOn();

    assertEquals(Preference.PROPERTY_CREATIONDATE + " desc", orders.get(0).toString());
    verify(criteria).setMaxResults(1);
  }

  @Test
  public void rowsCreatedInOneTransactionStillHaveADeterministicWinner() {
    // Two rows inserted in one transaction share now() as their creation date, so the order must
    // not stop there: updated desc, then the id, exactly as the operator verification query sorts.
    OBCriteria<Preference> criteria = criteriaReturning(rowWithValue("Y"));
    doReturn(criteria).when(obDal).createCriteria(Preference.class);

    new SubscriptionModelSwitch().isOn();

    assertEquals(List.of(Preference.PROPERTY_CREATIONDATE + " desc",
        Preference.PROPERTY_UPDATED + " desc", Preference.PROPERTY_ID + " desc"),
        orders.stream().map(Order::toString).collect(java.util.stream.Collectors.toList()));
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
  public void theKeyItReadsIsShippedAsAPropertyConfigurationListValue() throws IOException {
    // A key that is not in AD_REF_LIST under the Property Configuration reference cannot be picked
    // in the Preference window — and a renamed constant would read a key no operator can set, so
    // every environment would stay OFF with nothing reporting it.
    String xml = new String(Files.readAllBytes(moduleFile(AD_REF_LIST_XML)),
        StandardCharsets.UTF_8);
    Matcher records = Pattern.compile("<!--([0-9A-F]{32})--><AD_REF_LIST>(.*?)</AD_REF_LIST>",
        Pattern.DOTALL).matcher(xml);
    List<String> matching = new ArrayList<>();
    while (records.find()) {
      String body = records.group(2);
      if (body.contains("<VALUE><![CDATA[" + SubscriptionModelSwitch.PREFERENCE_PROPERTY
          + "]]></VALUE>")) {
        assertTrue("the value must belong to the Property Configuration reference: " + body,
            body.contains("<AD_REFERENCE_ID><![CDATA[" + PROPERTY_CONFIGURATION_REFERENCE
                + "]]></AD_REFERENCE_ID>"));
        assertTrue("the value must be active: " + body,
            body.contains("<ISACTIVE><![CDATA[Y]]></ISACTIVE>"));
        matching.add(records.group(1));
      }
    }
    assertEquals("exactly one list value for " + SubscriptionModelSwitch.PREFERENCE_PROPERTY,
        List.of(SHIPPED_REF_LIST_ID), matching);
    assertEquals("Y", SubscriptionModelSwitch.ON);
  }

  private static final String AD_REF_LIST_XML = "src-db/database/sourcedata/AD_REF_LIST.xml";
  private static final String MODULE_DIR = "com.etendoerp.go";
  private static final String PROPERTY_CONFIGURATION_REFERENCE =
      "A26BA480E2014707B47257024C3CBFF7";
  private static final String SHIPPED_REF_LIST_ID = "543C9E4BD9DB44A1AB41A575E0949CA2";

  /**
   * Locates a module file from the Etendo root or the module directory, walking up from the
   * working directory and from this class's code-source location (same approach as
   * {@code FiscalDeclSubmittedSnapshotColumnTest}). The Etendo-root form is tried first, because
   * the root also has a core {@code src-db} that a bare relative path would silently resolve to.
   */
  private static Path moduleFile(String relative) {
    List<Path> starts = new ArrayList<>();
    starts.add(Paths.get("").toAbsolutePath());
    try {
      starts.add(Paths.get(SubscriptionModelSwitchTest.class.getProtectionDomain()
          .getCodeSource().getLocation().toURI()));
    } catch (Exception e) {
      // no usable code-source location: the working directory is still tried
    }
    for (Path start : starts) {
      for (Path dir = start; dir != null; dir = dir.getParent()) {
        Path viaRoot = dir.resolve("modules").resolve(MODULE_DIR).resolve(relative);
        if (Files.exists(viaRoot)) {
          return viaRoot;
        }
        if (dir.getFileName() != null && MODULE_DIR.equals(dir.getFileName().toString())
            && Files.exists(dir.resolve(relative))) {
          return dir.resolve(relative);
        }
      }
    }
    fail("Cannot locate " + MODULE_DIR + "/" + relative + " from " + starts);
    return null;
  }

  private static void assertContains(String applied, String expected) {
    assertTrue("expected restriction '" + expected + "' in " + applied,
        applied.contains(expected));
  }
}
