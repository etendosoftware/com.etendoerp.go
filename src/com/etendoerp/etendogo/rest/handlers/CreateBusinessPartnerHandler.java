package com.etendoerp.etendogo.rest.handlers;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.etendoerp.etendogo.rest.EndpointHandler;
import com.etendoerp.etendogo.rest.contract.model.BusinessPartner;
import com.fasterxml.jackson.databind.ObjectMapper;

public class CreateBusinessPartnerHandler implements EndpointHandler {
  private static final String APPLICATION_JSON_CHARSET_UTF_8 = "application/json;charset=UTF-8";
  private static final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public String getMethod() {
    return "POST";
  }

  @Override
  public String getPath() {
    return "/business-partners";
  }

  @Override
  public String getOperationId() {
    return "createBusinessPartner";
  }

  @Override
  public void handle(HttpServletRequest req, HttpServletResponse res) throws IOException {
    BusinessPartner bp = objectMapper.readValue(req.getInputStream(), BusinessPartner.class);

    String id = BusinessPartnerStore.getInstance().nextId();
    bp.setId(id);
    BusinessPartnerStore.getInstance().put(id, bp);

    res.setStatus(HttpServletResponse.SC_CREATED);
    res.setContentType(APPLICATION_JSON_CHARSET_UTF_8);
    res.getWriter().write(objectMapper.writeValueAsString(bp));
  }
}
