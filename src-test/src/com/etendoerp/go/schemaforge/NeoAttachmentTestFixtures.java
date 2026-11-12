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

import java.nio.charset.StandardCharsets;

import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.client.application.attachment.AttachImplementationManager;
import org.openbravo.model.ad.utility.Attachment;

/** Shared byte and storage-boundary fixtures for the mocked attachment tests. */
final class NeoAttachmentTestFixtures {

  // C_File.C_Datatype_ID (AD_Column 800669) is a 32-character field in the core model.
  private static final int DATA_TYPE_LENGTH = 32;
  private static boolean storageManagerInitialized;

  /** A RIFF container whose form type is WEBP: what the WEBP signature looks for. */
  static final byte[] WEBP = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 "
      .getBytes(StandardCharsets.ISO_8859_1);

  private NeoAttachmentTestFixtures() {
  }

  /**
   * Creates a core-storage mock without making its class initializer read a live DAL model.
   * Every caller uses this boundary, so any attachment or fiscal test can run first in a JVM.
   *
   * @return an independent Mockito mock of the core attachment manager
   */
  static AttachImplementationManager mockStorageManager() {
    initializeStorageManager();
    return Mockito.mock(AttachImplementationManager.class);
  }

  /**
   * Initializes the core manager under the same narrow metadata stub formerly owned by
   * {@code NeoAttachmentsHelperTest}. Its static data-type length is metadata, not storage
   * behavior under test. The static mock is closed before the caller exercises any handler.
   */
  static synchronized void initializeStorageManager() {
    if (storageManagerInitialized) {
      return;
    }
    Property dataType = Mockito.mock(Property.class);
    Mockito.when(dataType.getFieldLength()).thenReturn(DATA_TYPE_LENGTH);
    Entity attachmentEntity = Mockito.mock(Entity.class);
    Mockito.when(attachmentEntity.getProperty(Attachment.PROPERTY_DATATYPE)).thenReturn(dataType);
    ModelProvider provider = Mockito.mock(ModelProvider.class);
    Mockito.when(provider.getEntity(Attachment.class)).thenReturn(attachmentEntity);
    try (MockedStatic<ModelProvider> models = Mockito.mockStatic(ModelProvider.class)) {
      models.when(ModelProvider::getInstance).thenReturn(provider);
      Class.forName(AttachImplementationManager.class.getName(), true,
          AttachImplementationManager.class.getClassLoader());
      storageManagerInitialized = true;
    } catch (ClassNotFoundException exception) {
      throw new IllegalStateException("Core attachment manager is not on the test classpath", exception);
    }
  }
}
