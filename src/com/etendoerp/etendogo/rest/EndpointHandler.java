package com.etendoerp.etendogo.rest;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public interface EndpointHandler {
  String getMethod();
  String getPath();
  String getOperationId();
  void handle(HttpServletRequest req, HttpServletResponse res) throws IOException;
}
