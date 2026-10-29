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

package com.etendoerp.go.usage.quota;

import static com.etendoerp.go.usage.quota.QuotaTestFixtures.local;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.midnight;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.schemaforge.data.BillingResource;
import com.etendoerp.go.schemaforge.data.UsageDaily;
import com.etendoerp.go.usage.UsageFreshness;
import com.etendoerp.go.usage.UsageResourceValidator;

/**
 * <b>REQUIRES A DATABASE.</b> The consumption window of a quota is the subscription period — the
 * 17th to the 17th here, not a calendar month — and it is half-open: the start day is counted, the
 * end day is not. The mocks in {@code DalConsumptionReaderTest} show which bounds are bound to the
 * query; only the database can show which rows those bounds actually let through, so this suite
 * writes rows on both sides of each bound and reads them back through both sources.
 *
 * <p>Nothing is committed: the fixture is flushed into the test's own transaction, read in that
 * same transaction, and rolled back in {@link #rollBack()}.
 *
 * <p>The window is counted in whole local days (the stored aggregate has one row per day, and the
 * live count uses the same day bounds so the two sources agree).
 *
 * @covers com.etendoerp.go.usage.quota.DalConsumptionReader
 */
public class DalConsumptionReaderPeriodWindowIntegrationTest extends OBBaseTest {

  /** Marks this suite's rows; no underscore, it goes into an HQL {@code like}. */
  private static final String PREFIX = "ETP5051QWIN";
  private static final AtomicLong SEQUENCE = new AtomicLong();
  private static final String SYSTEM = "0";

  /** The 17th to the 17th, starting mid-morning. */
  private static final Date PERIOD_START = local(2026, 10, 17, 9, 30);
  private static final Date PERIOD_END = local(2026, 11, 17, 9, 30);
  private static final QuotaPeriod PERIOD = QuotaPeriod.current(PERIOD_START, PERIOD_END, false,
      local(2026, 10, 24, 12, 0));

  private final DalConsumptionReader reader = new DalConsumptionReader(mock(UsageFreshness.class));

  @Before
  public void enterAdminMode() {
    setSystemAdministratorContext();
    OBContext.setAdminMode(false);
  }

  @After
  public void rollBack() {
    try {
      OBDal.getInstance().rollbackAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void theStoredAggregateCountsTheStartDayAndNotTheEndDay() {
    BillingResource resource = givenResource();
    givenStoredDay(resource, TEST_CLIENT_ID, midnight(2026, 10, 16), 1000L);
    givenStoredDay(resource, TEST_CLIENT_ID, midnight(2026, 10, 17), 1L);
    givenStoredDay(resource, TEST_CLIENT_ID, midnight(2026, 10, 31), 10L);
    givenStoredDay(resource, TEST_CLIENT_ID, midnight(2026, 11, 1), 20L);
    givenStoredDay(resource, TEST_CLIENT_ID, midnight(2026, 11, 16), 100L);
    givenStoredDay(resource, TEST_CLIENT_ID, midnight(2026, 11, 17), 5000L);
    // Another tenant's day inside the window must not be read either.
    givenStoredDay(resource, SYSTEM, midnight(2026, 10, 20), 7000L);
    OBDal.getInstance().flush();

    QuotaDefinition sum = definition(resource, UsageAggregation.SUM, ConsumptionSource.AGGREGATE);
    QuotaDefinition max = definition(resource, UsageAggregation.MAX, ConsumptionSource.AGGREGATE);
    QuotaDefinition last = definition(resource, UsageAggregation.LAST,
        ConsumptionSource.AGGREGATE);

    // 1 + 10 + 20 + 100: the 16th of October and the 17th of November are outside the period,
    // and the 1st of November (a calendar-month boundary) is inside it.
    assertEquals(131L, reader.read(sum, TEST_CLIENT_ID, PERIOD));
    assertEquals(100L, reader.read(max, TEST_CLIENT_ID, PERIOD));
    assertEquals(100L, reader.read(last, TEST_CLIENT_ID, PERIOD));
  }

  @Test
  public void theLiveCountCountsTheStartDayAndNotTheEndDay() {
    BillingResource resource = givenResource();
    givenUser(local(2026, 10, 16, 23, 59));
    givenUser(midnight(2026, 10, 17));
    givenUser(local(2026, 10, 31, 23, 0));
    givenUser(midnight(2026, 11, 1));
    givenUser(local(2026, 11, 16, 23, 59));
    givenUser(midnight(2026, 11, 17));
    OBDal.getInstance().flush();

    QuotaDefinition live = definition(resource, UsageAggregation.SUM, ConsumptionSource.LIVE);

    assertEquals(4L, reader.read(live, TEST_CLIENT_ID, PERIOD));
  }

  private BillingResource givenResource() {
    BillingResource resource = OBProvider.getInstance().get(BillingResource.class);
    resource.setNewOBObject(true);
    resource.setClient(OBDal.getInstance().get(Client.class, SYSTEM));
    resource.setOrganization(OBDal.getInstance().get(Organization.class, SYSTEM));
    resource.setSearchKey(PREFIX + SEQUENCE.incrementAndGet() + "_" + System.nanoTime());
    resource.setName(resource.getSearchKey());
    resource.setUnitLabel("users");
    resource.setActive(true);
    resource.setCountingMode(UsageResourceValidator.MODE_DECLARATIVE);
    resource.setCountedEntity("ADUser");
    resource.setDateProperty("lastPasswordUpdate");
    resource.setHQLRestriction("e.username like '" + PREFIX + "%'");
    OBDal.getInstance().save(resource);
    OBDal.getInstance().flush();
    return resource;
  }

  private void givenStoredDay(BillingResource resource, String clientId, Date day,
      long quantity) {
    UsageDaily row = OBProvider.getInstance().get(UsageDaily.class);
    row.setNewOBObject(true);
    row.setClient(OBDal.getInstance().get(Client.class, SYSTEM));
    row.setOrganization(OBDal.getInstance().get(Organization.class, SYSTEM));
    row.setActive(true);
    row.setTenantClient(OBDal.getInstance().get(Client.class, clientId));
    row.setBillingResource(resource);
    row.setUsageDay(day);
    row.setQuantity(quantity);
    row.setComputedAt(new Date());
    row.setSettled(false);
    OBDal.getInstance().save(row);
  }

  private void givenUser(Date lastPasswordUpdate) {
    User user = OBProvider.getInstance().get(User.class);
    user.setNewOBObject(true);
    user.setClient(OBDal.getInstance().get(Client.class, TEST_CLIENT_ID));
    user.setOrganization(OBDal.getInstance().get(Organization.class, SYSTEM));
    user.setUsername(PREFIX + Long.toString(SEQUENCE.incrementAndGet(), 36)
        + Long.toString(System.nanoTime(), 36));
    user.setName(user.getUsername());
    user.setLastPasswordUpdate(lastPasswordUpdate);
    OBDal.getInstance().save(user);
  }

  private static QuotaDefinition definition(BillingResource resource,
      UsageAggregation aggregation, ConsumptionSource source) {
    return QuotaDefinition.builder()
        .resourceId(resource.getId())
        .resourceKey(resource.getSearchKey())
        .countingMode(resource.getCountingMode())
        .countedEntity(resource.getCountedEntity())
        .dateProperty(resource.getDateProperty())
        .hqlRestriction(resource.getHQLRestriction())
        .aggregation(aggregation)
        .source(source)
        .enforcementMode("block")
        .includedQty(1L)
        .build();
  }
}
