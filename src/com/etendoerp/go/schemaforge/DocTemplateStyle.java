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
package com.etendoerp.go.schemaforge;

import java.util.Objects;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The common appearance options of a printable, apart from its variant, logo and footer: the base
 * color, the font family, the font size and whether table rows are striped. Every option is
 * optional and {@code null} means "the design's own default", so an organization that never touches
 * one prints exactly what it printed before. Values are the vocabulary of
 * {@link DocTemplateConfigSupport}; this class only carries them.
 */
final class DocTemplateStyle {

  static final DocTemplateStyle NONE = new DocTemplateStyle(null, null, null, null);

  private final String color;
  private final String font;
  private final String fontSize;
  private final String rowStripes;

  DocTemplateStyle(String color, String font, String fontSize, String rowStripes) {
    this.color = color;
    this.font = font;
    this.fontSize = fontSize;
    this.rowStripes = rowStripes;
  }

  String getColor() {
    return color;
  }

  String getFont() {
    return font;
  }

  String getFontSize() {
    return fontSize;
  }

  String getRowStripes() {
    return rowStripes;
  }

  /** Adds {@code color, font, fontSize, rowStripes} (JSON null when unset) to the wire object. */
  void writeTo(JSONObject json) throws JSONException {
    json.put("color", orNull(color));
    json.put("font", orNull(font));
    json.put("fontSize", orNull(fontSize));
    json.put("rowStripes", orNull(rowStripes));
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof DocTemplateStyle)) {
      return false;
    }
    DocTemplateStyle o = (DocTemplateStyle) other;
    return Objects.equals(color, o.color) && Objects.equals(font, o.font)
        && Objects.equals(fontSize, o.fontSize) && Objects.equals(rowStripes, o.rowStripes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(color, font, fontSize, rowStripes);
  }

  private static Object orNull(String value) {
    return value == null ? JSONObject.NULL : value;
  }
}
