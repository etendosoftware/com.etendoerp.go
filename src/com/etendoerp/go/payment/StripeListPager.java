/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * ETP-5048 — walks a Stripe list endpoint page by page.
 *
 * <p>Stripe pages with {@code limit} and {@code starting_after=<id of the last object seen>}, and
 * says whether more remain with {@code has_more}. This class owns that loop, on top of the one-method
 * {@link StripeApiClient#get}, so a caller only sees objects.
 *
 * <p><b>A sweep that did not finish says so.</b> {@link Sweep#isComplete()} is false when a page
 * failed (transport failure, a non-2xx answer, a malformed body) or the page cap was reached. A
 * caller that infers anything from an object's <em>absence</em> — the billing reconciliation does
 * — must only do so from a complete sweep, or one provider hiccup would read as "everything after
 * page three is gone".
 */
public class StripeListPager {

  private static final Logger log = LogManager.getLogger(StripeListPager.class);

  /** Stripe's maximum page size. */
  public static final int MAX_PAGE_SIZE = 100;

  private final StripeApiClient client;
  private final int pageSize;
  private final int maxPages;

  /**
   * Creates a pager.
   *
   * @param client the provider client
   * @param pageSize objects per page, clamped to 1…{@value #MAX_PAGE_SIZE}
   * @param maxPages pages read at most in one sweep; at least 1
   */
  public StripeListPager(StripeApiClient client, int pageSize, int maxPages) {
    this.client = client;
    this.pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));
    this.maxPages = Math.max(1, maxPages);
  }

  /** What one sweep saw. */
  public static final class Sweep {
    private final int objects;
    private final int pages;
    private final boolean complete;
    private final String failure;

    Sweep(int objects, int pages, boolean complete, String failure) {
      this.objects = objects;
      this.pages = pages;
      this.complete = complete;
      this.failure = failure;
    }

    /** @return how many objects were handed to the consumer */
    public int getObjects() {
      return objects;
    }

    /** @return how many pages were read */
    public int getPages() {
      return pages;
    }

    /** @return whether the provider answered {@code has_more = false} on the last page read */
    public boolean isComplete() {
      return complete;
    }

    /** @return why the sweep stopped early, or null when it is complete */
    public String getFailure() {
      return failure;
    }
  }

  /**
   * Reads every page of {@code path} and hands each object to {@code consumer}, in provider order.
   *
   * @param path list path, e.g. {@code /v1/subscriptions}, without {@code limit} or
   *     {@code starting_after}; it may carry its own query string
   * @param consumer receives each element of {@code data}
   * @return what the sweep saw
   */
  public Sweep forEach(String path, Consumer<JSONObject> consumer) {
    String startingAfter = null;
    int objects = 0;
    for (int page = 1; page <= maxPages; page++) {
      JSONObject body;
      try {
        StripeResponse response = client.get(pagePath(path, startingAfter));
        if (!response.isSuccess()) {
          return stopped(objects, page, "provider answered " + response.status() + " ("
              + StringUtils.defaultString(response.errorCode(), "no code") + ")", path);
        }
        body = response.json();
      } catch (StripeTransportException e) {
        return stopped(objects, page, "provider unreachable: " + e.getMessage(), path);
      } catch (RuntimeException e) {
        return stopped(objects, page, "unreadable page: " + e.getClass().getSimpleName(), path);
      }
      JSONArray data = body == null ? null : body.optJSONArray("data");
      if (data == null) {
        return stopped(objects, page, "page without a data array", path);
      }
      String lastId = null;
      for (int i = 0; i < data.length(); i++) {
        JSONObject object = data.optJSONObject(i);
        if (object == null) {
          continue;
        }
        consumer.accept(object);
        objects++;
        lastId = StringUtils.defaultIfBlank(object.optString("id", ""), lastId);
      }
      if (!body.optBoolean("has_more", false)) {
        return new Sweep(objects, page, true, null);
      }
      if (lastId == null) {
        return stopped(objects, page, "has_more without an object id to continue from", path);
      }
      startingAfter = lastId;
    }
    return stopped(objects, maxPages, "page cap of " + maxPages + " reached", path);
  }

  private static Sweep stopped(int objects, int pages, String reason, String path) {
    log.warn("Stripe list sweep of {} stopped after {} page(s): {}", path, pages, reason);
    return new Sweep(objects, pages, false, reason);
  }

  private String pagePath(String path, String startingAfter) {
    StringBuilder url = new StringBuilder(path).append(path.contains("?") ? '&' : '?')
        .append("limit=").append(pageSize);
    if (startingAfter != null) {
      url.append("&starting_after=")
          .append(URLEncoder.encode(startingAfter, StandardCharsets.UTF_8));
    }
    return url.toString();
  }
}
