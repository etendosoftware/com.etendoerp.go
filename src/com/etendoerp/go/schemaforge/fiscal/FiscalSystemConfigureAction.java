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

package com.etendoerp.go.schemaforge.fiscal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.BatchService;
import com.etendoerp.go.schemaforge.NeoCertificateHelper;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoMessageText;
import com.etendoerp.go.schemaforge.NeoProcessService;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoActionContract.Param;

/**
 * The fiscal-system actions of {@code sii-config/siiConfiguration} (ETP-6053), served by that
 * pair's customization ({@code SiiConfigDeactivateHandler}); {@code id} is the organization, not a
 * config row. The single way to activate a billing system (SIF), for the SPA's Fiscal
 * Configuration window (REST) and for agents (MCP) alike.
 *
 * <p><b>Access.</b> The route is gated, like every request of that spec, on the role's access to
 * the SII Configuration window — the window the Fiscal Configuration page itself is gated on —
 * read for the catalog, read-write for the activation. The activation then requires read-write on
 * the window of every system it creates (TicketBAI and VERI*FACTU have their own) and refuses, in
 * the request language, a role without it ({@code ETGO_FiscalSifNoWindowAccess}), before writing
 * anything; the batch re-checks each window anyway.</p>
 *
 * <ul>
 *   <li>{@code fiscalSystemCatalog} (read, GET) — the territory x system rules
 *       ({@link FiscalSifRules#catalog()}), which the window renders its choices from;</li>
 *   <li>{@code configureFiscalSystem} (write, POST) — activates a system.</li>
 * </ul>
 *
 * <p>{@code configureFiscalSystem} activates SII, TicketBAI, SII + TicketBAI or VERI*FACTU with
 * these rules:</p>
 * <ul>
 *   <li>the territory is mandatory and must allow the system ({@link FiscalSifRules}) — when SII
 *       is added to an existing TicketBAI it is TicketBAI's own, and may be left out;</li>
 *   <li>never in forced fiscal test mode ({@code ETSG_ForceTestMode});</li>
 *   <li>only when the organization has no active SIF — except adding SII to an existing TicketBAI
 *       in the same Basque territory ("Añadir SII"); a legacy TicketBAI without territory is
 *       refused;</li>
 *   <li>every value derived from the territory (SII {@code navarra}/{@code guipuzcoa}/
 *       {@code taxtype}, TicketBAI territory, VERI*FACTU tax type) is built here and cannot be
 *       supplied; the SII is "acogido" ({@code INSIISYSTEM = 'Y'}) from creation.</li>
 * </ul>
 *
 * <p>The records are created through the normal CRUD path — {@link BatchService}, the same
 * pipeline as {@code POST /sws/neo/batch} — so each entity's customization runs (TicketBAI
 * sequences, auto-send schedules, VERI*FACTU adoption date, SII acogida), the role's
 * access to each fiscal window is checked, and SII + TicketBAI are created atomically.</p>
 *
 * <p>When the organization has no active signing certificate the answer carries a warning saying
 * it must be uploaded from Configuración fiscal: agents cannot write the certificate.</p>
 */
public class FiscalSystemConfigureAction {

  private static final Logger log = LogManager.getLogger(FiscalSystemConfigureAction.class);

  /** The write action. */
  public static final String ACTION = "configureFiscalSystem";
  /** The read action with the territory x system rules. */
  public static final String CATALOG_ACTION = "fiscalSystemCatalog";
  static final String P_SYSTEM = "system";
  static final String P_TERRITORY = "territory";

  static final String MSG_NOT_ALLOWED = "ETGO_FiscalSifNotAllowed";
  static final String MSG_ALREADY_CONFIGURED = "ETGO_FiscalSifAlreadyConfigured";
  static final String MSG_TERRITORY_MISMATCH = "ETGO_FiscalSifTerritoryMismatch";
  static final String MSG_ORG_MISMATCH = "ETGO_FiscalSifOrgMismatch";
  static final String MSG_NO_CERTIFICATE = "ETGO_FiscalSifNoCertificate";
  static final String MSG_TERRITORY_REQUIRED = "ETGO_FiscalSifTerritoryRequired";
  static final String MSG_TBAI_NO_TERRITORY = "ETGO_FiscalSifTbaiNoTerritory";
  static final String MSG_NO_WINDOW_ACCESS = "ETGO_FiscalSifNoWindowAccess";

  private static final String METHOD_POST = "POST";
  private static final String METHOD_GET = "GET";

  /** spec / entity of each record system. */
  private static final Map<String, String[]> ENDPOINTS = Map.of(
      FiscalSifRules.SII, new String[] { "sii-config", "siiConfiguration" },
      FiscalSifRules.TBAI, new String[] { "tbai-config", "header" },
      FiscalSifRules.VERIFACTU, new String[] { "verifactu-config",
          "cabeceraDeConfiguraciónVerifactu" });

  private static final Map<String, NeoActionContract> CONTRACTS = contracts();

  /** The CRUD pipeline the records go through. A seam for unit tests. */
  private final Function<JSONArray, JSONObject> batch;
  /** "Today", the adoption day of the records. A seam for unit tests. */
  private final Supplier<LocalDate> today;
  /** The organization's certificate expiry, {@code null} without one. A seam for unit tests. */
  private final Function<String, Date> certificateExpiry;
  /** Whether the role may create records of a spec (POST access to its window). A seam. */
  private final Predicate<String> canCreate;

  /** Production wiring. */
  public FiscalSystemConfigureAction() {
    this(FiscalSystemConfigureAction::runBatch, LocalDate::now,
        NeoCertificateHelper::activeCertificateExpiry, NeoAccessHelper::canWriteSpec);
  }

  /** Test wiring with every fiscal window writable. */
  FiscalSystemConfigureAction(Function<JSONArray, JSONObject> batch, Supplier<LocalDate> today,
      Function<String, Date> certificateExpiry) {
    this(batch, today, certificateExpiry, spec -> true);
  }

  FiscalSystemConfigureAction(Function<JSONArray, JSONObject> batch, Supplier<LocalDate> today,
      Function<String, Date> certificateExpiry, Predicate<String> canCreate) {
    this.batch = batch;
    this.today = today;
    this.certificateExpiry = certificateExpiry;
    this.canCreate = canCreate;
  }

  private static Map<String, NeoActionContract> contracts() {
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(CATALOG_ACTION, NeoActionContract.read(CATALOG_ACTION,
        "The rules configureFiscalSystem applies, as data: every billing system and, per "
            + "territory, the systems it allows and the values its configuration records carry "
            + "(SII navarra/guipuzcoa/taxtype, TicketBAI territory, VERI*FACTU tax type). Read it "
            + "to choose a valid system/territory pair before activating one.")
        .withIdDescription("the organization id")
        .withHttpMethod(METHOD_GET));
    contracts.put(ACTION, NeoActionContract.write(ACTION,
          "Activates the organization's billing system (SIF), as the Fiscal Configuration "
              + "onboarding does: creates the SII, TicketBAI or VERI*FACTU configuration (two "
              + "records for SII+TBAI) with every value the territory implies. Refused when the "
              + "territory does not allow the system, when fiscal test mode is forced, and when "
              + "the organization already has an active SIF — except system SII on an "
              + "organization with TicketBAI, which adds SII to it (same Basque territory). "
              + "Changing or switching off a SIF, and uploading the digital certificate, are only "
              + "done in the app (Configuración fiscal). Returns the created records and, when "
              + "the organization has no certificate, a warning to upload it in the app.",
          Param.requiredOptions(P_SYSTEM,
              "The billing system: SII, TBAI (TicketBAI), SII+TBAI (both, Basque Country with "
                  + "national operations) or VERIFACTU.", FiscalSifRules.SYSTEMS),
          Param.options(P_TERRITORY,
              "The fiscal territory, required: navarra (SII), alava / bizkaia / gipuzkoa (TBAI or "
                  + "SII+TBAI), baleares = Spain common territory and Balearic Islands, canarias "
                  + "(SII or VERIFACTU), ceuta = Ceuta and Melilla (VERIFACTU only). It may only be "
                  + "left out when adding SII to an existing TicketBAI, whose territory it takes.",
              FiscalSifRules.TERRITORIES))
          .withIdDescription("the organization id"));
    return Collections.unmodifiableMap(contracts);
  }

  /** @return the declared actions */
  public static Map<String, NeoActionContract> actionContracts() {
    return CONTRACTS;
  }

  /**
   * @param action an action name
   * @return whether this class serves it
   */
  public static boolean serves(String action) {
    return ACTION.equals(action) || CATALOG_ACTION.equals(action);
  }

  /**
   * Runs one of the actions.
   *
   * @param context the ACTION context; {@code recordId} is the organization
   * @return the answer, or {@code null} when {@code context} is not one of these actions
   */
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())
        || !serves(context.getFieldName())) {
      return null;
    }
    if (CATALOG_ACTION.equals(context.getFieldName())) {
      return catalog(context);
    }
    if (!METHOD_POST.equalsIgnoreCase(context.getHttpMethod())) {
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method not allowed.");
    }
    JSONObject params = context.getRequestBody() != null ? context.getRequestBody()
        : new JSONObject();
    NeoResponse invalid = NeoActionContract.validate(CONTRACTS, ACTION, params);
    if (invalid != null) {
      return invalid;
    }
    String orgId = context.getRecordId();
    if (orgId == null || !isReadable(orgId)
        || OBDal.getInstance().get(Organization.class, orgId) == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Organization not found");
    }
    try {
      String territory = params.isNull(P_TERRITORY) ? null
          : StringUtils.trimToNull(params.optString(P_TERRITORY, null));
      return configure(context, orgId, params.getString(P_SYSTEM), territory);
    } catch (JSONException e) {
      log.error("configureFiscalSystem failed on organization {}", orgId, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Could not configure the fiscal system. Please check logs for details.");
    }
  }

  private NeoResponse configure(NeoContext context, String orgId, String system,
      String territory) throws JSONException {
    // The records land in the session's organization (organization is server-owned), so a
    // different id would create them somewhere the caller did not ask for.
    if (!orgId.equals(FiscalSifGuard.currentOrganizationId(context.getObContext()))) {
      return FiscalSifGuard.refusal(HttpServletResponse.SC_BAD_REQUEST, MSG_ORG_MISMATCH);
    }
    if (FiscalSifGuard.isForceTestMode(context.getObContext())) {
      return FiscalSifGuard.refusal(HttpServletResponse.SC_CONFLICT, FiscalSifGuard.MSG_TEST_MODE);
    }
    Map<String, String> active = FiscalSifGuard.activeRecords(orgId);
    String effectiveTerritory = territory;
    boolean addingSii = isAddingSiiToTbai(system, territory, active);
    if (addingSii) {
      String tbaiTerritory = FiscalSifGuard.tbaiTerritoryOf(active.get(FiscalSifRules.TBAI));
      String tbaiTerritoryId = FiscalSifRules.territoryOfTbai(tbaiTerritory);
      if (tbaiTerritoryId == null) {
        // A legacy TicketBAI with no (or an unknown) territory: the SII flags cannot be derived.
        return FiscalSifGuard.refusal(HttpServletResponse.SC_CONFLICT, MSG_TBAI_NO_TERRITORY);
      }
      if (territory != null && !territory.equals(tbaiTerritoryId)) {
        return FiscalSifGuard.refusal(HttpServletResponse.SC_BAD_REQUEST, MSG_TERRITORY_MISMATCH,
            FiscalSifGuard.territoryName(tbaiTerritoryId));
      }
      effectiveTerritory = tbaiTerritoryId;
    } else {
      NeoResponse refusal = activationRefusal(system, territory, active);
      if (refusal != null) {
        return refusal;
      }
    }

    String day = today.get().toString();
    Map<String, JSONObject> payloads = addingSii
        ? Collections.singletonMap(FiscalSifRules.SII,
            FiscalSifRules.recordPayload(FiscalSifRules.SII, effectiveTerritory, day))
        : FiscalSifRules.onboardingPayloads(system, effectiveTerritory, day);

    // Each system has its own window: a role allowed to configure one system but not another gets
    // a refusal in its language here, not the batch's generic "Access denied to spec".
    for (String recordSystem : payloads.keySet()) {
      if (!canCreate.test(ENDPOINTS.get(recordSystem)[0])) {
        return FiscalSifGuard.refusal(HttpServletResponse.SC_FORBIDDEN, MSG_NO_WINDOW_ACCESS,
            FiscalSifGuard.displayName(recordSystem));
      }
    }

    JSONArray operations = new JSONArray();
    for (Map.Entry<String, JSONObject> e : payloads.entrySet()) {
      String[] endpoint = ENDPOINTS.get(e.getKey());
      operations.put(new JSONObject()
          .put("id", e.getKey())
          .put("spec", endpoint[0])
          .put("entity", endpoint[1])
          .put("body", e.getValue()));
    }
    JSONObject outcome = FiscalSifGuard.asFiscalSystemActivation(() -> batch.apply(operations));
    if (outcome == null || !outcome.optBoolean(BatchService.FIELD_COMMITTED, false)) {
      return batchFailure(outcome);
    }
    return success(context, orgId, addingSii ? FiscalSifRules.SII_TBAI : system,
        effectiveTerritory, outcome);
  }

  /** The refusal of a first activation, or {@code null}: territory, compatibility, one SIF. */
  private static NeoResponse activationRefusal(String system, String territory,
      Map<String, String> active) {
    if (territory == null) {
      return FiscalSifGuard.refusal(HttpServletResponse.SC_BAD_REQUEST, MSG_TERRITORY_REQUIRED);
    }
    if (!FiscalSifRules.isAllowed(system, territory)) {
      return FiscalSifGuard.refusal(HttpServletResponse.SC_BAD_REQUEST, MSG_NOT_ALLOWED,
          FiscalSifGuard.displayName(system), FiscalSifGuard.territoryName(territory),
          String.join(", ", displayNames(FiscalSifRules.allowedSystems(territory))));
    }
    if (!active.isEmpty()) {
      return FiscalSifGuard.refusal(HttpServletResponse.SC_CONFLICT, MSG_ALREADY_CONFIGURED,
          String.join(" + ", displayNames(new ArrayList<>(active.keySet()))));
    }
    return null;
  }

  /**
   * "Añadir SII": SII asked for an organization whose only active SIF is TicketBAI, either
   * without a territory (it takes TicketBAI's) or with a Basque one (checked against TicketBAI's).
   */
  private static boolean isAddingSiiToTbai(String system, String territory,
      Map<String, String> active) {
    return FiscalSifRules.SII.equals(system)
        && (territory == null || FiscalSifRules.BASQUE_TERRITORIES.contains(territory))
        && active.size() == 1 && active.containsKey(FiscalSifRules.TBAI);
  }

  /** The {@code fiscalSystemCatalog} answer: read only, the same for every organization. */
  private static NeoResponse catalog(NeoContext context) {
    String method = context.getHttpMethod();
    if (!METHOD_GET.equalsIgnoreCase(method) && !METHOD_POST.equalsIgnoreCase(method)) {
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method not allowed.");
    }
    String orgId = context.getRecordId();
    if (orgId == null || !isReadable(orgId)) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Organization not found");
    }
    try {
      return NeoResponse.ok(FiscalSifRules.catalog());
    } catch (JSONException e) {
      log.error("fiscalSystemCatalog could not be built", e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Could not read the fiscal system catalog.");
    }
  }

  private NeoResponse success(NeoContext context, String orgId, String resultingSystem,
      String territory, JSONObject outcome) throws JSONException {
    JSONArray created = new JSONArray();
    JSONArray ops = outcome.optJSONArray("operations");
    for (int i = 0; ops != null && i < ops.length(); i++) {
      JSONObject op = ops.getJSONObject(i);
      String recordSystem = op.optString("id");
      String[] endpoint = ENDPOINTS.get(recordSystem);
      created.put(new JSONObject()
          .put(P_SYSTEM, recordSystem)
          .put("spec", endpoint != null ? endpoint[0] : null)
          .put("entity", endpoint != null ? endpoint[1] : null)
          .put("id", op.optString("recordId", null)));
    }
    boolean hasCertificate = certificateExpiry.apply(orgId) != null;
    JSONObject body = new JSONObject()
        .put(P_SYSTEM, resultingSystem)
        .put(P_TERRITORY, territory)
        .put("created", created)
        .put("certificate", new JSONObject().put("exists", hasCertificate));
    if (!hasCertificate) {
      NeoMessageText.addWarning(body, context.isMcpOrigin(), MSG_NO_CERTIFICATE);
    }
    return NeoResponse.ok(body);
  }

  /** The batch refusal as this action's own error: status, message and message keys kept. */
  private static NeoResponse batchFailure(JSONObject outcome) throws JSONException {
    JSONObject error = outcome != null ? outcome.optJSONObject("error") : null;
    int status = error != null ? error.optInt("status", HttpServletResponse.SC_BAD_REQUEST)
        : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
    String message = error != null ? error.optString("message", null) : null;
    NeoResponse response = NeoResponse.error(status,
        StringUtils.defaultIfBlank(message, "Could not configure the fiscal system."));
    JSONArray keys = error != null ? findMessageKeys(error) : null;
    if (keys != null && response.getBody() != null) {
      response.getBody().getJSONObject("error").put(NeoProcessService.MESSAGE_KEYS, keys);
    }
    return response;
  }

  private static JSONArray findMessageKeys(JSONObject error) {
    JSONArray keys = error.optJSONArray(NeoProcessService.MESSAGE_KEYS);
    if (keys != null) {
      return keys;
    }
    JSONObject detail = error.optJSONObject("detail");
    JSONObject nested = detail != null ? detail.optJSONObject("error") : null;
    return nested != null ? nested.optJSONArray(NeoProcessService.MESSAGE_KEYS) : null;
  }

  private static List<String> displayNames(List<String> systems) {
    List<String> names = new ArrayList<>(systems.size());
    systems.forEach(s -> names.add(FiscalSifGuard.displayName(s)));
    return names;
  }

  private static boolean isReadable(String orgId) {
    String[] readable = OBContext.getOBContext().getReadableOrganizations();
    return readable != null && Arrays.asList(readable).contains(orgId);
  }

  private static JSONObject runBatch(JSONArray operations) {
    try {
      return BatchService.forBatchOnly().executeBatch(operations);
    } catch (JSONException e) {
      log.error("configureFiscalSystem: the batch could not be built", e);
      return null;
    }
  }
}
