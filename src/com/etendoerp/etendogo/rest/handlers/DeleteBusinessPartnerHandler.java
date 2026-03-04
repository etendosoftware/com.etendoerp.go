package com.etendoerp.etendogo.rest.handlers;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.etendoerp.etendogo.rest.EndpointHandler;

public class DeleteBusinessPartnerHandler implements EndpointHandler {

  @Override
  public String getMethod() {
    return "DELETE";
  }

  @Override
  public String getPath() {
    return "/business-partners/{id}";
  }

  @Override
  public String getOperationId() {
    return "deleteBusinessPartner";
  }

  @Override
  public void handle(HttpServletRequest req, HttpServletResponse res) throws IOException {
    String id = extractPathParam(req);

    if (BusinessPartnerStore.getInstance().remove(id)) {
      res.setStatus(HttpServletResponse.SC_NO_CONTENT);
    } else {
      res.sendError(HttpServletResponse.SC_NOT_FOUND);
    }
  }

  private String extractPathParam(HttpServletRequest req) {
    String pathInfo = req.getPathInfo();
    String[] segments = pathInfo.split("/");
    return segments[segments.length - 1];
  }
}
