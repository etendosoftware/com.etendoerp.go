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
package com.etendoerp.go.mcp;

import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.data.SFField;

/**
 * One {@code SFField} as the MCP sees it: the curated {@code visibility}, {@code readOnly} and
 * {@code businessCritical} after the {@link McpFieldsSection} override has been applied.
 *
 * <h2>Why this exists</h2>
 * <p>Three places in the MCP derived these properties straight off {@code SFField}, each with its
 * own arithmetic:</p>
 * <ul>
 *   <li>{@link McpSchemaFieldBuilder#loadFieldMetadata} read {@code getVisibility()},
 *       {@code isBusinessCritical()} and {@code isReadOnly()} — this is what {@code neo_schema}
 *       reports and what {@code McpToolRouter}'s method gate reads;</li>
 *   <li>{@link McpQuerySupport#editablePropertyNames} ignored {@code visibility} entirely and
 *       computed {@code isIncluded && !isReadOnly} instead — {@code neo_selectors}' notion of
 *       editable;</li>
 *   <li>{@link McpResourceProvider} read {@code isReadOnly()} for the resource field list.</li>
 * </ul>
 *
 * <p>An override honoured by only the first of those is a worse state than no override: an agent
 * would be told a field is {@code editable} by {@code neo_schema} and not editable by
 * {@code neo_selectors}, with nothing in either response admitting the disagreement. So all three
 * now go through this class, and there is exactly one answer per field.</p>
 *
 * <h2>What "editable" means here</h2>
 * <p>{@link #isEditable()} keeps {@code neo_selectors}' original meaning —
 * {@code isIncluded && !isReadOnly}, which is what {@code push-to-neo.js} writes for the
 * {@code editable} visibility — and adds the curated string as a further requirement wherever one
 * is available, from the {@code SFField} row or from the override. The two agree wherever curation
 * is complete; where it is not, the classification narrows the derivation but never widens it. See
 * {@link #isEditable()} on why an override may reclassify a field but not include one.</p>
 *
 * <h2>Curation, not permission</h2>
 * <p>Nothing here is an access decision. These values shape what the MCP <em>offers</em> the agent;
 * whether a write actually lands is settled downstream by the DAL and {@code NeoCrudHandler}. See
 * {@link McpFieldsSection}'s javadoc for the full boundary.</p>
 */
final class McpFieldView {

  private final String visibility;
  private final boolean readOnly;
  private final boolean businessCritical;
  private final boolean included;

  private McpFieldView(String visibility, boolean readOnly, boolean businessCritical,
      boolean included) {
    this.visibility = visibility;
    this.readOnly = readOnly;
    this.businessCritical = businessCritical;
    this.included = included;
  }

  /**
   * Resolve one field's effective curation.
   *
   * <p>The {@code fields} section is read through {@link McpEntityConfig#forField}, so the
   * {@code spec} → {@code entity} → {@code field} chain is already merged most-specific-wins by the
   * time it is applied here. A chain whose payload failed validation is ignored outright and the
   * raw {@code SFField} values stand: acting on a body that was reported as broken would be the one
   * way this class could widen the agent surface without anybody having asked it to.</p>
   *
   * @param field the SchemaForge field; {@code null} yields an unconfigured, non-editable view
   * @return the effective view, never {@code null}
   */
  static McpFieldView of(SFField field) {
    if (field == null) {
      return new McpFieldView(null, false, false, false);
    }
    String visibility = StringUtils.trimToNull(field.getVisibility());
    boolean readOnly = Boolean.TRUE.equals(field.isReadOnly());
    boolean businessCritical = Boolean.TRUE.equals(field.isBusinessCritical());
    boolean included = Boolean.TRUE.equals(field.isIncluded());

    McpEntityConfig.Resolved resolved = McpEntityConfig.forField(field);
    if (!resolved.isUsable()) {
      return new McpFieldView(visibility, readOnly, businessCritical, included);
    }
    Optional<JSONObject> body = resolved.section(McpFieldsSection.NAME);
    if (body.isEmpty()) {
      return new McpFieldView(visibility, readOnly, businessCritical, included);
    }
    JSONObject fields = body.get();

    String configuredVisibility = McpFieldsSection.visibility(fields);
    if (configuredVisibility != null) {
      visibility = configuredVisibility;
    }
    // orElse(current) IS the merge rule: an unstated flag leaves the SFField row's value
    // standing. Same three outcomes as the previous explicit null checks.
    readOnly = McpFieldsSection.readOnly(fields).orElse(readOnly);
    businessCritical = McpFieldsSection.businessCritical(fields).orElse(businessCritical);
    return new McpFieldView(visibility, readOnly, businessCritical, included);
  }

  /**
   * The effective curated visibility.
   *
   * @return one of {@code editable}/{@code readOnly}/{@code system}/{@code discarded}, or
   *         {@code null} when neither the {@code SFField} row nor the override classifies this
   *         field. Callers that report it must keep omitting the key in that case — an absent
   *         {@code visibility} is what {@code neo_schema} has always emitted for an uncurated
   *         field, and inventing a value here would change every existing response
   */
  String getVisibility() {
    return visibility;
  }

  /**
   * @return the effective read-only flag
   */
  boolean isReadOnly() {
    return readOnly;
  }

  /**
   * @return the effective business-critical flag
   */
  boolean isBusinessCritical() {
    return businessCritical;
  }

  /**
   * Whether the agent may supply this field.
   *
   * <p><b>{@code ISINCLUDED} and {@code VISIBILITY} are different axes, and this section may only
   * move one of them.</b> {@code ISINCLUDED} answers "is this field part of the MCP surface at
   * all"; {@code VISIBILITY} answers "how is it classified once it is". The {@code fields} section
   * is a <em>classification</em> override, so it must never silently flip <em>inclusion</em> —
   * widening the surface is {@code ISINCLUDED}'s job and belongs in the shared contract, not in an
   * MCP-side override. Hence {@code included} is required in both branches: an entity-level
   * {@code visibility:"editable"} reclassifies the fields the spec already exposes and leaves the
   * excluded ones excluded. Without that term, {@code bp-location/bpLocation}'s override promoted
   * all ten {@code C_Location} rows, primary key included ({@code C_Location_ID} is mandatory and
   * {@code AD_Column.isUpdateable = 'N'}), where the spec includes six.</p>
   *
   * <p>The term costs nothing on today's data — all 1040 {@code VISIBILITY = 'editable'} rows in
   * the instance already carry {@code ISINCLUDED = 'Y'}, and no row exists in either combination
   * where the visibility branch and the fallback disagree.</p>
   *
   * <p><b>Scope.</b> The one consumer of this is {@link McpQuerySupport#editablePropertyNames},
   * read by {@code McpToolRouter} for {@link McpDefaultsView#apply} — the {@code neo_defaults}
   * grouped/minimal split between {@code confirm} and {@code systemManaged}. It is purely
   * presentational and gates no write. The create path does not consult {@code isIncluded} at all
   * ({@code mapFieldsToDalProperties} takes only the tab and maps names to DAL properties), so an
   * {@code ISINCLUDED = 'N'} column such as {@code C_Location.RegionName} stays writable through
   * {@code neo_create} either way.</p>
   *
   * @return {@code true} when the field is included in the spec, is not read-only, and is either
   *         curated {@code editable} or carries no curated visibility at all
   */
  boolean isEditable() {
    if (!included || readOnly) {
      return false;
    }
    return visibility == null || McpSchemaFieldBuilder.VISIBILITY_EDITABLE.equals(visibility);
  }
}
