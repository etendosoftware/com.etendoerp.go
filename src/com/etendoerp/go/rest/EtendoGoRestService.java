package com.etendoerp.go.rest;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;

/**
 * Routes incoming requests to the appropriate {@link RequestHandler}
 * via the {@link HandlerRegistry}.
 */
public class EtendoGoRestService {
  private static final Logger log4j = LogManager.getLogger(EtendoGoRestService.class);
  private static final String APPLICATION_JSON_CHARSET_UTF_8 = "application/json;charset=UTF-8";

  private static EtendoGoRestService instance;
  private final HandlerRegistry registry = HandlerRegistry.getInstance();

  private EtendoGoRestService() {
  }

  public static EtendoGoRestService getInstance() {
    if (instance == null) {
      instance = new EtendoGoRestService();
    }
    return instance;
  }

  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    dispatch(request, response, "GET");
  }

  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    dispatch(request, response, "POST");
  }

  public void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
    dispatch(request, response, "PUT");
  }

  public void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
    dispatch(request, response, "DELETE");
  }

  private void dispatch(HttpServletRequest request, HttpServletResponse response, String method) throws IOException {
    String pathInfo = request.getPathInfo();
    if (pathInfo == null) {
      pathInfo = "/";
    }

    RequestHandler handler = registry.findHandler(pathInfo);
    if (handler == null) {
      sendNotFound(response, method, pathInfo);
      return;
    }

    String subPath = registry.getSubPath(pathInfo, handler);
    try {
      switch (method) {
        case "GET":
          handler.doGet(request, response, subPath);
          break;
        case "POST":
          handler.doPost(request, response, subPath);
          break;
        case "PUT":
          handler.doPut(request, response, subPath);
          break;
        case "DELETE":
          handler.doDelete(request, response, subPath);
          break;
        default:
          response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
      }
    } catch (Exception e) {
      log4j.error("Error dispatching {} {}: {}", method, pathInfo, e.getMessage(), e);
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  private void sendNotFound(HttpServletResponse response, String method, String path) throws IOException {
    try {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
      response.setContentType(APPLICATION_JSON_CHARSET_UTF_8);
      JSONObject json = new JSONObject();
      json.put("status", "error");
      json.put("method", method);
      json.put("path", path);
      json.put("message", "No handler found for path");
      response.getWriter().write(json.toString());
    } catch (Exception e) {
      log4j.error("Error sending not-found response: {}", e.getMessage(), e);
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }
}
