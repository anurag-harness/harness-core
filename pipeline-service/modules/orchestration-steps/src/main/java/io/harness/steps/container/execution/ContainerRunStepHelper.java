/*
 * Copyright 2022 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.steps.container.execution;

import static io.harness.ci.commonconstants.ContainerExecutionConstants.LITE_ENGINE_PORT;
import static io.harness.ci.commonconstants.ContainerExecutionConstants.TMP_PATH;
import static io.harness.data.structure.EmptyPredicate.isEmpty;
import static io.harness.data.structure.EmptyPredicate.isNotEmpty;

import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;
import io.harness.beans.outcomes.LiteEnginePodDetailsOutcome;
import io.harness.beans.yaml.extended.CIShellType;
import io.harness.callback.DelegateCallbackToken;
import io.harness.delegate.beans.TaskData;
import io.harness.delegate.beans.ci.k8s.CIK8ExecuteStepTaskParams;
import io.harness.pms.contracts.ambiance.Ambiance;
import io.harness.pms.expression.ExpressionResolverUtils;
import io.harness.pms.sdk.core.plugin.ContainerDelegateTaskHelper;
import io.harness.pms.sdk.core.plugin.ContainerPortHelper;
import io.harness.pms.sdk.core.plugin.ContainerUnitStepUtils;
import io.harness.pms.sdk.core.resolver.RefObjectUtils;
import io.harness.pms.sdk.core.resolver.outcome.OutcomeService;
import io.harness.product.ci.engine.proto.ExecuteStepRequest;
import io.harness.product.ci.engine.proto.RunStep;
import io.harness.product.ci.engine.proto.StepContext;
import io.harness.product.ci.engine.proto.UnitStep;
import io.harness.steps.container.exception.ContainerStepExecutionException;
import io.harness.steps.container.execution.plugin.PluginStepSerializer;
import io.harness.steps.container.utils.ContainerStepResolverUtils;
import io.harness.steps.plugin.ContainerStepInfo;
import io.harness.steps.plugin.ContainerStepSpec;
import io.harness.steps.plugin.PluginStep;
import io.harness.yaml.core.variables.OutputNGVariable;

import com.google.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@OwnedBy(HarnessTeam.PIPELINE)
public class ContainerRunStepHelper {
  @Inject ContainerDelegateTaskHelper containerDelegateTaskHelper;
  @Inject Supplier<DelegateCallbackToken> delegateCallbackTokenSupplier;
  @Inject OutcomeService outcomeService;
  @Inject ContainerExecutionConfig containerExecutionConfig;
  @Inject PluginStepSerializer pluginStepSerializer;
  @Inject ContainerPortHelper containerPortHelper;

  public TaskData getRunStepTask(Ambiance ambiance, ContainerStepSpec containerStepInfo, String accountId,
      String logKey, long timeout, String parkedTaskId) {
    UnitStep unitStep = serialiseStep(ambiance, containerStepInfo, accountId, logKey, timeout, parkedTaskId);
    LiteEnginePodDetailsOutcome liteEnginePodDetailsOutcome = (LiteEnginePodDetailsOutcome) outcomeService.resolve(
        ambiance, RefObjectUtils.getOutcomeRefObject(LiteEnginePodDetailsOutcome.POD_DETAILS_OUTCOME));
    String ip = liteEnginePodDetailsOutcome.getIpAddress();

    ExecuteStepRequest executeStepRequest = ExecuteStepRequest.newBuilder()
                                                .setExecutionId(ambiance.getPlanExecutionId())
                                                .setStep(unitStep)
                                                .setTmpFilePath(TMP_PATH)
                                                .build();
    CIK8ExecuteStepTaskParams params =
        CIK8ExecuteStepTaskParams.builder()
            .ip(ip)
            .port(LITE_ENGINE_PORT)
            .serializedStep(executeStepRequest.toByteArray())
            .isLocal(containerExecutionConfig.isLocal())
            .delegateSvcEndpoint(containerExecutionConfig.getDelegateServiceEndpointVariableValue())
            .build();
    return containerDelegateTaskHelper.getDelegateTaskDataForExecuteStep(ambiance, timeout, params);
  }

  private UnitStep serialiseStep(Ambiance ambiance, ContainerStepSpec containerStepInfo, String accountId,
      String logKey, long timeout, String parkedTaskId) {
    String identifier = containerStepInfo.getIdentifier();
    Integer port = containerPortHelper.getPort(ambiance, identifier);
    switch (containerStepInfo.getType()) {
      case RUN_CONTAINER:
        return serializeStepWithStepParameters((ContainerStepInfo) containerStepInfo, port, parkedTaskId, logKey,
            identifier, accountId, containerStepInfo.getName(), timeout);
      case CD_SSCA_ORCHESTRATION:
        return pluginStepSerializer.serializeStepWithStepParameters((PluginStep) containerStepInfo, port, parkedTaskId,
            logKey, identifier, timeout, accountId, containerStepInfo.getName(), delegateCallbackTokenSupplier,
            ambiance);
      default:
        throw new ContainerStepExecutionException("Step serialization not handled");
    }
  }

  private UnitStep serializeStepWithStepParameters(ContainerStepInfo runStepInfo, Integer port, String callbackId,
      String logKey, String identifier, String accountId, String stepName, long timeout) {
    if (callbackId == null) {
      throw new ContainerStepExecutionException("CallbackId can not be null");
    }
    if (port == null) {
      throw new ContainerStepExecutionException("Port can not be null");
    }

    RunStep.Builder runStepBuilder = RunStep.newBuilder();
    runStepBuilder.setCommand(
        ExpressionResolverUtils.resolveStringParameter("Command", "Run", identifier, runStepInfo.getCommand(), true));

    runStepBuilder.setContainerPort(port);
    Map<String, String> envvars = ExpressionResolverUtils.resolveMapParameter(
        "envVariables", "Run", identifier, runStepInfo.getEnvVariables(), false);
    if (!isEmpty(envvars)) {
      runStepBuilder.putAllEnvironment(envvars);
    }

    if (isNotEmpty(runStepInfo.getOutputVariables().getValue())) {
      List<String> outputVarNames = runStepInfo.getOutputVariables()
                                        .getValue()
                                        .stream()
                                        .map(OutputNGVariable::getName)
                                        .collect(Collectors.toList());
      runStepBuilder.addAllEnvVarOutputs(outputVarNames);
    }

    runStepBuilder.setContext(StepContext.newBuilder().setExecutionTimeoutSecs(timeout).build());

    CIShellType shellType = ContainerStepResolverUtils.resolveShellType(runStepInfo.getShell());
    return ContainerUnitStepUtils.getUnitStep(port, callbackId, logKey, identifier, accountId, stepName, runStepBuilder,
        shellType, delegateCallbackTokenSupplier);
  }
}
