package com.etendoerp.etendogo.rest.handlers;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.etendoerp.etendogo.rest.EndpointHandler;
import com.etendoerp.etendogo.rest.contract.model.BusinessPartner;
import com.fasterxml.jackson.databind.ObjectMapper;

public class UpdateBusinessPartnerHandler implements EndpointHandler {
  private static final String APPLICATION_JSON_CHARSET_UTF_8 = "application/json;charset=UTF-8";
  private static final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public String getMethod() {
    return "PUT";
  }

  @Override
  public String getPath() {
    return "/business-partners/{id}";
  }

  @Override
  public String getOperationId() {
    return "updateBusinessPartner";
  }

  @Override
  public void handle(HttpServletRequest req, HttpServletResponse res) throws IOException {
    String id = extractPathParam(req);

    if (!BusinessPartnerStore.getInstance().contains(id)) {
      res.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }

    BusinessPartner bp = objectMapper.readValue(req.getInputStream(), BusinessPartner.class);
    bp.setId(id);
    BusinessPartnerStore.getInstance().put(id, bp);

    res.setStatus(HttpServletResponse.SC_OK);
    res.setContentType(APPLICATION_JSON_CHARSET_UTF_8);
    res.getWriter().write(objectMapper.writeValueAsString(bp));
  }

  private String extractPathParam(HttpServletRequest req) {
    String pathInfo = req.getPathInfo();
    String[] segments = pathInfo.split("/");
    return segments[segments.length - 1];
  }
}
