/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.pms.plan.execution;

import static io.harness.plancreator.pipelinerollback.PipelineRollbackStageHelper.PIPELINE_ROLLBACK_STAGE_NAME;

import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;
import io.harness.data.structure.EmptyPredicate;
import io.harness.engine.executions.node.NodeExecutionService;
import io.harness.exception.InvalidRequestException;
import io.harness.exception.UnexpectedException;
import io.harness.execution.NodeExecution;
import io.harness.execution.NodeExecution.NodeExecutionKeys;
import io.harness.execution.PlanExecutionMetadata;
import io.harness.execution.StagesExecutionMetadata;
import io.harness.plan.IdentityPlanNode;
import io.harness.plan.Node;
import io.harness.plan.Plan;
import io.harness.plan.PlanNode;
import io.harness.pms.contracts.advisers.AdviserObtainment;
import io.harness.pms.contracts.ambiance.Ambiance;
import io.harness.pms.contracts.plan.ExecutionMetadata;
import io.harness.pms.contracts.plan.ExecutionMetadata.Builder;
import io.harness.pms.contracts.plan.ExecutionMode;
import io.harness.pms.contracts.plan.ExecutionTriggerInfo;
import io.harness.pms.contracts.plan.PipelineStageInfo;
import io.harness.pms.contracts.plan.PostExecutionRollbackInfo;
import io.harness.pms.contracts.steps.StepCategory;
import io.harness.pms.execution.utils.AmbianceUtils;
import io.harness.pms.execution.utils.NodeProjectionUtils;
import io.harness.pms.helpers.PrincipalInfoHelper;
import io.harness.pms.pipeline.service.PipelineMetadataService;
import io.harness.pms.yaml.YAMLFieldNameConstants;
import io.harness.pms.yaml.YamlUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.util.CloseableIterator;

@OwnedBy(HarnessTeam.PIPELINE)
@Singleton
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PACKAGE, onConstructor = @__({ @Inject }))
@Slf4j
public class RollbackModeExecutionHelper {
  NodeExecutionService nodeExecutionService;
  PipelineMetadataService pipelineMetadataService;
  PrincipalInfoHelper principalInfoHelper;

  public ExecutionMetadata transformExecutionMetadata(ExecutionMetadata executionMetadata, String planExecutionID,
      ExecutionTriggerInfo triggerInfo, String accountId, String orgIdentifier, String projectIdentifier,
      ExecutionMode executionMode, PipelineStageInfo parentStageInfo, List<String> stageNodeExecutionIds) {
    Builder newMetadata = executionMetadata.toBuilder()
                              .setExecutionUuid(planExecutionID)
                              .setTriggerInfo(triggerInfo)
                              .setRunSequence(pipelineMetadataService.incrementExecutionCounter(accountId,
                                  orgIdentifier, projectIdentifier, executionMetadata.getPipelineIdentifier()))
                              .setPrincipalInfo(principalInfoHelper.getPrincipalInfoFromSecurityContext())
                              .setExecutionMode(executionMode);
    if (parentStageInfo != null) {
      newMetadata = newMetadata.setPipelineStageInfo(parentStageInfo);
    }
    if (EmptyPredicate.isNotEmpty(stageNodeExecutionIds)) {
      List<NodeExecution> rollbackStageNodeExecutions = nodeExecutionService.getAllWithFieldIncluded(
          new HashSet<>(stageNodeExecutionIds), NodeProjectionUtils.fieldsForNodeAndAmbiance);
      newMetadata.addAllPostExecutionRollbackInfo(rollbackStageNodeExecutions.stream()
                                                      .map(ne -> createPostExecutionRollbackInfo(ne.getAmbiance()))
                                                      .collect(Collectors.toList()));
    }
    return newMetadata.build();
  }

  private PostExecutionRollbackInfo createPostExecutionRollbackInfo(Ambiance ambiance) {
    PostExecutionRollbackInfo.Builder builder = PostExecutionRollbackInfo.newBuilder();
    String stageId;
    // This stageId will also be the startingNodeId in the execution graph. So if its under the
    // strategy(Multi-deployment) then it must be set to strategy setupId so that graph is shown correctly.
    if (AmbianceUtils.getStrategyLevelFromAmbiance(ambiance).isPresent()) {
      // If the nodeExecutions is under the strategy, then set the stageId to strategy setupId.
      stageId = ambiance.getLevels(ambiance.getLevelsCount() - 2).getSetupId();
      builder.setRollbackStageStrategyMetadata(AmbianceUtils.obtainCurrentLevel(ambiance).getStrategyMetadata());
    } else {
      // If not under strategy then stage setupId will be the stageId.
      stageId = AmbianceUtils.obtainCurrentSetupId(ambiance);
    }
    builder.setPostExecutionRollbackStageId(stageId);
    return builder.build();
  }

  public PlanExecutionMetadata transformPlanExecutionMetadata(PlanExecutionMetadata planExecutionMetadata,
      String planExecutionID, ExecutionMode executionMode, List<String> stageNodeExecutionIds) {
    String originalPlanExecutionId = planExecutionMetadata.getPlanExecutionId();
    PlanExecutionMetadata metadata =
        planExecutionMetadata.withPlanExecutionId(planExecutionID)
            .withProcessedYaml(transformProcessedYaml(
                planExecutionMetadata.getProcessedYaml(), executionMode, originalPlanExecutionId))
            .withUuid(null); // this uuid is the mongo uuid. It is being set as null so that when this Plan Execution
                             // Metadata is saved later on in the execution, a new object is stored rather than
                             // replacing the Metadata for the original execution

    if (EmptyPredicate.isEmpty(stageNodeExecutionIds)) {
      return metadata;
    }
    List<String> rollbackStageFQNs =
        nodeExecutionService
            .getAllWithFieldIncluded(new HashSet<>(stageNodeExecutionIds), Set.of(NodeExecutionKeys.planNode))
            .stream()
            .map(NodeExecution::getStageFqn)
            .collect(Collectors.toList());
    metadata.setStagesExecutionMetadata(StagesExecutionMetadata.builder()
                                            .fullPipelineYaml(planExecutionMetadata.getYaml())
                                            .stageIdentifiers(rollbackStageFQNs)
                                            .build());
    return metadata;
  }

  String transformProcessedYaml(String processedYaml, ExecutionMode executionMode, String originalPlanExecutionId) {
    switch (executionMode) {
      case PIPELINE_ROLLBACK:
        return transformProcessedYamlForPipelineRollbackMode(processedYaml, originalPlanExecutionId);
      case POST_EXECUTION_ROLLBACK:
        return transformProcessedYamlForPostExecutionRollbackMode(processedYaml);
      default:
        throw new InvalidRequestException(String.format(
            "Unsupported Execution Mode %s in RollbackModeExecutionHelper while transforming plan for execution with id %s",
            executionMode.name(), originalPlanExecutionId));
    }
  }

  /**
   * This is to reverse the stages in the processed yaml, and remove stages that were not run in the original execution
   * Original->
   * pipeline:
   *   stages:
   *   - stage:
   *       identifier: s1
   *  - stage:
   *       identifier: s2
   *  - stage:
   *       identifier: s3
   * Lets say s3 was not run.
   * Transformed->
   * pipeline:
   *   stages:
   *   - stage:
   *       identifier: s2
   *   - stage:
   *       identifier: s1
   */
  String transformProcessedYamlForPipelineRollbackMode(String processedYaml, String originalPlanExecutionId) {
    List<String> executedStages = nodeExecutionService.getStageDetailFromPlanExecutionId(originalPlanExecutionId)
                                      .stream()
                                      .filter(info -> !info.getName().equals(PIPELINE_ROLLBACK_STAGE_NAME))
                                      .map(info -> info.getIdentifier())
                                      .collect(Collectors.toList());

    JsonNode pipelineNode;
    try {
      pipelineNode = YamlUtils.readTree(processedYaml).getNode().getCurrJsonNode();
    } catch (IOException e) {
      throw new UnexpectedException("Unable to transform processed YAML while executing in Rollback Mode");
    }
    ObjectNode pipelineInnerNode = (ObjectNode) pipelineNode.get(YAMLFieldNameConstants.PIPELINE);
    ArrayNode stagesList = (ArrayNode) pipelineInnerNode.get(YAMLFieldNameConstants.STAGES);
    ArrayNode reversedStages = stagesList.deepCopy().removeAll();
    int numStages = stagesList.size();
    for (int i = numStages - 1; i >= 0; i--) {
      JsonNode currentNode = stagesList.get(i);
      JsonNode currentStageNode = currentNode.get(YAMLFieldNameConstants.PARALLEL) == null
          ? currentNode
          : currentNode.get(YAMLFieldNameConstants.PARALLEL).get(0);
      String stageId =
          currentStageNode.get(YAMLFieldNameConstants.STAGE).get(YAMLFieldNameConstants.IDENTIFIER).asText();
      if (executedStages.contains(stageId)) {
        reversedStages.add(currentNode);
      }
    }
    pipelineInnerNode.set(YAMLFieldNameConstants.STAGES, reversedStages);
    return YamlUtils.write(pipelineNode).replace("---\n", "");
  }

  /**
   * This is to reverse the stages in the processed yaml
   * Original->
   * pipeline:
   *   stages:
   *   - stage:
   *       identifier: s1
   *  - stage:
   *       identifier: s2
   * Transformed->
   * pipeline:
   *   stages:
   *   - stage:
   *       identifier: s2
   *   - stage:
   *       identifier: s1
   */
  String transformProcessedYamlForPostExecutionRollbackMode(String processedYaml) {
    JsonNode pipelineNode;
    try {
      pipelineNode = YamlUtils.readTree(processedYaml).getNode().getCurrJsonNode();
    } catch (IOException e) {
      throw new UnexpectedException("Unable to transform processed YAML while executing in Rollback Mode");
    }
    ObjectNode pipelineInnerNode = (ObjectNode) pipelineNode.get(YAMLFieldNameConstants.PIPELINE);
    ArrayNode stagesList = (ArrayNode) pipelineInnerNode.get(YAMLFieldNameConstants.STAGES);
    ArrayNode reversedStages = stagesList.deepCopy().removeAll();
    int numStages = stagesList.size();
    for (int i = numStages - 1; i >= 0; i--) {
      reversedStages.add(stagesList.get(i));
    }
    pipelineInnerNode.set(YAMLFieldNameConstants.STAGES, reversedStages);
    return YamlUtils.write(pipelineNode).replace("---\n", "");
  }

  /**
   * Step1: Initialise a map from planNodeIDs to Plan Nodes
   * Step2: fetch all node executions of previous execution that are the descendants of any stage
   * Step3: create identity plan nodes for all node executions that are the descendants of any stage, and add them to
   * the map
   * Step4: Go through `createdPlan`. If any Plan node has AdvisorObtainments for POST_EXECUTION_ROLLBACK Mode, add them
   * to the corresponding Identity Plan Node in the initialised map
   * Step5: From `createdPlan`, pick out all nodes that are not a descendants of some stage, and add them to the
   * initialised map.
   * Step6: For all IDs in `nodeIDsToPreserve`, remove the Identity Plan Nodes in the map, and put the
   * Plan nodes from `createdPlan`
   */
  public Plan transformPlanForRollbackMode(Plan createdPlan, String previousExecutionId, List<String> nodeIDsToPreserve,
      ExecutionMode executionMode, List<String> rollbackStageIds) {
    // steps 1, 2, and 3
    Map<String, Node> planNodeIDToUpdatedPlanNodes =
        buildIdentityNodes(previousExecutionId, createdPlan.getPlanNodes());

    // step 4
    addAdvisorsToIdentityNodes(createdPlan, planNodeIDToUpdatedPlanNodes, executionMode, rollbackStageIds);

    // steps 5 and 6
    addPreservedPlanNodes(createdPlan, nodeIDsToPreserve, planNodeIDToUpdatedPlanNodes);

    return Plan.builder()
        .uuid(createdPlan.getUuid())
        .planNodes(planNodeIDToUpdatedPlanNodes.values())
        .startingNodeId(createdPlan.getStartingNodeId())
        .setupAbstractions(createdPlan.getSetupAbstractions())
        .graphLayoutInfo(createdPlan.getGraphLayoutInfo())
        .validUntil(createdPlan.getValidUntil())
        .valid(createdPlan.isValid())
        .errorResponse(createdPlan.getErrorResponse())
        .build();
  }

  Map<String, Node> buildIdentityNodes(String previousExecutionId, List<Node> createdPlanNodes) {
    Map<String, Node> planNodeIDToUpdatedNodes = new HashMap<>();

    CloseableIterator<NodeExecution> nodeExecutions =
        getNodeExecutionsWithOnlyRequiredFields(previousExecutionId, createdPlanNodes);

    while (nodeExecutions.hasNext()) {
      NodeExecution nodeExecution = nodeExecutions.next();
      Node planNode = nodeExecution.getNode();
      if (planNode.getStepType().getStepCategory() == StepCategory.STAGE) {
        continue;
      }
      IdentityPlanNode identityPlanNode = IdentityPlanNode.mapPlanNodeToIdentityNode(
          nodeExecution.getNode(), nodeExecution.getStepType(), nodeExecution.getUuid(), true);
      planNodeIDToUpdatedNodes.put(planNode.getUuid(), identityPlanNode);
    }
    return planNodeIDToUpdatedNodes;
  }

  CloseableIterator<NodeExecution> getNodeExecutionsWithOnlyRequiredFields(
      String previousExecutionId, List<Node> createdPlanNodes) {
    List<String> stageFQNs = createdPlanNodes.stream()
                                 .filter(n -> n.getStepCategory() == StepCategory.STAGE)
                                 .map(Node::getStageFqn)
                                 .collect(Collectors.toList());
    return nodeExecutionService.fetchNodeExecutionsForGivenStageFQNs(
        previousExecutionId, stageFQNs, NodeProjectionUtils.fieldsForIdentityNodeCreation);
  }

  void addAdvisorsToIdentityNodes(Plan createdPlan, Map<String, Node> planNodeIDToUpdatedPlanNodes,
      ExecutionMode executionMode, List<String> stageFQNsToRollback) {
    for (Node planNode : createdPlan.getPlanNodes()) {
      if (EmptyPredicate.isEmpty(planNode.getAdvisorObtainmentsForExecutionMode())) {
        continue;
      }
      if (executionMode == ExecutionMode.POST_EXECUTION_ROLLBACK) {
        if (EmptyPredicate.isEmpty(stageFQNsToRollback) || !stageFQNsToRollback.contains(planNode.getStageFqn())) {
          continue;
        }
      }
      List<AdviserObtainment> adviserObtainments = planNode.getAdvisorObtainmentsForExecutionMode().get(executionMode);
      if (EmptyPredicate.isNotEmpty(adviserObtainments)) {
        IdentityPlanNode updatedNode = (IdentityPlanNode) planNodeIDToUpdatedPlanNodes.get(planNode.getUuid());
        if (updatedNode == null) {
          // this means that the stage had failed before the node could start in the previous execution
          continue;
        }
        planNodeIDToUpdatedPlanNodes.put(
            planNode.getUuid(), updatedNode.withAdviserObtainments(adviserObtainments).withUseAdviserObtainments(true));
      }
    }
  }

  void addPreservedPlanNodes(
      Plan createdPlan, List<String> nodeIDsToPreserve, Map<String, Node> planNodeIDToUpdatedPlanNodes) {
    for (Node node : createdPlan.getPlanNodes()) {
      if (nodeIDsToPreserve.contains(node.getUuid()) || isStageOrAncestorOfSomeStage(node)) {
        PlanNode planNode = ((PlanNode) node).withPreserveInRollbackMode(true);
        planNodeIDToUpdatedPlanNodes.put(node.getUuid(), planNode);
      }
    }
  }

  boolean isStageOrAncestorOfSomeStage(Node planNode) {
    StepCategory stepCategory = planNode.getStepCategory();
    if (Arrays.asList(StepCategory.PIPELINE, StepCategory.STAGES, StepCategory.STAGE).contains(stepCategory)) {
      return true;
    }
    // todo: once fork and strategy are divided in sub categories of step and stage, add that check as well
    // parallel nodes and strategy nodes need to be plan nodes so that we don't take the advisor response from the
    // previous execution. Previous execution's advisor response would be setting next step as something we dont want in
    // rollback mode. We want the new advisors set in the Plan Node to be used
    return Arrays.asList(StepCategory.FORK, StepCategory.STRATEGY).contains(stepCategory);
  }
}
