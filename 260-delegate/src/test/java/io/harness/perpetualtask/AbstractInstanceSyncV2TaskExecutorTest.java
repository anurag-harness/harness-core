/*
 * Copyright 2023 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.perpetualtask;

import static io.harness.annotations.dev.HarnessTeam.CDP;

import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.harness.annotations.dev.OwnedBy;
import io.harness.category.element.UnitTests;
import io.harness.delegate.beans.instancesync.info.K8sServerInstanceInfo;
import io.harness.logging.CommandExecutionStatus;
import io.harness.managerclient.DelegateAgentManagerClient;
import io.harness.network.SafeHttpCall;
import io.harness.perpetualtask.instancesync.DeploymentReleaseDetails;
import io.harness.perpetualtask.instancesync.InstanceSyncResponseV2;
import io.harness.perpetualtask.instancesync.InstanceSyncTaskDetails;
import io.harness.perpetualtask.instancesync.K8sDeploymentReleaseDetails;
import io.harness.perpetualtask.instancesync.K8sInstanceSyncPerpetualTaskParamsV2;
import io.harness.perpetualtask.instancesync.ResponseBatchConfig;
import io.harness.rule.Owner;
import io.harness.rule.OwnerRule;
import io.harness.serializer.KryoSerializer;

import software.wings.WingsBaseTest;

import com.google.protobuf.Any;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

@FieldDefaults(level = AccessLevel.PRIVATE)
@OwnedBy(CDP)
public class AbstractInstanceSyncV2TaskExecutorTest extends WingsBaseTest {
  @Mock private KryoSerializer kryoSerializer;
  @Mock private DelegateAgentManagerClient delegateAgentManagerClient;
  @Mock private K8sInstanceSyncV2Helper k8sInstanceSyncV2Helper;
  @InjectMocks private K8sInstanceSyncPerpetualTaskV2Executor k8sInstanceSyncPerpetualTaskV2Executor;

  private static final int INSTANCE_COUNT_LIMIT =
      Integer.parseInt(System.getenv().getOrDefault("INSTANCE_SYNC_RESPONSE_BATCH_INSTANCE_COUNT", "100"));
  private static final int RELEASE_COUNT_LIMIT =
      Integer.parseInt(System.getenv().getOrDefault("INSTANCE_SYNC_RESPONSE_BATCH_RELEASE_COUNT", "5"));

  private final String PERPETUAL_TASK = "perpetualTaskId";
  private final String ACCOUNT_IDENTIFIER = "acc";
  private final String PROJECT_IDENTIFIER = "proj";
  private final String ORG_IDENTIFIER = "org";

  @Test
  @Owner(developers = OwnerRule.NAMAN_TALAYCHA)
  @Category(UnitTests.class)
  public void runOnceTest() {
    MockedStatic<SafeHttpCall> aStatic = Mockito.mockStatic(SafeHttpCall.class);
    PerpetualTaskId taskId = PerpetualTaskId.newBuilder().setId(PERPETUAL_TASK).build();
    PerpetualTaskExecutionParams params =
        PerpetualTaskExecutionParams.newBuilder()
            .setCustomizedParams(Any.pack(K8sInstanceSyncPerpetualTaskParamsV2.newBuilder()
                                              .setAccountId(ACCOUNT_IDENTIFIER)
                                              .setOrgId(ORG_IDENTIFIER)
                                              .setProjectId(PROJECT_IDENTIFIER)
                                              .build()))
            .build();

    LinkedHashSet<String> namespaces = new LinkedHashSet<>();
    namespaces.add("namespace1");
    List<K8sDeploymentReleaseDetails> k8sDeploymentReleaseDetailsList = new ArrayList<>();
    K8sDeploymentReleaseDetails k8sDeploymentReleaseDetails =
        K8sDeploymentReleaseDetails.newBuilder().setReleaseName("releaseName").addAllNamespaces(namespaces).build();
    k8sDeploymentReleaseDetailsList.add(k8sDeploymentReleaseDetails);
    InstanceSyncTaskDetails instanceSyncTaskDetails =
        InstanceSyncTaskDetails.newBuilder()
            .addAllDetails(List.of(
                DeploymentReleaseDetails.newBuilder()
                    .addAllDeploymentDetails(k8sDeploymentReleaseDetailsList.stream().map(Any::pack).collect(toList()))
                    .build()))
            .setResponseBatchConfig(ResponseBatchConfig.newBuilder()
                                        .setReleaseCount(RELEASE_COUNT_LIMIT)
                                        .setInstanceCount(INSTANCE_COUNT_LIMIT)
                                        .build())
            .build();
    aStatic.when(() -> SafeHttpCall.execute(any())).thenReturn(instanceSyncTaskDetails);
    when(k8sInstanceSyncV2Helper.getServerInstanceInfoList(any()))
        .thenReturn(
            List.of(K8sServerInstanceInfo.builder().namespace("namespace1").releaseName("releaseName").build()));
    k8sInstanceSyncPerpetualTaskV2Executor.runOnce(
        PerpetualTaskId.newBuilder().setId(PERPETUAL_TASK).build(), params, Instant.now());
    verify(delegateAgentManagerClient, times(1)).processInstanceSyncNGResultV2(any(), any(), any());
  }

  @Test
  @Owner(developers = OwnerRule.NAMAN_TALAYCHA)
  @Category(UnitTests.class)
  public void runOnceFailureTest() {
    MockedStatic<SafeHttpCall> aStatic = Mockito.mockStatic(SafeHttpCall.class);
    PerpetualTaskExecutionParams params =
        PerpetualTaskExecutionParams.newBuilder()
            .setCustomizedParams(Any.pack(K8sInstanceSyncPerpetualTaskParamsV2.newBuilder()
                                              .setAccountId(ACCOUNT_IDENTIFIER)
                                              .setOrgId(ORG_IDENTIFIER)
                                              .setProjectId(PROJECT_IDENTIFIER)
                                              .build()))
            .build();

    InstanceSyncTaskDetails instanceSyncTaskDetails =
        InstanceSyncTaskDetails.newBuilder()
            .setResponseBatchConfig(ResponseBatchConfig.newBuilder()
                                        .setReleaseCount(RELEASE_COUNT_LIMIT)
                                        .setInstanceCount(INSTANCE_COUNT_LIMIT)
                                        .build())
            .build();
    aStatic.when(() -> SafeHttpCall.execute(any())).thenReturn(instanceSyncTaskDetails);
    when(k8sInstanceSyncV2Helper.getServerInstanceInfoList(any()))
        .thenReturn(
            Arrays.asList(K8sServerInstanceInfo.builder().namespace("namespace1").releaseName("releaseName").build()));
    k8sInstanceSyncPerpetualTaskV2Executor.runOnce(
        PerpetualTaskId.newBuilder().setId(PERPETUAL_TASK).build(), params, Instant.now());
    ArgumentCaptor<InstanceSyncResponseV2> captor = ArgumentCaptor.forClass(InstanceSyncResponseV2.class);
    verify(delegateAgentManagerClient).processInstanceSyncNGResultV2(anyString(), anyString(), captor.capture());
    assertThat(captor.getValue().getStatus().getExecutionStatus()).isEqualTo(CommandExecutionStatus.SKIPPED.name());
  }
}
