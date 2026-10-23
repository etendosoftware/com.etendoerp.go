/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Specs for {@link StripeListPager}: Stripe's {@code limit} / {@code starting_after} /
 * {@code has_more} loop, its page cap, and how a failed page ends the sweep.
 *
 * <p>The property the billing reconciliation leans on: <b>a sweep that did not finish says
 * so</b>. A 5xx, a 429 or a transport exception on page N must give an incomplete sweep with a
 * failure, never a sweep that looks complete with fewer objects in it.
 *
 * @covers com.etendoerp.go.payment.StripeListPager
 */
public class StripeListPagerTest {

  private final StripeApiClient client = mock(StripeApiClient.class);

  private static StripeResponse page(boolean hasMore, String... ids) {
    StringBuilder data = new StringBuilder();
    for (String id : ids) {
      if (data.length() > 0) {
        data.append(',');
      }
      data.append("{\"id\":\"").append(id).append("\"}");
    }
    return new StripeResponse(200, "{\"object\":\"list\",\"has_more\":" + hasMore
        + ",\"data\":[" + data + "]}");
  }

  private static List<String> idsOf(StripeListPager pager, String path,
      StripeListPager.Sweep[] sweep) {
    List<String> seen = new ArrayList<>();
    sweep[0] = pager.forEach(path, object -> seen.add(object.optString("id")));
    return seen;
  }

  @Test
  public void pagesWithStartingAfterTheLastIdWhileHasMoreIsSet() throws Exception {
    when(client.get("/v1/subscriptions?status=all&limit=2")).thenReturn(page(true, "a", "b"));
    when(client.get("/v1/subscriptions?status=all&limit=2&starting_after=b"))
        .thenReturn(page(true, "c", "d"));
    when(client.get("/v1/subscriptions?status=all&limit=2&starting_after=d"))
        .thenReturn(page(false, "e"));
    StripeListPager.Sweep[] sweep = new StripeListPager.Sweep[1];

    List<String> seen = idsOf(new StripeListPager(client, 2, 10), "/v1/subscriptions?status=all",
        sweep);

    assertEquals(List.of("a", "b", "c", "d", "e"), seen);
    assertTrue(sweep[0].isComplete());
    assertNull(sweep[0].getFailure());
    assertEquals(3, sweep[0].getPages());
    assertEquals(5, sweep[0].getObjects());
  }

  @Test
  public void aPathWithoutAQueryStringGetsOneAndTheIdIsUrlEncoded() throws Exception {
    when(client.get("/v1/subscriptions?limit=1")).thenReturn(page(true, "sub 1&x"));
    when(client.get("/v1/subscriptions?limit=1&starting_after=sub+1%26x"))
        .thenReturn(page(false));
    StripeListPager.Sweep[] sweep = new StripeListPager.Sweep[1];

    List<String> seen = idsOf(new StripeListPager(client, 1, 5), "/v1/subscriptions", sweep);

    assertEquals(List.of("sub 1&x"), seen);
    assertTrue(sweep[0].isComplete());
  }

  @Test
  public void stopsAtThePageCapAndReportsTheSweepAsIncomplete() throws Exception {
    when(client.get(anyString())).thenReturn(page(true, "a"), page(true, "b"), page(true, "c"));
    StripeListPager.Sweep[] sweep = new StripeListPager.Sweep[1];

    List<String> seen = idsOf(new StripeListPager(client, 1, 2), "/v1/subscriptions", sweep);

    assertEquals(List.of("a", "b"), seen);
    verify(client, times(2)).get(anyString());
    assertFalse("A capped sweep must not claim it saw everything", sweep[0].isComplete());
    assertEquals("page cap of 2 reached", sweep[0].getFailure());
    assertEquals(2, sweep[0].getPages());
  }

  @Test
  public void aServerErrorOnALaterPageEndsTheSweepAsIncomplete() throws Exception {
    when(client.get(anyString())).thenReturn(page(true, "a"),
        new StripeResponse(500, "{\"error\":{\"code\":\"api_error\"}}"));
    StripeListPager.Sweep[] sweep = new StripeListPager.Sweep[1];

    List<String> seen = idsOf(new StripeListPager(client, 1, 10), "/v1/subscriptions", sweep);

    assertEquals("The objects of the pages read are still handed over", List.of("a"), seen);
    assertFalse(sweep[0].isComplete());
    assertEquals("provider answered 500 (api_error)", sweep[0].getFailure());
    assertEquals(2, sweep[0].getPages());
  }

  @Test
  public void aRateLimitAnswerIsAFailureNotAnEmptyLastPage() throws Exception {
    when(client.get(anyString())).thenReturn(new StripeResponse(429, ""));
    StripeListPager.Sweep[] sweep = new StripeListPager.Sweep[1];

    List<String> seen = idsOf(new StripeListPager(client, 10, 10), "/v1/subscriptions", sweep);

    assertTrue(seen.isEmpty());
    assertFalse(sweep[0].isComplete());
    assertEquals("provider answered 429 (no code)", sweep[0].getFailure());
  }

  @Test
  public void aTransportExceptionEndsTheSweepAsIncomplete() throws Exception {
    when(client.get(anyString())).thenReturn(page(true, "a"))
        .thenThrow(new StripeTransportException("connection reset"));
    StripeListPager.Sweep[] sweep = new StripeListPager.Sweep[1];

    List<String> seen = idsOf(new StripeListPager(client, 1, 10), "/v1/subscriptions", sweep);

    assertEquals(List.of("a"), seen);
    assertFalse(sweep[0].isComplete());
    assertEquals("provider unreachable: connection reset", sweep[0].getFailure());
  }

  @Test
  public void aPageWithoutADataArrayIsAFailure() throws Exception {
    when(client.get(anyString())).thenReturn(new StripeResponse(200, "{\"has_more\":false}"));
    StripeListPager.Sweep[] sweep = new StripeListPager.Sweep[1];

    idsOf(new StripeListPager(client, 10, 10), "/v1/subscriptions", sweep);

    assertFalse(sweep[0].isComplete());
    assertEquals("page without a data array", sweep[0].getFailure());
  }

  @Test
  public void hasMoreWithoutAnIdToContinueFromStopsInsteadOfLooping() throws Exception {
    when(client.get(anyString())).thenReturn(
        new StripeResponse(200, "{\"has_more\":true,\"data\":[{\"object\":\"subscription\"}]}"));
    StripeListPager.Sweep[] sweep = new StripeListPager.Sweep[1];

    List<String> seen = idsOf(new StripeListPager(client, 10, 10), "/v1/subscriptions", sweep);

    assertEquals(1, seen.size());
    verify(client, times(1)).get(anyString());
    assertFalse(sweep[0].isComplete());
    assertEquals("has_more without an object id to continue from", sweep[0].getFailure());
  }

  @Test
  public void thePageSizeIsClampedToStripesMaximumAndTheCapToAtLeastOnePage() throws Exception {
    when(client.get(anyString())).thenReturn(page(true, "a"));
    ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);

    StripeListPager.Sweep sweep = new StripeListPager(client, 1000, 0)
        .forEach("/v1/subscriptions", object -> { });

    verify(client).get(path.capture());
    assertEquals("/v1/subscriptions?limit=" + StripeListPager.MAX_PAGE_SIZE, path.getValue());
    assertEquals(1, sweep.getPages());
    assertFalse(sweep.isComplete());
  }
}
