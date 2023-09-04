/*
 * Copyright 2023 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.idp.onboarding.service.impl;

import static io.harness.data.structure.EmptyPredicate.isEmpty;
import static io.harness.data.structure.EmptyPredicate.isNotEmpty;
import static io.harness.eventsframework.EventsFrameworkMetadataConstants.CREATE_ACTION;
import static io.harness.idp.common.CommonUtils.readFileFromClassPath;
import static io.harness.idp.common.YamlUtils.writeObjectAsYaml;
import static io.harness.idp.onboarding.utils.Constants.BACKSTAGE_LOCATION_URL_TYPE;
import static io.harness.idp.onboarding.utils.Constants.ENTITY_REQUIRED_ERROR_MESSAGE;
import static io.harness.idp.onboarding.utils.Constants.ORGANIZATION;
import static io.harness.idp.onboarding.utils.Constants.PAGE_LIMIT_FOR_ENTITY_FETCH;
import static io.harness.idp.onboarding.utils.Constants.PROJECT;
import static io.harness.idp.onboarding.utils.Constants.SAMPLE_ENTITY_CLASSPATH_LOCATION;
import static io.harness.idp.onboarding.utils.Constants.SAMPLE_ENTITY_NAME;
import static io.harness.idp.onboarding.utils.Constants.SERVICE;
import static io.harness.idp.onboarding.utils.Constants.SLASH_DELIMITER;
import static io.harness.idp.onboarding.utils.Constants.SOURCE_FORMAT;
import static io.harness.idp.onboarding.utils.Constants.STATUS_UPDATE_REASON_FOR_ONBOARDING_COMPLETED;
import static io.harness.idp.onboarding.utils.Constants.SUCCESS_RESPONSE_STRING;
import static io.harness.idp.onboarding.utils.Constants.YAML_FILE_EXTENSION;
import static io.harness.idp.onboarding.utils.FileUtils.cleanUpDirectories;
import static io.harness.idp.onboarding.utils.FileUtils.createDirectories;
import static io.harness.idp.onboarding.utils.FileUtils.writeObjectAsYamlInFile;
import static io.harness.idp.onboarding.utils.FileUtils.writeStringInFile;
import static io.harness.remote.client.NGRestUtils.getGeneralResponse;
import static io.harness.remote.client.NGRestUtils.getResponse;

import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;
import io.harness.clients.BackstageCatalogLocationCreateRequest;
import io.harness.clients.BackstageResourceClient;
import io.harness.connector.ConnectorInfoDTO;
import io.harness.delegate.beans.connector.ConnectorType;
import io.harness.eventsframework.entity_crud.EntityChangeDTO;
import io.harness.exception.InvalidRequestException;
import io.harness.exception.UnexpectedException;
import io.harness.idp.common.Constants;
import io.harness.idp.common.GsonUtils;
import io.harness.idp.events.producers.IdpProducer;
import io.harness.idp.gitintegration.beans.CatalogInfraConnectorType;
import io.harness.idp.gitintegration.beans.CatalogRepositoryDetails;
import io.harness.idp.gitintegration.entities.CatalogConnectorEntity;
import io.harness.idp.gitintegration.mappers.CatalogConnectorMapper;
import io.harness.idp.gitintegration.processor.base.ConnectorProcessor;
import io.harness.idp.gitintegration.processor.factory.ConnectorProcessorFactory;
import io.harness.idp.gitintegration.repositories.CatalogConnectorRepository;
import io.harness.idp.gitintegration.service.GitIntegrationService;
import io.harness.idp.gitintegration.utils.GitIntegrationUtils;
import io.harness.idp.gitintegration.utils.delegateselectors.DelegateSelectorsCache;
import io.harness.idp.onboarding.beans.AsyncCatalogImportDetails;
import io.harness.idp.onboarding.beans.BackstageCatalogComponentEntity;
import io.harness.idp.onboarding.beans.BackstageCatalogDomainEntity;
import io.harness.idp.onboarding.beans.BackstageCatalogEntity;
import io.harness.idp.onboarding.beans.BackstageCatalogSystemEntity;
import io.harness.idp.onboarding.config.OnboardingModuleConfig;
import io.harness.idp.onboarding.entities.AsyncCatalogImportEntity;
import io.harness.idp.onboarding.mappers.HarnessEntityToBackstageEntity;
import io.harness.idp.onboarding.mappers.HarnessOrgToBackstageDomain;
import io.harness.idp.onboarding.mappers.HarnessProjectToBackstageSystem;
import io.harness.idp.onboarding.mappers.HarnessServiceToBackstageComponent;
import io.harness.idp.onboarding.repositories.AsyncCatalogImportRepository;
import io.harness.idp.onboarding.service.OnboardingService;
import io.harness.idp.status.enums.StatusType;
import io.harness.idp.status.service.StatusInfoService;
import io.harness.ng.beans.PageResponse;
import io.harness.ng.core.dto.OrganizationDTO;
import io.harness.ng.core.dto.OrganizationResponse;
import io.harness.ng.core.dto.ProjectDTO;
import io.harness.ng.core.dto.ProjectResponse;
import io.harness.ng.core.service.dto.ServiceResponse;
import io.harness.ng.core.service.dto.ServiceResponseDTO;
import io.harness.organization.remote.OrganizationClient;
import io.harness.project.remote.ProjectClient;
import io.harness.security.SourcePrincipalContextBuilder;
import io.harness.security.dto.UserPrincipal;
import io.harness.service.remote.ServiceResourceClient;
import io.harness.spec.server.idp.v1.model.CatalogConnectorInfo;
import io.harness.spec.server.idp.v1.model.EntitiesForImport;
import io.harness.spec.server.idp.v1.model.GenerateYamlRequest;
import io.harness.spec.server.idp.v1.model.GenerateYamlResponse;
import io.harness.spec.server.idp.v1.model.GenerateYamlResponseGeneratedYaml;
import io.harness.spec.server.idp.v1.model.HarnessBackstageEntities;
import io.harness.spec.server.idp.v1.model.HarnessEntitiesCountResponse;
import io.harness.spec.server.idp.v1.model.ImportEntitiesBase;
import io.harness.spec.server.idp.v1.model.ImportEntitiesResponse;
import io.harness.spec.server.idp.v1.model.IndividualEntitiesImport;
import io.harness.spec.server.idp.v1.model.ManualImportEntityRequest;
import io.harness.spec.server.idp.v1.model.StatusInfo;
import io.harness.utils.PageUtils;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.commons.math3.util.Pair;

@FieldDefaults(level = AccessLevel.PRIVATE)
@Slf4j
@OwnedBy(HarnessTeam.IDP)
public class OnboardingServiceImpl implements OnboardingService {
  @Inject @Named("onboardingModuleConfig") OnboardingModuleConfig onboardingModuleConfig;
  @Inject @Named("PRIVILEGED") OrganizationClient organizationClient;
  @Inject @Named("PRIVILEGED") ProjectClient projectClient;
  @Inject ServiceResourceClient serviceResourceClient;
  @Inject HarnessOrgToBackstageDomain harnessOrgToBackstageDomain;
  @Inject HarnessProjectToBackstageSystem harnessProjectToBackstageSystem;
  @Inject HarnessServiceToBackstageComponent harnessServiceToBackstageComponent;
  @Inject ConnectorProcessorFactory connectorProcessorFactory;
  @Inject CatalogConnectorRepository catalogConnectorRepository;
  @Inject BackstageResourceClient backstageResourceClient;
  @Inject GitIntegrationService gitIntegrationService;
  @Inject StatusInfoService statusInfoService;
  @Inject DelegateSelectorsCache delegateSelectorsCache;
  @Inject AsyncCatalogImportRepository asyncCatalogImportRepository;
  @Inject IdpProducer idpProducer;

  @Override
  public HarnessEntitiesCountResponse getHarnessEntitiesCount(String accountIdentifier) {
    long organizationsTotalCount = getOrganizationsTotalCount(accountIdentifier);
    long projectsTotalCount = getProjectsTotalCount(accountIdentifier);
    long servicesTotalCount = getServicesTotalCount(accountIdentifier);
    log.info("Found {} organizations, {} projects, {} services for IDP onboarding import", organizationsTotalCount,
        projectsTotalCount, servicesTotalCount);

    HarnessEntitiesCountResponse harnessEntitiesCountResponse = new HarnessEntitiesCountResponse();

    harnessEntitiesCountResponse.setOrgCount((int) organizationsTotalCount);
    harnessEntitiesCountResponse.setProjectCount((int) projectsTotalCount);
    harnessEntitiesCountResponse.setServiceCount((int) servicesTotalCount);

    return harnessEntitiesCountResponse;
  }

  @Override
  public PageResponse<HarnessBackstageEntities> getHarnessEntities(String accountIdentifier, int page, int limit,
      String sort, String order, String searchTerm, String projectToFilter) {
    List<ServiceResponseDTO> services = getServices(accountIdentifier, searchTerm);
    services = filterByProject.apply(services, projectToFilter);

    List<BackstageCatalogComponentEntity> catalogComponents = harnessServiceToBackstageComponent(services);
    log.info("Mapped harness entities to backstage entities for IDP onboarding import");

    List<HarnessBackstageEntities> harnessBackstageEntities = new ArrayList<>();
    harnessBackstageEntities.addAll(BackstageCatalogComponentEntity.map(catalogComponents));
    log.info("Converted harness backstage entities to response view");

    return PageUtils.offsetAndLimit(harnessBackstageEntities, page, limit);
  }

  @Override
  public GenerateYamlResponse generateYaml(String harnessAccount, GenerateYamlRequest generateYamlRequest) {
    List<EntitiesForImport> entities = generateYamlRequest.getEntities();
    GenerateYamlResponse generateYamlResponse = new GenerateYamlResponse();
    GenerateYamlResponseGeneratedYaml generatedYaml = new GenerateYamlResponseGeneratedYaml();
    if (!entities.isEmpty()) {
      Map<String, Map<String, List<String>>> orgProjectsServicesMapping =
          getOrgProjectsServicesMapping(Collections.singletonList(entities.get(0).getIdentifier()));
      ServiceResponseDTO serviceResponseDTO = getServiceDTOS(harnessAccount, orgProjectsServicesMapping).get(0);
      BackstageCatalogComponentEntity backstageCatalogComponentEntity =
          harnessServiceToBackstageComponent(Collections.singletonList(serviceResponseDTO)).get(0);
      generatedYaml.setYamlDef(writeObjectAsYaml(backstageCatalogComponentEntity));
      generatedYaml.setDescription(onboardingModuleConfig.getDescriptionForEntitySelected());
    } else {
      generatedYaml.setYamlDef(readFileFromClassPath(SAMPLE_ENTITY_CLASSPATH_LOCATION));
      generatedYaml.setDescription(onboardingModuleConfig.getDescriptionForSampleEntity());
    }
    generateYamlResponse.setGeneratedYaml(generatedYaml);
    return generateYamlResponse;
  }

  @Override
  public ImportEntitiesResponse importHarnessEntities(
      String accountIdentifier, ImportEntitiesBase importHarnessEntitiesRequest) throws ExecutionException {
    CatalogConnectorInfo catalogConnectorInfo = importHarnessEntitiesRequest.getCatalogConnectorInfo();

    if (importHarnessEntitiesRequest.getType().equals(ImportEntitiesBase.TypeEnum.SAMPLE)) {
      return importSampleEntity(accountIdentifier, catalogConnectorInfo);
    }

    Triple<List<OrganizationDTO>, List<ProjectDTO>, List<ServiceResponseDTO>> orgProjectService =
        getOrgProjectService(accountIdentifier, importHarnessEntitiesRequest);

    List<BackstageCatalogDomainEntity> catalogDomains = harnessOrgToBackstageDomain(orgProjectService.getLeft());
    List<BackstageCatalogSystemEntity> catalogSystems = harnessProjectToBackstageSystem(orgProjectService.getMiddle());
    List<BackstageCatalogComponentEntity> catalogComponents =
        harnessServiceToBackstageComponent(orgProjectService.getRight());
    log.info("Mapped harness entities to backstage entities for IDP onboarding import");

    catalogConnectorInfo.getConnector().setIdentifier(
        GitIntegrationUtils.replaceAccountScopeFromConnectorId(catalogConnectorInfo.getConnector().getIdentifier()));

    log.info("IDP onboarding import - connector processor initialized for type = {}",
        catalogConnectorInfo.getConnector().getType());

    ConnectorProcessor connectorProcessor = connectorProcessorFactory.getConnectorProcessor(
        ConnectorType.fromString(String.valueOf(catalogConnectorInfo.getConnector().getType())));
    ConnectorInfoDTO connectorInfoDTO =
        connectorProcessor.getConnectorInfo(accountIdentifier, catalogConnectorInfo.getConnector().getIdentifier());
    String catalogInfraConnectorType = connectorProcessor.getInfraConnectorType(connectorInfoDTO);
    saveCatalogConnector(accountIdentifier, catalogConnectorInfo, catalogInfraConnectorType, connectorInfoDTO);
    createCatalogInfraConnectorInBackstageK8S(
        accountIdentifier, catalogConnectorInfo, catalogInfraConnectorType, connectorInfoDTO);

    String tmpPathForCatalogInfoYamlStore =
        onboardingModuleConfig.getTmpPathForCatalogInfoYamlStore() + SLASH_DELIMITER + accountIdentifier;
    String entitiesFolderPath = getEntitiesFolderPath(catalogConnectorInfo);
    String catalogInfoLocationParentPath = tmpPathForCatalogInfoYamlStore + entitiesFolderPath + SLASH_DELIMITER;
    String orgYamlPath = catalogInfoLocationParentPath + ORGANIZATION + SLASH_DELIMITER;
    String projectYamlPath = catalogInfoLocationParentPath + PROJECT + SLASH_DELIMITER;
    String serviceYamlPath = catalogInfoLocationParentPath + SERVICE + SLASH_DELIMITER;

    createDirectories(orgYamlPath, projectYamlPath, serviceYamlPath);
    log.info("Initialized directories to write yaml files for IDP onboarding import");

    String entityTargetParentPath = catalogConnectorInfo.getRepo() + SLASH_DELIMITER + SOURCE_FORMAT + SLASH_DELIMITER
        + catalogConnectorInfo.getBranch() + SLASH_DELIMITER + accountIdentifier + entitiesFolderPath + SLASH_DELIMITER;

    Pair<BackstageCatalogEntity, Pair<String, String>> backstageCatalogEntityInitial = getFirstAmongAll(
        catalogInfoLocationParentPath, entityTargetParentPath, catalogDomains, catalogSystems, catalogComponents);
    log.info("Fetched {} for initial import in IDP onboarding flow", backstageCatalogEntityInitial);
    List<String> initialFileToPush =
        writeEntityAsYamlInFile(Collections.singletonList(backstageCatalogEntityInitial.getFirst()),
            backstageCatalogEntityInitial.getSecond().getFirst());
    connectorProcessor.performPushOperation(accountIdentifier, catalogConnectorInfo,
        onboardingModuleConfig.getTmpPathForCatalogInfoYamlStore(), initialFileToPush,
        onboardingModuleConfig.isUseGitServiceGrpcForSingleEntityPush());

    log.info("Cleaning up directories created during IDP onboarding");
    cleanUpDirectories(tmpPathForCatalogInfoYamlStore);

    saveStatusInfo(accountIdentifier, StatusType.ONBOARDING.name(), StatusInfo.CurrentStatusEnum.COMPLETED,
        STATUS_UPDATE_REASON_FOR_ONBOARDING_COMPLETED);

    log.info("Finished operation of yaml generation, pushing to source for one initial entity, saving status info");

    asyncCatalogImportRepository.save(
        AsyncCatalogImportEntity.builder()
            .accountIdentifier(accountIdentifier)
            .catalogDomains(new AsyncCatalogImportDetails(
                catalogDomains, orgYamlPath, entityTargetParentPath + ORGANIZATION + SLASH_DELIMITER))
            .catalogSystems(new AsyncCatalogImportDetails(
                catalogSystems, projectYamlPath, entityTargetParentPath + PROJECT + SLASH_DELIMITER))
            .catalogComponents(new AsyncCatalogImportDetails(
                catalogComponents, serviceYamlPath, entityTargetParentPath + SERVICE + SLASH_DELIMITER))
            .catalogInfraConnectorType(catalogInfraConnectorType)
            .catalogConnectorInfo(catalogConnectorInfo)
            .userPrincipal((UserPrincipal) SourcePrincipalContextBuilder.getSourcePrincipal())
            .build());
    boolean producerResult = idpProducer.publishAsyncCatalogImportChangeEventToRedis(accountIdentifier, CREATE_ACTION);
    if (!producerResult) {
      log.error("Error in producing event for async catalog import.");
    }

    return new ImportEntitiesResponse().status(SUCCESS_RESPONSE_STRING);
  }

  @Override
  public ImportEntitiesResponse manualImportEntity(
      String harnessAccount, ManualImportEntityRequest manualImportEntityRequest) {
    CatalogConnectorEntity catalogConnectorEntity = getCatalogConnector(harnessAccount);
    CatalogConnectorInfo catalogConnectorInfo = CatalogConnectorMapper.toDTO(catalogConnectorEntity);

    String tmpPathForCatalogInfoYamlStore =
        onboardingModuleConfig.getTmpPathForCatalogInfoYamlStore() + SLASH_DELIMITER + harnessAccount;
    String entitiesFolderPath = !catalogConnectorInfo.getPath().isEmpty()
        ? catalogConnectorInfo.getPath()
        : onboardingModuleConfig.getCatalogInfoLocationDefaultPath();
    String catalogInfoLocationParentPath = tmpPathForCatalogInfoYamlStore + entitiesFolderPath + SLASH_DELIMITER;
    String catalogInfoLocationFilePath =
        catalogInfoLocationParentPath + manualImportEntityRequest.getEntityName() + YAML_FILE_EXTENSION;
    String entityTargetParentPath = catalogConnectorInfo.getRepo() + SLASH_DELIMITER + SOURCE_FORMAT + SLASH_DELIMITER
        + catalogConnectorInfo.getBranch() + SLASH_DELIMITER + harnessAccount + entitiesFolderPath + SLASH_DELIMITER;
    String entityTargetFilePath =
        entityTargetParentPath + manualImportEntityRequest.getEntityName() + YAML_FILE_EXTENSION;

    createDirectories(catalogInfoLocationParentPath);
    log.info("Initialized directory to write yaml files for IDP manual entity import");

    writeObjectAsYamlInFile(GsonUtils.convertJsonStringToObject(manualImportEntityRequest.getYaml(), Object.class),
        catalogInfoLocationFilePath);

    ConnectorProcessor connectorProcessor = connectorProcessorFactory.getConnectorProcessor(
        ConnectorType.fromString(catalogConnectorEntity.getConnectorProviderType()));
    connectorProcessor.performPushOperation(harnessAccount, catalogConnectorInfo,
        onboardingModuleConfig.getTmpPathForCatalogInfoYamlStore(),
        Collections.singletonList(catalogInfoLocationFilePath),
        onboardingModuleConfig.isUseGitServiceGrpcForSingleEntityPush());

    registerLocationInBackstage(
        harnessAccount, BACKSTAGE_LOCATION_URL_TYPE, Collections.singletonList(entityTargetFilePath));

    log.info(
        "Finished operation of yaml generation, pushing to source, registering in backstage for manual import entity");

    return new ImportEntitiesResponse().status(SUCCESS_RESPONSE_STRING);
  }

  public void asyncCatalogImport(EntityChangeDTO entityChangeDTO) {
    log.info("Starting async operations for remaining entities import");

    try {
      String accountIdentifier = entityChangeDTO.getAccountIdentifier().getValue();

      AsyncCatalogImportEntity asyncCatalogImportEntity =
          asyncCatalogImportRepository.findByAccountIdentifier(accountIdentifier);

      AsyncCatalogImportDetails catalogDomains = asyncCatalogImportEntity.getCatalogDomains();
      AsyncCatalogImportDetails catalogSystems = asyncCatalogImportEntity.getCatalogSystems();
      AsyncCatalogImportDetails catalogComponents = asyncCatalogImportEntity.getCatalogComponents();
      CatalogConnectorInfo catalogConnectorInfo = asyncCatalogImportEntity.getCatalogConnectorInfo();
      SourcePrincipalContextBuilder.setSourcePrincipal(asyncCatalogImportEntity.getUserPrincipal());

      String orgYamlPath = catalogDomains.getYamlPath();
      String projectYamlPath = catalogSystems.getYamlPath();
      String serviceYamlPath = catalogComponents.getYamlPath();

      createDirectories(orgYamlPath, projectYamlPath, serviceYamlPath);

      List<String> filesToPush = new ArrayList<>();
      List<String> targets;
      List<String> locationTargets = new ArrayList<>();

      filesToPush.addAll(writeEntityAsYamlInFile(catalogDomains.getEntities(), orgYamlPath));
      targets = prepareEntitiesTarget(catalogDomains.getEntities(), catalogDomains.getEntityTargetParentPath());
      locationTargets.addAll(targets);

      filesToPush.addAll(writeEntityAsYamlInFile(catalogSystems.getEntities(), projectYamlPath));
      targets = prepareEntitiesTarget(catalogSystems.getEntities(), catalogSystems.getEntityTargetParentPath());
      locationTargets.addAll(targets);

      filesToPush.addAll(writeEntityAsYamlInFile(catalogComponents.getEntities(), serviceYamlPath));
      targets = prepareEntitiesTarget(catalogComponents.getEntities(), catalogComponents.getEntityTargetParentPath());
      locationTargets.addAll(targets);

      ConnectorProcessor connectorProcessor = connectorProcessorFactory.getConnectorProcessor(
          ConnectorType.fromString(String.valueOf(catalogConnectorInfo.getConnector().getType())));
      connectorProcessor.performPushOperation(accountIdentifier, catalogConnectorInfo,
          onboardingModuleConfig.getTmpPathForCatalogInfoYamlStore(), filesToPush, false);

      registerLocationInBackstage(accountIdentifier, BACKSTAGE_LOCATION_URL_TYPE, locationTargets);
      onboardingModuleConfig.getSampleEntities().forEach(sampleEntity
          -> registerLocationInBackstage(
              accountIdentifier, BACKSTAGE_LOCATION_URL_TYPE, Collections.singletonList(sampleEntity)));

      createCatalogInfraConnectorInBackstageK8S(accountIdentifier, catalogConnectorInfo,
          asyncCatalogImportEntity.getCatalogInfraConnectorType(),
          connectorProcessor.getConnectorInfo(accountIdentifier, catalogConnectorInfo.getConnector().getIdentifier()));

      log.info("Cleaning up directories created during IDP async onboarding");
      cleanUpDirectories(orgYamlPath, serviceYamlPath, serviceYamlPath);

      log.info("Finished async operation of yaml generation, pushing to source, registering in backstage, "
          + "creating connector secret in K8S for all entities");
    } catch (Exception ex) {
      log.error(
          "Error in asyncCatalogImport for entityChangeDTO = {} with error = {}", entityChangeDTO, ex.getMessage(), ex);
    }
  }

  private long getOrganizationsTotalCount(String accountIdentifier) {
    PageResponse<OrganizationResponse> organizations =
        getResponse(organizationClient.listOrganization(accountIdentifier, null, null, 0, 1, null));
    return organizations.getTotalItems();
  }

  private long getProjectsTotalCount(String accountIdentifier) {
    PageResponse<ProjectResponse> projects =
        getResponse(projectClient.listProject(accountIdentifier, null, false, null, null, 0, 1, null));
    return projects.getTotalItems();
  }

  private long getServicesTotalCount(String accountIdentifier) {
    PageResponse<ServiceResponse> services =
        getResponse(serviceResourceClient.getAllServicesList(accountIdentifier, null, null, null, 0, 1, null));
    return services.getTotalItems();
  }

  private List<OrganizationDTO> getOrganizations(String accountIdentifier, String searchTerm) {
    PageResponse<OrganizationResponse> organizationResponse =
        getResponse(organizationClient.listAllOrganizations(accountIdentifier, new ArrayList<>(), searchTerm));
    return organizationResponse.getContent()
        .stream()
        .map(OrganizationResponse::getOrganization)
        .collect(Collectors.toList());
  }

  private List<ProjectDTO> getProjects(String accountIdentifier, String searchTerm) {
    return getResponse(projectClient.getProjectList(accountIdentifier, searchTerm));
  }

  private List<ServiceResponseDTO> getServices(String accountIdentifier, String searchTerm) {
    List<ServiceResponseDTO> serviceResponseDTOS = new ArrayList<>();
    PageResponse<ServiceResponse> services;
    int page = 0;
    do {
      services = getResponse(serviceResourceClient.getAllServicesList(
          accountIdentifier, null, null, searchTerm, page, PAGE_LIMIT_FOR_ENTITY_FETCH, null));
      if (services != null && isNotEmpty(services.getContent())) {
        serviceResponseDTOS.addAll(
            services.getContent().stream().map(ServiceResponse::getService).collect(Collectors.toList()));
      }
      page++;
    } while (services != null && isNotEmpty(services.getContent()));
    return serviceResponseDTOS;
  }

  private final BiFunction<List<ServiceResponseDTO>, String, List<ServiceResponseDTO>> filterByProject =
      (services, projectToFilter) -> {
    if (!isEmpty(projectToFilter)) {
      return services.stream()
          .filter(service
              -> service.getProjectIdentifier() != null && service.getProjectIdentifier().contains(projectToFilter))
          .collect(Collectors.toList());
    }
    return services;
  };

  private List<BackstageCatalogDomainEntity> harnessOrgToBackstageDomain(List<OrganizationDTO> organizationDTOList) {
    HarnessOrgToBackstageDomain harnessOrgToBackstageDomainMapper =
        (HarnessOrgToBackstageDomain) getMapperByType(ORGANIZATION);
    harnessOrgToBackstageDomainMapper.entityNamesSeenSoFar.clear();
    return organizationDTOList.stream().map(harnessOrgToBackstageDomainMapper::map).collect(Collectors.toList());
  }

  private List<BackstageCatalogSystemEntity> harnessProjectToBackstageSystem(List<ProjectDTO> projectDTOList) {
    HarnessProjectToBackstageSystem harnessProjectToBackstageSystemMapper =
        (HarnessProjectToBackstageSystem) getMapperByType(PROJECT);
    harnessProjectToBackstageSystemMapper.entityNamesSeenSoFar.clear();
    return projectDTOList.stream().map(harnessProjectToBackstageSystemMapper::map).collect(Collectors.toList());
  }

  private List<BackstageCatalogComponentEntity> harnessServiceToBackstageComponent(
      List<ServiceResponseDTO> serviceResponseDTOList) {
    HarnessServiceToBackstageComponent harnessServiceToBackstageComponentMapper =
        (HarnessServiceToBackstageComponent) getMapperByType(SERVICE);
    harnessServiceToBackstageComponentMapper.entityNamesSeenSoFar.clear();
    return serviceResponseDTOList.stream()
        .map(harnessServiceToBackstageComponentMapper::map)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  private HarnessEntityToBackstageEntity<?, ? extends BackstageCatalogEntity> getMapperByType(String type) {
    switch (type) {
      case ORGANIZATION:
        return harnessOrgToBackstageDomain;
      case PROJECT:
        return harnessProjectToBackstageSystem;
      case SERVICE:
        return harnessServiceToBackstageComponent;
      default:
        throw new UnsupportedOperationException(type + " type not supported for harness to backstage entity mapping");
    }
  }

  private ImportEntitiesResponse importSampleEntity(String accountIdentifier, CatalogConnectorInfo catalogConnectorInfo)
      throws ExecutionException {
    catalogConnectorInfo.getConnector().setIdentifier(
        GitIntegrationUtils.replaceAccountScopeFromConnectorId(catalogConnectorInfo.getConnector().getIdentifier()));

    ConnectorProcessor connectorProcessor = connectorProcessorFactory.getConnectorProcessor(
        ConnectorType.fromString(catalogConnectorInfo.getConnector().getType().toString()));
    log.info("IDP onboarding import - connector processor initialized for type = {}",
        catalogConnectorInfo.getConnector().getType());

    String catalogInfraConnectorType = connectorProcessor.getInfraConnectorType(
        connectorProcessor.getConnectorInfo(accountIdentifier, catalogConnectorInfo.getConnector().getIdentifier()));
    ConnectorInfoDTO connectorInfoDTO =
        connectorProcessor.getConnectorInfo(accountIdentifier, catalogConnectorInfo.getConnector().getIdentifier());
    saveCatalogConnector(accountIdentifier, catalogConnectorInfo, catalogInfraConnectorType, connectorInfoDTO);
    createCatalogInfraConnectorInBackstageK8S(
        accountIdentifier, catalogConnectorInfo, catalogInfraConnectorType, connectorInfoDTO);

    String tmpPathForCatalogInfoYamlStore =
        onboardingModuleConfig.getTmpPathForCatalogInfoYamlStore() + SLASH_DELIMITER + accountIdentifier;
    String entitiesFolderPath = getEntitiesFolderPath(catalogConnectorInfo);
    String catalogInfoLocationParentPath = tmpPathForCatalogInfoYamlStore + entitiesFolderPath + SLASH_DELIMITER;
    String sampleYamlPath = catalogInfoLocationParentPath + SAMPLE_ENTITY_NAME + SLASH_DELIMITER;

    createDirectories(sampleYamlPath);
    log.info("Initialized directories to write yaml files for IDP onboarding import");

    List<String> sampleEntityFileToPush = new ArrayList<>();
    String sampleEntityYamlFilePath = sampleYamlPath + SAMPLE_ENTITY_NAME.toLowerCase() + YAML_FILE_EXTENSION;
    writeStringInFile(readFileFromClassPath(SAMPLE_ENTITY_CLASSPATH_LOCATION), sampleEntityYamlFilePath);
    sampleEntityFileToPush.add(sampleEntityYamlFilePath);

    connectorProcessor.performPushOperation(accountIdentifier, catalogConnectorInfo,
        onboardingModuleConfig.getTmpPathForCatalogInfoYamlStore(), sampleEntityFileToPush,
        onboardingModuleConfig.isUseGitServiceGrpcForSingleEntityPush());

    List<String> locationTargets = new ArrayList<>();
    locationTargets.add(catalogConnectorInfo.getRepo() + SLASH_DELIMITER + SOURCE_FORMAT + SLASH_DELIMITER
        + catalogConnectorInfo.getBranch() + SLASH_DELIMITER + accountIdentifier + entitiesFolderPath + SLASH_DELIMITER
        + SAMPLE_ENTITY_NAME + SLASH_DELIMITER + SAMPLE_ENTITY_NAME.toLowerCase() + YAML_FILE_EXTENSION);
    registerLocationInBackstage(accountIdentifier, BACKSTAGE_LOCATION_URL_TYPE, locationTargets);

    saveStatusInfo(accountIdentifier, StatusType.ONBOARDING.name(), StatusInfo.CurrentStatusEnum.COMPLETED,
        STATUS_UPDATE_REASON_FOR_ONBOARDING_COMPLETED);

    log.info("Finished operation of yaml generation, pushing to source, registering in backstage, "
        + "creating connector secret in K8S for all entities, saving connector & status info");

    log.info("Cleaning up directories created during IDP onboarding");
    cleanUpDirectories(sampleYamlPath);

    return new ImportEntitiesResponse().status(SUCCESS_RESPONSE_STRING);
  }

  private Triple<List<OrganizationDTO>, List<ProjectDTO>, List<ServiceResponseDTO>> getOrgProjectService(
      String accountIdentifier, ImportEntitiesBase importHarnessEntitiesRequest) {
    List<EntitiesForImport> idpSaveHarnessEntities = new ArrayList<>();
    if (importHarnessEntitiesRequest.getType().equals(ImportEntitiesBase.TypeEnum.INDIVIDUAL)) {
      idpSaveHarnessEntities = ((IndividualEntitiesImport) importHarnessEntitiesRequest).getEntities();
    }
    boolean allImport = importHarnessEntitiesRequest.getType().equals(ImportEntitiesBase.TypeEnum.ALL);

    List<String> orgToImport = getEntitiesByType(idpSaveHarnessEntities, ORGANIZATION);
    List<String> projectToImport = getEntitiesByType(idpSaveHarnessEntities, PROJECT);
    List<String> serviceToImport = getEntitiesByType(idpSaveHarnessEntities, SERVICE);
    log.info("Found {} organizations, {} projects, {} services for IDP onboarding import", orgToImport.size(),
        projectToImport.size(), serviceToImport.size());
    throwExceptionIfNothingToImport(orgToImport, projectToImport, serviceToImport, allImport);

    Map<String, List<String>> orgProjectsMapping = getOrgProjectsMapping(projectToImport);
    Map<String, Map<String, List<String>>> orgProjectsServicesMapping = getOrgProjectsServicesMapping(serviceToImport);

    List<OrganizationDTO> organizationDTOS;
    List<ProjectDTO> projectDTOS;
    List<ServiceResponseDTO> serviceDTOS;
    if (allImport) {
      organizationDTOS = getOrganizations(accountIdentifier, (String) null);
      projectDTOS = getProjects(accountIdentifier, null);
      serviceDTOS = getServices(accountIdentifier, (String) null);
    } else {
      organizationDTOS = getOrganizationDTOS(accountIdentifier, orgToImport);
      projectDTOS = getProjectDTOS(accountIdentifier, orgProjectsMapping);
      serviceDTOS = getServiceDTOS(accountIdentifier, orgProjectsServicesMapping);
    }
    log.info("Fetched {} organizations, {} projects, {} services for IDP onboarding import", organizationDTOS.size(),
        projectDTOS.size(), serviceDTOS.size());
    throwExceptionIfMismatchBetweenFoundAndProvided(
        orgToImport, projectToImport, serviceToImport, organizationDTOS, projectDTOS, serviceDTOS, allImport);

    return Triple.of(organizationDTOS, projectDTOS, serviceDTOS);
  }

  private List<String> getEntitiesByType(List<EntitiesForImport> idpSaveHarnessEntities, String type) {
    return idpSaveHarnessEntities.stream()
        .filter(entitiesForImport -> entitiesForImport.getEntityType().equals(type))
        .map(EntitiesForImport::getIdentifier)
        .collect(Collectors.toList());
  }

  private void throwExceptionIfNothingToImport(
      List<String> orgToImport, List<String> projectToImport, List<String> serviceToImport, boolean allImport) {
    if ((orgToImport.size() + projectToImport.size() + serviceToImport.size() == 0) && !allImport) {
      throw new InvalidRequestException(ENTITY_REQUIRED_ERROR_MESSAGE);
    }
  }

  private Map<String, List<String>> getOrgProjectsMapping(List<String> harnessEntitiesProjects) {
    Map<String, List<String>> projectIdentifiers = new HashMap<>();
    harnessEntitiesProjects.forEach(project -> {
      String[] orgProject = project.split("\\|");
      if (projectIdentifiers.containsKey(orgProject[0])) {
        List<String> existingProjects = projectIdentifiers.get(orgProject[0]);
        existingProjects.add(orgProject[1]);
        projectIdentifiers.put(orgProject[0], existingProjects);
      } else {
        projectIdentifiers.put(orgProject[0], Collections.singletonList(orgProject[1]));
      }
    });
    return projectIdentifiers;
  }

  private Map<String, Map<String, List<String>>> getOrgProjectsServicesMapping(List<String> harnessEntitiesServices) {
    Map<String, Map<String, List<String>>> serviceIdentifiers = new HashMap<>();
    harnessEntitiesServices.forEach(service -> {
      String[] orgProjectService = service.split("\\|");
      if (serviceIdentifiers.containsKey(orgProjectService[0])) {
        Map<String, List<String>> existingProjectsServices =
            new HashMap<>(serviceIdentifiers.get(orgProjectService[0]));
        if (existingProjectsServices.containsKey(orgProjectService[1])) {
          List<String> existingServices = new ArrayList<>(existingProjectsServices.get(orgProjectService[1]));
          existingServices.add(orgProjectService[2]);
          existingProjectsServices.put(orgProjectService[1], existingServices);
          serviceIdentifiers.put(orgProjectService[0], existingProjectsServices);
        } else {
          existingProjectsServices.put(orgProjectService[1], Collections.singletonList(orgProjectService[2]));
          serviceIdentifiers.put(orgProjectService[0], existingProjectsServices);
        }
      } else {
        serviceIdentifiers.put(
            orgProjectService[0], Map.of(orgProjectService[1], Collections.singletonList(orgProjectService[2])));
      }
    });
    return serviceIdentifiers;
  }

  private List<OrganizationDTO> getOrganizationDTOS(String accountIdentifier, List<String> orgToImport) {
    return !orgToImport.isEmpty() ? getOrganizations(accountIdentifier, orgToImport) : new ArrayList<>();
  }

  private List<ProjectDTO> getProjectDTOS(String accountIdentifier, Map<String, List<String>> orgProjectsMapping) {
    return orgProjectsMapping.size() > 0 ? getProjects(accountIdentifier, orgProjectsMapping.keySet(),
               orgProjectsMapping.values().stream().flatMap(Collection::stream).collect(Collectors.toList()))
                                         : new ArrayList<>();
  }

  private List<ServiceResponseDTO> getServiceDTOS(
      String accountIdentifier, Map<String, Map<String, List<String>>> orgProjectsServicesMapping) {
    return orgProjectsServicesMapping.size() > 0 ? getServices(accountIdentifier, orgProjectsServicesMapping)
                                                 : new ArrayList<>();
  }

  private void throwExceptionIfMismatchBetweenFoundAndProvided(List<String> orgToImport, List<String> projectToImport,
      List<String> serviceToImport, List<OrganizationDTO> organizationDTOS, List<ProjectDTO> projectDTOS,
      List<ServiceResponseDTO> serviceResponseDTOS, boolean allImport) {
    if ((organizationDTOS.size() != orgToImport.size() || projectDTOS.size() != projectToImport.size()
            || serviceResponseDTOS.size() != serviceToImport.size())
        && !allImport) {
      throw new UnexpectedException("Mismatch between provided and found harness entities for IDP import");
    }
  }

  private List<OrganizationDTO> getOrganizations(String accountIdentifier, List<String> identifiers) {
    List<OrganizationDTO> organizationDTOS = new ArrayList<>();
    PageResponse<OrganizationResponse> organizations;
    int page = 0;
    do {
      organizations = getResponse(organizationClient.listOrganization(
          accountIdentifier, identifiers, null, page, PAGE_LIMIT_FOR_ENTITY_FETCH, null));
      if (organizations != null && isNotEmpty(organizations.getContent())) {
        organizationDTOS.addAll(organizations.getContent()
                                    .stream()
                                    .map(OrganizationResponse::getOrganization)
                                    .collect(Collectors.toList()));
      }
      page++;
    } while (organizations != null && isNotEmpty(organizations.getContent()));
    return organizationDTOS;
  }

  private List<ProjectDTO> getProjects(
      String accountIdentifier, Set<String> organizationIdentifiers, List<String> identifiers) {
    List<ProjectDTO> projectDTOS = new ArrayList<>();
    PageResponse<ProjectResponse> projects;
    int page = 0;
    do {
      projects = getResponse(projectClient.listWithMultiOrg(accountIdentifier, organizationIdentifiers, false,
          identifiers, null, null, page, PAGE_LIMIT_FOR_ENTITY_FETCH, null));
      if (projects != null && isNotEmpty(projects.getContent())) {
        projectDTOS.addAll(
            projects.getContent().stream().map(ProjectResponse::getProject).collect(Collectors.toList()));
      }
      page++;
    } while (projects != null && isNotEmpty(projects.getContent()));
    return projectDTOS;
  }

  private List<ServiceResponseDTO> getServices(
      String accountIdentifier, Map<String, Map<String, List<String>>> orgProjectsServicesMapping) {
    List<ServiceResponseDTO> serviceResponseDTOS = new ArrayList<>();
    for (var serviceIdentifier : orgProjectsServicesMapping.entrySet()) {
      String org = serviceIdentifier.getKey();
      for (var projectService : serviceIdentifier.getValue().entrySet()) {
        PageResponse<ServiceResponse> services;
        int page = 0;
        do {
          services = getResponse(serviceResourceClient.listServicesForProject(page, PAGE_LIMIT_FOR_ENTITY_FETCH,
              accountIdentifier, org, projectService.getKey(), projectService.getValue(), null));
          if (services != null && isNotEmpty(services.getContent())) {
            serviceResponseDTOS.addAll(
                services.getContent().stream().map(ServiceResponse::getService).collect(Collectors.toList()));
          }
          page++;
        } while (services != null && isNotEmpty(services.getContent()));
      }
    }
    return serviceResponseDTOS;
  }

  private void saveCatalogConnector(String accountIdentifier, CatalogConnectorInfo catalogConnectorInfo,
      String catalogInfraConnectorType, ConnectorInfoDTO connectorInfoDTO) throws ExecutionException {
    Set<String> delegateSelectors = GitIntegrationUtils.extractDelegateSelectors(connectorInfoDTO);
    String host = GitIntegrationUtils.getHostForConnector(connectorInfoDTO);
    CatalogConnectorEntity catalogConnectorEntity = new CatalogConnectorEntity();

    catalogConnectorEntity.setAccountIdentifier(accountIdentifier);
    catalogConnectorEntity.setIdentifier(Constants.IDP_PREFIX + catalogConnectorInfo.getConnector().getIdentifier());
    catalogConnectorEntity.setType(CatalogInfraConnectorType.valueOf(catalogInfraConnectorType));
    catalogConnectorEntity.setConnectorIdentifier(catalogConnectorInfo.getConnector().getIdentifier());
    catalogConnectorEntity.setConnectorProviderType(String.valueOf(catalogConnectorInfo.getConnector().getType()));
    catalogConnectorEntity.setCatalogRepositoryDetails(new CatalogRepositoryDetails(
        catalogConnectorInfo.getRepo(), catalogConnectorInfo.getBranch(), catalogConnectorInfo.getPath()));
    catalogConnectorEntity.setHost(host);
    catalogConnectorEntity.setDelegateSelectors(delegateSelectors);

    catalogConnectorRepository.save(catalogConnectorEntity);
    delegateSelectorsCache.put(accountIdentifier, host, delegateSelectors);
    log.info("Saved catalogConnector to DB. Account = {}", accountIdentifier);
  }

  private String getEntitiesFolderPath(CatalogConnectorInfo catalogConnectorInfo) {
    String entitiesFolderPath = !catalogConnectorInfo.getPath().isEmpty()
        ? catalogConnectorInfo.getPath()
        : onboardingModuleConfig.getCatalogInfoLocationDefaultPath();
    entitiesFolderPath =
        !entitiesFolderPath.startsWith(SLASH_DELIMITER) ? (SLASH_DELIMITER + entitiesFolderPath) : entitiesFolderPath;
    return entitiesFolderPath;
  }

  private Pair<BackstageCatalogEntity, Pair<String, String>> getFirstAmongAll(String catalogInfoLocationParentPath,
      String entityTargetParentPath, List<? extends BackstageCatalogEntity>... backstageCatalogEntities) {
    for (List<? extends BackstageCatalogEntity> backstageCatalogEntity : backstageCatalogEntities) {
      for (BackstageCatalogEntity catalogEntity : backstageCatalogEntity) {
        if (catalogEntity instanceof BackstageCatalogDomainEntity) {
          return new Pair<>(catalogEntity,
              new Pair<>(catalogInfoLocationParentPath + ORGANIZATION + SLASH_DELIMITER,
                  entityTargetParentPath + ORGANIZATION + SLASH_DELIMITER));
        } else if (catalogEntity instanceof BackstageCatalogSystemEntity) {
          return new Pair<>(catalogEntity,
              new Pair<>(catalogInfoLocationParentPath + PROJECT + SLASH_DELIMITER,
                  entityTargetParentPath + PROJECT + SLASH_DELIMITER));
        } else if (catalogEntity instanceof BackstageCatalogComponentEntity) {
          return new Pair<>(catalogEntity,
              new Pair<>(catalogInfoLocationParentPath + SERVICE + SLASH_DELIMITER,
                  entityTargetParentPath + SERVICE + SLASH_DELIMITER));
        } else {
          throw new UnexpectedException("Entity should be off one among domain / system / component");
        }
      }
    }
    throw new UnexpectedException("Found invalid entity to import in IDP onboarding flow");
  }

  private List<String> writeEntityAsYamlInFile(List<? extends BackstageCatalogEntity> entities, String prefixPath) {
    List<String> files = new ArrayList<>();
    entities.forEach(entity -> {
      writeObjectAsYamlInFile(entity, prefixPath + entity.getMetadata().getName() + YAML_FILE_EXTENSION);
      files.add(prefixPath + entity.getMetadata().getName() + YAML_FILE_EXTENSION);
    });
    return files;
  }

  private List<String> prepareEntitiesTarget(List<? extends BackstageCatalogEntity> entities, String prefixPath) {
    List<String> targets = new ArrayList<>();
    entities.forEach(entity -> targets.add(prefixPath + entity.getMetadata().getName() + YAML_FILE_EXTENSION));
    return targets;
  }

  private void registerLocationInBackstage(String accountIdentifier, String type, List<String> targets) {
    for (String target : targets) {
      try {
        getGeneralResponse(backstageResourceClient.createCatalogLocation(
            accountIdentifier, new BackstageCatalogLocationCreateRequest(type, target)));
      } catch (Exception e) {
        log.error("Unable to register target of type = {} with location = {} in backstage, ex = {}", type, target,
            e.getMessage(), e);
      }
    }
  }

  private void createCatalogInfraConnectorInBackstageK8S(String accountIdentifier,
      CatalogConnectorInfo catalogConnectorInfo, String catalogInfraConnectorType, ConnectorInfoDTO connectorInfoDTO) {
    try {
      gitIntegrationService.createOrUpdateConnectorInBackstage(accountIdentifier, connectorInfoDTO,
          CatalogInfraConnectorType.valueOf(catalogInfraConnectorType),
          catalogConnectorInfo.getConnector().getIdentifier());
    } catch (Exception e) {
      log.error("Unable to create infra connector secrets in backstage k8s, ex = {}", e.getMessage(), e);
    }
  }

  private void saveStatusInfo(
      String accountIdentifier, String type, StatusInfo.CurrentStatusEnum currentStatus, String reason) {
    StatusInfo statusInfo = new StatusInfo();
    statusInfo.setCurrentStatus(currentStatus);
    statusInfo.setReason(reason);
    statusInfoService.save(statusInfo, accountIdentifier, type);
  }

  private CatalogConnectorEntity getCatalogConnector(String accountIdentifier) {
    Optional<CatalogConnectorEntity> catalogConnector =
        catalogConnectorRepository.findByAccountIdentifier(accountIdentifier);
    if (catalogConnector.isEmpty()) {
      throw new InvalidRequestException(
          String.format("Catalog connector not found for accountIdentifier: [%s]]", accountIdentifier));
    }
    return catalogConnector.get();
  }
}
