package com.etendoerp.go.rest;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.etendoerp.go.salesorder.handler.COrderHandler;
import com.etendoerp.go.salesorder.handler.COrderLineHandler;
import com.etendoerp.go.salesorder.handler.COrderLineTaxHandler;
import com.etendoerp.go.salesorder.handler.MSolReservedStockVHandler;
import com.etendoerp.go.salesorder.handler.COrderlineServicerelationHandler;
import com.etendoerp.go.salesorder.handler.COrderlineProductrelationHandler;
import com.etendoerp.go.salesorder.handler.COrderDiscountHandler;
import com.etendoerp.go.salesorder.handler.COrderTaxHandler;
import com.etendoerp.go.salesorder.handler.FinPaymentSchedOrdVHandler;
import com.etendoerp.go.salesorder.handler.FinPaymentDetailVHandler;
import com.etendoerp.go.salesorder.handler.COrderReplacementHandler;

/**
 * Registry of all request handlers, keyed by base path.
 * Auto-generated — regenerate when adding new windows.
 */
public class HandlerRegistry {

  private static final HandlerRegistry INSTANCE = new HandlerRegistry();
  private final Map<String, RequestHandler> handlers = new ConcurrentHashMap<>();

  private HandlerRegistry() {
    register(new COrderHandler());
    register(new COrderLineHandler());
    register(new COrderLineTaxHandler());
    register(new MSolReservedStockVHandler());
    register(new COrderlineServicerelationHandler());
    register(new COrderlineProductrelationHandler());
    register(new COrderDiscountHandler());
    register(new COrderTaxHandler());
    register(new FinPaymentSchedOrdVHandler());
    register(new FinPaymentDetailVHandler());
    register(new COrderReplacementHandler());
  }

  public static HandlerRegistry getInstance() {
    return INSTANCE;
  }

  private void register(RequestHandler handler) {
    handlers.put(handler.getBasePath(), handler);
  }

  public RequestHandler findHandler(String path) {
    if (path == null) return null;
    // Match longest prefix: /salesorder/cOrder/123 → find handler for /salesorder/cOrder
    String remaining = path;
    while (remaining.length() > 0) {
      RequestHandler handler = handlers.get(remaining);
      if (handler != null) return handler;
      int lastSlash = remaining.lastIndexOf('/');
      if (lastSlash <= 0) break;
      remaining = remaining.substring(0, lastSlash);
    }
    return null;
  }

  public String getSubPath(String fullPath, RequestHandler handler) {
    String base = handler.getBasePath();
    if (fullPath.length() <= base.length()) return "";
    return fullPath.substring(base.length());
  }
}
