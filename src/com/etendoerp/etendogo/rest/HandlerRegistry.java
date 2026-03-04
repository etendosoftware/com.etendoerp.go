package com.etendoerp.etendogo.rest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.etendoerp.etendogo.rest.handlers.CreateBusinessPartnerHandler;
import com.etendoerp.etendogo.rest.handlers.DeleteBusinessPartnerHandler;
import com.etendoerp.etendogo.rest.handlers.GetBusinessPartnerHandler;
import com.etendoerp.etendogo.rest.handlers.HealthHandler;
import com.etendoerp.etendogo.rest.handlers.ListBusinessPartnersHandler;
import com.etendoerp.etendogo.rest.handlers.ScanProductHandler;
import com.etendoerp.etendogo.rest.handlers.UpdateBusinessPartnerHandler;

public class HandlerRegistry {
  private static HandlerRegistry instance;

  private final Map<String, EndpointHandler> handlers = new HashMap<>();
  private final List<EndpointHandler> allHandlers = new ArrayList<>();

  private HandlerRegistry() {
    register(new HealthHandler());
    register(new ScanProductHandler());
    register(new ListBusinessPartnersHandler());
    register(new CreateBusinessPartnerHandler());
    register(new GetBusinessPartnerHandler());
    register(new UpdateBusinessPartnerHandler());
    register(new DeleteBusinessPartnerHandler());
  }

  public static synchronized HandlerRegistry getInstance() {
    if (instance == null) {
      instance = new HandlerRegistry();
    }
    return instance;
  }

  private void register(EndpointHandler handler) {
    String key = handler.getMethod().toUpperCase() + ":" + handler.getPath();
    handlers.put(key, handler);
    allHandlers.add(handler);
  }

  public Optional<EndpointHandler> findHandler(String method, String path) {
    // Try exact match first
    String key = method.toUpperCase() + ":" + path;
    EndpointHandler handler = handlers.get(key);
    if (handler != null) {
      return Optional.of(handler);
    }

    // Try pattern matching for paths with parameters like {id}
    String upperMethod = method.toUpperCase();
    for (EndpointHandler h : allHandlers) {
      if (h.getMethod().equalsIgnoreCase(upperMethod) && matchesPattern(h.getPath(), path)) {
        return Optional.of(h);
      }
    }
    return Optional.empty();
  }

  private boolean matchesPattern(String pattern, String path) {
    String[] patternSegments = pattern.split("/");
    String[] pathSegments = path.split("/");

    if (patternSegments.length != pathSegments.length) {
      return false;
    }

    for (int i = 0; i < patternSegments.length; i++) {
      String seg = patternSegments[i];
      if (seg.startsWith("{") && seg.endsWith("}")) {
        continue; // wildcard segment, matches anything
      }
      if (!seg.equals(pathSegments[i])) {
        return false;
      }
    }
    return true;
  }

  public List<EndpointHandler> getAllHandlers() {
    return Collections.unmodifiableList(allHandlers);
  }
}
