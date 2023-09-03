/*
 * Copyright 2022 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.artifacts.githubpackages.service;

import static io.harness.annotations.dev.HarnessTeam.CDC;
import static io.harness.exception.WingsException.USER;

import io.harness.annotations.dev.OwnedBy;
import io.harness.artifact.ArtifactMetadataKeys;
import io.harness.artifacts.docker.DockerRegistryRestClient;
import io.harness.artifacts.docker.beans.DockerImageManifestResponse;
import io.harness.artifacts.docker.beans.DockerInternalConfig;
import io.harness.artifacts.docker.service.DockerRegistryServiceImpl;
import io.harness.artifacts.docker.service.DockerRegistryUtils;
import io.harness.artifacts.gar.service.GARUtils;
import io.harness.artifacts.githubpackages.beans.GithubPackagesInternalConfig;
import io.harness.artifacts.githubpackages.beans.GithubPackagesVersion;
import io.harness.artifacts.githubpackages.beans.GithubPackagesVersionsResponse;
import io.harness.artifacts.githubpackages.client.GithubPackagesRestClient;
import io.harness.artifacts.githubpackages.client.GithubPackagesRestClientFactory;
import io.harness.beans.ArtifactMetaInfo;
import io.harness.data.structure.EmptyPredicate;
import io.harness.exception.ArtifactServerException;
import io.harness.exception.ExceptionUtils;
import io.harness.exception.InvalidArtifactServerException;
import io.harness.exception.InvalidRequestException;
import io.harness.exception.NestedExceptionUtils;
import io.harness.exception.WingsException;
import io.harness.exception.runtime.GithubPackagesServerRuntimeException;

import software.wings.helpers.ext.jenkins.BuildDetails;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Headers;
import org.apache.commons.lang3.StringUtils;
import retrofit2.Response;

@OwnedBy(CDC)
@Singleton
@Slf4j
public class GithubPackagesRegistryServiceImpl implements GithubPackagesRegistryService {
  @Inject private GithubPackagesRestClientFactory githubPackagesRestClientFactory;
  @Inject private DockerRegistryUtils dockerRegistryUtils;
  @Inject private DockerRegistryServiceImpl dockerRegistryService;

  private int DEFAULT_GITHUB_LIST_RESPONSE_SIZE = 100;
  private static final String COULD_NOT_FETCH_IMAGE_MANIFEST = "Could not fetch image manifest";
  private static final String ERROR_MESSAGE =
      "Check if the package and the version exists and if the permissions are scoped for the authenticated user";
  private static final String COULD_NOT_FETCH_VERSION = "Could not fetch the version for the package";

  @Override
  public List<BuildDetails> getBuilds(GithubPackagesInternalConfig githubPackagesInternalConfig, String packageName,
      String packageType, String org, String versionRegex) {
    List<BuildDetails> buildDetails;

    if (!isPackageType(packageType)) {
      throw new InvalidRequestException("Incorrect Package Type");
    }

    try {
      buildDetails = getBuildDetails(githubPackagesInternalConfig, packageName, packageType, org);
    } catch (GithubPackagesServerRuntimeException ex) {
      throw ex;
    } catch (Exception e) {
      throw NestedExceptionUtils.hintWithExplanationException("Could not fetch versions for the package",
          "Check if the package exists and if the permissions are scoped for the authenticated user",
          new ArtifactServerException(ExceptionUtils.getMessage(e), e, USER));
    }

    Pattern pattern = Pattern.compile(versionRegex.replace(".", "\\.").replace("?", ".?").replace("*", ".*?"));

    buildDetails = buildDetails.stream()
                       .filter(build -> !build.getNumber().endsWith("/") && pattern.matcher(build.getNumber()).find())
                       .collect(Collectors.toList());

    return buildDetails;
  }

  @Override
  public BuildDetails getLastSuccessfulBuildFromRegex(GithubPackagesInternalConfig githubPackagesInternalConfig,
      String packageName, String packageType, String versionRegex, String org) {
    List<BuildDetails> buildDetails;

    if (!isPackageType(packageType)) {
      throw new InvalidRequestException("Incorrect Package Type");
    }

    try {
      buildDetails = getBuildDetails(githubPackagesInternalConfig, packageName, packageType, org);
    } catch (GithubPackagesServerRuntimeException ex) {
      throw ex;
    } catch (Exception e) {
      throw NestedExceptionUtils.hintWithExplanationException(
          COULD_NOT_FETCH_VERSION, ERROR_MESSAGE, new ArtifactServerException(ExceptionUtils.getMessage(e), e, USER));
    }

    buildDetails = regexFilteringForGetBuilds(versionRegex, buildDetails, packageName);

    if (buildDetails.isEmpty()) {
      throw new InvalidRequestException("No version with matching regex is present");
    }

    return getBuild(githubPackagesInternalConfig, packageName, packageType, buildDetails.get(0).getNumber(), org);
  }

  @Override
  public BuildDetails getBuild(GithubPackagesInternalConfig githubPackagesInternalConfig, String packageName,
      String packageType, String version, String org) {
    if (!isPackageType(packageType)) {
      throw new InvalidRequestException("Incorrect Package Type");
    }

    ArtifactMetaInfo artifactMetaInfo = ArtifactMetaInfo.builder().build();
    try {
      ArtifactMetaInfo artifactMetaInfoSchemaVersion1 =
          getArtifactMetaInfo(githubPackagesInternalConfig, packageName, version, org, true);
      if (artifactMetaInfoSchemaVersion1 != null) {
        artifactMetaInfo.setSha(artifactMetaInfoSchemaVersion1.getSha());
        artifactMetaInfo.setLabels(artifactMetaInfoSchemaVersion1.getLabels());
      }
    } catch (Exception e) {
      log.error(COULD_NOT_FETCH_IMAGE_MANIFEST, e);
    }

    try {
      ArtifactMetaInfo artifactMetaInfoSchemaVersion2 =
          getArtifactMetaInfo(githubPackagesInternalConfig, packageName, version, org, false);
      if (artifactMetaInfoSchemaVersion2 != null) {
        artifactMetaInfo.setShaV2(artifactMetaInfoSchemaVersion2.getSha());
      }
    } catch (Exception e) {
      log.error(COULD_NOT_FETCH_IMAGE_MANIFEST, e);
    }

    if (EmptyPredicate.isEmpty(artifactMetaInfo.getShaV2()) && EmptyPredicate.isEmpty(artifactMetaInfo.getSha())) {
      List<BuildDetails> builds = new ArrayList<>();
      try {
        builds = getBuildDetails(githubPackagesInternalConfig, packageName, packageType, org);
      } catch (GithubPackagesServerRuntimeException ex) {
        throw ex;
      } catch (Exception e) {
        throw NestedExceptionUtils.hintWithExplanationException(
            COULD_NOT_FETCH_VERSION, ERROR_MESSAGE, new ArtifactServerException(ExceptionUtils.getMessage(e), e, USER));
      }

      return versionFiltering(version, builds, packageName);
    }
    return constructBuildDetails(
        version, packageName, artifactMetaInfo, org, githubPackagesInternalConfig.getUsername());
  }

  @Override
  public List<Map<String, String>> listPackages(
      GithubPackagesInternalConfig githubPackagesInternalConfig, String packageType, String org) {
    List<Map<String, String>> map;

    if (!isPackageType(packageType)) {
      throw new InvalidRequestException("Incorrect Package Type");
    }

    try {
      map = getPackages(githubPackagesInternalConfig, packageType, org);
    } catch (GithubPackagesServerRuntimeException ex) {
      throw ex;
    } catch (Exception e) {
      throw NestedExceptionUtils.hintWithExplanationException("Could not fetch the packages",
          "Check if the permissions are scoped for the authenticated user",
          new ArtifactServerException(ExceptionUtils.getMessage(e), e, USER));
    }

    return map;
  }

  private List<Map<String, String>> getPackages(
      GithubPackagesInternalConfig githubPackagesInternalConfig, String packageType, String org) throws IOException {
    GithubPackagesRestClient githubPackagesRestClient =
        githubPackagesRestClientFactory.getGithubPackagesRestClient(githubPackagesInternalConfig);

    String authType = githubPackagesInternalConfig.getAuthMechanism();

    String basicAuthHeader = "token " + githubPackagesInternalConfig.getToken();

    Response<List<JsonNode>> response;

    if (EmptyPredicate.isEmpty(org)) {
      response = githubPackagesRestClient.listPackages(basicAuthHeader, packageType).execute();
    } else {
      response = githubPackagesRestClient.listPackagesForOrg(basicAuthHeader, org, packageType).execute();
    }

    return processPackagesResponse(response.body());
  }

  private List<Map<String, String>> processPackagesResponse(List<JsonNode> response) {
    List<Map<String, String>> packages = new ArrayList<>();

    if (EmptyPredicate.isEmpty(response)) {
      throw new InvalidRequestException("Empty response for the get packages call");
    }

    for (JsonNode node : response) {
      Map<String, String> resMap = new HashMap<>();

      resMap.put("packageId", node.get("id").asText());
      resMap.put("packageName", node.get("name").asText());
      resMap.put("packageType", node.get("package_type").asText());
      resMap.put("visibility", node.get("visibility").asText());
      resMap.put("packageUrl", node.get("html_url").asText());

      packages.add(resMap);
    }

    return packages;
  }

  private List<BuildDetails> regexFilteringForGetBuilds(
      String versionRegex, List<BuildDetails> builds, String packageName) {
    Pattern pattern = Pattern.compile(versionRegex.replace(".", "\\.").replace("?", ".?").replace("*", ".*?"));

    for (BuildDetails build : builds) {
      Map<String, String> map = build.getMetadata();

      List<String> keys = map.keySet().stream().collect(Collectors.toList());

      keys =
          keys.stream().filter(key -> !key.endsWith("/") && pattern.matcher(key).find()).collect(Collectors.toList());

      if (!keys.isEmpty()) {
        build.setNumber(keys.get(0));
        build.setBuildDisplayName(packageName + ": " + keys.get(0));
        build.setUiDisplayName("Tag# " + keys.get(0));

        Map<String, String> finalMap = new HashMap<>();

        for (String key : keys) {
          finalMap.put(key, map.get(key));
        }

        build.setMetadata(finalMap);
      } else {
        builds.remove(build);

        return regexFilteringForGetBuilds(versionRegex, builds, packageName);
      }
    }

    return builds;
  }

  private BuildDetails constructBuildDetails(
      String version, String packageName, ArtifactMetaInfo artifactMetaInfo, String org, String userName) {
    Map<String, String> metadata = new HashMap<>();
    metadata.put(ArtifactMetadataKeys.SHA, artifactMetaInfo.getSha());
    metadata.put(ArtifactMetadataKeys.SHAV2, artifactMetaInfo.getShaV2());
    return BuildDetails.Builder.aBuildDetails()
        .withBuildDisplayName(packageName + ": " + version)
        .withUiDisplayName("Tag# " + version)
        .withNumber(version)
        .withMetadata(metadata)
        .withLabels(artifactMetaInfo.getLabels())
        .withStatus(BuildDetails.BuildStatus.SUCCESS)
        .withBuildFullDisplayName(artifactMetaInfo.getShaV2())
        .withArtifactPath(getArtifactPath(org, packageName, version, userName.toLowerCase()))
        .build();
  }

  private BuildDetails versionFiltering(String version, List<BuildDetails> builds, String packageName) {
    for (BuildDetails build : builds) {
      Map<String, String> map = build.getMetadata();

      if (map.containsKey(version)) {
        build.setBuildDisplayName(packageName + ": " + version);
        build.setUiDisplayName("Tag# " + version);
        build.setNumber(version);

        return build;
      }
    }

    throw new InvalidRequestException("Could not find version " + version + " in the package " + packageName);
  }

  private String getArtifactPath(String org, String packageName, String tag, String username) {
    String artifactPath;
    if (GARUtils.isSHA(tag)) {
      artifactPath = "ghcr.io/%s/%s@%s";
    } else {
      artifactPath = "ghcr.io/%s/%s:%s";
    }
    if (EmptyPredicate.isEmpty(org)) {
      return String.format(artifactPath, username, packageName, tag);
    } else {
      return String.format(artifactPath, org, packageName, tag);
    }
  }

  private List<BuildDetails> getBuildDetails(GithubPackagesInternalConfig githubPackagesInternalConfig,
      String packageName, String packageType, String org) throws IOException {
    GithubPackagesRestClient githubPackagesRestClient =
        githubPackagesRestClientFactory.getGithubPackagesRestClient(githubPackagesInternalConfig);

    String authType = githubPackagesInternalConfig.getAuthMechanism();

    String basicAuthHeader = "token " + githubPackagesInternalConfig.getToken();

    GithubPackagesVersionsResponse githubPackagesVersionsResponse = GithubPackagesVersionsResponse.builder().build();

    List<JsonNode> response = new ArrayList<>();

    if (EmptyPredicate.isEmpty(org)) {
      for (int i = 1;; i++) {
        Response<List<JsonNode>> pageResponse = githubPackagesRestClient
                                                    .listVersionsForPackages(basicAuthHeader, packageName, packageType,
                                                        DEFAULT_GITHUB_LIST_RESPONSE_SIZE, i)
                                                    .execute();

        if (!isSuccessful(pageResponse)) {
          throw NestedExceptionUtils.hintWithExplanationException("Unable to fetch the versions for the package",
              "Check if the package exists and if the permissions are scoped for the authenticated user",
              new InvalidArtifactServerException(pageResponse.message(), USER));
        }

        if (EmptyPredicate.isNotEmpty(pageResponse.body())) {
          response.addAll(pageResponse.body());
          if (pageResponse.body().size() < DEFAULT_GITHUB_LIST_RESPONSE_SIZE) {
            break;
          }
        } else {
          break;
        }
      }
    } else {
      for (int i = 1;; i++) {
        Response<List<JsonNode>> pageResponse = githubPackagesRestClient
                                                    .listVersionsForPackagesInOrg(basicAuthHeader, org, packageName,
                                                        packageType, DEFAULT_GITHUB_LIST_RESPONSE_SIZE, i)
                                                    .execute();

        if (!isSuccessful(pageResponse)) {
          throw NestedExceptionUtils.hintWithExplanationException("Unable to fetch the versions for the package",
              "Check if the package exists and if the permissions are correct for the org",
              new InvalidArtifactServerException(pageResponse.message(), USER));
        }

        if (EmptyPredicate.isNotEmpty(pageResponse.body())) {
          response.addAll(pageResponse.body());
          if (pageResponse.body().size() < DEFAULT_GITHUB_LIST_RESPONSE_SIZE) {
            break;
          }
        } else {
          break;
        }
      }
    }

    githubPackagesVersionsResponse = processResponse(response);

    return processBuildDetails(
        githubPackagesVersionsResponse, packageName, githubPackagesInternalConfig.getUsername(), org);
  }

  private List<BuildDetails> processBuildDetails(
      GithubPackagesVersionsResponse githubPackagesVersionsResponse, String packageName, String username, String org) {
    List<GithubPackagesVersion> versions = githubPackagesVersionsResponse.getVersionDetails();

    List<BuildDetails> buildDetails = new ArrayList<>();

    for (GithubPackagesVersion v : versions) {
      BuildDetails build = new BuildDetails();

      List<String> tags = v.getTags();

      Map<String, String> metadata = new HashMap<>();

      for (String t : tags) {
        metadata.put(t, packageName);
      }

      String tag = null;
      if (tags.size() > 0) {
        tag = tags.get(0);
      }

      String artifactPath = "ghcr.io";

      if (tag != null) {
        if (EmptyPredicate.isEmpty(org)) {
          artifactPath += "/" + username + "/" + packageName + ":" + tag;
        } else {
          artifactPath += "/" + org + "/" + packageName + ":" + tag;
        }

        build.setBuildDisplayName(packageName + ": " + tag);
        build.setUiDisplayName("Tag# " + tag);
        build.setNumber(tag);
        build.setBuildUrl(v.getVersionHtmlUrl());
        build.setStatus(BuildDetails.BuildStatus.SUCCESS);
        build.setBuildFullDisplayName(v.getVersionName());
        build.setMetadata(metadata);
        build.setArtifactPath(artifactPath);

        buildDetails.add(build);
      }
    }

    return buildDetails;
  }

  private GithubPackagesVersionsResponse processResponse(List<JsonNode> versionDetails) {
    List<GithubPackagesVersion> versions = new ArrayList<>();

    if (versionDetails != null) {
      for (JsonNode node : versionDetails) {
        JsonNode metadata = node.get("metadata");

        JsonNode container = metadata.get("container");

        ArrayNode tags = (ArrayNode) container.get("tags");

        List<String> tagList = new ArrayList<>();

        for (JsonNode jsonNode : tags) {
          String tag = jsonNode.asText();

          tagList.add(tag);
        }

        GithubPackagesVersion version = GithubPackagesVersion.builder()
                                            .versionId(node.get("id").asText())
                                            .versionName(node.get("name").asText())
                                            .versionUrl(node.get("url").asText())
                                            .versionHtmlUrl(node.get("html_url").asText())
                                            .packageUrl(node.get("package_html_url").asText())
                                            .createdAt(node.get("created_at").asText())
                                            .lastUpdatedAt(node.get("updated_at").asText())
                                            .packageType(metadata.get("package_type").asText())
                                            .tags(tagList)
                                            .build();

        versions.add(version);
      }
    } else {
      if (versionDetails == null) {
        log.warn("Github Packages Version response was null.");
      } else {
        log.warn("Github Packages Version response was empty.");
      }

      return null;
    }

    return GithubPackagesVersionsResponse.builder().versionDetails(versions).build();
  }

  public static boolean isSuccessful(Response<?> response) {
    if (response == null) {
      throw new InvalidArtifactServerException("Null response found", USER);
    }

    if (response.isSuccessful()) {
      return true;
    }

    log.error("Request not successful. Reason: {}", response);

    int code = response.code();

    switch (code) {
      case 404:
      case 400:
        return false;
      case 401:
        throw unauthorizedException();
      default:
        throw new InvalidArtifactServerException(StringUtils.isNotBlank(response.message())
                ? response.message()
                : String.format("Server responded with the following error code - %d", code),
            USER);
    }
  }

  public static WingsException unauthorizedException() {
    return NestedExceptionUtils.hintWithExplanationException("Update the credentials",
        "Check if the provided credentials are correct",
        new InvalidArtifactServerException("Invalid Github Packages Registry credentials", USER));
  }

  private boolean isPackageType(String packageType) {
    if (StringUtils.equals(packageType, "container") || StringUtils.equals(packageType, "nuget")
        || StringUtils.equals(packageType, "maven") || StringUtils.equals(packageType, "npm")
        || StringUtils.equals(packageType, "rubygems")) {
      return true;
    } else {
      return false;
    }
  }

  public ArtifactMetaInfo getArtifactMetaInfo(GithubPackagesInternalConfig githubPackagesInternalConfig,
      String packageName, String version, String org, boolean isV1) throws IOException {
    DockerRegistryRestClient githubPackagesDockerRestClient =
        githubPackagesRestClientFactory.getGithubPackagesDockerRestClient(githubPackagesInternalConfig);
    DockerInternalConfig dockerInternalConfig = getDockerInternalConfig(githubPackagesInternalConfig);
    Function<Headers, String> getToken =
        headers -> dockerRegistryService.getToken(dockerInternalConfig, headers, githubPackagesDockerRestClient);
    String token;
    Response<DockerImageManifestResponse> response;
    if (isV1) {
      response = githubPackagesDockerRestClient
                     .getImageManifest(getBearerAuth(githubPackagesInternalConfig.getToken()),
                         getFullPackageName(githubPackagesInternalConfig.getUsername(), packageName, org), version)
                     .execute();
      token = getToken.apply(response.headers());
      response = githubPackagesDockerRestClient
                     .getImageManifest(getBearerAuth(token),
                         getFullPackageName(githubPackagesInternalConfig.getUsername(), packageName, org), version)
                     .execute();
    } else {
      response = githubPackagesDockerRestClient
                     .getImageManifestV2(getBearerAuth(githubPackagesInternalConfig.getToken()),
                         getFullPackageName(githubPackagesInternalConfig.getUsername(), packageName, org), version)
                     .execute();
      token = getToken.apply(response.headers());
      response = githubPackagesDockerRestClient
                     .getImageManifestV2(getBearerAuth(token),
                         getFullPackageName(githubPackagesInternalConfig.getUsername(), packageName, org), version)
                     .execute();
    }
    return getArtifactMetaInfoHelper(response, packageName);
  }

  private ArtifactMetaInfo getArtifactMetaInfoHelper(
      Response<DockerImageManifestResponse> response, String packageName) {
    if (isSuccessful(response)) {
      return dockerRegistryUtils.parseArtifactMetaInfoResponse(response, packageName);
    }
    throw NestedExceptionUtils.hintWithExplanationException(
        COULD_NOT_FETCH_IMAGE_MANIFEST, ERROR_MESSAGE, new ArtifactServerException(ERROR_MESSAGE));
  }

  private DockerInternalConfig getDockerInternalConfig(GithubPackagesInternalConfig githubPackagesInternalConfig) {
    return DockerInternalConfig.builder()
        .dockerRegistryUrl("https://ghcr.io")
        .username(githubPackagesInternalConfig.getUsername())
        .password(githubPackagesInternalConfig.getToken())
        .build();
  }

  private String getBearerAuth(String token) {
    return "Bearer " + token;
  }

  private String getFullPackageName(String userName, String packageName, String org) {
    return String.format("%s/%s", EmptyPredicate.isEmpty(org) ? userName : org, packageName);
  }
}
