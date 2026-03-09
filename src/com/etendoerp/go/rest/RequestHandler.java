package com.etendoerp.go.rest;

import javax.annotation.Generated;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Interface for all entity request handlers.
 * Implementations are registered in HandlerRegistry and dispatched by EtendoGoRestService.
 */
@Generated(value = "schema-forge", date = "2026-03-06T18:53:17.519Z")
public interface RequestHandler {
  String getBasePath();
  void doGet(HttpServletRequest request, HttpServletResponse response, String subPath) throws IOException;
  void doPost(HttpServletRequest request, HttpServletResponse response, String subPath) throws IOException;
  void doPut(HttpServletRequest request, HttpServletResponse response, String subPath) throws IOException;
  void doDelete(HttpServletRequest request, HttpServletResponse response, String subPath) throws IOException;
}
