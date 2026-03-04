package com.etendoerp.etendogo.rest.handlers;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.etendogo.rest.EndpointHandler;

public class HealthHandler implements EndpointHandler {
  private static final String APPLICATION_JSON_CHARSET_UTF_8 = "application/json;charset=UTF-8";

  @Override
  public String getMethod() {
    return "GET";
  }

  @Override
  public String getPath() {
    return "/health";
  }

  @Override
  public String getOperationId() {
    return "getHealth";
  }

  @Override
  public void handle(HttpServletRequest req, HttpServletResponse res) throws IOException {
    res.setStatus(HttpServletResponse.SC_OK);
    res.setContentType(APPLICATION_JSON_CHARSET_UTF_8);
    try {
      JSONObject result = new JSONObject();
      result.put("status", "ok");
      res.getWriter().write(result.toString());
    } catch (JSONException e) {
      throw new IOException(e);
    }
  }
}
