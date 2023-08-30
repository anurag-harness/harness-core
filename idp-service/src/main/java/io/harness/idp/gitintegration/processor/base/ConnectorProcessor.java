/*
 * Copyright 2023 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.idp.gitintegration.processor.base;

import static io.harness.idp.gitintegration.utils.GitIntegrationConstants.ACCOUNT_SCOPED;
import static io.harness.idp.gitintegration.utils.GitIntegrationConstants.HARNESS_ENTITIES_IMPORT_COMMIT_MESSAGE;

import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;
import io.harness.beans.Scope;
import io.harness.connector.ConnectorDTO;
import io.harness.connector.ConnectorInfoDTO;
import io.harness.connector.ConnectorResourceClient;
import io.harness.exception.InvalidRequestException;
import io.harness.exception.UnexpectedException;
import io.harness.git.GitClientV2Impl;
import io.harness.git.UsernamePasswordAuthRequest;
import io.harness.git.model.ChangeType;
import io.harness.git.model.CommitAndPushRequest;
import io.harness.git.model.GitBaseRequest;
import io.harness.git.model.GitFileChange;
import io.harness.git.model.GitRepositoryType;
import io.harness.gitsync.CreateFileRequest;
import io.harness.gitsync.CreateFileResponse;
import io.harness.gitsync.HarnessToGitPushInfoServiceGrpc;
import io.harness.gitsync.common.beans.GitOperation;
import io.harness.gitsync.common.helper.GitSyncGrpcClientUtils;
import io.harness.gitsync.common.helper.GitSyncLogContextHelper;
import io.harness.gitsync.common.helper.ScopeIdentifierMapper;
import io.harness.gitsync.common.helper.UserPrincipalMapper;
import io.harness.remote.client.NGRestUtils;
import io.harness.secretmanagerclient.services.api.SecretManagerClientService;
import io.harness.security.Principal;
import io.harness.security.SourcePrincipalContextBuilder;
import io.harness.security.dto.UserPrincipal;
import io.harness.spec.server.idp.v1.model.BackstageEnvVariable;
import io.harness.spec.server.idp.v1.model.CatalogConnectorInfo;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Ref;

@Slf4j
@OwnedBy(HarnessTeam.IDP)
public abstract class ConnectorProcessor {
  @Inject public ConnectorResourceClient connectorResourceClient;
  @Inject @Named("PRIVILEGED") public SecretManagerClientService ngSecretService;
  @Inject public GitClientV2Impl gitClientV2;
  @Inject public HarnessToGitPushInfoServiceGrpc.HarnessToGitPushInfoServiceBlockingStub harnessToGitPushInfoService;

  public abstract String getInfraConnectorType(ConnectorInfoDTO connectorInfoDTO);

  public ConnectorInfoDTO getConnectorInfo(String accountIdentifier, String connectorIdentifier) {
    Optional<ConnectorDTO> connectorDTO =
        NGRestUtils.getResponse(connectorResourceClient.get(connectorIdentifier, accountIdentifier, null, null));
    if (connectorDTO.isEmpty()) {
      throw new InvalidRequestException(String.format(
          "Connector not found for identifier: [%s], accountIdentifier: [%s]", connectorIdentifier, accountIdentifier));
    }
    return connectorDTO.get().getConnectorInfo();
  }

  public abstract Map<String, BackstageEnvVariable> getConnectorAndSecretsInfo(
      String accountIdentifier, ConnectorInfoDTO connectorInfoDTO);

  public abstract void performPushOperation(String accountIdentifier, CatalogConnectorInfo catalogConnectorInfo,
      String locationParentPath, List<String> filesToPush, boolean throughGrpc);

  protected void performPushOperationInternal(String accountIdentifier, CatalogConnectorInfo catalogConnectorInfo,
      String locationParentPath, List<String> filesToPush, String username, String password, boolean throughGrpc) {
    UserPrincipal userPrincipalFromContext = (UserPrincipal) SourcePrincipalContextBuilder.getSourcePrincipal();
    if (throughGrpc) {
      performPushGitServiceGrpc(accountIdentifier, catalogConnectorInfo, locationParentPath, filesToPush, username,
          password, userPrincipalFromContext);
    } else {
      performPushJGit(accountIdentifier, catalogConnectorInfo, locationParentPath, filesToPush, username, password,
          userPrincipalFromContext);
    }
  }

  private void performPushGitServiceGrpc(String accountIdentifier, CatalogConnectorInfo catalogConnectorInfo,
      String locationParentPath, List<String> filesToPush, String username, String password,
      UserPrincipal userPrincipalFromContext) {
    GitBaseRequest gitBaseRequest =
        GitBaseRequest.builder()
            .repoUrl(catalogConnectorInfo.getRepo())
            .authRequest(
                UsernamePasswordAuthRequest.builder().username(username).password(password.toCharArray()).build())
            .connectorId(catalogConnectorInfo.getConnector().getIdentifier())
            .accountId(accountIdentifier)
            .build();
    Map<String, Ref> remoteList = gitClientV2.listRemote(gitBaseRequest);
    boolean commitToNewBranch;
    String baseBranchName;
    if (remoteList.containsKey("refs/heads/" + catalogConnectorInfo.getBranch())) {
      commitToNewBranch = false;
      baseBranchName = catalogConnectorInfo.getBranch();
    } else {
      commitToNewBranch = true;
      baseBranchName = remoteList.get("HEAD").getTarget().getName();
    }

    Scope scope = Scope.of(accountIdentifier, null, null);
    String repoName =
        Objects.equals(catalogConnectorInfo.getRepo().substring(catalogConnectorInfo.getRepo().length() - 1), "/")
        ? catalogConnectorInfo.getRepo()
              .substring(0, catalogConnectorInfo.getRepo().length() - 1)
              .substring(catalogConnectorInfo.getRepo().lastIndexOf('/') + 1)
        : catalogConnectorInfo.getRepo().substring(catalogConnectorInfo.getRepo().lastIndexOf('/') + 1);

    try {
      for (String fileToPush : filesToPush) {
        String filePathInRepo = fileToPush.replace(locationParentPath + "/", "");
        Map<String, String> contextMap = new HashMap<>();
        contextMap = GitSyncLogContextHelper.setContextMap(scope, repoName, catalogConnectorInfo.getBranch(), "",
            filePathInRepo, GitOperation.CREATE_FILE, contextMap);
        final CreateFileRequest createFileRequest =
            CreateFileRequest.newBuilder()
                .setRepoName(repoName)
                .setBranchName(catalogConnectorInfo.getBranch())
                .setFilePath(filePathInRepo)
                .setConnectorRef(ACCOUNT_SCOPED + catalogConnectorInfo.getConnector().getIdentifier())
                .setFileContent(Files.readString(Path.of(fileToPush)))
                .setIsCommitToNewBranch(commitToNewBranch)
                .setBaseBranchName(baseBranchName)
                .setCommitMessage(HARNESS_ENTITIES_IMPORT_COMMIT_MESSAGE)
                .setScopeIdentifiers(ScopeIdentifierMapper.getScopeIdentifiersFromScope(scope))
                .putAllContextMap(contextMap)
                .setPrincipal(Principal.newBuilder()
                                  .setUserPrincipal(UserPrincipalMapper.toProto(userPrincipalFromContext))
                                  .build())
                .build();
        final CreateFileResponse createFileResponse =
            GitSyncGrpcClientUtils.retryAndProcessException(harnessToGitPushInfoService::createFile, createFileRequest);
        if (createFileResponse.getStatusCode() >= 300) {
          log.error(
              "Error response from git sync grpc while pushing files to source in IDP catalog onboarding flow = {}",
              createFileResponse);
          throw new UnexpectedException("Error response while pushing files to source in IDP catalog onboarding flow");
        }
      }
    } catch (Exception ex) {
      log.error("Exception while pushing files to source in IDP catalog onboarding flow, ex = {}", ex.getMessage(), ex);
      throw new UnexpectedException("Error response while pushing files to source in IDP catalog onboarding flow");
    }
  }

  private void performPushJGit(String accountIdentifier, CatalogConnectorInfo catalogConnectorInfo,
      String locationParentPath, List<String> filesToPush, String username, String password,
      UserPrincipal userPrincipalFromContext) {
    List<GitFileChange> gitFileChanges = new ArrayList<>();
    filesToPush.forEach((String fileToPush) -> {
      GitFileChange gitFileChange;
      try {
        gitFileChange = GitFileChange.builder()
                            .filePath(fileToPush.replace(locationParentPath, ""))
                            .fileContent(Files.readString(Path.of(fileToPush)))
                            .changeType(ChangeType.ADD)
                            .accountId(accountIdentifier)
                            .build();
      } catch (IOException e) {
        log.error("Error while doing git add on files. Exception = {}", e.getMessage(), e);
        throw new UnexpectedException("Error in preparing git files for commit.");
      }
      gitFileChanges.add(gitFileChange);
    });
    log.info("Prepared git files for push");
    CommitAndPushRequest commitAndPushRequest =
        CommitAndPushRequest.builder()
            .repoUrl(catalogConnectorInfo.getRepo())
            .branch(catalogConnectorInfo.getBranch())
            .unsureOrNonExistentBranch(true)
            .cloneDepth(1)
            .connectorId(catalogConnectorInfo.getConnector().getIdentifier())
            .accountId(accountIdentifier)
            .authRequest(
                UsernamePasswordAuthRequest.builder().username(username).password(password.toCharArray()).build())
            .repoType(GitRepositoryType.YAML)
            .gitFileChanges(gitFileChanges)
            .authorName(username)
            .authorEmail(userPrincipalFromContext.getEmail())
            .commitMessage(HARNESS_ENTITIES_IMPORT_COMMIT_MESSAGE)
            .build();
    gitClientV2.commitAndPush(commitAndPushRequest);
    log.info("Git commit and push done for files");
  }
}
