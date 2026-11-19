package com.etendoerp.go.schemaforge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.util.NeoValueEquivalence;

/**
 * IMP-45 / ETP-5680: records, during the create callout cascade, a protected field whose value the
 * caller sent and a callout derived differently. Extracted from {@link NeoDefaultsCascadeHelper}
 * (class-size limit); behaviour unchanged.
 */
final class NeoSupersededRecorder {

  private static final Logger log = LogManager.getLogger(NeoSupersededRecorder.class);
  private static final String IDENTIFIER_SUFFIX = "$_identifier";

  private NeoSupersededRecorder() {
  }

  /**
   * IMP-45: note that a protected field kept the caller's value over a callout-derived one.
   *
   * <p>Only a genuine divergence is recorded. A callout that re-proposes the value already on the
   * record has superseded nothing, and an identifier companion key ({@code x$_identifier}) is the
   * label of a field already reported under its own name.</p>
   *
   * @param result       the cascade result collecting the divergences
   * @param defaults     the in-progress payload, holding the caller's value
   * @param fieldName    the protected field
   * @param updateObj    the callout's proposed update for that field
   * @param adTab        the tab, to tell a Yes/No field from a text one; may be {@code null}
   */
  static void recordIfDiverged(NeoDefaultsService.CalloutCascadeResult result, JSONObject defaults,
      String fieldName, JSONObject updateObj, Tab adTab) {
    if (result == null || fieldName.endsWith(IDENTIFIER_SUFFIX)) {
      return;
    }
    Object kept = defaults.opt(fieldName);
    Object proposed = updateObj.opt(NeoDefaultsCascadeHelper.FIELD_VALUE);
    if (proposed == null || JSONObject.NULL.equals(proposed)) {
      return;
    }
    if (kept != null && sameValue(kept, proposed, adTab, fieldName)) {
      return;
    }
    result.recordSuperseded(fieldName, kept, proposed);
  }

  /**
   * Same text or number, or — ETP-5680 — the same Yes/No flag ({@code false} vs {@code "N"}).
   *
   * <p>The Yes/No test is decided by the column's type when it is reachable: the DAL property of a
   * Yes/No reference is boolean, so a text field never gets the lenient comparison. When no
   * property can be resolved (no tab, a name the entity does not know) the value-based equivalence
   * stands in, and only holds when both sides are Yes/No tokens. See
   * {@link NeoValueEquivalence}.</p>
   */
  private static boolean sameValue(Object kept, Object proposed, Tab adTab, String fieldName) {
    return NeoValueEquivalence.same(kept, proposed, resolveProperty(adTab, fieldName));
  }

  private static Property resolveProperty(Tab adTab, String fieldName) {
    if (adTab == null || adTab.getTable() == null) {
      return null;
    }
    try {
      Entity entity = ModelProvider.getInstance().getEntityByTableId(adTab.getTable().getId());
      return entity == null ? null : entity.getProperty(fieldName, false);
    } catch (Exception e) {
      log.debug("Could not resolve property '{}' for the superseded comparison: {}", fieldName,
          e.getMessage());
      return null;
    }
  }
}
