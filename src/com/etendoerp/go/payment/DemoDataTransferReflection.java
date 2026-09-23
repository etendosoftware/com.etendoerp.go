/* Etendo License. */
package com.etendoerp.go.payment;

import java.lang.reflect.Method;
import java.util.Optional;

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
        Object value = source.getClass().getMethod("get" + property).invoke(source);
        if (value == null) continue;
        for (Method method : target.getClass().getMethods()) {
          if (method.getName().equals("set" + property) && method.getParameterCount() == 1) {
            method.invoke(target, value);
            break;
          }
        }
      } catch (ReflectiveOperationException ignored) {
        // Optional module columns differ by installed module/version; their absence is not a row failure.
      }
    }
  }

  /** Resolves whether a price belongs to a sales list.
   * @param price price row to inspect
   * @return sales-list classification, or empty if the relation cannot be resolved
   */
  static Optional<Boolean> salesPriceList(ProductPrice price) {
    try {
      Object version = price.getPriceListVersion();
      Object list = version.getClass().getMethod("getPriceList").invoke(version);
      return Optional.ofNullable((Boolean) list.getClass().getMethod("isSalesPriceList").invoke(list));
    } catch (ReflectiveOperationException e) {
      return Optional.empty();
    }
  }
}
