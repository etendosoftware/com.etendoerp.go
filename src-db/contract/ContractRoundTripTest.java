package com.etendoerp.etendogo.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.util.Arrays;

import com.etendoerp.etendogo.rest.contract.model.BusinessPartner;
import com.etendoerp.etendogo.rest.contract.model.BusinessPartnerListResponse;
import com.etendoerp.etendogo.rest.contract.model.ErrorResponse;
import com.etendoerp.etendogo.rest.contract.model.HealthResponse;
import com.etendoerp.etendogo.rest.contract.model.ProductScanRequest;
import com.etendoerp.etendogo.rest.contract.model.ProductScanResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ContractRoundTripTest {

  private static final ObjectMapper mapper = new ObjectMapper();

  @Test
  public void healthResponseRoundTrip() throws Exception {
    HealthResponse original = new HealthResponse();
    original.setStatus("ok");

    String json = mapper.writeValueAsString(original);
    HealthResponse deserialized = mapper.readValue(json, HealthResponse.class);

    assertNotNull(deserialized);
    assertEquals("ok", deserialized.getStatus());
  }

  @Test
  public void productScanRequestRoundTrip() throws Exception {
    ProductScanRequest original = new ProductScanRequest();
    original.setSearchKey("ABC123");
    original.setWarehouseId("WH-001");

    String json = mapper.writeValueAsString(original);
    ProductScanRequest deserialized = mapper.readValue(json, ProductScanRequest.class);

    assertNotNull(deserialized);
    assertEquals("ABC123", deserialized.getSearchKey());
    assertEquals("WH-001", deserialized.getWarehouseId());
  }

  @Test
  public void productScanResponseRoundTrip() throws Exception {
    ProductScanResponse original = new ProductScanResponse();
    original.setProductId("PROD-1");
    original.setName("Test Product");
    original.setPrice(29.99);
    original.setStock(50);
    original.setFound(true);

    String json = mapper.writeValueAsString(original);
    ProductScanResponse deserialized = mapper.readValue(json, ProductScanResponse.class);

    assertNotNull(deserialized);
    assertEquals("PROD-1", deserialized.getProductId());
    assertEquals("Test Product", deserialized.getName());
    assertEquals(29.99, deserialized.getPrice(), 0.001);
    assertEquals(Integer.valueOf(50), deserialized.getStock());
    assertEquals(true, deserialized.getFound());
  }

  @Test
  public void errorResponseRoundTrip() throws Exception {
    ErrorResponse original = new ErrorResponse();
    original.setError("something went wrong");

    String json = mapper.writeValueAsString(original);
    ErrorResponse deserialized = mapper.readValue(json, ErrorResponse.class);

    assertNotNull(deserialized);
    assertEquals("something went wrong", deserialized.getError());
  }

  @Test
  public void businessPartnerRoundTrip() throws Exception {
    BusinessPartner original = new BusinessPartner();
    original.setId("BP-001");
    original.setName("Acme Corp");
    original.setTaxID("B12345678");
    original.setCategory("Customer");

    String json = mapper.writeValueAsString(original);
    BusinessPartner deserialized = mapper.readValue(json, BusinessPartner.class);

    assertNotNull(deserialized);
    assertEquals("BP-001", deserialized.getId());
    assertEquals("Acme Corp", deserialized.getName());
    assertEquals("B12345678", deserialized.getTaxID());
    assertEquals("Customer", deserialized.getCategory());
  }

  @Test
  public void businessPartnerListResponseRoundTrip() throws Exception {
    BusinessPartner bp = new BusinessPartner();
    bp.setId("1");
    bp.setName("Test BP");

    BusinessPartnerListResponse original = new BusinessPartnerListResponse();
    original.setData(Arrays.asList(bp));

    String json = mapper.writeValueAsString(original);
    BusinessPartnerListResponse deserialized = mapper.readValue(json, BusinessPartnerListResponse.class);

    assertNotNull(deserialized);
    assertNotNull(deserialized.getData());
    assertEquals(1, deserialized.getData().size());
    assertEquals("Test BP", deserialized.getData().get(0).getName());
  }
}
