/* Etendo License. */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DemoDataTransferReflectionTest {

  @Test
  void copiesBooleanBeanPropertiesExposedThroughIsGetters() {
    Source source = new Source();
    Target target = new Target();

    DemoDataTransferReflection.copy(source, target, "Customer", "Vendor", "Employee");

    assertTrue(target.customer);
    assertFalse(target.vendor);
    assertTrue(target.employee);
  }

  public static final class Source {
    public boolean isCustomer() { return true; }
    public boolean isVendor() { return false; }
    public boolean isEmployee() { return true; }
  }

  public static final class Target {
    boolean customer;
    boolean vendor;
    boolean employee;

    public void setCustomer(boolean value) { customer = value; }
    public void setVendor(boolean value) { vendor = value; }
    public void setEmployee(boolean value) { employee = value; }
  }
}
