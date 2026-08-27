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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.geography.Location;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * Country + IBAN support logic for {@link FinancialAccountHandler} (ETP-4896).
 *
 * <p>Extracted out of {@code FinancialAccountHandler} purely to keep that class under the Sonar
 * method-count threshold (java:S1448) — the handler was already at 29 methods against a 35
 * ceiling in this codebase, same rationale as {@link FinancialAccountDeleteSupport}. No CDI
 * annotation on purpose: this is a plain static utility, never looked up by qualifier.
 *
 * <p>The IBAN mod-97 checksum is implemented here rather than reused from
 * {@code SaltEdgeAccountLinkHelper.isIbanValidMod97} in the {@code com.etendoerp.psd2.bank.integration}
 * module: a cross-module visibility change was deliberately avoided for this ticket, so the two
 * checksum implementations exist independently. If they are ever unified, that is a separate,
 * cross-module change.
 */
final class FinancialAccountCountrySupport {

  private static final Logger log = LogManager.getLogger(FinancialAccountCountrySupport.class);

  /** ISO 13616 defines 15 as the shortest real IBAN (Norway); nothing shorter is worth checking
   *  against a country at all. */
  static final int IBAN_MIN_LENGTH = 15;

  private static final String KEY_ID = "id";
  private static final String KEY_ISO = "iso";
  private static final String KEY_NAME = "name";
  private static final String KEY_IBAN_PREFIX = "ibanPrefix";
  private static final String KEY_IBAN_LENGTH = "ibanLength";

  /** The product's implicit home country (ETP-4896): {@code OnboardingOrgInfoService
   *  .DEFAULT_COUNTRY_ISO}, {@code FinancialAccountBankConnectionHandler.DEFAULT_PROVIDER_COUNTRY}
   *  and {@code SyncBankProviders.COUNTRY_CODE_ES} already treat ES this way. Used only as the
   *  last-resort default when no organization in the tree has a location with a country. */
  private static final String FALLBACK_IBAN_COUNTRY = "ES";

  /**
   * The {@code countryIbanRules} catalog (≤45 countries with IBAN metadata, out of 243) is static
   * master data shared by every client, so a single cached entry is enough — unlike
   * {@code FinancialAccountBankConnectionHandler.PROVIDERS_CACHE}, which is keyed per client
   * because Salt Edge providers differ by API key. 24h TTL mirrors that same cache: editing a
   * Country's IBAN metadata takes up to a day to reach the SPA, accepted for the same reason.
   */
  private static final Cache<String, String> IBAN_RULES_CACHE = CacheBuilder.newBuilder()
      .maximumSize(1)
      .expireAfterWrite(24, TimeUnit.HOURS)
      .build();
  private static final String IBAN_RULES_CACHE_KEY = "countryIbanRules";

  private FinancialAccountCountrySupport() {
  }

  // ---------------------------------------------------------------------------
  // Normalisation and checksum
  // ---------------------------------------------------------------------------

  /**
   * Strips every non-alphanumeric character and upper-cases. Superset of the SPA's
   * {@code normalizeIban()} ({@code \s+} only) and the trigger's {@code REPLACE(iban,' ','')}, so
   * the value validated here is byte-for-byte the value {@code FIN_FINANCIAL_ACCOUNT_TRG2} will
   * see after its own normalisation.
   */
  static String normalizeIban(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
  }

  /**
   * ISO 13616 mod-97 checksum. Assumes {@code iban} is already {@link #normalizeIban(String)
   * normalized}; returns {@code false} for anything that does not match the basic
   * {@code CCppBBAN...} shape instead of throwing.
   */
  static boolean isChecksumValid(String iban) {
    if (iban == null || !iban.matches("^[A-Z]{2}\\d{2}[A-Z0-9]+$")) {
      return false;
    }
    String rearranged = iban.substring(4) + iban.substring(0, 4);
    StringBuilder numeric = new StringBuilder(rearranged.length() * 2);
    for (int i = 0; i < rearranged.length(); i++) {
      char ch = rearranged.charAt(i);
      if (Character.isDigit(ch)) {
        numeric.append(ch);
      } else {
        numeric.append(ch - 'A' + 10);
      }
    }
    return new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
  }

  // ---------------------------------------------------------------------------
  // The (IBAN, country) pair validator
  // ---------------------------------------------------------------------------

  /**
   * Validates the pair that {@code FIN_FINANCIAL_ACCOUNT_TRG2} /
   * {@code C_GET_IBAN_DISPLAYED_ACCOUNT} would otherwise reject at the database, surfacing a
   * friendly message instead of the 500 {@code NeoErrorSanitizer} maps every PL/SQL exception to.
   *
   * <p>Deliberately checked in a different order than the PL/SQL function, which runs the mod-97
   * checksum first: a prefix or length mismatch is the actionable, user-caused error (the country
   * they picked does not match what they typed), and reporting it as "invalid check digits" would
   * be actively misleading — a truncated IBAN fails both, and the user needs to hear "wrong length
   * for Spain", not "bad checksum". The DB's ordering is irrelevant here because this method
   * short-circuits before the database ever runs.
   *
   * <p><b>The returned strings are a wire contract with the SPA (ETP-4896 QA follow-up).</b> The
   * error envelope carries no machine-readable {@code code} (only {@code message}), so
   * {@code tools/app-shell/src/lib/backendErrors.js} recognises these messages by their literal
   * text — exact-match for the fixed ones, prefix/suffix matchers for the three that interpolate.
   * Rewording any of them silently drops the user back to untranslated English; update the
   * matchers and their {@code backendError.*} locale keys in the same change.
   *
   * @param iban    already {@link #normalizeIban(String) normalized}; assumed non-blank — callers
   *                only invoke this once they know the effective IBAN is present.
   * @param country the effective country for the account; may be {@code null}.
   * @return {@code null} when the pair is acceptable, otherwise the message to return as a 400.
   */
  static String validateIbanCountryPair(String iban, Country country) {
    if (iban.length() < IBAN_MIN_LENGTH) {
      return "The IBAN is too short.";
    }
    if (country == null) {
      return "A bank account with an IBAN must have a country.";
    }
    String ibanPrefix = country.getIBANCode();
    Long ibanLength = country.getIBANLength();
    if (StringUtils.isBlank(ibanPrefix) || ibanLength == null) {
      return String.format("%s has no IBAN configuration, so it cannot be used on an account "
          + "with an IBAN.", country.getName());
    }
    String actualPrefix = iban.substring(0, 2);
    if (!ibanPrefix.equalsIgnoreCase(actualPrefix)) {
      return String.format("The IBAN starts with '%s' but the selected country is %s (%s).",
          actualPrefix, country.getName(), ibanPrefix);
    }
    if (ibanLength.intValue() != iban.length()) {
      return String.format("An IBAN for %s must have %d characters (received %d).",
          country.getName(), ibanLength.intValue(), iban.length());
    }
    if (!isChecksumValid(iban)) {
      return "The IBAN is not valid: the check digits do not match.";
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Country resolution
  // ---------------------------------------------------------------------------

  /**
   * Resolves the {@link Country} an IBAN belongs to from its first two characters, preferring a
   * match on {@link Country#PROPERTY_IBANCODE} over {@link Country#PROPERTY_ISOCOUNTRYCODE}: only
   * ~45 of 243 seeded countries carry IBAN metadata, and matching on the plain ISO code can return
   * one of the other ~198, which {@code FIN_FINANCIAL_ACCOUNT_TRG2} then rejects. The ISO match is
   * kept as a fallback for datasets where {@code IBANCOUNTRY} was never populated.
   *
   * @return the matching country, or {@code null} when the IBAN is too short or no active country
   *         matches the prefix either way.
   */
  static Country resolveCountryForIbanPrefix(String normalizedIban) {
    if (normalizedIban == null || normalizedIban.length() < 2) {
      return null;
    }
    String prefix = normalizedIban.substring(0, 2).toUpperCase(Locale.ROOT);
    Country byIbanCode = findCountryByIbanCode(prefix);
    if (byIbanCode != null) {
      return byIbanCode;
    }
    return findCountryByIsoCode(prefix);
  }

  /**
   * The active organization's country (ETP-4896 requirement 1), walking up the org tree when the
   * organization itself has no location, and falling back to Spain when nothing in the tree does
   * — the same implicit home-country convention {@code OnboardingOrgInfoService} already applies.
   * Never returns the AD-seeded {@code ISDEFAULT='Y'} country (United States, no IBAN metadata):
   * callers must get a usable value or nothing, never a plausible-but-wrong one.
   *
   * @return the resolved country, or {@code null} when even the ES fallback is missing from this
   *         instance's {@code C_COUNTRY} data — callers must omit the default entirely in that case.
   */
  static Country resolveOrganizationCountry(String orgId) {
    if (StringUtils.isNotBlank(orgId)) {
      Country ownCountry = countryFromOrgLocation(orgId);
      if (ownCountry != null) {
        return ownCountry;
      }
      OrganizationStructureProvider structureProvider = new OrganizationStructureProvider();
      List<String> ancestors = structureProvider.getParentList(orgId, false);
      for (String ancestorId : ancestors) {
        Country ancestorCountry = countryFromOrgLocation(ancestorId);
        if (ancestorCountry != null) {
          return ancestorCountry;
        }
      }
    }
    Country fallback = findCountryByIbanCode(FALLBACK_IBAN_COUNTRY);
    if (fallback == null) {
      log.warn("No country resolved for organization {} (nor its ancestors), and the fallback "
          + "country '{}' is missing from C_Country — omitting the default entirely.",
          orgId, FALLBACK_IBAN_COUNTRY);
      return null;
    }
    if (StringUtils.isBlank(orgId)) {
      return fallback;
    }
    log.warn("No country found for organization {} or its ancestors; falling back to '{}'.",
        orgId, FALLBACK_IBAN_COUNTRY);
    return fallback;
  }

  private static Country countryFromOrgLocation(String orgId) {
    OrganizationInformation orgInfo = OBDal.getReadOnlyInstance().get(OrganizationInformation.class, orgId);
    if (orgInfo == null) {
      return null;
    }
    Location location = orgInfo.getLocationAddress();
    return location != null ? location.getCountry() : null;
  }

  private static Country findCountryByIbanCode(String ibanCode) {
    OBCriteria<Country> criteria = OBDal.getInstance().createCriteria(Country.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(Country.PROPERTY_IBANCODE, ibanCode));
    criteria.add(Restrictions.eq(Country.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (Country) criteria.uniqueResult();
  }

  private static Country findCountryByIsoCode(String isoCode) {
    OBCriteria<Country> criteria = OBDal.getInstance().createCriteria(Country.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(Country.PROPERTY_ISOCOUNTRYCODE, isoCode));
    criteria.add(Restrictions.eq(Country.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (Country) criteria.uniqueResult();
  }

  // ---------------------------------------------------------------------------
  // countryIbanRules catalog
  // ---------------------------------------------------------------------------

  /**
   * The ≤45 countries that carry IBAN metadata, as {@code [{ id, iso, name, ibanPrefix,
   * ibanLength }, …]} ordered by name — everything the SPA needs to validate an IBAN against a
   * chosen country inline, without a second round-trip per keystroke. {@code name} is the
   * base-language name for message text only; the SPA should keep using the translated label from
   * the {@code C_Country_ID} selector for display.
   */
  static JSONArray buildIbanRules() throws JSONException {
    String cached = IBAN_RULES_CACHE.getIfPresent(IBAN_RULES_CACHE_KEY);
    if (cached != null) {
      return new JSONArray(cached);
    }
    JSONArray rules = fetchIbanRules();
    // Never cache an empty result, mirroring FinancialAccountBankConnectionHandler#cachedProviders:
    // an empty catalog is more likely a transient DAL hiccup than a real "no countries" state.
    if (rules.length() > 0) {
      IBAN_RULES_CACHE.put(IBAN_RULES_CACHE_KEY, rules.toString());
    }
    return rules;
  }

  /** Test-only: {@link #IBAN_RULES_CACHE} is a static field shared for the whole JVM/test run, so
   *  any test asserting on {@link #buildIbanRules} DAL interactions must clear it first — otherwise
   *  a value cached by a test that ran earlier (in this class or in a sibling handler test) leaks
   *  in and makes the assertion depend on execution order. */
  static void clearIbanRulesCacheForTests() {
    IBAN_RULES_CACHE.invalidateAll();
  }

  private static JSONArray fetchIbanRules() throws JSONException {
    OBCriteria<Country> criteria = OBDal.getInstance().createCriteria(Country.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.isNotNull(Country.PROPERTY_IBANCODE));
    criteria.add(Restrictions.isNotNull(Country.PROPERTY_IBANLENGTH));
    criteria.add(Restrictions.eq(Country.PROPERTY_ACTIVE, true));
    criteria.addOrderBy(Country.PROPERTY_NAME, true);
    JSONArray rules = new JSONArray();
    for (Country country : criteria.list()) {
      JSONObject rule = new JSONObject();
      rule.put(KEY_ID, country.getId());
      rule.put(KEY_ISO, country.getISOCountryCode());
      rule.put(KEY_NAME, country.getName());
      rule.put(KEY_IBAN_PREFIX, country.getIBANCode());
      rule.put(KEY_IBAN_LENGTH, country.getIBANLength());
      rules.put(rule);
    }
    return rules;
  }

  // ---------------------------------------------------------------------------
  // JSON-null-aware body accessors
  // ---------------------------------------------------------------------------

  /**
   * Reads a body string, treating an explicit JSON {@code null} the same as an absent key
   * ({@code null}) rather than the literal {@code "null"} string {@code JSONObject.optString}
   * would yield — see the bug this fixes at {@code FinancialAccountHandler}'s note on
   * {@code optString() on a JSON null}.
   */
  static String bodyString(JSONObject body, String key) {
    if (body == null || !body.has(key) || body.isNull(key)) {
      return null;
    }
    return body.optString(key, "");
  }

  /** {@code true} when {@code key} is present in the body and explicitly cleared (JSON
   *  {@code null} or a blank string) — an intentional "unset this field", not merely absent. */
  static boolean isExplicitClear(JSONObject body, String key) {
    if (body == null || !body.has(key)) {
      return false;
    }
    if (body.isNull(key)) {
      return true;
    }
    return StringUtils.isBlank(body.optString(key, ""));
  }

  // ---------------------------------------------------------------------------
  // "What will actually be persisted" resolvers (body value, else stored value)
  // ---------------------------------------------------------------------------

  /**
   * The account type the write will end up with: the body's when it carries one, otherwise the
   * stored account's, otherwise {@code null}.
   *
   * <p>Returns the RAW value on purpose — the caller passes it through its own
   * {@code normalizeType}, which already maps {@code null} (and anything unrecognized) to Bank. So
   * this resolver never needs to know the type codes, and the fallback lives in exactly one place
   * instead of being duplicated here as a literal.
   *
   * <p>Extracted out of {@code FinancialAccountHandler#validateCountryAndIban} together with
   * {@link #effectiveIban}: inline they were nested ternaries (java:S3358) and together carried
   * about six of that method's cognitive-complexity points (java:S3776).
   */
  static String rawEffectiveType(JSONObject body, FIN_FinancialAccount stored) {
    if (body != null && body.has(FinancialAccountHandler.FIELD_TYPE)) {
      return StringUtils.trimToEmpty(bodyString(body, FinancialAccountHandler.FIELD_TYPE));
    }
    return stored != null ? stored.getType() : null;
  }

  /**
   * The normalized IBAN the write will end up with: the body's when it carries the key (including
   * an explicit clear, which normalizes to {@code ""}), otherwise the stored account's.
   *
   * <p>Always normalized, so the length this is later compared against {@code IBANNODIGITS} is the
   * same one the trigger will see after its own {@code REPLACE}.
   *
   * @param bodyHasIban passed in rather than re-derived: the caller already computed it, and it is
   *                    what distinguishes "clearing the IBAN" from "not touching it".
   */
  static String effectiveIban(JSONObject body, boolean bodyHasIban, FIN_FinancialAccount stored) {
    if (bodyHasIban) {
      return normalizeIban(bodyString(body, FinancialAccountHandler.FIELD_IBAN));
    }
    return stored != null ? normalizeIban(stored.getIBAN()) : "";
  }
}
