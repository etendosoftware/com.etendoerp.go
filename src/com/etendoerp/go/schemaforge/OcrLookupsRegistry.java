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

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.etendoerp.go.schemaforge.ocr.OcrLookups;

/**
 * Binds the {@code resolver} name of an OCR document type to its {@link OcrLookups} (ETP-5715). The
 * one place a document type's lookups are chosen; the shared review never names a document type, it
 * asks for the lookups its configuration says.
 */
public final class OcrLookupsRegistry {

  private static final Map<String, Supplier<OcrLookups>> RESOLVERS = Map.of(
      PurchaseInvoiceOcrLookups.RESOLVER, PurchaseInvoiceOcrLookups::new);

  private OcrLookupsRegistry() {
  }

  /**
   * The lookups bound to a resolver name.
   *
   * @param resolver the {@code resolver} of an {@code OcrDocType}
   * @return a fresh instance, empty when no lookups are bound to the name
   */
  public static Optional<OcrLookups> forResolver(String resolver) {
    Supplier<OcrLookups> supplier = resolver == null ? null : RESOLVERS.get(resolver);
    return Optional.ofNullable(supplier).map(Supplier::get);
  }
}
