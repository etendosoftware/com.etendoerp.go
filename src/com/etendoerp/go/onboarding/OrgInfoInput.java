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
 * The signup form's fiscal/address data the {@code orgInfo} step persists.
 *
 * <p>{@code streetName}/{@code buildingNumber}/{@code buildingName}/{@code cityName} (ETP-5806)
 * are the structured address fields from the onboarding company step; {@code address} is kept as
 * a legacy free-text fallback used only when none of the structured fields is present — see
 * {@link OnboardingOrgInfoService#applyAddressFields}.
 *
 * <p>{@code accountEmail} (ETP-5806 follow-up) is the {@code ETGO_ACCOUNT} email the
 * {@code structured-address-for-sepa} flag is evaluated for when the address is persisted.
 * Provisioning runs under an admin execution context with no caller identity to recover, so the
 * account has to be carried here from the request that started it; blank for a pooled
 * placeholder tenant, which belongs to no account and has no address to compose anyway.
 *
 * <p>It lives in this package, not beside {@code OnboardingProvisioningChain} in
 * {@code com.etendoerp.go.rest}, because the service that consumes it is here and the dependency
 * only ever points this way (rest → onboarding, never back).
 */
public record OrgInfoInput(String countryCode, String address, String streetName,
    String buildingNumber, String buildingName, String cityName, String taxId,
    String accountEmail) {

  /** All fields unset — what a caller with no signup data to pass means. */
  public static final OrgInfoInput EMPTY =
      new OrgInfoInput(null, null, null, null, null, null, null, null);

  /**
   * A build that has no signup data at all — the tenant pool's, which provisions a placeholder
   * tenant standardized on one country and belonging to no account yet.
   *
   * @param countryCode the ISO country code to locate the organization in
   * @return an input carrying only that country
   */
  public static OrgInfoInput countryOnly(String countryCode) {
    return new OrgInfoInput(countryCode, null, null, null, null, null, null, null);
  }

  /**
   * Normalizes a possibly absent input, so every consumer can read its fields without a null
   * check of its own.
   *
   * @param input the input to normalize, possibly {@code null}
   * @return {@code input}, or {@link #EMPTY} when it is {@code null}
   */
  public static OrgInfoInput orEmpty(OrgInfoInput input) {
    return input == null ? EMPTY : input;
  }
}
