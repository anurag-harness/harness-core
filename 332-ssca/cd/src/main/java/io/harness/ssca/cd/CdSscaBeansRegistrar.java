/*
 * Copyright 2023 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.ssca.cd;

import io.harness.EntityType;
import io.harness.ModuleType;
import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;
import io.harness.beans.FeatureName;
import io.harness.pms.contracts.steps.StepCategory;
import io.harness.pms.contracts.steps.StepInfo;
import io.harness.pms.contracts.steps.StepMetaData;
import io.harness.ssca.beans.SscaConstants;
import io.harness.ssca.cd.beans.orchestration.CdSscaOrchestrationStepNode;
import io.harness.yaml.schema.beans.SchemaNamespaceConstants;
import io.harness.yaml.schema.beans.YamlGroup;
import io.harness.yaml.schema.beans.YamlSchemaMetadata;
import io.harness.yaml.schema.beans.YamlSchemaRootClass;

import com.google.common.collect.ImmutableList;

@OwnedBy(HarnessTeam.SSCA)
public class CdSscaBeansRegistrar {
  public static final ImmutableList<YamlSchemaRootClass> yamlSchemaRegistrars =
      ImmutableList.<YamlSchemaRootClass>builder()
          .add(YamlSchemaRootClass.builder()
                   .entityType(EntityType.CD_SSCA_ORCHESTRATION)
                   .clazz(CdSscaOrchestrationStepNode.class)
                   .availableAtProjectLevel(true)
                   .availableAtOrgLevel(false)
                   .availableAtAccountLevel(false)
                   .yamlSchemaMetadata(YamlSchemaMetadata.builder()
                                           .namespace(SchemaNamespaceConstants.PMS)
                                           .modulesSupported(ImmutableList.of(ModuleType.CD))
                                           .yamlGroup(YamlGroup.builder().group(StepCategory.STEP.name()).build())
                                           .build())
                   .build())
          .build();

  public static final ImmutableList<StepInfo> sscaStepPaletteSteps =
      ImmutableList.<StepInfo>builder()
          .add(StepInfo.newBuilder()
                   .setName(SscaConstants.SSCA_ORCHESTRATION_STEP)
                   .setType(SscaConstants.CD_SSCA_ORCHESTRATION)
                   .setStepMetaData(StepMetaData.newBuilder()
                                        .addCategory("ssca")
                                        .addFolderPaths(SscaConstants.SSCA_STEPS_FOLDER_NAME)
                                        .build())
                   .setFeatureFlag(FeatureName.SSCA_ENABLED.name())
                   .build())
          .build();
}
