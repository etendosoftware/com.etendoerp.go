package com.etendoerp.go.onboarding;

public class OnboardingContext {

  // Input (set once from request)
  private String clientName;
  private String orgName;
  private String adminUser;
  private String adminPassword;
  private String currencyCode;
  private String languageCode;
  private String countryCode;

  // Accumulated IDs (set by steps, read by subsequent steps)
  private String clientId;
  private String orgId;
  private String clientAdminUserId;
  private String orgAdminUserId;
  private String roleId;
  private String warehouseId;
  private String calendarId;
  private String priceListSalesId;
  private String priceListPurchaseId;
  private String financialAccountId;
  private String productCategoryId;
  private String taxCategoryId;

  // Getters and setters

  public String getClientName() {
    return clientName;
  }

  public void setClientName(String clientName) {
    this.clientName = clientName;
  }

  public String getOrgName() {
    return orgName;
  }

  public void setOrgName(String orgName) {
    this.orgName = orgName;
  }

  public String getAdminUser() {
    return adminUser;
  }

  public void setAdminUser(String adminUser) {
    this.adminUser = adminUser;
  }

  public String getAdminPassword() {
    return adminPassword;
  }

  public void setAdminPassword(String adminPassword) {
    this.adminPassword = adminPassword;
  }

  public String getCurrencyCode() {
    return currencyCode;
  }

  public void setCurrencyCode(String currencyCode) {
    this.currencyCode = currencyCode;
  }

  public String getLanguageCode() {
    return languageCode;
  }

  public void setLanguageCode(String languageCode) {
    this.languageCode = languageCode;
  }

  public String getCountryCode() {
    return countryCode;
  }

  public void setCountryCode(String countryCode) {
    this.countryCode = countryCode;
  }

  public String getClientId() {
    return clientId;
  }

  public void setClientId(String clientId) {
    this.clientId = clientId;
  }

  public String getOrgId() {
    return orgId;
  }

  public void setOrgId(String orgId) {
    this.orgId = orgId;
  }

  public String getClientAdminUserId() {
    return clientAdminUserId;
  }

  public void setClientAdminUserId(String clientAdminUserId) {
    this.clientAdminUserId = clientAdminUserId;
  }

  public String getOrgAdminUserId() {
    return orgAdminUserId;
  }

  public void setOrgAdminUserId(String orgAdminUserId) {
    this.orgAdminUserId = orgAdminUserId;
  }

  public String getRoleId() {
    return roleId;
  }

  public void setRoleId(String roleId) {
    this.roleId = roleId;
  }

  public String getWarehouseId() {
    return warehouseId;
  }

  public void setWarehouseId(String warehouseId) {
    this.warehouseId = warehouseId;
  }

  public String getCalendarId() {
    return calendarId;
  }

  public void setCalendarId(String calendarId) {
    this.calendarId = calendarId;
  }

  public String getPriceListSalesId() {
    return priceListSalesId;
  }

  public void setPriceListSalesId(String priceListSalesId) {
    this.priceListSalesId = priceListSalesId;
  }

  public String getPriceListPurchaseId() {
    return priceListPurchaseId;
  }

  public void setPriceListPurchaseId(String priceListPurchaseId) {
    this.priceListPurchaseId = priceListPurchaseId;
  }

  public String getFinancialAccountId() {
    return financialAccountId;
  }

  public void setFinancialAccountId(String financialAccountId) {
    this.financialAccountId = financialAccountId;
  }

  public String getProductCategoryId() {
    return productCategoryId;
  }

  public void setProductCategoryId(String productCategoryId) {
    this.productCategoryId = productCategoryId;
  }

  public String getTaxCategoryId() {
    return taxCategoryId;
  }

  public void setTaxCategoryId(String taxCategoryId) {
    this.taxCategoryId = taxCategoryId;
  }
}
