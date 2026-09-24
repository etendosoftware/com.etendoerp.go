/* Etendo License. */
package com.etendoerp.go.payment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Category;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.plm.ProductCategory;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.PriceListVersion;
import org.openbravo.model.pricing.pricelist.ProductPrice;
import org.openbravo.model.materialmgmt.cost.Costing;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.financialmgmt.tax.TaxCategory;

/** Copies product and contact records for a demo data transfer. */
final class DemoDataTransferEntityCopier {
  private static final String ZERO_ID = "0";
  private static final String SEARCH_KEY_PROPERTY = "SearchKey";
  private static final String DESCRIPTION_PROPERTY = "Description";
  private static final String CATEGORY_BY_NAME_QUERY =
      "as c where c.client.id = :clientId and c.name = :key";
  private static final String PRODUCTS_TOTAL = "ETGO_DemoDataTransferProductsTotal";
  private static final String PRODUCTS_DONE = "ETGO_DemoDataTransferProductsDone";
  private static final String CONTACTS_TOTAL = "ETGO_DemoDataTransferContactsTotal";
  private static final String CONTACTS_DONE = "ETGO_DemoDataTransferContactsDone";
  private final DemoDataTransferService owner;

  DemoDataTransferEntityCopier(DemoDataTransferService owner) {
    this.owner = owner;
  }

  void copyProducts(Client source, Client target, Organization targetOrg) {
    List<Product> products = owner.query(Product.class, "as p where p.client.id = :clientId", source.getId());
    owner.progress(target, PRODUCTS_TOTAL, products.size());
    int completed = 0;
    owner.progress(target, PRODUCTS_DONE, completed);
    for (Product product : products) {
      Product targetProduct = owner.unique(Product.class, "as p where p.client.id = :clientId and p.searchKey = :key",
          target.getId(), product.getSearchKey());
      if (targetProduct == null) {
        targetProduct = OBProvider.getInstance().get(Product.class);
        targetProduct.setClient(target);
        targetProduct.setOrganization(targetOrg);
      }
      copyProductFields(product, targetProduct);
      targetProduct.setUOM(targetUom(product.getUOM(), target, targetOrg));
      targetProduct.setProductCategory(targetProductCategory(product.getProductCategory(), target, targetOrg));
      targetProduct.setTaxCategory(targetTaxCategory(product.getTaxCategory(), target));
      OBDal.getInstance().save(targetProduct);
      copyPrices(product, targetProduct, target, targetOrg);
      copyCurrentCost(product, targetProduct, target, targetOrg);
      owner.progress(target, PRODUCTS_DONE, ++completed);
      OBDal.getInstance().flush();
    }
  }

  void copyContacts(Client source, Client target, Organization targetOrg) {
    List<BusinessPartner> contacts = owner.query(BusinessPartner.class,
        "as bp where bp.client.id = :clientId and bp.active = true", source.getId());
    owner.progress(target, CONTACTS_TOTAL, contacts.size());
    int completed = 0;
    owner.progress(target, CONTACTS_DONE, completed);
    for (BusinessPartner contact : contacts) {
      BusinessPartner targetContact = owner.unique(BusinessPartner.class,
          "as bp where bp.client.id = :clientId and bp.searchKey = :key", target.getId(), contact.getSearchKey());
      if (targetContact == null) {
        targetContact = OBProvider.getInstance().get(BusinessPartner.class);
        targetContact.setClient(target);
        targetContact.setOrganization(targetOrg);
      }
      copyBusinessPartnerFields(contact, targetContact);
      targetContact.setBusinessPartnerCategory(targetBusinessPartnerCategory(
          contact.getBusinessPartnerCategory(), target, targetOrg));
      OBDal.getInstance().save(targetContact);
      Map<String, Location> targetLocations = copyBusinessPartnerLocations(
          contact, targetContact, target, targetOrg);
      copyBusinessPartnerPersons(contact, targetContact, target, targetOrg, targetLocations);
      owner.progress(target, CONTACTS_DONE, ++completed);
      OBDal.getInstance().flush();
    }
  }

  /** Product fields mirror the import contract; prices/costing are added through their own records. */
  private void copyProductFields(Product source, Product target) {
    DemoDataTransferReflection.copy(source, target, SEARCH_KEY_PROPERTY, "Name", DESCRIPTION_PROPERTY, "ProductType");
  }

  /** UOM rows can be client-scoped; never attach a source tenant's entity to the new product. */
  private UOM targetUom(UOM source, Client target, Organization targetOrg) {
    if (source == null) throw new IllegalStateException("Source product has no unit of measure");
    if (ZERO_ID.equals(source.getClient().getId())) return source;
    UOM existing = owner.unique(UOM.class, "as u where u.client.id = :clientId and u.name = :key",
        target.getId(), source.getName());
    if (existing != null) return existing;
    UOM copy = OBProvider.getInstance().get(UOM.class);
    copy.setClient(target); copy.setOrganization(targetOrg);
    copy.setName(source.getName());
    copy.setSymbol(source.getSymbol());
    copy.setEDICode(source.getEDICode());
    copy.setStandardPrecision(source.getStandardPrecision());
    copy.setCostingPrecision(source.getCostingPrecision());
    copy.setUOMType(source.getUOMType());
    OBDal.getInstance().save(copy);
    return copy;
  }

  /** Tax categories are provisioned with accounting; an absent match needs an operator fix. */
  TaxCategory targetTaxCategory(TaxCategory source, Client target) {
    if (source == null) throw new IllegalStateException("Source product has no tax category");
    if (ZERO_ID.equals(source.getClient().getId())) return source;
    TaxCategory category = owner.unique(TaxCategory.class, CATEGORY_BY_NAME_QUERY, target.getId(),
        source.getName());
    if (category == null) {
      throw new IllegalStateException("Target tax category is missing: " + source.getName());
    }
    return category;
  }

  private ProductCategory targetProductCategory(ProductCategory source, Client target,
      Organization targetOrg) {
    if (source == null) return null;
    if (ZERO_ID.equals(source.getClient().getId())) return source;
    ProductCategory existing = owner.unique(ProductCategory.class, CATEGORY_BY_NAME_QUERY,
        target.getId(), source.getName());
    if (existing != null) return existing;
    ProductCategory copy = OBProvider.getInstance().get(ProductCategory.class);
    copy.setClient(target); copy.setOrganization(targetOrg);
    DemoDataTransferReflection.copy(source, copy, "Name", SEARCH_KEY_PROPERTY, DESCRIPTION_PROPERTY);
    OBDal.getInstance().save(copy);
    return copy;
  }

  /** Copies the source product's displayed sales and purchase prices onto the target defaults. */
  void copyPrices(Product source, Product target, Client targetClient, Organization targetOrg) {
    List<ProductPrice> prices = owner.query(ProductPrice.class, "as pp where pp.product.id = :clientId", source.getId());
    boolean copiedSales = false;
    boolean copiedPurchase = false;
    for (ProductPrice sourcePrice : prices) {
      Optional<Boolean> salesPriceList = DemoDataTransferReflection.salesPriceList(sourcePrice);
      if (salesPriceList.isEmpty()) {
        throw new IllegalStateException("Cannot classify source product price list");
      }
      boolean sales = salesPriceList.get();
      boolean alreadyCopied = sales ? copiedSales : copiedPurchase;
      if (copyPriceIfNeeded(sourcePrice, target, targetClient, targetOrg, sales, alreadyCopied)) {
        copiedSales |= sales;
        copiedPurchase |= !sales;
      }
    }
  }

  private boolean copyPriceIfNeeded(ProductPrice sourcePrice, Product targetProduct,
      Client targetClient, Organization targetOrg, boolean sales, boolean alreadyCopied) {
    if (alreadyCopied) return false;
    PriceListVersion version = targetVersion(targetClient.getId(), targetOrg.getId(), sales);
    if (version == null) {
      throw new IllegalStateException("Target " + (sales ? "sales" : "purchase")
          + " price list version is missing");
    }
    ProductPrice targetProductPrice = targetPrice(targetProduct.getId(), version.getId());
    if (targetProductPrice == null) {
      targetProductPrice = OBProvider.getInstance().get(ProductPrice.class);
      targetProductPrice.setClient(targetClient);
      targetProductPrice.setOrganization(targetOrg);
      targetProductPrice.setProduct(targetProduct);
      targetProductPrice.setPriceListVersion(version);
    }
    DemoDataTransferReflection.copy(sourcePrice, targetProductPrice,
        "StandardPrice", "ListPrice", "PriceLimit");
    targetProductPrice.setActive(true);
    OBDal.getInstance().save(targetProductPrice);
    return true;
  }

  /** Cost is a history row, not a product column: migrate the current row and its start date. */
  private void copyCurrentCost(Product source, Product target, Client targetClient, Organization targetOrg) {
    List<Costing> costs = owner.query(Costing.class,
        "as c where c.product.id = :clientId and c.active = true order by c.startingDate desc", source.getId());
    if (costs.isEmpty()) return;
    Costing sourceCost = costs.get(0);
    Costing copy = targetCost(target.getId(), sourceCost.getStartingDate());
    if (copy == null) {
      copy = OBProvider.getInstance().get(Costing.class);
      copy.setClient(targetClient);
      copy.setOrganization(targetOrg);
      copy.setProduct(target);
    }
    DemoDataTransferReflection.copy(sourceCost, copy, "Cost", "StartingDate", "EndingDate", "CostType", "Manual", "Permanent", "Production", "Currency");
    OBDal.getInstance().save(copy);
  }

  /** Resolves a target tariff within the transfer's explicit tenant and organization scope. */
  private PriceListVersion targetVersion(String clientId, String orgId, boolean sales) {
    String[] orgsToTry = ZERO_ID.equals(orgId) ? new String[] { ZERO_ID }
        : new String[] { orgId, ZERO_ID };
    OBContext.setAdminMode();
    try {
      // Prefer explicitly default lists across both organization scopes, then retain the
      // resolver's historical fallback to any active list if no default is configured.
      for (boolean requireDefault : new boolean[] { true, false }) {
        for (String candidateOrgId : orgsToTry) {
          OBCriteria<PriceListVersion> criteria = OBDal.getInstance()
              .createCriteria(PriceListVersion.class);
          // This worker runs as System and resolves only the tenant/org IDs it obtained for the
          // transfer target. Avoid System's readable-org filter hiding that exact target org.
          criteria.setFilterOnReadableClients(false);
          criteria.setFilterOnReadableOrganization(false);
          criteria.add(Restrictions.eq(PriceListVersion.PROPERTY_CLIENT + ".id", clientId));
          criteria.add(Restrictions.eq(PriceListVersion.PROPERTY_ORGANIZATION + ".id",
              candidateOrgId));
          criteria.add(Restrictions.eq(PriceListVersion.PROPERTY_ACTIVE, true));
          criteria.createAlias(PriceListVersion.PROPERTY_PRICELIST, "pl");
          criteria.add(Restrictions.eq("pl." + PriceList.PROPERTY_ACTIVE, true));
          criteria.add(Restrictions.eq("pl." + PriceList.PROPERTY_SALESPRICELIST, sales));
          if (requireDefault) {
            criteria.add(Restrictions.eq("pl." + PriceList.PROPERTY_DEFAULT, true));
          }
          criteria.addOrder(Order.desc(PriceListVersion.PROPERTY_VALIDFROMDATE));
          criteria.setMaxResults(1);
          List<PriceListVersion> matches = criteria.list();
          if (!matches.isEmpty()) {
            return matches.get(0);
          }
        }
      }
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private ProductPrice targetPrice(String productId, String versionId) {
    OBQuery<ProductPrice> query = OBDal.getInstance().createQuery(ProductPrice.class,
        "as pp where pp.product.id = :productId and pp.priceListVersion.id = :versionId");
    query.setNamedParameter("productId", productId); query.setNamedParameter("versionId", versionId);
    query.setFilterOnReadableClients(false); query.setFilterOnReadableOrganization(false); query.setMaxResult(1);
    return query.uniqueResult();
  }

  private Costing targetCost(String productId, java.util.Date startingDate) {
    OBQuery<Costing> query = OBDal.getInstance().createQuery(Costing.class,
        "as c where c.product.id = :productId and c.startingDate = :startingDate");
    query.setNamedParameter("productId", productId); query.setNamedParameter("startingDate", startingDate);
    query.setFilterOnReadableClients(false); query.setFilterOnReadableOrganization(false); query.setMaxResult(1);
    return query.uniqueResult();
  }

  /** Deliberately no CIF validation: existing demo contacts are authoritative migration input. */
  private void copyBusinessPartnerFields(BusinessPartner source, BusinessPartner target) {
    DemoDataTransferReflection.copy(source, target, SEARCH_KEY_PROPERTY, "Name", "TaxID",
        "Name2", DESCRIPTION_PROPERTY, "URL", "ReferenceNo", "Customer", "Vendor", "Employee",
        "EtgoIdentifier", "EtgoIsperson", "EtgoFirstname", "EtgoLastname", "EtgoEmail",
        "EtgoPhone", "EtgoWeb");
  }

  /** Copies the address rows shown in the Contact window's Location/Address tab. */
  private Map<String, Location> copyBusinessPartnerLocations(BusinessPartner source,
      BusinessPartner target, Client targetClient, Organization targetOrg) {
    List<Location> sourceLocations = owner.query(Location.class,
        "as l where l.businessPartner.id = :clientId", source.getId());
    List<Location> targetLocations = owner.query(Location.class,
        "as l where l.businessPartner.id = :clientId", target.getId());
    List<org.openbravo.model.common.geography.Location> targetAddresses = owner.query(
        org.openbravo.model.common.geography.Location.class,
        "as l where l.client.id = :clientId", targetClient.getId());
    Map<String, Location> sourceToTarget = new HashMap<>();

    for (Location sourceLocation : sourceLocations) {
      org.openbravo.model.common.geography.Location sourceAddress = sourceLocation.getLocationAddress();
      org.openbravo.model.common.geography.Location targetAddress = targetAddress(
          sourceAddress, targetClient, targetOrg, targetAddresses);
      Location targetLocation = targetLocations.stream()
          .filter(candidate -> sameBusinessPartnerLocation(sourceLocation, candidate))
          .findFirst().orElse(null);
      if (targetLocation == null) {
        targetLocation = OBProvider.getInstance().get(Location.class);
        targetLocation.setClient(targetClient);
        targetLocation.setOrganization(targetOrg);
        targetLocation.setBusinessPartner(target);
        targetLocations.add(targetLocation);
      }
      DemoDataTransferReflection.copy(sourceLocation, targetLocation, "Name", "InvoiceToAddress",
          "ShipToAddress", "PayFromAddress", "RemitToAddress", "Phone", "AlternativePhone",
          "Fax", "TaxLocation", "UPCEAN", "Active");
      targetLocation.setLocationAddress(targetAddress);
      OBDal.getInstance().save(targetLocation);
      sourceToTarget.put(sourceLocation.getId(), targetLocation);
    }
    return sourceToTarget;
  }

  private org.openbravo.model.common.geography.Location targetAddress(
      org.openbravo.model.common.geography.Location sourceAddress, Client targetClient,
      Organization targetOrg,
      List<org.openbravo.model.common.geography.Location> targetAddresses) {
    if (sourceAddress == null) return null;
    org.openbravo.model.common.geography.Location existing = targetAddresses.stream()
        .filter(candidate -> sameAddress(sourceAddress, candidate)).findFirst().orElse(null);
    if (existing != null) return existing;

    org.openbravo.model.common.geography.Location copy = OBProvider.getInstance()
        .get(org.openbravo.model.common.geography.Location.class);
    copy.setClient(targetClient);
    copy.setOrganization(targetOrg);
    DemoDataTransferReflection.copy(sourceAddress, copy, "AddressLine1", "AddressLine2",
        "CityName", "PostalCode", "PostalAdd", "RegionName");
    copy.setCountry(sharedAddressReference(sourceAddress.getCountry(), "country"));
    copy.setRegion(sharedAddressReference(sourceAddress.getRegion(), "region"));
    copy.setCity(sharedAddressReference(sourceAddress.getCity(), "city"));
    OBDal.getInstance().save(copy);
    targetAddresses.add(copy);
    return copy;
  }

  private <T extends BaseOBObject> T sharedAddressReference(T reference, String label) {
    if (reference == null) return null;
    if (!ZERO_ID.equals(((org.openbravo.base.structure.ClientEnabled) reference).getClient().getId())) {
      throw new IllegalStateException("Contact address references a tenant-specific " + label);
    }
    return reference;
  }

  private boolean sameAddress(org.openbravo.model.common.geography.Location left,
      org.openbravo.model.common.geography.Location right) {
    if (left == null || right == null) return left == right;
    return Objects.equals(left.getAddressLine1(), right.getAddressLine1())
        && Objects.equals(left.getAddressLine2(), right.getAddressLine2())
        && Objects.equals(left.getCityName(), right.getCityName())
        && Objects.equals(left.getPostalCode(), right.getPostalCode())
        && Objects.equals(left.getPostalAdd(), right.getPostalAdd())
        && Objects.equals(left.getRegionName(), right.getRegionName())
        && sameReference(left.getCountry(), right.getCountry())
        && sameReference(left.getRegion(), right.getRegion())
        && sameReference(left.getCity(), right.getCity());
  }

  private boolean sameBusinessPartnerLocation(Location left, Location right) {
    return Objects.equals(left.getName(), right.getName())
        && sameAddress(left.getLocationAddress(), right.getLocationAddress())
        && Objects.equals(left.getPhone(), right.getPhone())
        && Objects.equals(left.getAlternativePhone(), right.getAlternativePhone())
        && Objects.equals(left.getFax(), right.getFax())
        && Objects.equals(left.isInvoiceToAddress(), right.isInvoiceToAddress())
        && Objects.equals(left.isShipToAddress(), right.isShipToAddress())
        && Objects.equals(left.isPayFromAddress(), right.isPayFromAddress())
        && Objects.equals(left.isRemitToAddress(), right.isRemitToAddress())
        && Objects.equals(left.isTaxLocation(), right.isTaxLocation());
  }

  private boolean sameReference(BaseOBObject left, BaseOBObject right) {
    return Objects.equals(left == null ? null : left.getId(), right == null ? null : right.getId());
  }

  /** Copies the person/contact rows shown in the Contact window's Contact tab. */
  private void copyBusinessPartnerPersons(BusinessPartner source, BusinessPartner target,
      Client targetClient, Organization targetOrg, Map<String, Location> targetLocations) {
    List<User> sourceUsers = owner.query(User.class,
        "as u where u.businessPartner.id = :clientId", source.getId());
    List<User> targetUsers = owner.query(User.class,
        "as u where u.businessPartner.id = :clientId", target.getId());
    for (User sourceUser : sourceUsers) {
      User targetUser = targetUsers.stream().filter(candidate -> sameContactPerson(sourceUser, candidate))
          .findFirst().orElse(null);
      if (targetUser == null) {
        targetUser = OBProvider.getInstance().get(User.class);
        targetUser.setClient(targetClient);
        targetUser.setOrganization(targetOrg);
        targetUser.setBusinessPartner(target);
        targetUsers.add(targetUser);
      }
      DemoDataTransferReflection.copy(sourceUser, targetUser, "FirstName", "LastName", "Name",
          "Email", "Phone", "AlternativePhone", "Position", "Comments", "Active",
          "Defaultfordocs", "GrantPortalAccess", "Commercialauth", "Viasms", "Viaemail");
      Location sourcePartnerAddress = sourceUser.getPartnerAddress();
      Location targetPartnerAddress = sourcePartnerAddress == null ? null
          : targetLocations.get(sourcePartnerAddress.getId());
      if (sourcePartnerAddress != null && targetPartnerAddress == null) {
        throw new IllegalStateException("Contact person's address was not transferred");
      }
      targetUser.setPartnerAddress(targetPartnerAddress);
      OBDal.getInstance().save(targetUser);
    }
  }

  private boolean sameContactPerson(User left, User right) {
    return Objects.equals(left.getName(), right.getName())
        && Objects.equals(left.getFirstName(), right.getFirstName())
        && Objects.equals(left.getLastName(), right.getLastName())
        && Objects.equals(left.getEmail(), right.getEmail());
  }

  private Category targetBusinessPartnerCategory(Category source,
      Client target, Organization targetOrg) {
    if (source == null) {
      return null;
    }
    if (ZERO_ID.equals(source.getClient().getId())) {
      return source;
    }
    Category existing = owner.unique(Category.class, CATEGORY_BY_NAME_QUERY, target.getId(),
        source.getName());
    if (existing != null) return existing;
    Category copy = OBProvider.getInstance().get(Category.class);
    copy.setClient(target); copy.setOrganization(targetOrg);
    DemoDataTransferReflection.copy(source, copy, "Name", SEARCH_KEY_PROPERTY, DESCRIPTION_PROPERTY);
    OBDal.getInstance().save(copy);
    return copy;
  }

}
