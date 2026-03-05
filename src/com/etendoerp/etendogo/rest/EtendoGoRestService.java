package com.etendoerp.etendogo.rest;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;

public class EtendoGoRestService {
  private static final Logger log4j = LogManager.getLogger(EtendoGoRestService.class);
  private static final String APPLICATION_JSON_CHARSET_UTF_8 = "application/json;charset=UTF-8";

  private static EtendoGoRestService instance;

  private EtendoGoRestService() {
  }

  public static EtendoGoRestService getInstance() {
    if (instance == null) {
      instance = new EtendoGoRestService();
    }
    return instance;
  }

  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    sendDummy(response, "GET", getSubPath(request));
  }

  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    sendDummy(response, "POST", getSubPath(request));
  }

  public void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
    sendDummy(response, "PUT", getSubPath(request));
  }

  public void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
    sendDummy(response, "DELETE", getSubPath(request));
  }

  private void sendDummy(HttpServletResponse response, String method, String path) throws IOException {
    try {
      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType(APPLICATION_JSON_CHARSET_UTF_8);
      JSONObject json = new JSONObject();
      json.put("status", "ok");
      json.put("method", method);
      json.put("path", path);
      json.put("message", "EtendoGo dummy response - service is running");
      response.getWriter().write(json.toString());
    } catch (Exception e) {
      log4j.error("Error sending dummy response: {}", e.getMessage(), e);
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  private String getSubPath(HttpServletRequest request) {
    String pathInfo = request.getPathInfo();
    if (pathInfo == null) {
      return "/";
    }
    return pathInfo;
  }
}
