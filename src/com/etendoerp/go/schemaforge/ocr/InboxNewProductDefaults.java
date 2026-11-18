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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.ocr;

import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CATEGORY_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CATEGORY_SOURCE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UOM_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UOM_SOURCE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.SRC_DEFAULT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.SRC_OCR;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.ocr.OcrLookups.Party;

/**
 * The data a product that does not exist yet is pre-filled with (ETP-6051): the unit of measure
 * (the one the line printed when it matches a tenant unit, else the tenant default) and the
 * category (the tenant default), each tagged with where it came from. Shared by the worker, which
 * writes it into the {@code new} block of a line it could not link, and by the review's
 * {@code newProduct} when a line leaves another state, so both build the same base.
 *
 * <p>One instance per document or per request: the defaults are asked at most once. A value that
 * has no printed unit, no default or no tenant default stays absent, and the validator keeps the
 * product pending until the user picks it.</p>
 */
public final class InboxNewProductDefaults {

  private final OcrLookups lookups;
  private final String organizationId;
  private Optional<Party> defaultUom;
  private Optional<Party> defaultCategory;
  private final Map<String, Optional<Party>> printed = new HashMap<>();

  /**
   * Creates the helper.
   *
   * @param lookups        the lookups of the document type
   * @param organizationId the organization of the document, whose tree decides the default
   *                       category (the worker and the review must use the same one)
   */
  public InboxNewProductDefaults(OcrLookups lookups, String organizationId) {
    this.lookups = lookups;
    this.organizationId = organizationId;
  }

  /**
   * The pre-filled data of a product to create.
   *
   * @param printedUnit the unit the line printed, may be {@code null}
   * @return {@code {uomId, uomSource, categoryId, categorySource}}, each pair only when a value
   *         was found; an empty object when nothing was
   * @throws JSONException when an object cannot be built
   */
  public JSONObject draftFor(String printedUnit) throws JSONException {
    JSONObject draft = new JSONObject();
    Optional<Party> fromPrinted = unitPrinted(printedUnit);
    if (fromPrinted.isPresent()) {
      draft.put(K_UOM_ID, fromPrinted.get().id()).put(K_UOM_SOURCE, SRC_OCR);
    } else {
      Optional<Party> uom = defaultUom();
      if (uom.isPresent()) {
        draft.put(K_UOM_ID, uom.get().id()).put(K_UOM_SOURCE, SRC_DEFAULT);
      }
    }
    Optional<Party> category = defaultCategory();
    if (category.isPresent()) {
      draft.put(K_CATEGORY_ID, category.get().id()).put(K_CATEGORY_SOURCE, SRC_DEFAULT);
    }
    return draft;
  }

  private Optional<Party> unitPrinted(String text) {
    if (text == null || text.trim().isEmpty()) {
      return Optional.empty();
    }
    return printed.computeIfAbsent(text.trim(), lookups::uomByPrintedUnit);
  }

  private Optional<Party> defaultUom() {
    if (defaultUom == null) {
      defaultUom = lookups.defaultUom();
    }
    return defaultUom;
  }

  private Optional<Party> defaultCategory() {
    if (defaultCategory == null) {
      defaultCategory = lookups.defaultProductCategory(organizationId);
    }
    return defaultCategory;
  }
}
