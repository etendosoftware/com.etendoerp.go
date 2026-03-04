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
    dispatch("GET", request, response);
  }

  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    dispatch("POST", request, response);
  }

  public void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
    dispatch("PUT", request, response);
  }

  public void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
    dispatch("DELETE", request, response);
  }

  private void dispatch(String method, HttpServletRequest request, HttpServletResponse response) throws IOException {
    String subPath = getSubPath(request);
    try {
      HandlerRegistry.getInstance().findHandler(method, subPath)
          .ifPresentOrElse(
              handler -> {
                try {
                  handler.handle(request, response);
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              },
              () -> {
                try {
                  response.sendError(HttpServletResponse.SC_NOT_FOUND);
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              }
          );
    } catch (RuntimeException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      log4j.error("Error during {} request: {}", method, cause.getMessage(), cause);
      try {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(APPLICATION_JSON_CHARSET_UTF_8);
        JSONObject errorJson = new JSONObject();
        errorJson.put("error", cause.getMessage());
        response.getWriter().write(errorJson.toString());
      } catch (Exception ioException) {
        log4j.error(ioException);
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ioException.getMessage());
      }
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
