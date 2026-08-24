package com.etendoerp.go.schemaforge;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoDateFormat;
import com.etendoerp.go.schemaforge.util.NeoTypeCoercionHelper;

// ── Background entity defaults (non-HTTP callers) ───────────────────────
// ETP-4888: header-builder methods that construct a document (Invoice,
// ShipmentInOut, ...) directly via OBProvider/manual setters — instead of
// going through NeoCrudHandler's HTTP "new record" path — never triggered
// NeoDefaultsService's declared-derivation resolution. Any field whose value comes
// purely from a contract.json derivation (callout/fromConfig/lookup) and is
// never set by hand in the builder was silently left null (e.g. SII/SIF
// fields like etsgDateOperation, aeatsiiFechaRegCont). This class gives
// those background callers a non-HTTP entry point into the same resolution
// pass that /defaults already exposes over HTTP.
//
// Extracted from NeoDefaultsService (ETP-4978 merge block) to keep that class under
// SonarQube's method-count threshold — fully self-contained, its only external
// dependency is NeoDefaultsService#resolveDefaults.
public class NeoBackgroundDefaultsService {

  private static final Logger log = LogManager.getLogger(NeoBackgroundDefaultsService.class);

  private NeoBackgroundDefaultsService() {
  }

  /**
   * Resolves declared field derivations (contract.json defaults/callouts/lookups configured via
   * ETGO_SF_FIELD) for a NEO-registered spec/entity and applies them onto a header entity built
   * directly by a background Java caller — i.e. one that constructs its bean via
   * {@code OBProvider}/manual setters instead of going through the normal NEO CRUD "new record"
   * HTTP path (which calls {@link NeoDefaultsService#resolveDefaults} automatically via
   * {@code GET .../defaults} during form bootstrap). Without this, any field whose value comes
   * purely from a declared derivation — never set by hand in the builder — is silently left
   * {@code null}.
   *
   * <p>Only fields the caller left blank are touched: a property already carrying a non-blank
   * value on {@code entity} is never overwritten, mirroring the "skip if already present" rule
   * {@link NeoDefaultsService#injectMandatoryDefaults} applies to the request body on the HTTP
   * create path — so fields the builder set explicitly (order, business partner, currency,
   * accounting date, etc.) always win over a generic derivation. Primitive properties are
   * coerced and set directly; FK-typed (non-primitive) properties are resolved via
   * {@code OBDal.getInstance().get(target, id)} — mirroring the same lookup
   * {@code NeoDefaultsService#tryInjectIdentifier} performs on the HTTP path — and only set
   * when the referenced record actually exists, otherwise the field is left untouched.
   *
   * <p>Failures anywhere in this method (missing spec/entity, resolution error, coercion
   * failure) are swallowed and logged — a background document-creation flow must never fail
   * because an optional declared default could not be resolved.
   *
   * @param specName   NEO spec name (kebab-case, e.g. {@code "sales-invoice"})
   * @param entityName NEO entity name within the spec (e.g. {@code "header"})
   * @param entity     the already-populated header entity to enrich; a no-op if {@code null}
   * @param parentId   optional id of the source document (order/shipment/receipt) this entity is
   *                   being created from, used for {@code fromParent}-style derivations
   */
  public static void applyDeclaredDefaultsToBackgroundEntity(String specName, String entityName,
      BaseOBObject entity, String parentId) {
    if (entity == null || StringUtils.isBlank(specName) || StringUtils.isBlank(entityName)) {
      return;
    }
    try {
      JSONObject defaults = resolveBackgroundDefaults(specName, entityName, parentId);
      if (defaults == null || defaults.length() == 0) {
        return;
      }
      Entity dalEntity = entity.getEntity();
      Iterator<String> keys = defaults.keys();
      while (keys.hasNext()) {
        String propName = keys.next();
        if (propName.endsWith("$_identifier")) {
          continue;
        }
        applyDeclaredDefaultIfMissing(entity, dalEntity, propName, defaults.opt(propName));
      }
    } catch (Exception e) {
      log.error("Could not apply declared defaults for {}/{}: {}", specName, entityName,
          e.getMessage(), e);
    }
  }

  /**
   * Looks up the SFSpec/SFEntity/AD_Tab for {@code specName}/{@code entityName} and delegates to
   * {@link NeoDefaultsService#resolveDefaults}, returning just the {@code defaults} JSON object
   * (or {@code null} if the spec/entity cannot be resolved or the underlying call fails).
   */
  private static JSONObject resolveBackgroundDefaults(String specName, String entityName,
      String parentId) throws JSONException {
    SFSpec spec = NeoServletSupport.findSpec(specName);
    if (spec == null) {
      log.debug("No SFSpec found for '{}' — skipping background declared-default resolution",
          specName);
      return null;
    }
    SFEntity sfEntity = findBackgroundEntity(spec.getId(), entityName);
    if (sfEntity == null) {
      log.debug("No SFEntity '{}' found for spec '{}' — skipping background declared-default "
          + "resolution", entityName, specName);
      return null;
    }
    Tab adTab = sfEntity.getADTab();
    NeoContext ctx = NeoContext.builder()
        .specName(specName)
        .entityName(entityName)
        .adTab(adTab)
        .sfEntity(sfEntity)
        .obContext(OBContext.getOBContext())
        .endpointType(NeoEndpointType.CRUD)
        .build();

    NeoResponse response = NeoDefaultsService.resolveDefaults(ctx, parentId);
    if (response == null || response.getHttpStatus() != 200 || response.getBody() == null) {
      return null;
    }
    return response.getBody().optJSONObject("defaults");
  }

  /**
   * Finds an active, included {@link SFEntity} by parent spec ID and entity name. The same
   * five-line criteria query is already duplicated across {@code NeoServlet#findEntity},
   * {@code BatchService#findEntity} and other siblings (pre-existing pattern, not introduced
   * here) — kept as a local private copy since this is the only non-HTTP caller of
   * {@link NeoDefaultsService#resolveDefaults} and consolidating all of those copies is out of
   * scope for this fix.
   */
  private static SFEntity findBackgroundEntity(String specId, String entityName) {
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
    criteria.add(Restrictions.ilike(SFEntity.PROPERTY_NAME, entityName, MatchMode.EXACT));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    criteria.setMaxResults(1);
    List<SFEntity> results = criteria.list();
    return results.isEmpty() ? null : results.get(0);
  }

  /**
   * Applies a single resolved declared-default value onto {@code entity}, unless the property is
   * already carrying a non-blank value. All failures are swallowed and logged at debug level — an
   * unresolvable single field must never abort the rest of the pass.
   *
   * <p>FK-typed (non-primitive) properties are resolved via {@link #resolveFkDefaultTarget}, the
   * same {@code OBDal.getInstance().get(targetEntity, id)} lookup {@code
   * NeoDefaultsService#tryInjectIdentifier} already uses on the HTTP {@code /defaults} path to
   * turn a resolved id string into a display identifier. If the referenced bean cannot be found
   * (invalid id, unresolvable target entity), the field is skipped and logged exactly like the
   * previous primitive-only gate did — this method never throws out of a failed FK resolution.
   */
  private static void applyDeclaredDefaultIfMissing(BaseOBObject entity, Entity dalEntity,
      String propName, Object rawValue) {
    if (rawValue == null || dalEntity == null) {
      return;
    }
    try {
      Property prop = dalEntity.getProperty(propName);
      if (prop == null) {
        return;
      }
      if (!isBlankValue(entity.get(propName))) {
        return; // caller already set this field explicitly — never clobber it
      }
      if (!prop.isPrimitive()) {
        applyDeclaredFkDefaultIfMissing(entity, prop, propName, rawValue);
        return;
      }
      Object coerced = coercePrimitiveDefault(prop, rawValue);
      if (coerced != null) {
        entity.set(propName, coerced);
        log.debug("Applied declared default on background entity {}: {} = {}",
            entity.getEntityName(), propName, coerced);
      }
    } catch (Exception e) {
      log.debug("Could not apply declared default for property '{}': {}", propName,
          e.getMessage());
    }
  }

  /**
   * FK-typed counterpart of the primitive branch in {@link #applyDeclaredDefaultIfMissing}.
   * {@code rawValue} is expected to be the id string a {@code @SQL=} lookup derivation resolves
   * to (e.g. {@code aeatsii_description_id} for {@code aeatsiiDescription}/
   * {@code aeatsiiPurDescription}) — mirrors what {@code NeoDefaultsService#tryInjectIdentifier}
   * already does with such a value on the HTTP {@code /defaults} path, minus the
   * {@code $_identifier} companion key (background entities are never read back through the
   * JSON selector UI, so there's no identifier field to populate).
   *
   * <p>Resolution failures (blank id, no target entity, record not found, DAL error) are logged
   * at debug level and the field is left untouched — matching the "never abort the rest of the
   * pass" contract of the caller.
   */
  private static void applyDeclaredFkDefaultIfMissing(BaseOBObject entity, Property prop,
      String propName, Object rawValue) {
    BaseOBObject resolved = resolveFkDefaultTarget(prop, rawValue);
    if (resolved == null) {
      log.debug("Could not resolve FK declared default '{}' = '{}' on background entity {}",
          propName, rawValue, entity.getEntityName());
      return;
    }
    entity.set(propName, resolved);
    log.debug("Applied FK declared default on background entity {}: {} = {}",
        entity.getEntityName(), propName, resolved.getId());
  }

  /**
   * Resolves a raw id string to a DAL bean of {@code prop}'s target entity, or {@code null} if
   * the value is blank, the property has no target entity, or no record with that id exists.
   * Same lookup {@code NeoDefaultsService#tryInjectIdentifier} performs.
   */
  private static BaseOBObject resolveFkDefaultTarget(Property prop, Object rawValue) {
    if (rawValue == null) {
      return null;
    }
    String idStr = rawValue.toString().trim();
    if (idStr.isEmpty()) {
      return null;
    }
    Entity targetEntity = prop.getTargetEntity();
    if (targetEntity == null) {
      return null;
    }
    try {
      return OBDal.getInstance().get(targetEntity.getName(), idStr);
    } catch (Exception e) {
      log.debug("Could not resolve FK target '{}' for id '{}': {}", targetEntity.getName(),
          idStr, e.getMessage());
      return null;
    }
  }

  private static boolean isBlankValue(Object current) {
    if (current == null) {
      return true;
    }
    return current instanceof String && ((String) current).isEmpty();
  }

  /**
   * Coerces a raw declared-default value (as returned by {@link NeoDefaultsService#resolveDefaults},
   * typically a {@code String}) to the Java type the given primitive DAL property expects.
   *
   * <p>Date properties are canonicalized through {@link NeoDateFormat} before parsing — the
   * {@code @#Date@} session variable resolved by {@code Utility.getDefault} is one of the
   * known non-ISO sources it documents (hardcoded {@code dd-MM-yyyy}), so parsing the raw
   * value as {@link NeoDateFormat#ISO_DATE} directly would silently misread it.
   * {@link NeoTypeCoercionHelper} does not cover dates, since on the HTTP path date coercion
   * is handled downstream by {@code DefaultJsonDataService}. Other numeric/boolean types
   * delegate to
   * {@link NeoTypeCoercionHelper#coerceField}, the same coercion the HTTP create path uses via
   * {@code NeoTypeCoercionHelper.coerceTypes}.
   */
  private static Object coercePrimitiveDefault(Property prop, Object rawValue) {
    Class<?> type = prop.getPrimitiveObjectType();
    if (type == null) {
      return null;
    }
    if (Date.class.isAssignableFrom(type)) {
      return coerceDateDefault(prop, rawValue);
    }
    if (rawValue instanceof String) {
      Map<String, Object> coerced = new HashMap<>();
      NeoTypeCoercionHelper.coerceField(prop.getEntity(), prop.getName(), (String) rawValue,
          coerced);
      return coerced.getOrDefault(prop.getName(), rawValue);
    }
    return rawValue; // already correctly typed (e.g. Boolean from coerceBooleanDefault)
  }

  private static Object coerceDateDefault(Property prop, Object rawValue) {
    if (rawValue instanceof Date) {
      return rawValue;
    }
    if (rawValue instanceof String && !((String) rawValue).isEmpty()) {
      String raw = (String) rawValue;
      String canonical = NeoDateFormat.isCanonical(raw, false) ? raw
          : NeoDateFormat.toCanonical(raw, false);
      if (canonical == null) {
        log.debug("Could not canonicalize date default '{}' for property {}",
            rawValue, prop.getName());
        return null;
      }
      try {
        return new SimpleDateFormat(NeoDateFormat.ISO_DATE).parse(canonical);
      } catch (java.text.ParseException e) {
        log.debug("Could not parse date default '{}' for property {}: {}",
            rawValue, prop.getName(), e.getMessage());
      }
    }
    return null;
  }

}
