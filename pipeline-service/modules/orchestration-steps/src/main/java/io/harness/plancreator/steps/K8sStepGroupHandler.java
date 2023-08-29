/*
 * Copyright 2023 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.plancreator.steps;

import io.harness.plancreator.execution.StepsExecutionConfig;
import io.harness.pms.sdk.core.adviser.success.OnSuccessAdviserParameters;
import io.harness.pms.sdk.core.plan.PlanNode;
import io.harness.pms.sdk.core.plan.creation.beans.PlanCreationContext;
import io.harness.pms.yaml.YamlField;
import io.harness.serializer.KryoSerializer;
import io.harness.steps.StepSpecTypeConstants;
import io.harness.steps.plugin.InitContainerV2StepInfo;
import io.harness.steps.plugin.infrastructure.ContainerK8sInfra;
import io.harness.steps.plugin.infrastructure.K8sDirectInfra;

import com.google.inject.Inject;
import com.google.protobuf.ByteString;

public class K8sStepGroupHandler implements StepGroupInfraHandler {
  public static final int DEFAULT_TIMEOUT = 600 * 1000;

  @Inject KryoSerializer kryoSerializer;

  @Override
  public PlanNode handle(StepGroupElementConfigV2 config, PlanCreationContext ctx, YamlField stepsField) {
    InitContainerV2StepInfo initContainerV2StepInfo =
        InitContainerV2StepInfo.builder()
            .stepGroupIdentifier(config.getIdentifier())
            .stepGroupName(config.getName())
            .infrastructure(
                ContainerK8sInfra.builder().spec(((K8sDirectInfra) config.getStepGroupInfra()).getSpec()).build())
            .stepsExecutionConfig(StepsExecutionConfig.builder().steps(config.getSteps()).build())
            .build();
    String initNodeId = "init-" + ctx.getCurrentField().getNode().getUuid();

    String nextNodeId = stepsField.getNode().asArray().get(0).getField("step").getNode().getUuid();
    ByteString advisorParametersInitStep = ByteString.copyFrom(
        kryoSerializer.asBytes(OnSuccessAdviserParameters.builder().nextNodeId(nextNodeId).build()));
    return InitContainerStepPlanCreater.createPlanForField(
        initNodeId, initContainerV2StepInfo, advisorParametersInitStep, StepSpecTypeConstants.INIT_CONTAINER_STEP_V2);
  }
}
