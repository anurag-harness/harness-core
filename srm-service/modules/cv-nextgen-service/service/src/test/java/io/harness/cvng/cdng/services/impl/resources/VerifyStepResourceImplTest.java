/*
 * Copyright 2022 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.cvng.cdng.services.impl.resources;

import static io.harness.data.structure.UUIDGenerator.generateUuid;
import static io.harness.rule.OwnerRule.DHRUVX;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.harness.CvNextGenTestBase;
import io.harness.category.element.UnitTests;
import io.harness.cvng.BuilderFactory;
import io.harness.cvng.activity.beans.DeploymentActivityResultDTO.DeploymentVerificationJobInstanceSummary;
import io.harness.cvng.activity.beans.DeploymentActivitySummaryDTO;
import io.harness.cvng.analysis.beans.CanaryAdditionalInfo;
import io.harness.cvng.analysis.beans.CanaryBlueGreenAdditionalInfo.HostSummaryInfo;
import io.harness.cvng.analysis.beans.Risk;
import io.harness.cvng.analysis.services.api.DeploymentLogAnalysisService;
import io.harness.cvng.analysis.services.api.DeploymentTimeSeriesAnalysisService;
import io.harness.cvng.beans.DataSourceType;
import io.harness.cvng.beans.MonitoredServiceDataSourceType;
import io.harness.cvng.beans.activity.ActivityVerificationStatus;
import io.harness.cvng.beans.job.Sensitivity;
import io.harness.cvng.beans.job.VerificationJobType;
import io.harness.cvng.cdng.beans.MonitoredServiceSpec.MonitoredServiceSpecType;
import io.harness.cvng.cdng.beans.v2.AnalysedDeploymentNode;
import io.harness.cvng.cdng.beans.v2.AnalysedNodeType;
import io.harness.cvng.cdng.beans.v2.AnalysisReason;
import io.harness.cvng.cdng.beans.v2.AnalysisResult;
import io.harness.cvng.cdng.beans.v2.AppliedDeploymentAnalysisType;
import io.harness.cvng.cdng.beans.v2.ClusterAnalysisOverview;
import io.harness.cvng.cdng.beans.v2.ControlDataType;
import io.harness.cvng.cdng.beans.v2.HealthSource;
import io.harness.cvng.cdng.beans.v2.MetricsAnalysis;
import io.harness.cvng.cdng.beans.v2.MetricsAnalysisOverview;
import io.harness.cvng.cdng.beans.v2.ProviderType;
import io.harness.cvng.cdng.beans.v2.VerificationOverview;
import io.harness.cvng.cdng.beans.v2.VerificationResult;
import io.harness.cvng.cdng.entities.CVNGStepTask;
import io.harness.cvng.cdng.resources.VerifyStepResourceImpl;
import io.harness.cvng.cdng.services.api.CVNGStepTaskService;
import io.harness.cvng.core.beans.monitoredService.healthSouceSpec.HealthSourceDTO;
import io.harness.cvng.core.services.impl.FeatureFlagServiceImpl;
import io.harness.cvng.resources.VerifyStepResource;
import io.harness.cvng.verificationjob.entities.VerificationJobInstance;
import io.harness.cvng.verificationjob.services.api.VerificationJobInstanceService;
import io.harness.ng.beans.PageResponse;
import io.harness.rule.Owner;
import io.harness.rule.ResourceTestRule;
import io.harness.security.SecurityContextBuilder;
import io.harness.security.dto.UserPrincipal;
import io.harness.serializer.JsonUtils;
import io.harness.telemetry.TelemetryReporter;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import com.google.inject.Inject;
import com.google.inject.Injector;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;

public class VerifyStepResourceImplTest extends CvNextGenTestBase {
  @Inject private Injector injector;
  @Mock private FeatureFlagServiceImpl featureFlagService;

  @Mock private CVNGStepTaskService stepTaskService;
  @Mock private VerificationJobInstanceService verificationJobInstanceService;
  @Mock private DeploymentLogAnalysisService deploymentLogAnalysisService;
  @Mock private DeploymentTimeSeriesAnalysisService deploymentTimeSeriesAnalysisService;
  @Mock TelemetryReporter telemetryReporter;

  private static VerifyStepResource verifyStepResource = new VerifyStepResourceImpl();
  private BuilderFactory builderFactory;
  private VerificationJobInstance verificationJobInstance;
  private CVNGStepTask cvngStepTask;
  private DeploymentActivitySummaryDTO deploymentActivitySummaryDTO;
  private List<MetricsAnalysis> metricsAnalyses;

  String baseUrl;
  String verifyStepExecutionId;

  @ClassRule
  public static final ResourceTestRule RESOURCES = ResourceTestRule.builder().addResource(verifyStepResource).build();

  @Before
  public void setup() throws IllegalAccessException, IOException {
    injector.injectMembers(verifyStepResource);
    builderFactory = BuilderFactory.getDefault();
    verifyStepExecutionId = generateUuid();
    baseUrl = "http://localhost:9998/account/" + builderFactory.getContext().getAccountId() + "/orgs/"
        + builderFactory.getContext().getOrgIdentifier() + "/projects/"
        + builderFactory.getContext().getProjectIdentifier() + "/verifications/" + verifyStepExecutionId;

    FieldUtils.writeField(verifyStepResource, "verificationJobInstanceService", verificationJobInstanceService, true);
    FieldUtils.writeField(verifyStepResource, "deploymentLogAnalysisService", deploymentLogAnalysisService, true);
    FieldUtils.writeField(
        verifyStepResource, "deploymentTimeSeriesAnalysisService", deploymentTimeSeriesAnalysisService, true);
    FieldUtils.writeField(verifyStepResource, "stepTaskService", stepTaskService, true);

    loadVerificationRelatedDocuments();
    deploymentActivitySummaryDTO = getDeploymentActivitySummaryDTO();
    verificationJobInstance = builderFactory.verificationJobInstanceBuilder().build();
    metricsAnalyses = new ArrayList<>();
    metricsAnalyses.add(builderFactory.getMetricsAnalysis());
    UserPrincipal userPrincipal = new UserPrincipal("test", "test@harness.io", "test", "accountIdentifier");
    SecurityContextBuilder.setContext(userPrincipal);

    when(stepTaskService.getByCallBackId(any())).thenReturn(cvngStepTask);
    when(verificationJobInstanceService.getVerificationJobInstance(any())).thenReturn(verificationJobInstance);
    when(stepTaskService.getDeploymentSummary(any())).thenReturn(deploymentActivitySummaryDTO);
    when(deploymentTimeSeriesAnalysisService.getMetricsAnalysisOverview(any()))
        .thenReturn(MetricsAnalysisOverview.builder().noAnalysis(1).healthy(1).unhealthy(1).warning(1).build());
    when(deploymentLogAnalysisService.getLogsAnalysisOverview(any(), any()))
        .thenReturn(ClusterAnalysisOverview.builder()
                        .knownClustersCount(1)
                        .unknownClustersCount(1)
                        .unexpectedFrequencyClustersCount(1)
                        .build());
    when(deploymentLogAnalysisService.getErrorsAnalysisOverview(any(), any()))
        .thenReturn(ClusterAnalysisOverview.builder()
                        .knownClustersCount(1)
                        .unknownClustersCount(1)
                        .unexpectedFrequencyClustersCount(1)
                        .build());
    when(deploymentTimeSeriesAnalysisService.getFilteredMetricAnalysesForVerifyStepExecutionId(any(), any(), any()))
        .thenReturn(metricsAnalyses);
  }

  @Test
  @Owner(developers = DHRUVX)
  @Category(UnitTests.class)
  public void testGetTransactionGroupsForVerifyStepExecutionId() {
    when(stepTaskService.getTransactionNames(any(), any())).thenReturn(Collections.singletonList("abc"));

    Response response =
        RESOURCES.client().target(baseUrl + "/transaction-groups").request(MediaType.APPLICATION_JSON_TYPE).get();

    assertThat(response.getStatus()).isEqualTo(200);
    List<String> transactionGroups = response.readEntity(List.class);
    assertThat(transactionGroups).hasSize(1);
    assertThat(transactionGroups.get(0)).isEqualTo("abc");
  }

  @Test
  @Owner(developers = DHRUVX)
  @Category(UnitTests.class)
  public void testGetHealthSourcesForVerifyStepExecutionId() {
    when(stepTaskService.healthSources(any(), any()))
        .thenReturn(Collections.singleton(HealthSourceDTO.builder()
                                              .name("healthSourceName")
                                              .identifier("healthSourceIdentifier")
                                              .type(DataSourceType.CLOUDWATCH_METRICS)
                                              .verificationType(DataSourceType.CLOUDWATCH_METRICS.getVerificationType())
                                              .build()));

    Response response =
        RESOURCES.client().target(baseUrl + "/health-sources").request(MediaType.APPLICATION_JSON_TYPE).get();

    assertThat(response.getStatus()).isEqualTo(200);
    List<HealthSource> healthSources = response.readEntity(new GenericType<List<HealthSource>>() {});
    assertThat(healthSources).hasSize(1);
    assertThat(healthSources.get(0).getName()).isEqualTo("healthSourceName");
    assertThat(healthSources.get(0).getIdentifier()).isEqualTo("healthSourceIdentifier");
    assertThat(healthSources.get(0).getType()).isEqualTo(MonitoredServiceDataSourceType.CLOUDWATCH_METRICS);
    assertThat(healthSources.get(0).getProviderType()).isEqualTo(ProviderType.METRICS);
  }

  @Test
  @Owner(developers = DHRUVX)
  @Category(UnitTests.class)
  public void testGetVerificationOverviewForVerifyStepExecutionId() {
    Response response = RESOURCES.client().target(baseUrl + "/overview").request(MediaType.APPLICATION_JSON_TYPE).get();
    assertThat(response.getStatus()).isEqualTo(200);
    VerificationOverview verificationOverview = response.readEntity(new GenericType<VerificationOverview>() {});

    assertThat(verificationOverview.getVerificationStatus()).isEqualTo(ActivityVerificationStatus.VERIFICATION_PASSED);
    assertThat(verificationOverview.getVerificationProgressPercentage()).isEqualTo(1);
    assertThat(verificationOverview.getVerificationStartTimestamp()).isEqualTo(1);
    assertThat(verificationOverview.getAppliedDeploymentAnalysisType()).isEqualTo(AppliedDeploymentAnalysisType.CANARY);

    assertThat(verificationOverview.getControlNodes().getNodeType()).isEqualTo(AnalysedNodeType.PRIMARY);
    AnalysedDeploymentNode analysedDeploymentControlNode =
        (AnalysedDeploymentNode) verificationOverview.getControlNodes().getNodes().get(0);
    assertThat(analysedDeploymentControlNode.getNodeIdentifier()).isEqualTo("primary");

    assertThat(verificationOverview.getTestNodes().getNodeType()).isEqualTo(AnalysedNodeType.CANARY);
    AnalysedDeploymentNode analysedDeploymentTestNode =
        (AnalysedDeploymentNode) verificationOverview.getTestNodes().getNodes().get(0);
    assertThat(analysedDeploymentTestNode.getNodeIdentifier()).isEqualTo("canary");
    assertThat(analysedDeploymentTestNode.getVerificationResult()).isEqualTo(VerificationResult.PASSED);
    assertThat(analysedDeploymentTestNode.getFailedErrorClusters()).isNull();
    assertThat(analysedDeploymentTestNode.getFailedLogClusters()).isEqualTo(1);
    assertThat(analysedDeploymentTestNode.getFailedMetrics()).isEqualTo(1);

    assertThat(verificationOverview.getSpec().getAnalysisType()).isEqualTo(VerificationJobType.TEST);
    assertThat(verificationOverview.getSpec().getDurationInMinutes()).isEqualTo(10);
    assertThat(verificationOverview.getSpec().getIsFailOnNoAnalysis()).isFalse();
    assertThat(verificationOverview.getSpec().getSensitivity()).isEqualTo(Sensitivity.MEDIUM);
    assertThat(verificationOverview.getSpec().getAnalysedServiceIdentifier())
        .isEqualTo(builderFactory.getContext().getServiceIdentifier());
    assertThat(verificationOverview.getSpec().getAnalysedEnvIdentifier())
        .isEqualTo(builderFactory.getContext().getEnvIdentifier());
    assertThat(verificationOverview.getSpec().getMonitoredServiceIdentifier())
        .isEqualTo(builderFactory.getContext().getMonitoredServiceIdentifier());
    assertThat(verificationOverview.getSpec().getMonitoredServiceTemplateIdentifier()).isNull();
    assertThat(verificationOverview.getSpec().getMonitoredServiceTemplateVersionLabel()).isNull();
    assertThat(verificationOverview.getSpec().getMonitoredServiceType()).isEqualTo(MonitoredServiceSpecType.DEFAULT);

    assertThat(verificationOverview.getMetricsAnalysis().getNoAnalysis()).isEqualTo(1);
    assertThat(verificationOverview.getMetricsAnalysis().getHealthy()).isEqualTo(1);
    assertThat(verificationOverview.getMetricsAnalysis().getWarning()).isEqualTo(1);
    assertThat(verificationOverview.getMetricsAnalysis().getUnhealthy()).isEqualTo(1);

    assertThat(verificationOverview.getLogClusters().getKnownClustersCount()).isEqualTo(1);
    assertThat(verificationOverview.getLogClusters().getUnknownClustersCount()).isEqualTo(1);
    assertThat(verificationOverview.getLogClusters().getUnexpectedFrequencyClustersCount()).isEqualTo(1);

    assertThat(verificationOverview.getErrorClusters().getKnownClustersCount()).isEqualTo(1);
    assertThat(verificationOverview.getErrorClusters().getUnknownClustersCount()).isEqualTo(1);
    assertThat(verificationOverview.getErrorClusters().getUnexpectedFrequencyClustersCount()).isEqualTo(1);
  }

  @Test
  @Owner(developers = DHRUVX)
  @Category(UnitTests.class)
  public void testGetVerificationOverviewForVerifyStepExecutionId_monitoredServiceSpecTypeIsTemplate() {
    verificationJobInstance.getResolvedJob().setMonitoredServiceTemplateIdentifier(
        "monitoredServiceTemplateIdentifier");
    verificationJobInstance.getResolvedJob().setMonitoredServiceTemplateVersionLabel(
        "monitoredServiceTemplateVersionLabel");

    Response response = RESOURCES.client().target(baseUrl + "/overview").request(MediaType.APPLICATION_JSON_TYPE).get();
    assertThat(response.getStatus()).isEqualTo(200);
    VerificationOverview verificationOverview = response.readEntity(new GenericType<VerificationOverview>() {});

    assertThat(verificationOverview.getSpec().getAnalysedServiceIdentifier())
        .isEqualTo(builderFactory.getContext().getServiceIdentifier());
    assertThat(verificationOverview.getSpec().getAnalysedEnvIdentifier())
        .isEqualTo(builderFactory.getContext().getEnvIdentifier());
    assertThat(verificationOverview.getSpec().getMonitoredServiceIdentifier())
        .isEqualTo(builderFactory.getContext().getMonitoredServiceIdentifier());
    assertThat(verificationOverview.getSpec().getMonitoredServiceTemplateIdentifier())
        .isEqualTo("monitoredServiceTemplateIdentifier");
    assertThat(verificationOverview.getSpec().getMonitoredServiceTemplateVersionLabel())
        .isEqualTo("monitoredServiceTemplateVersionLabel");
    assertThat(verificationOverview.getSpec().getMonitoredServiceType()).isEqualTo(MonitoredServiceSpecType.TEMPLATE);
  }

  @Test
  @Owner(developers = DHRUVX)
  @Category(UnitTests.class)
  public void testGetMetricsAnalysisForVerifyStepExecutionId() {
    Response response =
        RESOURCES.client().target(baseUrl + "/analysis/metrics").request(MediaType.APPLICATION_JSON_TYPE).get();
    assertThat(response.getStatus()).isEqualTo(200);
    PageResponse<MetricsAnalysis> metricsAnalysisPage =
        response.readEntity(new GenericType<PageResponse<MetricsAnalysis>>() {});
    List<MetricsAnalysis> metricsAnalyses = metricsAnalysisPage.getContent();
    assertThat(metricsAnalyses).hasSize(1);
    assertThat(metricsAnalyses.get(0).getAnalysisResult()).isEqualTo(AnalysisResult.NO_ANALYSIS);
    assertThat(metricsAnalyses.get(0).getMetricName()).isEqualTo("metricName");
    assertThat(metricsAnalyses.get(0).getMetricIdentifier()).isEqualTo("metricIdentifier");
    assertThat(metricsAnalyses.get(0).getThresholds()).hasSize(1);
    assertThat(metricsAnalyses.get(0).getHealthSource().getIdentifier()).isEqualTo("healthSourceIdentifier");
    assertThat(metricsAnalyses.get(0).getTransactionGroup()).isEqualTo("transactionGroup");
    assertThat(metricsAnalyses.get(0).getTestDataNodes()).hasSize(1);
    assertThat(metricsAnalyses.get(0).getTestDataNodes().get(0).getAnalysisResult())
        .isEqualTo(AnalysisResult.NO_ANALYSIS);
    assertThat(metricsAnalyses.get(0).getTestDataNodes().get(0).getAnalysisReason())
        .isEqualTo(AnalysisReason.NO_CONTROL_DATA);
    assertThat(metricsAnalyses.get(0).getTestDataNodes().get(0).getControlNodeIdentifier())
        .isEqualTo("controlNodeIdentifier");
    assertThat(metricsAnalyses.get(0).getTestDataNodes().get(0).getControlData()).hasSize(1);
    assertThat(metricsAnalyses.get(0).getTestDataNodes().get(0).getTestData()).hasSize(1);
    assertThat(metricsAnalyses.get(0).getTestDataNodes().get(0).getNormalisedControlData()).hasSize(1);
    assertThat(metricsAnalyses.get(0).getTestDataNodes().get(0).getNormalisedTestData()).hasSize(1);
    assertThat(metricsAnalyses.get(0).getTestDataNodes().get(0).getControlDataType())
        .isEqualTo(ControlDataType.MINIMUM_DEVIATION);
    assertThat(metricsAnalyses.get(0).getTestDataNodes().get(0).getAppliedThresholds()).isNull();
  }

  private void loadVerificationRelatedDocuments() throws IOException {
    String stepTaskDocument = readJsonResourceFile("step-task-1.json");
    cvngStepTask = JsonUtils.asObject(stepTaskDocument, CVNGStepTask.class);
  }

  private String readJsonResourceFile(String fileName) throws IOException {
    return Resources.toString(VerifyStepResourceImplTest.class.getResource("/analysis/" + fileName), Charsets.UTF_8);
  }

  private DeploymentActivitySummaryDTO getDeploymentActivitySummaryDTO() {
    CanaryAdditionalInfo canaryAdditionalInfo = new CanaryAdditionalInfo();
    canaryAdditionalInfo.setCanary(Collections.singleton(HostSummaryInfo.builder()
                                                             .hostName("canary")
                                                             .anomalousLogClustersCount(1)
                                                             .anomalousMetricsCount(1)
                                                             .risk(Risk.HEALTHY)
                                                             .build()));
    canaryAdditionalInfo.setPrimary(Collections.singleton(HostSummaryInfo.builder().hostName("primary").build()));

    return DeploymentActivitySummaryDTO.builder()
        .deploymentVerificationJobInstanceSummary(DeploymentVerificationJobInstanceSummary.builder()
                                                      .activityStartTime(1L)
                                                      .startTime(1L)
                                                      .verificationJobInstanceId("verificationJobInstanceId")
                                                      .durationMs(600000L)
                                                      .progressPercentage(1)
                                                      .risk(Risk.HEALTHY)
                                                      .status(ActivityVerificationStatus.VERIFICATION_PASSED)
                                                      .additionalInfo(canaryAdditionalInfo)
                                                      .build())
        .build();
  }
}
