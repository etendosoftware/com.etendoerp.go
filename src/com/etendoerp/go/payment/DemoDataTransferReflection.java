/* Etendo License. */
package com.etendoerp.go.payment;

import java.lang.reflect.Method;
import java.util.Optional;

import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.PriceListVersion;
import org.openbravo.model.pricing.pricelist.ProductPrice;

/** Reflection helpers for optional module fields used during demo data transfer. */
final class DemoDataTransferReflection {
  private DemoDataTransferReflection() { }

  /** Copies available bean properties while tolerating module-specific fields.
   * @param source object providing the property values
   * @param target object receiving the property values
   * @param properties bean property names to copy
   */
  static void copy(Object source, Object target, String... properties) {
    for (String property : properties) {
      try {
        copyProperty(source, target, property);
      } catch (NoSuchMethodException ignored) {
        // Optional module columns differ by installed module/version.
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("Could not copy transfer property " + property, e);
      }
    }
  }

  private static void copyProperty(Object source, Object target, String property)
      throws ReflectiveOperationException {
    Method getter = findGetter(source, property);
    Object value = getter.invoke(source);
    if (value == null) return;
    for (Method method : target.getClass().getMethods()) {
      if (method.getName().equals("set" + property) && method.getParameterCount() == 1) {
        method.invoke(target, value);
        return;
      }
    }
  }

  private static Method findGetter(Object source, String property) throws NoSuchMethodException {
    try {
      return source.getClass().getMethod("get" + property);
    } catch (NoSuchMethodException e) {
      return source.getClass().getMethod("is" + property);
    }
  }

  /** Resolves whether a price belongs to a sales list.
   * @param price price row to inspect
   * @return sales-list classification, or empty if the relation cannot be resolved
   */
  static Optional<Boolean> salesPriceList(ProductPrice price) {
    PriceListVersion version = price.getPriceListVersion();
    PriceList list = version == null ? null : version.getPriceList();
    return list == null ? Optional.empty() : Optional.of(list.isSalesPriceList());
  }
}
