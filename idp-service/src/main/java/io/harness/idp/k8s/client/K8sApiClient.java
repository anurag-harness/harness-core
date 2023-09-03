/*
 * Copyright 2023 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.idp.k8s.client;

import static io.harness.data.structure.EmptyPredicate.isNotEmpty;
import static io.harness.exception.WingsException.USER;

import static java.lang.String.format;
import static org.apache.commons.lang3.StringUtils.isBlank;

import io.harness.exception.InvalidRequestException;
import io.harness.exception.sanitizer.ExceptionMessageSanitizer;
import io.harness.idp.k8s.exception.ClusterCredentialsNotFoundException;
import io.harness.k8s.KubernetesHelperService;
import io.harness.k8s.model.KubernetesConfig;
import io.harness.k8s.model.KubernetesConfig.KubernetesConfigBuilder;
import io.harness.retry.RetryHelper;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import io.github.resilience4j.retry.Retry;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1Secret;
import java.net.ConnectException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import okhttp3.internal.http2.ConnectionShutdownException;
import okhttp3.internal.http2.StreamResetException;
import org.apache.commons.lang3.StringUtils;

@Singleton
@Slf4j
public class K8sApiClient implements K8sClient {
  @Inject @Named("backstageSaToken") private String backstageSaToken;
  @Inject @Named("backstageSaCaCrt") private String backstageSaCaCrt;
  @Inject @Named("backstageMasterUrl") private String backstageMasterUrl;
  @Inject @Named("backstagePodLabel") private String backstagePodLabel;
  @Inject private KubernetesHelperService kubernetesHelperService;
  private final Retry retry = buildRetryAndRegisterListeners();

  @Override
  public V1Secret updateSecretData(String namespace, String secretName, Map<String, byte[]> data, boolean replace) {
    KubernetesConfig kubernetesConfig = getKubernetesConfig();
    ApiClient apiClient = kubernetesHelperService.getApiClient(kubernetesConfig);
    CoreV1Api coreV1Api = new CoreV1Api(apiClient);
    V1Secret secret = getSecret(coreV1Api, namespace, secretName);
    Map<String, byte[]> secretData = secret.getData();
    secretData = secretData == null ? new HashMap<>() : secretData;
    if (replace) {
      secretData.clear();
    }
    secretData.putAll(data);
    secret.setData(secretData);
    return replaceSecret(coreV1Api, secret);
  }

  @Override
  public V1ConfigMap updateConfigMapData(
      String namespace, String configMapName, Map<String, String> data, boolean replace) {
    KubernetesConfig kubernetesConfig = getKubernetesConfig();
    ApiClient apiClient = kubernetesHelperService.getApiClient(kubernetesConfig);
    CoreV1Api coreV1Api = new CoreV1Api(apiClient);
    V1ConfigMap configMap = getConfigMap(coreV1Api, namespace, configMapName);
    Map<String, String> configMapData = configMap.getData();
    configMapData = configMapData == null ? new HashMap<>() : configMapData;
    if (replace) {
      configMapData.clear();
    }
    configMapData.putAll(data);
    configMap.setData(configMapData);
    return replaceConfigMap(coreV1Api, configMap);
  }

  @Override
  public V1PodList getBackstagePodList(String namespace) {
    KubernetesConfig kubernetesConfig = getKubernetesConfig();
    ApiClient apiClient = kubernetesHelperService.getApiClient(kubernetesConfig);
    CoreV1Api coreV1Api = new CoreV1Api(apiClient);
    final Supplier<V1PodList> podListSupplier = Retry.decorateSupplier(retry, () -> {
      try {
        return coreV1Api.listNamespacedPod(
            namespace, null, null, null, null, backstagePodLabel, null, null, null, null, false);
      } catch (ApiException e) {
        String err = format("Could not check for pod status. Code: %s, message: %s", e.getCode(), e.getMessage());
        log.error(err, e);
        throw new InvalidRequestException(err, e, USER);
      }
    });
    return podListSupplier.get();
  }

  @Override
  public void removeSecretData(String namespace, String secretName, List<String> envNames) {
    KubernetesConfig kubernetesConfig = getKubernetesConfig();
    ApiClient apiClient = kubernetesHelperService.getApiClient(kubernetesConfig);
    CoreV1Api coreV1Api = new CoreV1Api(apiClient);
    V1Secret secret = getSecret(coreV1Api, namespace, secretName);
    Map<String, byte[]> secretData = secret.getData();
    if (secretData != null) {
      envNames.forEach(secretData::remove);
    }
    secret.setData(secretData);
    replaceSecret(coreV1Api, secret);
    log.info(
        "Successfully removed [{}] environment secrets from [{}/Secret/{}]", envNames.size(), namespace, secretName);
  }

  private V1Secret getSecret(CoreV1Api coreV1Api, String namespace, String secretName) {
    if (isBlank(secretName)) {
      throw new InvalidRequestException("Secret name is empty");
    }
    final Supplier<V1Secret> secretSupplier = Retry.decorateSupplier(retry, () -> {
      try {
        return coreV1Api.readNamespacedSecret(secretName, namespace, null);
      } catch (ApiException e) {
        if (e.getCode() == 404) {
          return createSecret(coreV1Api, namespace, secretName);
        }
        ApiException ex = ExceptionMessageSanitizer.sanitizeException(e);
        String errorMessage = format(
            "Failed to get %s/Secret/%s. Code: %s, message: %s", namespace, secretName, ex.getCode(), ex.getMessage());
        throw new InvalidRequestException(errorMessage, ex, USER);
      }
    });
    return secretSupplier.get();
  }

  private V1Secret createSecret(CoreV1Api coreV1Api, String namespace, String secretName) {
    if (isBlank(secretName)) {
      throw new InvalidRequestException("Secret name is empty");
    }
    final Supplier<V1Secret> secretSupplier = Retry.decorateSupplier(retry, () -> {
      V1Secret secret = new V1Secret();
      V1ObjectMeta metadata = new V1ObjectMeta();
      metadata.setNamespace(namespace);
      metadata.setName(secretName);
      secret.setMetadata(metadata);
      try {
        return coreV1Api.createNamespacedSecret(namespace, secret, null, null, null, null);
      } catch (ApiException e) {
        ApiException ex = ExceptionMessageSanitizer.sanitizeException(e);
        String errorMessage = format("Failed to create %s/Secret/%s. Code: %s, message: %s", namespace, secretName,
            ex.getCode(), ex.getMessage());
        throw new InvalidRequestException(errorMessage, ex, USER);
      }
    });
    return secretSupplier.get();
  }

  private V1ConfigMap createConfigMap(CoreV1Api coreV1Api, String namespace, String configMapName) {
    if (isBlank(configMapName)) {
      throw new InvalidRequestException("ConfigMap name is empty");
    }
    final Supplier<V1ConfigMap> config = Retry.decorateSupplier(retry, () -> {
      V1ConfigMap configMap = new V1ConfigMap();
      V1ObjectMeta metadata = new V1ObjectMeta();
      metadata.setNamespace(namespace);
      metadata.setName(configMapName);
      configMap.setMetadata(metadata);
      try {
        return coreV1Api.createNamespacedConfigMap(namespace, configMap, null, null, null, null);
      } catch (ApiException e) {
        ApiException ex = ExceptionMessageSanitizer.sanitizeException(e);
        String errorMessage = format("Failed to create %s/ConfigMap/%s. Code: %s, message: %s", namespace,
            configMapName, ex.getCode(), ex.getMessage());
        throw new InvalidRequestException(errorMessage, ex, USER);
      }
    });
    return config.get();
  }

  private V1Secret replaceSecret(CoreV1Api coreV1Api, V1Secret secret) {
    String secretName = Objects.requireNonNull(secret.getMetadata()).getName();
    String namespace = Objects.requireNonNull(secret.getMetadata()).getNamespace();
    final Supplier<V1Secret> secretSupplier = Retry.decorateSupplier(retry, () -> {
      try {
        return coreV1Api.replaceNamespacedSecret(secretName, namespace, secret, null, null, null, null);
      } catch (ApiException e) {
        ApiException ex = ExceptionMessageSanitizer.sanitizeException(e);
        String secretDef = secret.getMetadata() != null && isNotEmpty(secret.getMetadata().getName())
            ? format("%s/Secret/%s", secret.getMetadata().getNamespace(), secret.getMetadata().getName())
            : "ConfigMap";
        String message =
            format("Failed to replace %s. Code: %s, message: %s", secretDef, ex.getCode(), ex.getMessage());
        throw new InvalidRequestException(message, ex, USER);
      }
    });
    return secretSupplier.get();
  }

  private V1ConfigMap getConfigMap(CoreV1Api coreV1Api, String namespace, String configMapName) {
    if (isBlank(configMapName)) {
      throw new InvalidRequestException("Config Map name is empty");
    }
    final Supplier<V1ConfigMap> configMapSupplier = Retry.decorateSupplier(retry, () -> {
      try {
        return coreV1Api.readNamespacedConfigMap(configMapName, namespace, null);
      } catch (ApiException e) {
        if (e.getCode() == 404) {
          return createConfigMap(coreV1Api, namespace, configMapName);
        }
        ApiException ex = ExceptionMessageSanitizer.sanitizeException(e);
        String message = format("Failed to get %s/ConfigMap/%s. Code: %s, message: %s", namespace, configMapName,
            ex.getCode(), ex.getMessage());
        throw new InvalidRequestException(message, ex, USER);
      }
    });
    return configMapSupplier.get();
  }

  private V1ConfigMap replaceConfigMap(CoreV1Api coreV1Api, V1ConfigMap configMap) {
    String configMapName = Objects.requireNonNull(configMap.getMetadata()).getName();
    String namespace = Objects.requireNonNull(configMap.getMetadata()).getNamespace();
    final Supplier<V1ConfigMap> configMapSupplier = Retry.decorateSupplier(retry, () -> {
      try {
        return coreV1Api.replaceNamespacedConfigMap(configMapName, namespace, configMap, null, null, null, null);
      } catch (ApiException e) {
        ApiException ex = ExceptionMessageSanitizer.sanitizeException(e);
        String configMapDef = configMap.getMetadata() != null && isNotEmpty(configMap.getMetadata().getName())
            ? format("%s/ConfigMap/%s", configMap.getMetadata().getNamespace(), configMap.getMetadata().getName())
            : "ConfigMap";
        String message =
            format("Failed to replace %s. Code: %s, message: %s", configMapDef, ex.getCode(), ex.getMessage());
        throw new InvalidRequestException(message, ex, USER);
      }
    });
    return configMapSupplier.get();
  }

  @Override
  public KubernetesConfig getKubernetesConfig() {
    if (StringUtils.isBlank(backstageMasterUrl)) {
      throw new ClusterCredentialsNotFoundException("Master URL not found");
    }
    if (StringUtils.isBlank(backstageSaToken)) {
      throw new ClusterCredentialsNotFoundException("Service Account Token not found");
    }
    KubernetesConfigBuilder builder = KubernetesConfig.builder();
    builder.masterUrl(backstageMasterUrl);
    builder.serviceAccountTokenSupplier(() -> backstageSaToken);

    if (StringUtils.isNotBlank(backstageSaCaCrt)) {
      builder.clientCert(backstageSaCaCrt.toCharArray());
    }
    return builder.build();
  }

  @Override
  public V1Namespace createNamespace(String namespace) {
    KubernetesConfig kubernetesConfig = getKubernetesConfig();
    ApiClient apiClient = kubernetesHelperService.getApiClient(kubernetesConfig);
    CoreV1Api coreV1Api = new CoreV1Api(apiClient);
    final Supplier<V1Namespace> namespaceSupplier = Retry.decorateSupplier(retry, () -> {
      try {
        V1Namespace v1Namespace = new V1Namespace();
        V1ObjectMeta v1ObjectMeta = new V1ObjectMeta();
        v1ObjectMeta.setName(namespace);
        v1Namespace.setMetadata(v1ObjectMeta);
        return coreV1Api.createNamespace(v1Namespace, null, null, null, null);
      } catch (ApiException e) {
        ApiException ex = ExceptionMessageSanitizer.sanitizeException(e);
        if (ex.getCode() == 409) {
          log.info("Namespace {} already exists", namespace);
          return null;
        }
        String message =
            format("Failed to create namespace %s. Code: %s, message: %s", namespace, ex.getCode(), ex.getMessage());
        throw new InvalidRequestException(message, ex, USER);
      }
    });
    return namespaceSupplier.get();
  }

  private Retry buildRetryAndRegisterListeners() {
    final Retry exponentialRetry = RetryHelper.getExponentialRetry(this.getClass().getSimpleName(),
        new Class[] {ConnectException.class, TimeoutException.class, ConnectionShutdownException.class,
            StreamResetException.class});
    RetryHelper.registerEventListeners(exponentialRetry);
    return exponentialRetry;
  }
}
