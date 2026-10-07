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

package com.etendoerp.go.schemaforge;

/**
 * The caller family a customization dispatch comes from.
 *
 * <p>It is not decoration: it selects the resolver. The two {@link NeoHandler} resolvers in this
 * module are <b>not equivalent</b> — {@code NeoServletSupport.lookupHandler} reads {@code @Named}
 * off the resolved instance's class and therefore silently skips a normal-scoped bean, while
 * {@code NeoHandlerLookup.byQualifier} matches {@code Bean#getName()} and sees it. Which one a
 * given path uses is today's behaviour and this step preserves it exactly, so the channel is the
 * value that carries that decision into {@link NeoExtensionDispatcher}.</p>
 */
public enum NeoExtensionChannel {

  /** A single REST request, dispatched from {@code NeoServletSupport.handleWithHooks}. */
  REST_SINGLE,

  /** One operation of a {@code /batch} request, dispatched from {@code BatchService}. */
  REST_BATCH,

  /** An MCP tool call, dispatched from {@code McpToolRouter}. */
  MCP
}
