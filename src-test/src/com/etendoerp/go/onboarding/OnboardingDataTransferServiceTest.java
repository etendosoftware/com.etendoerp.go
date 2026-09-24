/* Etendo License. */
package com.etendoerp.go.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.pricing.pricelist.ProductPrice;

/** Verifies that onboarding builds the same atomic operations consumed by grid import. */
public class OnboardingDataTransferServiceTest {

  @Test
  public void testBuildsTenProductsWithPricesAndTenContactsAsGridImportOperations()
      throws Exception {
    List<Product> products = new ArrayList<>();
    List<ProductPrice> prices = new ArrayList<>();
    List<BusinessPartner> contacts = new ArrayList<>();

    for (int i = 1; i <= 10; i++) {
      Product product = mock(Product.class);
      when(product.getId()).thenReturn("product-" + i);
      when(product.getSearchKey()).thenReturn("P-" + i);
      when(product.getName()).thenReturn("Product " + i);
      products.add(product);

      ProductPrice price = mock(ProductPrice.class);
      when(price.getProduct()).thenReturn(product);
      when(price.getListPrice()).thenReturn(BigDecimal.valueOf(i * 10L));
      when(price.getStandardPrice()).thenReturn(BigDecimal.valueOf(i * 10L));
      when(price.getPriceLimit()).thenReturn(BigDecimal.valueOf(i * 10L));
      prices.add(price);

      BusinessPartner contact = mock(BusinessPartner.class);
      when(contact.getId()).thenReturn("contact-" + i);
      when(contact.getSearchKey()).thenReturn("C-" + i);
      when(contact.getName()).thenReturn("Contact " + i);
      contacts.add(contact);
    }

    JSONArray operations = new OnboardingDataTransferService().buildGridImportOperations(
        products, prices, contacts, "target-sales-price-version");

    assertEquals("Each product, its price and each contact must be imported", 30,
        operations.length());
    for (int i = 0; i < 10; i++) {
      JSONObject product = operations.getJSONObject(i * 2);
      JSONObject price = operations.getJSONObject(i * 2 + 1);
      JSONObject contact = operations.getJSONObject(20 + i);

      assertEquals("product", product.getString("entity"));
      assertEquals("product-product-" + (i + 1), product.getString("id"));
      assertEquals("price", price.getString("entity"));
      assertEquals(product.getString("id"), price.getString("parentRef"));
      assertEquals("target-sales-price-version",
          price.getJSONObject("body").getString("priceListVersion"));
      assertEquals("businessPartner", contact.getString("entity"));
      assertEquals("contact-contact-" + (i + 1), contact.getString("id"));
    }
    assertTrue("Price operations must be linked to their product operation",
        operations.getJSONObject(1).has("parentRef"));
  }
}
