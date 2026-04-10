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

package com.etendoerp.go.onboarding;

/**
 * Mutable onboarding state shared across the step chain.
 */
public class OnboardingState {

  private String currencyId;
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

  public String getCurrencyId() {
    return currencyId;
  }

  public void setCurrencyId(String currencyId) {
    this.currencyId = currencyId;
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
