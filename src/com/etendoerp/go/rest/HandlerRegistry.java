package com.etendoerp.go.rest;

import javax.annotation.Generated;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of all request handlers, keyed by base path.
 * Auto-generated — regenerate when adding new windows.
 */
@Generated(value = "schema-forge", date = "2026-03-06T18:53:17.519Z")
public class HandlerRegistry {

  private static final HandlerRegistry INSTANCE = new HandlerRegistry();
  private final Map<String, RequestHandler> handlers = new ConcurrentHashMap<>();

  private HandlerRegistry() {
    register(new com.etendoerp.go.salesorder.handler.HeaderHandler());
    register(new com.etendoerp.go.salesorder.handler.LinesHandler());
    register(new com.etendoerp.go.salesorder.handler.SalesOrderSelectorHandler());
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
