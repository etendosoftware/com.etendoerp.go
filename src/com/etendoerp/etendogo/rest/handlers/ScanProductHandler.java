package com.etendoerp.etendogo.rest.handlers;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.etendogo.rest.EndpointHandler;
import com.etendoerp.etendogo.rest.contract.model.ProductScanRequest;
import com.etendoerp.etendogo.rest.contract.model.ProductScanResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ScanProductHandler implements EndpointHandler {
  private static final String APPLICATION_JSON_CHARSET_UTF_8 = "application/json;charset=UTF-8";
  private static final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public String getMethod() {
    return "POST";
  }

  @Override
  public String getPath() {
    return "/scan-product";
  }

  @Override
  public String getOperationId() {
    return "scanProduct";
  }

  @Override
  public void handle(HttpServletRequest req, HttpServletResponse res) throws IOException {
    ProductScanRequest scanReq = objectMapper.readValue(req.getInputStream(), ProductScanRequest.class);

    ProductScanResponse scanRes = new ProductScanResponse();
    if (StringUtils.isNotBlank(scanReq.getSearchKey())) {
      scanRes.setProductId("DUMMY-" + scanReq.getSearchKey());
      scanRes.setName("Producto de prueba (" + scanReq.getSearchKey() + ")");
      scanRes.setPrice(99.99);
      scanRes.setStock(100);
      scanRes.setFound(true);
    } else {
      scanRes.setFound(false);
    }

    res.setStatus(HttpServletResponse.SC_OK);
    res.setContentType(APPLICATION_JSON_CHARSET_UTF_8);
    res.getWriter().write(objectMapper.writeValueAsString(scanRes));
  }
}
