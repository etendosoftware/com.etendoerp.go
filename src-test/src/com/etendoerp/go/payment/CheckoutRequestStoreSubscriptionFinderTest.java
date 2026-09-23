/* Etendo License. */
package com.etendoerp.go.payment;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

import com.etendoerp.go.schemaforge.data.CheckoutRequest;

/**
 * Unit tests for {@link CheckoutRequestStore#findSubscriptionForAccount(String, String)}, the one
 * purchase both billing endpoints act on.
 *
 * <p>The DAL is mocked, so these pin the query's shape rather than executing it: the integration
 * harness currently fails to boot locally ({@code MappingNotFoundException} on
 * {@code Table.hbm.xml}), and a real newest-row / blank-skip / case-insensitive-email check belongs
 * in {@code CheckoutRequestStoreIntegrationTest} once it runs.
 */
public class CheckoutRequestStoreSubscriptionFinderTest {

  private final CheckoutRequestStore store = new CheckoutRequestStore();

  @Test
  public void blankArgumentsFindNothingWithoutQuerying() {
    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);

      assertNull(store.findSubscriptionForAccount(null, "owner@example.test"));
      assertNull(store.findSubscriptionForAccount(" ", "owner@example.test"));
      assertNull(store.findSubscriptionForAccount("account-1", null));
      assertNull(store.findSubscriptionForAccount("account-1", ""));
      ctx.verify(OBContext::restorePreviousMode, times(4));
    }
    verify(dal, never()).createQuery(eq(CheckoutRequest.class), anyString());
  }

  @Test
  public void queriesTheNewestAccountPurchaseCarryingBothProviderIds() {
    OBDal dal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<CheckoutRequest> query = mock(OBQuery.class);
    CheckoutRequest newest = mock(CheckoutRequest.class);
    when(dal.createQuery(eq(CheckoutRequest.class), anyString())).thenReturn(query);
    when(query.uniqueResult()).thenReturn(newest);
    ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);

    CheckoutRequest found;
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      found = store.findSubscriptionForAccount(" account-1 ", " Owner@Example.test ");
      ctx.verify(OBContext::restorePreviousMode);
    }

    assertSame(newest, found);
    verify(dal).createQuery(eq(CheckoutRequest.class), hql.capture());
    String where = hql.getValue();
    assertTrue(where, where.contains("cr.etendoGoAccount.id = :accountId"));
    assertTrue(where, where.contains("lower(cr.accountEmail) = lower(:accountEmail)"));
    assertTrue(where, where.contains("cr.stripeSubscription is not null"));
    assertTrue(where, where.contains("length(trim(cr.stripeSubscription)) > 0"));
    assertTrue(where, where.contains("cr.stripeCustomer is not null"));
    assertTrue(where, where.contains("length(trim(cr.stripeCustomer)) > 0"));
    assertTrue(where, where.trim().endsWith("order by cr.creationDate desc"));
    verify(query).setNamedParameter("accountId", "account-1");
    verify(query).setNamedParameter("accountEmail", "Owner@Example.test");
    verify(query).setMaxResult(1);
    verify(query).setFilterOnReadableClients(false);
    verify(query).setFilterOnReadableOrganization(false);
  }

  @Test
  public void noMatchingPurchaseFindsNothing() {
    OBDal dal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<CheckoutRequest> query = mock(OBQuery.class);
    when(dal.createQuery(eq(CheckoutRequest.class), anyString())).thenReturn(query);
    when(query.uniqueResult()).thenReturn(null);

    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      assertNull(store.findSubscriptionForAccount("account-1", "owner@example.test"));
    }
  }
}
