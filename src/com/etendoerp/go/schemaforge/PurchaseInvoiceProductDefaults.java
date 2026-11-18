/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.plm.ProductCategory;
import org.openbravo.model.common.uom.UOM;

import com.etendoerp.go.schemaforge.ocr.OcrUnitMatcher;

/**
 * The lookups behind the purchase-invoice review's product defaults (printed unit, default unit,
 * default category), answering ids so {@link PurchaseInvoiceOcrLookups} can describe them. Split
 * out of that class to keep it within its size budget.
 */
final class PurchaseInvoiceProductDefaults {

  private static final Logger log = LogManager.getLogger(PurchaseInvoiceProductDefaults.class);

  private PurchaseInvoiceProductDefaults() {
  }

  /** The id of the unit a line printed, among the active units the tenant can see. */
  static Optional<String> unitIdByPrintedUnit(String printed) {
    try {
      OBContext context = OBContext.getOBContext();
      List<OcrUnitMatcher.Unit> units = new ArrayList<>();
      for (UOM uom : OBDal.getInstance().createQuery(UOM.class, "as u where u.active = true")
          .list()) {
        if (TenantOwnership.isVisibleToCurrentTenant(uom)) {
          units.add(new OcrUnitMatcher.Unit(uom.getId(), uom.getClient().getId(), uom.getSymbol(),
              uom.getEDICode(), uom.getName()));
        }
      }
      String clientId = context == null ? null : context.getCurrentClient().getId();
      return OcrUnitMatcher.resolve(printed, units, clientId);
    } catch (RuntimeException e) {
      log.warn("OCR printed unit lookup failed: {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /** The id of the default unit of the product window. */
  static Optional<String> defaultUnitId() {
    try {
      OBContext context = OBContext.getOBContext();
      String clientId = context == null ? null : context.getCurrentClient().getId();
      return Optional.ofNullable(
          new ProductDefaultsHandler().resolveDefaultId(UOM.class, clientId));
    } catch (RuntimeException e) {
      log.warn("OCR default unit lookup failed: {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /**
   * The id per the AD default of {@code M_Product.M_Product_Category_ID}: the greatest id of the
   * client's {@code IsDefault} non-summary categories whose organization is the given (document)
   * organization or one of its parents (the {@code AD_ISORGINCLUDED(org, row.org, client)} test).
   */
  static Optional<String> defaultCategoryId(String organizationId) {
    try {
      OBContext context = OBContext.getOBContext();
      if (context == null) {
        return Optional.empty();
      }
      String orgId = StringUtils.isNotBlank(organizationId) ? organizationId
          : context.getCurrentOrganization().getId();
      Set<String> organizations = context.getOrganizationStructureProvider()
          .getParentTree(orgId, true);
      OBCriteria<ProductCategory> criteria = OBDal.getInstance()
          .createCriteria(ProductCategory.class);
      criteria.add(Restrictions.eq("client.id", context.getCurrentClient().getId()));
      criteria.add(Restrictions.eq("default", true));
      criteria.add(Restrictions.eq("summaryLevel", false));
      criteria.add(Restrictions.eq("active", true));
      criteria.add(Restrictions.in("organization.id", organizations));
      criteria.addOrder(Order.desc("id"));
      criteria.setMaxResults(1);
      ProductCategory category = (ProductCategory) criteria.uniqueResult();
      return category == null ? Optional.empty() : Optional.of(category.getId());
    } catch (RuntimeException e) {
      log.warn("OCR default category lookup failed: {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }
}
