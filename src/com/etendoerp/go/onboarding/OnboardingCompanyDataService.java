package com.etendoerp.go.onboarding;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.geography.Location;

/**
 * ETP-5190 — the company details a tenant entered while creating its account, read back for the
 * First Steps checklist so the "Datos de tu empresa" row can show them instead of asking the
 * user to leave the page to find out what is already there.
 *
 * <p>Read from the ORGANISATION, not from the onboarding draft: the draft is cleared once
 * provisioning finishes ({@code clearOnboardingDraftBestEffort}), and after that the
 * organisation is the only place these values live — which is also what makes the panel stay
 * correct after the user edits them in the Organization window.
 *
 * <p>The four values span two tables: {@code name}/{@code socialName} are on {@code AD_Org},
 * while {@code taxID} and the address are on {@code AD_OrgInfo} (and its {@code C_Location}).
 * Everything is optional — a tenant that skipped a field gets {@code null} rather than an
 * error, because this panel is informational.
 */
public class OnboardingCompanyDataService {

  /** The organisation every provisioned GO tenant owns is a child of the {@code *} org. */
  private static final String ORG_STAR = "0";

  /** A read-only view of the tenant's company details. */
  public static final class CompanyData {
    private final String name;
    private final String tradeName;
    private final String taxId;
    private final String address;

    CompanyData(String name, String tradeName, String taxId, String address) {
      this.name = name;
      this.tradeName = tradeName;
      this.taxId = taxId;
      this.address = address;
    }

    public String getName() {
      return name;
    }

    public String getTradeName() {
      return tradeName;
    }

    public String getTaxId() {
      return taxId;
    }

    public String getAddress() {
      return address;
    }
  }

  /**
   * Reads back the company details the tenant entered when it created its account, for the
   * read-only summary the First Steps checklist shows above its "Configure" button (ETP-5190).
   *
   * <p>Runs in admin mode: the caller is the tenant's own admin, but the organisation and its
   * {@code OrganizationInformation} row are read by id rather than through the session's
   * accessible-org filter, so a client-admin sitting on organisation {@code '0'} still resolves
   * its own legal entity.
   *
   * @param clientId the tenant whose company details to read
   * @param orgId    the organisation to read them from
   * @return the tenant's company details, or {@code null} when the tenant has no organisation of
   *     its own yet (a tenant mid-provisioning, which the panel renders as "nothing to show").
   */
  public CompanyData read(String clientId, String orgId) {
    OBContext.setAdminMode(true);
    try {
      Organization organization = findOrganization(clientId, orgId);
      if (organization == null) {
        return null;
      }
      OrganizationInformation info = findOrganizationInformation(organization);
      return new CompanyData(
          organization.getName(),
          organization.getSocialName(),
          info == null ? null : info.getTaxID(),
          info == null ? null : formatAddress(info.getLocationAddress()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The tenant's own organisation: its session organisation when it has one, otherwise the
   * single real (non-{@code *}) organisation the client owns. The session organisation is
   * {@code *} for every GO tenant provisioned so far, which is exactly why the fallback exists.
   */
  private Organization findOrganization(String clientId, String orgId) {
    if (!StringUtils.equals(orgId, ORG_STAR)) {
      Organization organization = OBDal.getInstance().get(Organization.class, orgId);
      if (organization != null && StringUtils.equals(organization.getClient().getId(), clientId)) {
        return organization;
      }
    }
    OBQuery<Organization> query = OBDal.getInstance().createQuery(Organization.class,
        "as o where o.client.id = :clientId and o.id <> :star and o.active = true");
    query.setNamedParameter("clientId", clientId);
    query.setNamedParameter("star", ORG_STAR);
    // The caller runs with the system context, so the readable-client/organization filters would
    // drop every tenant row; scoping is the explicit, already-validated client id above.
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    return query.uniqueResult();
  }

  private OrganizationInformation findOrganizationInformation(Organization organization) {
    OBQuery<OrganizationInformation> query = OBDal.getInstance().createQuery(
        OrganizationInformation.class, "as oi where oi.organization.id = :orgId");
    query.setNamedParameter("orgId", organization.getId());
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    return query.uniqueResult();
  }

  /**
   * One human-readable line, skipping the parts the tenant left blank so a half-filled address
   * never renders as ", , 28001" — the panel shows what exists and nothing else.
   */
  private String formatAddress(Location location) {
    if (location == null) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    addIfPresent(parts, location.getAddressLine1());
    addIfPresent(parts, location.getAddressLine2());
    addIfPresent(parts, StringUtils.trimToNull(StringUtils.trimToEmpty(location.getPostalCode())
        + " " + StringUtils.trimToEmpty(location.getCityName())));
    if (location.getCountry() != null) {
      addIfPresent(parts, location.getCountry().getName());
    }
    return parts.isEmpty() ? null : String.join(", ", parts);
  }

  private void addIfPresent(List<String> parts, String value) {
    String trimmed = StringUtils.trimToNull(value);
    if (trimmed != null) {
      parts.add(trimmed);
    }
  }
}
