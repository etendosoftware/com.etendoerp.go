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

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.DalUtil;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.model.financialmgmt.payment.PaymentTerm;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.pricing.pricelist.PriceList;

/**
 * Fixtures the remittance integration tests build on the <b>F&B International Group</b> sample
 * client that every fresh Etendo install carries ({@code referencedata/sampledata} of the core),
 * so they run the same on a developer database and on the CI one. Nothing here depends on a
 * tenant created by hand (a GO client, its accounts, remittance types or invoices): what the
 * sample data lacks — an IBAN on the EUR bank account, a second EUR bank account, an open sales
 * due — is created in the caller's transaction, which the tests roll back.
 *
 * <p>Every id below belongs to the F&B sample data shipped with the core.
 */
final class RemittanceSampleDataFixtures {

  /** F&B International Group. */
  static final String CLIENT_ID = "23C59575B9CF467C9620760EB255B389";
  /** «F&B International Group Admin». */
  static final String ADMIN_ROLE_ID = "42D0EEB1C66F497A90DD526DC597E6F0";
  /** «F&B España, S.A», the legal entity that owns the EUR bank account. */
  static final String SPAIN_ORG_ID = "B843C30461EA4501935CB1D125C9C25A";
  /** «Cuenta de Banco»: the EUR bank account of F&B España, S.A. */
  static final String EUR_BANK_ACCOUNT_ID = "DEDDE613C5314ACD8DCC60C474D1A107";

  /** «AR Invoice» of F&B España, S.A. */
  private static final String SALES_INVOICE_DOC_TYPE_ID = "7FCD49652E104E6BB06C3A0D787412E3";
  /** «Restaurantes Luna Llena, S.A.», a Spanish customer with a tax ID, and its address. */
  static final String CUSTOMER_ID = "44CB679908C64EF5B852E8DB8A1AA061";
  private static final String CUSTOMER_LOCATION_ID = "6F0E64374DA9411CA39E8F059ACD8E1B";
  /** «Tarifa de ventas» (EUR), «30 days», «Transferencia». */
  private static final String SALES_PRICE_LIST_ID = "AEE66281A08F42B6BC509B8A80A33C29";
  private static final String PAYMENT_TERM_ID = "66BA1164A7394344BB9CD1A6ECEED05D";
  private static final String PAYMENT_METHOD_ID = "A97CFD2AFC234B59BB0A72189BD8FC2A";
  /** «Zumo de Piña 0,5L», «Entregas IVA 21%», unit. */
  private static final String PRODUCT_ID = "C970393BDF6C43E2B030D23482D88EED";
  private static final String VAT_21_ID = "696801EA1AAF46A4AF56E367B40459AE";
  private static final String UNIT_UOM_ID = "100";
  private static final String EUR_ID = "102";
  private static final String SPAIN_COUNTRY_ID = "106";

  /** A net amount whose 21 % VAT gives a gross of exactly {@link #SALES_DUE_AMOUNT}. */
  private static final BigDecimal SALES_NET_AMOUNT = new BigDecimal("33.81");
  /** The amount of every sales due {@link #openSalesDue} creates. */
  static final BigDecimal SALES_DUE_AMOUNT = new BigDecimal("40.91");

  private static int invoiceSequence;

  private RemittanceSampleDataFixtures() {
  }

  /** The F&B sample client. */
  static Client client() {
    Client client = OBDal.getInstance().get(Client.class, CLIENT_ID);
    assertNotNull("the F&B sample client", client);
    return client;
  }

  /** Runs as the F&B admin role on {@code orgId}, in administrator mode. */
  static void asSampleAdmin(String orgId) {
    OBContext.setOBContext("100", ADMIN_ROLE_ID, CLIENT_ID, orgId);
    OBContext.setAdminMode(true);
  }

  /** The EUR bank account of F&B España, S.A, given {@code iban} (Spain) in this transaction. */
  static FIN_FinancialAccount eurBankAccount(String iban) {
    FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class,
        EUR_BANK_ACCOUNT_ID);
    assertNotNull("the F&B EUR bank account", account);
    account.setCountry(OBDal.getInstance().get(Country.class, SPAIN_COUNTRY_ID));
    account.setIBAN(iban);
    OBDal.getInstance().save(account);
    OBDal.getInstance().flush();
    return account;
  }

  /** A second EUR bank account of the same organization, a copy of {@code template}. */
  static FIN_FinancialAccount anotherBankAccount(FIN_FinancialAccount template, String name,
      String iban) {
    FIN_FinancialAccount account = (FIN_FinancialAccount) DalUtil.copy(template, false);
    account.setName(name);
    account.setIBAN(iban);
    account.setDefault(false);
    account.set(RemittanceCreditorId.PROPERTY, null);
    OBDal.getInstance().save(account);
    OBDal.getInstance().flush();
    return account;
  }

  /**
   * The only due of a new sales invoice of {@link #CUSTOMER_ID} in the organization of {@code
   * account}, gross {@link #SALES_DUE_AMOUNT}, completed by {@code C_Invoice_Post} on the
   * session's own connection: nothing is committed. Two invoices of the same {@code invoiceDate}
   * share partner and due date, so a GGPD grouping merges their dues.
   */
  static FIN_PaymentScheduleDetail openSalesDue(FIN_FinancialAccount account,
      LocalDate invoiceDate) {
    Client client = account.getClient();
    Date date = day(invoiceDate);
    Invoice invoice = OBProvider.getInstance().get(Invoice.class);
    invoice.setClient(client);
    invoice.setOrganization(account.getOrganization());
    invoice.setSalesTransaction(true);
    DocumentType docType = OBDal.getInstance().get(DocumentType.class,
        SALES_INVOICE_DOC_TYPE_ID);
    assertNotNull("the F&B sales invoice document type", docType);
    invoice.setDocumentType(docType);
    invoice.setTransactionDocument(docType);
    invoice.setDocumentNo("SF-REM-IT-" + (++invoiceSequence));
    invoice.setDocumentStatus("DR");
    invoice.setDocumentAction("CO");
    invoice.setProcessed(false);
    invoice.setInvoiceDate(date);
    invoice.setAccountingDate(date);
    invoice.setTaxDate(date);
    BusinessPartner customer = OBDal.getInstance().get(BusinessPartner.class, CUSTOMER_ID);
    assertNotNull("the F&B customer", customer);
    invoice.setBusinessPartner(customer);
    invoice.setPartnerAddress(OBDal.getInstance().get(Location.class, CUSTOMER_LOCATION_ID));
    invoice.setCurrency(OBDal.getInstance().get(Currency.class, EUR_ID));
    invoice.setPriceList(OBDal.getInstance().get(PriceList.class, SALES_PRICE_LIST_ID));
    invoice.setPaymentTerms(OBDal.getInstance().get(PaymentTerm.class, PAYMENT_TERM_ID));
    invoice.setPaymentMethod(OBDal.getInstance().get(FIN_PaymentMethod.class, PAYMENT_METHOD_ID));
    OBDal.getInstance().save(invoice);

    InvoiceLine line = OBProvider.getInstance().get(InvoiceLine.class);
    line.setClient(client);
    line.setOrganization(invoice.getOrganization());
    line.setInvoice(invoice);
    line.setLineNo(10L);
    line.setProduct(OBDal.getInstance().get(Product.class, PRODUCT_ID));
    line.setUOM(OBDal.getInstance().get(UOM.class, UNIT_UOM_ID));
    line.setInvoicedQuantity(BigDecimal.ONE);
    line.setUnitPrice(SALES_NET_AMOUNT);
    line.setListPrice(SALES_NET_AMOUNT);
    line.setStandardPrice(SALES_NET_AMOUNT);
    line.setPriceLimit(BigDecimal.ZERO);
    line.setLineNetAmount(SALES_NET_AMOUNT);
    line.setTaxableAmount(SALES_NET_AMOUNT);
    line.setTax(OBDal.getInstance().get(TaxRate.class, VAT_21_ID));
    invoice.getInvoiceLineList().add(line);
    OBDal.getInstance().save(line);
    OBDal.getInstance().flush();

    String invoiceId = invoice.getId();
    OBDal.getInstance().getSession().doWork(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "select c_invoice_post(null, ?)")) {
        statement.setString(1, invoiceId);
        statement.execute();
      }
    });
    OBDal.getInstance().refresh(invoice);
    assertEquals("the sales invoice is completed", "CO", invoice.getDocumentStatus());

    @SuppressWarnings("unchecked")
    List<FIN_PaymentScheduleDetail> dues = OBDal.getInstance().getSession().createQuery(
        "from FIN_Payment_ScheduleDetail d where d.invoicePaymentSchedule.invoice.id = :invoice"
            + " and d.paymentDetails is null and d.canceled = false")
        .setParameter("invoice", invoiceId)
        .list();
    assertEquals("the completed invoice has one open due", 1, dues.size());
    FIN_PaymentScheduleDetail due = dues.get(0);
    assertEquals("the due is the invoice's gross amount", 0,
        SALES_DUE_AMOUNT.compareTo(due.getAmount()));
    return due;
  }

  static Date day(LocalDate date) {
    return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
  }
}
