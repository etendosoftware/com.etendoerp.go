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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.utility.Image;

/**
 * Everything the MCP write path and {@code neo_schema} need to treat an {@code Image BLOB} column as
 * its own type (ETP-5184).
 *
 * <p>Two responsibilities, both keyed off {@link McpConstants#REF_IMAGE_BLOB} alone and therefore
 * generic across every image column in the instance — there is no per-window branch here, and
 * enabling a new image field (e.g. {@code M_Product_Category.AD_Image_ID}) needs no code change:</p>
 * <ul>
 *   <li><b>Discovery</b> — {@link #decorateImageField} turns the descriptor {@code neo_schema},
 *       {@code view:"create"} and {@code fields:[…]} all emit into a self-describing contract, so an
 *       agent is told what the field holds instead of inferring "text" from an untyped string.</li>
 *   <li><b>Writes</b> — {@link #validateImageFields} refuses a value that is not an existing
 *       {@code AD_Image} id with a self-correctable error naming the upload tools. Without it the
 *       value reaches {@link McpFkResolver}, which sees an FK to {@code AD_Image} and tries to
 *       resolve it by identifier: the agent then gets "no record named …" for a table it cannot
 *       search, which points nowhere.</li>
 * </ul>
 */
final class McpImageFieldSupport {

  private McpImageFieldSupport() {
    // utility class — no instances
  }

  /** Etendo ids are 32 hex characters, no hyphens. */
  private static final int IMAGE_ID_LENGTH = 32;

  /**
   * The guidance an {@code image} field carries in {@code neo_schema}.
   *
   * <p>Written as a prohibition plus a route, because the two failure modes an untyped string invites
   * are exactly "send a URL" and "send base64". Naming
   * {@link McpConstants#TOOL_NEO_REQUEST_IMAGE_UPLOAD} first is deliberate — it is the path whose
   * bytes never enter the conversation.
   */
  static final String IMAGE_FIELD_HINT =
      "Holds an AD_Image id (32 hex chars), not the image itself. Do NOT send base64 and do NOT send "
      + "a URL here — neither is what this column stores. Get an id first: call "
      + McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD + " and upload the file to the URL it returns "
      + "(cheapest — the bytes never pass through the conversation), or "
      + McpConstants.TOOL_NEO_UPLOAD_IMAGE + " for an image under 256 KB you already hold in memory. "
      + "Then write the returned imageId to this field with neo_update.";

  /**
   * Adds the type contract to an {@code image} field descriptor: the JSON-schema {@code format}, the
   * underlying wire type, and {@link #IMAGE_FIELD_HINT}.
   *
   * <p>The hint is written to both {@code description} and {@code hint} on purpose. {@code hint} is
   * the durable one: {@code description} is overlaid a few steps later by
   * {@link McpSchemaFieldBuilder#applyCuratedLabels} whenever the AD field carries help text, and
   * losing the AD author's own words would be the wrong trade. So {@code description} carries the
   * guidance only when AD has nothing to say, while {@code hint} always does.
   *
   * @param fieldObj the descriptor being built, mutated in place
   */
  static void decorateImageField(JSONObject fieldObj) throws JSONException {
    if (fieldObj == null) {
      return;
    }
    fieldObj.put("format", McpConstants.FORMAT_IMAGE_ID);
    fieldObj.put("valueType", McpConstants.TYPE_STRING);
    fieldObj.put(McpConstants.KEY_HINT, IMAGE_FIELD_HINT);
    fieldObj.put(McpConstants.KEY_DESCRIPTION, IMAGE_FIELD_HINT);
  }

  /** @return {@code true} when {@code refId} is the {@code Image BLOB} AD reference. */
  static boolean isImageReference(String refId) {
    return McpConstants.REF_IMAGE_BLOB.equals(refId);
  }

  /**
   * @return {@code true} when {@code value} has the shape of an Etendo id (32 hex characters). A
   *     shape check only — it says nothing about the row existing, which is what
   *     {@link #validateImageFields} asks the DAL.
   */
  static boolean isImageIdShaped(String value) {
    if (value == null || value.length() != IMAGE_ID_LENGTH) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
      if (!hex) {
        return false;
      }
    }
    return true;
  }

  /**
   * Refuses a write whose {@code image}-typed fields do not carry an existing {@code AD_Image} id.
   *
   * <p>Must run <b>before</b> FK-by-name resolution: an image column is an FK to {@code AD_Image},
   * so {@link McpFkResolver} would otherwise treat a base64 blob or a URL as a display name to look
   * up and answer with an unresolvable-FK error, which tells the agent nothing about how to obtain
   * an id.
   *
   * <p>A blank value is allowed through — clearing an image is a legitimate write.
   *
   * @param body      the already-mapped DAL-property body ({@code mapFieldsToDalProperties} output)
   * @param adTab     the tab whose {@code AD_Column}s decide which keys are image-typed
   * @param dalEntity the DAL entity for that tab, used to resolve column → property names
   * @return the error envelope to return to the agent, or {@code null} when every image field is
   *     acceptable
   */
  static JSONObject validateImageFields(JSONObject body, Tab adTab, Entity dalEntity)
      throws JSONException {
    if (body == null || adTab == null) {
      return null;
    }
    Set<String> imageKeys = imageFieldKeys(adTab, dalEntity);
    if (imageKeys.isEmpty()) {
      return null;
    }
    JSONArray rejected = new JSONArray();
    Iterator<?> keys = body.keys();
    List<String> bodyKeys = new ArrayList<>();
    while (keys.hasNext()) {
      bodyKeys.add(String.valueOf(keys.next()));
    }
    for (String key : bodyKeys) {
      if (!imageKeys.contains(key)) {
        continue;
      }
      String value = StringUtils.trimToNull(body.optString(key, null));
      if (value == null) {
        continue;
      }
      String reason = rejectionReason(value);
      if (reason != null) {
        JSONObject item = new JSONObject();
        item.put("field", key);
        item.put("reason", reason);
        rejected.put(item);
      }
    }
    return rejected.length() == 0 ? null : buildInvalidImageReferenceError(rejected);
  }

  /**
   * @return why {@code value} cannot be stored in an image field, or {@code null} when it can. The
   *     reason is machine-readable and distinguishes the three things an agent actually sends: a
   *     data URI / base64 blob, a URL, and a well-shaped id that names no row.
   */
  private static String rejectionReason(String value) {
    if (!isImageIdShaped(value)) {
      if (StringUtils.startsWithIgnoreCase(value, "data:")) {
        return "base64_data_uri";
      }
      if (StringUtils.startsWithIgnoreCase(value, "http://")
          || StringUtils.startsWithIgnoreCase(value, "https://")) {
        return "url";
      }
      return value.length() > IMAGE_ID_LENGTH ? "base64_or_binary" : "not_an_id";
    }
    return imageExists(value) ? null : "unknown_image_id";
  }

  /** Isolated so the DAL lookup is the only untestable line in {@link #rejectionReason}. */
  private static boolean imageExists(String imageId) {
    try {
      return OBDal.getInstance().get(Image.class, imageId) != null;
    } catch (Exception e) {
      // A malformed id can make the DAL throw rather than return null; either way it is not a row.
      return false;
    }
  }

  /**
   * The DAL property names (plus their DB column names, since
   * {@code mapFieldsToDalProperties} passes an unmappable key through untouched) of every
   * image-typed column on {@code adTab}.
   */
  private static Set<String> imageFieldKeys(Tab adTab, Entity dalEntity) {
    Set<String> keys = new HashSet<>();
    for (Column col : adTab.getTable().getADColumnList()) {
      String refId = col.getReference() == null ? null : (String) col.getReference().getId();
      if (!col.isActive() || !isImageReference(refId)) {
        continue;
      }
      keys.add(col.getDBColumnName());
      if (dalEntity == null) {
        continue;
      }
      try {
        Property prop = dalEntity.getPropertyByColumnName(col.getDBColumnName(), false);
        if (prop != null) {
          keys.add(prop.getName());
        }
      } catch (Exception ignored) {
        // Column not mappable to a DAL property — the DB column name alone is enough.
      }
    }
    return keys;
  }

  /**
   * The 422 for an unusable image value.
   *
   * <p>Self-correctable by construction (the M4 metric of {@code /mcp-comparison}): it names the tool
   * that produces a valid value, in the order the agent should prefer them, so the fix needs no
   * human. Mirrors the {@code missingFields}/{@code invalidDates} envelopes rather than inventing a
   * fourth shape — same keys, one list keyed by what is wrong with it.
   */
  private static JSONObject buildInvalidImageReferenceError(JSONArray rejected)
      throws JSONException {
    JSONObject error = new JSONObject();
    error.put(McpConstants.KEY_STATUS, McpConstants.STATUS_UNPROCESSABLE);
    error.put(McpConstants.KEY_ERROR, McpConstants.ERROR_INVALID_IMAGE_REFERENCE);
    error.put(McpConstants.KEY_DETAIL, "One or more image fields were sent something other than an "
        + "existing AD_Image id. An image field stores a reference to an already-uploaded image, "
        + "never the image data and never a URL. Nothing was written.");
    error.put("invalidImageFields", rejected);
    error.put(McpConstants.KEY_HINT, "Upload the image first, then write the id it returns. Prefer "
        + McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD + ": it returns a single-use upload URL and a "
        + "ready-to-run curl command, so the image bytes never pass through the conversation. Use "
        + McpConstants.TOOL_NEO_UPLOAD_IMAGE + " only for an image under 256 KB that you already "
        + "hold in memory and cannot upload with a shell command. Both return an imageId — resend "
        + "this write with that id as the field value.");
    error.put(McpConstants.KEY_SEE_ALSO, McpConstants.SEE_ALSO_WRITING);
    return error;
  }
}
