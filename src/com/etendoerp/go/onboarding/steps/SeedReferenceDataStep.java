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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.onboarding.steps;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;

import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.Category;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.OrgWarehouse;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.geography.Location;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.plm.ProductCategory;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.financialmgmt.calendar.Calendar;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.model.financialmgmt.tax.TaxCategory;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.PriceListSchema;
import org.openbravo.model.pricing.pricelist.PriceListVersion;
import org.openbravo.model.pricing.pricelist.ProductPrice;

import com.etendoerp.go.onboarding.OnboardingContext;
import com.etendoerp.go.onboarding.OnboardingStep;
import com.etendoerp.go.onboarding.OnboardingStepException;

/**
 * Creates all reference data for a new client/organization in strict dependency order.
 * Resolves shared system-level entities (currency, country, UOM) and creates
 * organization-specific entities (calendar, warehouse, price lists, BP categories,
 * product category, tax, product, financial accounts, payment methods).
 */
public class SeedReferenceDataStep implements OnboardingStep {

  @Override
  public String name() {
    return "seedReferenceData";
  }

  @Override
  public void execute(OnboardingContext ctx) throws OnboardingStepException {
    try {
      Client client = OBDal.getInstance().get(Client.class, ctx.getClientId());
      Organization org = OBDal.getInstance().get(Organization.class, ctx.getOrgId());
      if (client == null) {
        throw new OBException("Client not found with ID: " + ctx.getClientId());
      }
      if (org == null) {
        throw new OBException("Organization not found with ID: " + ctx.getOrgId());
      }

      // 1. Resolve shared entities from client 0
      Currency currency = resolveCurrency(ctx.getCurrencyCode());
      Country country = resolveCountry(ctx.getCountryCode());
      UOM uom = resolveUOM();

      // 2. Create locations
      Location whLocation = createLocation(client, org, country, "WH Street", null);
      createLocation(client, org, country, "Street 123", "Springfield");

      // 3. Create calendar with years and periods
      Calendar calendar = createCalendar(client, org);
      int currentYear = Year.now().getValue();
      createYearWithPeriods(client, org, calendar, currentYear);
      createYearWithPeriods(client, org, calendar, currentYear + 1);
      ctx.setCalendarId(calendar.getId());

      // 4. Create warehouse and link to org
      Warehouse warehouse = createWarehouse(client, org, whLocation);
      createOrgWarehouse(client, org, warehouse);
      ctx.setWarehouseId(warehouse.getId());

      // 5. Create price lists (sales + purchase) with versions
      PriceList salesPL = createPriceList(client, org, currency, "Default Sales", true);
      PriceListVersion salesPLV = createPriceListVersion(client, org, salesPL);
      ctx.setPriceListSalesId(salesPL.getId());

      PriceList purchasePL = createPriceList(client, org, currency, "Default Supplier Price List",
          false);
      PriceListVersion purchasePLV = createPriceListVersion(client, org, purchasePL);
      ctx.setPriceListPurchaseId(purchasePL.getId());

      // 6. Create BP categories
      createBPCategory(client, org, "CUS_T1", "Customer - Tier 1");
      createBPCategory(client, org, "SUP", "Supplier");

      // 7. Create product category
      ProductCategory productCategory = createProductCategory(client, org);
      ctx.setProductCategoryId(productCategory.getId());

      // 8. Create tax category and tax rate
      TaxCategory taxCategory = createTaxCategory(client, org);
      createTaxRate(client, org, taxCategory);
      ctx.setTaxCategoryId(taxCategory.getId());

      // 9. Create product with prices in both price list versions
      Product product = createProduct(client, org, uom, productCategory, taxCategory);
      createProductPrice(client, org, salesPLV, product, new BigDecimal("10"));
      createProductPrice(client, org, purchasePLV, product, new BigDecimal("7"));

      // 10. Create financial accounts (cash + bank)
      FIN_FinancialAccount cashAccount = createFinancialAccount(client, org, currency,
          "Default FinAcc - Cash", "C");
      FIN_FinancialAccount bankAccount = createFinancialAccount(client, org, currency,
          "Default FinAcc - Bank", "B");
      ctx.setFinancialAccountId(cashAccount.getId());

      // 11. Create payment methods
      FIN_PaymentMethod cashMethod = createPaymentMethod(client, org, "Cash", "DEP", "WIT");
      FIN_PaymentMethod bankMethod = createPaymentMethod(client, org, "Bank Transfer", null, null);

      // 12. Link payment methods to financial accounts (4 combinations)
      createFinAccPaymentMethod(client, org, cashAccount, cashMethod, "DEP", "WIT");
      createFinAccPaymentMethod(client, org, cashAccount, bankMethod, null, null);
      createFinAccPaymentMethod(client, org, bankAccount, cashMethod, "DEP", "WIT");
      createFinAccPaymentMethod(client, org, bankAccount, bankMethod, null, null);

      // 13. Update org with calendar, currency, and period control
      org.setCalendar(calendar);
      org.setCurrency(currency);
      org.setAllowPeriodControl(true);
      OBDal.getInstance().save(org);

      OBDal.getInstance().flush();
    } catch (OnboardingStepException e) {
      throw e;
    } catch (Exception e) {
      throw new OnboardingStepException(e.getMessage(), e);
    }
  }

  private Currency resolveCurrency(String isoCode) throws Exception {
    OBCriteria<Currency> criteria = OBDal.getInstance().createCriteria(Currency.class);
    criteria.add(Restrictions.eq(Currency.PROPERTY_ISOCODE, isoCode));
    criteria.setMaxResults(1);
    Currency currency = (Currency) criteria.uniqueResult();
    if (currency == null) {
      throw new OBException("Currency not found for ISO code: " + isoCode);
    }
    return currency;
  }

  private Country resolveCountry(String isoCountryCode) throws Exception {
    OBCriteria<Country> criteria = OBDal.getInstance().createCriteria(Country.class);
    criteria.add(Restrictions.eq(Country.PROPERTY_ISOCOUNTRYCODE, isoCountryCode));
    criteria.setMaxResults(1);
    Country country = (Country) criteria.uniqueResult();
    if (country == null) {
      throw new OBException("Country not found for ISO code: " + isoCountryCode);
    }
    return country;
  }

  private UOM resolveUOM() throws Exception {
    OBCriteria<UOM> criteria = OBDal.getInstance().createCriteria(UOM.class);
    criteria.add(Restrictions.eq(UOM.PROPERTY_NAME, "Unit"));
    criteria.setMaxResults(1);
    UOM uom = (UOM) criteria.uniqueResult();
    if (uom == null) {
      throw new OBException("UOM 'Unit' not found in system data");
    }
    return uom;
  }

  private Location createLocation(Client client, Organization org, Country country,
      String address, String city) {
    Location location = OBProvider.getInstance().get(Location.class);
    location.setNewOBObject(true);
    location.setClient(client);
    location.setOrganization(org);
    location.setAddressLine1(address);
    location.setCountry(country);
    if (city != null) {
      location.setCityName(city);
    }
    OBDal.getInstance().save(location);
    return location;
  }

  private Calendar createCalendar(Client client, Organization org) {
    Calendar calendar = OBProvider.getInstance().get(Calendar.class);
    calendar.setNewOBObject(true);
    calendar.setClient(client);
    calendar.setOrganization(org);
    calendar.setName("Fiscal Calendar");
    OBDal.getInstance().save(calendar);
    return calendar;
  }

  private void createYearWithPeriods(Client client, Organization org, Calendar calendar,
      int yearValue) {
    org.openbravo.model.financialmgmt.calendar.Year year = OBProvider.getInstance()
        .get(org.openbravo.model.financialmgmt.calendar.Year.class);
    year.setNewOBObject(true);
    year.setClient(client);
    year.setOrganization(org);
    year.setFiscalYear(String.valueOf(yearValue));
    year.setCalendar(calendar);
    OBDal.getInstance().save(year);

    for (int month = 1; month <= 12; month++) {
      Period period = OBProvider.getInstance().get(Period.class);
      period.setNewOBObject(true);
      period.setClient(client);
      period.setOrganization(org);
      period.setName(getMonthName(month) + " " + yearValue);
      period.setPeriodNo((long) month);
      period.setYear(year);
      period.setPeriodType("S");

      LocalDate startDate = LocalDate.of(yearValue, month, 1);
      LocalDate endDate = startDate.withDayOfMonth(startDate.lengthOfMonth());
      period.setStartingDate(java.sql.Date.valueOf(startDate));
      period.setEndingDate(java.sql.Date.valueOf(endDate));

      OBDal.getInstance().save(period);
    }
  }

  private Warehouse createWarehouse(Client client, Organization org, Location location) {
    Warehouse warehouse = OBProvider.getInstance().get(Warehouse.class);
    warehouse.setNewOBObject(true);
    warehouse.setClient(client);
    warehouse.setOrganization(org);
    warehouse.setSearchKey("DEFAULT_WH");
    warehouse.setName("Default Warehouse");
    warehouse.setLocationAddress(location);
    warehouse.setStorageBinSeparator("*");
    OBDal.getInstance().save(warehouse);
    return warehouse;
  }

  private void createOrgWarehouse(Client client, Organization org, Warehouse warehouse) {
    OrgWarehouse orgWarehouse = OBProvider.getInstance().get(OrgWarehouse.class);
    orgWarehouse.setNewOBObject(true);
    orgWarehouse.setClient(client);
    orgWarehouse.setOrganization(org);
    orgWarehouse.setWarehouse(warehouse);
    OBDal.getInstance().save(orgWarehouse);
  }

  private PriceList createPriceList(Client client, Organization org, Currency currency,
      String name, boolean isSales) {
    PriceList priceList = OBProvider.getInstance().get(PriceList.class);
    priceList.setNewOBObject(true);
    priceList.setClient(client);
    priceList.setOrganization(org);
    priceList.setName(name);
    priceList.setCurrency(currency);
    priceList.setSalesPriceList(isSales);
    priceList.setPriceIncludesTax(false);
    priceList.setDefault(false);
    OBDal.getInstance().save(priceList);
    return priceList;
  }

  private PriceListVersion createPriceListVersion(Client client, Organization org,
      PriceList priceList) {
    // PriceListSchema (discount schema) is required by PriceListVersion
    PriceListSchema schema = OBProvider.getInstance().get(PriceListSchema.class);
    schema.setNewOBObject(true);
    schema.setClient(client);
    schema.setOrganization(org);
    schema.setName(priceList.getName() + " Schema");
    OBDal.getInstance().save(schema);

    PriceListVersion version = OBProvider.getInstance().get(PriceListVersion.class);
    version.setNewOBObject(true);
    version.setClient(client);
    version.setOrganization(org);
    version.setName(priceList.getName() + " " + Year.now().getValue());
    version.setPriceList(priceList);
    version.setPriceListSchema(schema);
    LocalDate firstOfYear = LocalDate.of(Year.now().getValue(), 1, 1);
    version.setValidFromDate(java.sql.Date.valueOf(firstOfYear));
    OBDal.getInstance().save(version);
    return version;
  }

  private void createBPCategory(Client client, Organization org, String searchKey, String name) {
    Category category = OBProvider.getInstance().get(Category.class);
    category.setNewOBObject(true);
    category.setClient(client);
    category.setOrganization(org);
    category.setSearchKey(searchKey);
    category.setName(name);
    category.setDefault(false);
    OBDal.getInstance().save(category);
  }

  private ProductCategory createProductCategory(Client client, Organization org) {
    ProductCategory category = OBProvider.getInstance().get(ProductCategory.class);
    category.setNewOBObject(true);
    category.setClient(client);
    category.setOrganization(org);
    category.setSearchKey("OTHERS");
    category.setName("Others");
    category.setDefault(false);
    category.setPlannedMargin(BigDecimal.ZERO);
    OBDal.getInstance().save(category);
    return category;
  }

  private TaxCategory createTaxCategory(Client client, Organization org) {
    TaxCategory taxCategory = OBProvider.getInstance().get(TaxCategory.class);
    taxCategory.setNewOBObject(true);
    taxCategory.setClient(client);
    taxCategory.setOrganization(org);
    taxCategory.setName("Default Tax");
    taxCategory.setDefault(false);
    OBDal.getInstance().save(taxCategory);
    return taxCategory;
  }

  private void createTaxRate(Client client, Organization org, TaxCategory taxCategory) {
    TaxRate taxRate = OBProvider.getInstance().get(TaxRate.class);
    taxRate.setNewOBObject(true);
    taxRate.setClient(client);
    taxRate.setOrganization(org);
    taxRate.setName("Default Tax Rate");
    taxRate.setTaxCategory(taxCategory);
    taxRate.setRate(new BigDecimal("5"));
    taxRate.setSummaryLevel(false);
    taxRate.setDefault(false);
    taxRate.setTaxExempt(false);
    taxRate.setSalesPurchaseType("B");
    taxRate.setCascade(false);
    taxRate.setLineNo(10L);
    LocalDate validFrom = LocalDate.of(Year.now().getValue(), 1, 1);
    taxRate.setValidFromDate(java.sql.Date.valueOf(validFrom));
    OBDal.getInstance().save(taxRate);
  }

  private Product createProduct(Client client, Organization org, UOM uom,
      ProductCategory productCategory, TaxCategory taxCategory) {
    Product product = OBProvider.getInstance().get(Product.class);
    product.setNewOBObject(true);
    product.setClient(client);
    product.setOrganization(org);
    product.setSearchKey("DEFAULT_PROD");
    product.setName("Default Product");
    product.setUOM(uom);
    product.setProductCategory(productCategory);
    product.setTaxCategory(taxCategory);
    product.setProductType("I");
    product.setStocked(true);
    product.setPurchase(true);
    product.setSale(true);
    product.setSummaryLevel(false);
    product.setBillOfMaterials(false);
    OBDal.getInstance().save(product);
    return product;
  }

  private void createProductPrice(Client client, Organization org,
      PriceListVersion priceListVersion, Product product, BigDecimal price) {
    ProductPrice productPrice = OBProvider.getInstance().get(ProductPrice.class);
    productPrice.setNewOBObject(true);
    productPrice.setClient(client);
    productPrice.setOrganization(org);
    productPrice.setPriceListVersion(priceListVersion);
    productPrice.setProduct(product);
    productPrice.setListPrice(price);
    productPrice.setStandardPrice(price);
    productPrice.setPriceLimit(BigDecimal.ZERO);
    OBDal.getInstance().save(productPrice);
  }

  private FIN_FinancialAccount createFinancialAccount(Client client, Organization org,
      Currency currency, String name, String type) {
    FIN_FinancialAccount account = OBProvider.getInstance().get(FIN_FinancialAccount.class);
    account.setNewOBObject(true);
    account.setClient(client);
    account.setOrganization(org);
    account.setName(name);
    account.setCurrency(currency);
    account.setType(type);
    account.setCurrentBalance(BigDecimal.ZERO);
    account.setInitialBalance(BigDecimal.ZERO);
    account.setCreditLimit(BigDecimal.ZERO);
    account.setDefault(false);
    OBDal.getInstance().save(account);
    return account;
  }

  private FIN_PaymentMethod createPaymentMethod(Client client, Organization org, String name,
      String uponDepositUse, String uponWithdrawalUse) {
    FIN_PaymentMethod method = OBProvider.getInstance().get(FIN_PaymentMethod.class);
    method.setNewOBObject(true);
    method.setClient(client);
    method.setOrganization(org);
    method.setName(name);
    method.setDescription(name);
    method.setAutomaticReceipt(false);
    method.setAutomaticPayment(false);
    method.setAutomaticDeposit(true);
    method.setAutomaticWithdrawn(true);
    method.setPayinAllow(true);
    method.setPayoutAllow(true);
    method.setPayinExecutionType("M");
    method.setPayoutExecutionType("M");
    method.setPayinDeferred(false);
    method.setPayoutDeferred(false);
    method.setPayinIsMulticurrency(false);
    method.setPayoutIsMulticurrency(false);
    if (uponDepositUse != null) {
      method.setUponDepositUse(uponDepositUse);
    }
    if (uponWithdrawalUse != null) {
      method.setUponWithdrawalUse(uponWithdrawalUse);
    }
    if (uponDepositUse == null) {
      // Bank Transfer uses clearing
      method.setINUponClearingUse("CLE");
      method.setOUTUponClearingUse("CLE");
    }
    OBDal.getInstance().save(method);
    return method;
  }

  private void createFinAccPaymentMethod(Client client, Organization org,
      FIN_FinancialAccount account, FIN_PaymentMethod method,
      String uponDepositUse, String uponWithdrawalUse) {
    FinAccPaymentMethod fapm = OBProvider.getInstance().get(FinAccPaymentMethod.class);
    fapm.setNewOBObject(true);
    fapm.setClient(client);
    fapm.setOrganization(org);
    fapm.setAccount(account);
    fapm.setPaymentMethod(method);
    fapm.setAutomaticReceipt(false);
    fapm.setAutomaticPayment(false);
    fapm.setAutomaticDeposit(true);
    fapm.setAutomaticWithdrawn(true);
    fapm.setPayinAllow(true);
    fapm.setPayoutAllow(true);
    fapm.setPayinExecutionType("M");
    fapm.setPayoutExecutionType("M");
    fapm.setPayinDeferred(false);
    fapm.setPayoutDeferred(false);
    fapm.setPayinIsMulticurrency(false);
    fapm.setPayoutIsMulticurrency(false);
    fapm.setDefault(false);
    fapm.setPayinInvoicepaidstatus("RPR");
    fapm.setPayoutInvoicepaidstatus("PPM");
    if (uponDepositUse != null) {
      fapm.setUponDepositUse(uponDepositUse);
      fapm.setUponWithdrawalUse(uponWithdrawalUse);
    } else {
      fapm.setINUponClearingUse("CLE");
      fapm.setOUTUponClearingUse("CLE");
    }
    OBDal.getInstance().save(fapm);
  }

  private String getMonthName(int month) {
    switch (month) {
      case 1: return "January";
      case 2: return "February";
      case 3: return "March";
      case 4: return "April";
      case 5: return "May";
      case 6: return "June";
      case 7: return "July";
      case 8: return "August";
      case 9: return "September";
      case 10: return "October";
      case 11: return "November";
      case 12: return "December";
      default: return "Unknown";
    }
  }
}
