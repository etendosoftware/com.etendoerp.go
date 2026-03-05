package com.etendoerp.go.rest;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Interface for all entity request handlers.
 * Implementations are registered in HandlerRegistry and dispatched by EtendoGoRestService.
 */
public interface RequestHandler {
  String getBasePath();
  void doGet(HttpServletRequest request, HttpServletResponse response, String subPath) throws IOException;
  void doPost(HttpServletRequest request, HttpServletResponse response, String subPath) throws IOException;
  void doPut(HttpServletRequest request, HttpServletResponse response, String subPath) throws IOException;
  void doDelete(HttpServletRequest request, HttpServletResponse response, String subPath) throws IOException;
}
