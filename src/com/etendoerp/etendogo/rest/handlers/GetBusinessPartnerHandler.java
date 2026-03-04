package com.etendoerp.etendogo.rest.handlers;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.etendoerp.etendogo.rest.EndpointHandler;
import com.etendoerp.etendogo.rest.contract.model.BusinessPartner;
import com.fasterxml.jackson.databind.ObjectMapper;

public class GetBusinessPartnerHandler implements EndpointHandler {
  private static final String APPLICATION_JSON_CHARSET_UTF_8 = "application/json;charset=UTF-8";
  private static final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public String getMethod() {
    return "GET";
  }

  @Override
  public String getPath() {
    return "/business-partners/{id}";
  }

  @Override
  public String getOperationId() {
    return "getBusinessPartner";
  }

  @Override
  public void handle(HttpServletRequest req, HttpServletResponse res) throws IOException {
    String id = extractPathParam(req, "id");

    BusinessPartnerStore.getInstance().get(id).ifPresentOrElse(
        bp -> {
          try {
            res.setStatus(HttpServletResponse.SC_OK);
            res.setContentType(APPLICATION_JSON_CHARSET_UTF_8);
            res.getWriter().write(objectMapper.writeValueAsString(bp));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        },
        () -> {
          try {
            res.sendError(HttpServletResponse.SC_NOT_FOUND);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
    );
  }

  private String extractPathParam(HttpServletRequest req, String paramName) {
    String pathInfo = req.getPathInfo();
    // Path is /business-partners/{id}, so the id is the last segment
    String[] segments = pathInfo.split("/");
    return segments[segments.length - 1];
  }
}
