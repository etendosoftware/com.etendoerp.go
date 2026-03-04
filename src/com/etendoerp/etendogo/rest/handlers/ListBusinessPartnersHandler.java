package com.etendoerp.etendogo.rest.handlers;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.etendoerp.etendogo.rest.EndpointHandler;
import com.etendoerp.etendogo.rest.contract.model.BusinessPartnerListResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ListBusinessPartnersHandler implements EndpointHandler {
  private static final String APPLICATION_JSON_CHARSET_UTF_8 = "application/json;charset=UTF-8";
  private static final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public String getMethod() {
    return "GET";
  }

  @Override
  public String getPath() {
    return "/business-partners";
  }

  @Override
  public String getOperationId() {
    return "listBusinessPartners";
  }

  @Override
  public void handle(HttpServletRequest req, HttpServletResponse res) throws IOException {
    BusinessPartnerListResponse response = new BusinessPartnerListResponse();
    response.setData(BusinessPartnerStore.getInstance().getAll());

    res.setStatus(HttpServletResponse.SC_OK);
    res.setContentType(APPLICATION_JSON_CHARSET_UTF_8);
    res.getWriter().write(objectMapper.writeValueAsString(response));
  }
}
