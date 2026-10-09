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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.schemaforge.NeoExtensionChannel;
import com.etendoerp.go.schemaforge.NeoExtensionDispatcher;
import com.etendoerp.go.schemaforge.NeoExtensionRequest;
import com.etendoerp.go.schemaforge.NeoExtensionSurface;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.selector.policy.NeoSelectorPolicy;

/**
 * The fields the server resolves on create beyond what {@code etendo_defaults} answers without input:
 * the selector policies' wrapper fields (ETP-5368) and the ones the entity's customization declares
 * through {@link NeoHandler#serverResolvedCreateFields()} (ETP-5535).
 *
 * <p>Read by {@code etendo_schema(view:"create")}, which demotes these names to {@code optional} with
 * {@code serverDefaulted:true}. The {@code etendo_create} mandatory pre-check does NOT use this set: it
 * runs after the create callout cascade that derives a declared field, so a declared field still
 * empty there is a real gap it should report — see
 * {@code McpWriteRequestSupport#validateMandatoryFields}. It keeps skipping only the selector
 * policies' names, whose value the handler builds after the check.</p>
 *
 * <p>The customization is resolved through {@link NeoExtensionDispatcher#resolveOnly}, so an entity
 * bound by {@code @NeoExtension} is found as well as one bound by its {@code Java_Qualifier}. Nothing
 * is invoked: the method only reads a declaration.</p>
 */
final class McpServerResolvedFields {

  private static final Logger log = LogManager.getLogger(McpServerResolvedFields.class);

  private McpServerResolvedFields() {
    // utility class — no instances
  }

  /**
   * The property names the server resolves itself when the entity is created.
   *
   * @param sfEntity the Schema Forge entity; {@code null} answers an empty set
   * @return the names, never {@code null}
   */
  static Set<String> forCreate(SFEntity sfEntity) {
    if (sfEntity == null) {
      return Collections.emptySet();
    }
    Set<String> resolved = new HashSet<>(NeoSelectorPolicy.serverResolvedFieldNames(sfEntity));
    resolved.addAll(declaredByCustomization(sfEntity));
    return resolved;
  }

  /**
   * The response keys the entity's customization adds to every GET record
   * ({@link NeoHandler#responseEnrichedFields()}, ETP-5576): emittable although no spec field backs
   * them, so the {@code fields:[…]} projection validator must not report them unknown.
   *
   * @param sfEntity the Schema Forge entity; {@code null} answers an empty set
   * @return the names, never {@code null}
   */
  static Set<String> enrichedOnRead(SFEntity sfEntity) {
    if (sfEntity == null) {
      return Collections.emptySet();
    }
    return declaredByCustomization(sfEntity, NeoExtensionSurface.READ,
        NeoHandler::responseEnrichedFields, "response-enriched");
  }

  /**
   * The customization's own declaration, looked up quietly: a resolution failure must not break
   * {@code etendo_schema}, a create or a read for an entity that declares nothing. Answering empty
   * is the safe direction — on create the field stays {@code required}, on read an undeclared name
   * is judged as before.
   */
  private static Set<String> declaredByCustomization(SFEntity sfEntity) {
    return declaredByCustomization(sfEntity, NeoExtensionSurface.CREATE,
        NeoHandler::serverResolvedCreateFields, "server-resolved create");
  }

  private static Set<String> declaredByCustomization(SFEntity sfEntity,
      NeoExtensionSurface surface, Function<NeoHandler, Set<String>> declaration, String what) {
    try {
      SFSpec spec = sfEntity.getETGOSFSpec();
      NeoHandler customization = NeoExtensionDispatcher.resolveOnly(NeoExtensionRequest.builder()
          .qualifier(sfEntity.getJavaQualifier())
          .specName(spec != null ? spec.getName() : null)
          .entityName(sfEntity.getName())
          .surface(surface)
          .channel(NeoExtensionChannel.MCP)
          .build());
      Set<String> declared = customization != null ? declaration.apply(customization) : null;
      return declared != null ? declared : Collections.emptySet();
    } catch (Exception e) {
      log.warn("Could not read the {} fields of entity '{}': {}", what, sfEntity.getName(),
          e.getMessage());
      return Collections.emptySet();
    }
  }
}
