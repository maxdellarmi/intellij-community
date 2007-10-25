/*
 * Copyright 2000-2007 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.xml;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Dmitry Avdeev
 */
public abstract class XmlSchemaProvider {

  public final static ExtensionPointName<XmlSchemaProvider> EP_NAME = new ExtensionPointName<XmlSchemaProvider>("com.intellij.xml.schemaProvider");

  @Nullable
  public static XmlFile findSchema(@NotNull @NonNls String url, @Nullable Module module, @NotNull PsiFile file) {
    for (XmlSchemaProvider provider: Extensions.getExtensions(EP_NAME)) {
      final XmlFile schema = provider.getSchema(url, module, file);
      if (schema != null) {
        return schema;
      }
    }
    return null;
  }

  @Nullable
  public static XmlFile findSchema(@NotNull @NonNls String url, @NotNull PsiFile baseFile) {
    VirtualFile file = baseFile.getVirtualFile();
    if (file == null) {
      final PsiFile originalFile = baseFile.getOriginalFile();
      if (originalFile != null)
        file = originalFile.getVirtualFile();
    }
    final Module module = file != null ? ProjectRootManager.getInstance(baseFile.getProject()).getFileIndex().getModuleForFile(file) : null;
    return findSchema(url, module, baseFile);
  }

  @Nullable
  public abstract XmlFile getSchema(@NotNull @NonNls String url, @Nullable Module module, @NotNull final PsiFile baseFile);
}
