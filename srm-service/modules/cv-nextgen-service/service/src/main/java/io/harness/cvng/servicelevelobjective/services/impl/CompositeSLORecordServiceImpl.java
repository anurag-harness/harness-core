/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */
package io.harness.cvng.servicelevelobjective.services.impl;

import static io.harness.data.structure.EmptyPredicate.isEmpty;
import static io.harness.persistence.HQuery.excludeAuthorityCount;

import io.harness.SRMPersistence;
import io.harness.annotations.retry.RetryOnException;
import io.harness.cvng.servicelevelobjective.beans.SLIEvaluationType;
import io.harness.cvng.servicelevelobjective.beans.SLIMissingDataType;
import io.harness.cvng.servicelevelobjective.entities.CompositeSLORecord;
import io.harness.cvng.servicelevelobjective.entities.CompositeSLORecord.CompositeSLORecordKeys;
import io.harness.cvng.servicelevelobjective.entities.CompositeServiceLevelObjective;
import io.harness.cvng.servicelevelobjective.entities.CompositeServiceLevelObjective.ServiceLevelObjectivesDetail;
import io.harness.cvng.servicelevelobjective.entities.SLIRecord;
import io.harness.cvng.servicelevelobjective.services.api.CompositeSLORecordService;
import io.harness.cvng.servicelevelobjective.services.api.SLIRecordService;
import io.harness.cvng.servicelevelobjective.services.api.SLOHealthIndicatorService;
import io.harness.cvng.servicelevelobjective.services.api.ServiceLevelObjectiveV2Service;
import io.harness.exception.InvalidArgumentsException;

import com.google.common.collect.ImmutableSortedSet;
import com.google.inject.Inject;
import dev.morphia.query.FindOptions;
import dev.morphia.query.Sort;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

@Slf4j
public class CompositeSLORecordServiceImpl implements CompositeSLORecordService {
  private static final int RETRY_COUNT = 3;
  @Inject private SRMPersistence hPersistence;

  @Inject ServiceLevelObjectiveV2Service serviceLevelObjectiveV2Service;

  @Inject SLIRecordService sliRecordService;

  @Inject SLOHealthIndicatorService sloHealthIndicatorService;

  @Override
  public void create(CompositeServiceLevelObjective compositeServiceLevelObjective, Instant startTime, Instant endTime,
      String verificationTaskId) {
    Pair<Map<ServiceLevelObjectivesDetail, List<SLIRecord>>, Map<ServiceLevelObjectivesDetail, SLIMissingDataType>>
        sloDetailsSLIRecordsAndSLIMissingDataType = sliRecordService.getSLODetailsSLIRecordsAndSLIMissingDataType(
            compositeServiceLevelObjective.getServiceLevelObjectivesDetails(), startTime, endTime);
    if (sloDetailsSLIRecordsAndSLIMissingDataType.getKey().size()
        == compositeServiceLevelObjective.getServiceLevelObjectivesDetails().size()) {
      Map<ServiceLevelObjectivesDetail, List<SLIRecord>> serviceLevelObjectivesDetailCompositeSLORecordMap =
          sloDetailsSLIRecordsAndSLIMissingDataType.getKey();
      Map<ServiceLevelObjectivesDetail, SLIMissingDataType> objectivesDetailSLIMissingDataTypeMap =
          sloDetailsSLIRecordsAndSLIMissingDataType.getValue();
      String compositeSLOId = compositeServiceLevelObjective.getUuid();
      int sloVersion = compositeServiceLevelObjective.getVersion();
      SLIEvaluationType sliEvaluationType = compositeServiceLevelObjective.getSliEvaluationType();
      if (isEmpty(serviceLevelObjectivesDetailCompositeSLORecordMap)) {
        return;
      }

      double runningGoodCount = 0;
      double runningBadCount = 0;
      CompositeSLORecord lastCompositeSLORecord = getLastCompositeSLORecord(compositeSLOId, startTime);
      CompositeSLORecord latestCompositeSLORecord = getLatestCompositeSLORecord(compositeSLOId);
      if (Objects.nonNull(lastCompositeSLORecord)) {
        runningGoodCount = lastCompositeSLORecord.getRunningGoodCount();
        runningBadCount = lastCompositeSLORecord.getRunningBadCount();
      }
      if (Objects.nonNull(latestCompositeSLORecord) && latestCompositeSLORecord.getTimestamp().isAfter(startTime)) {
        // Update flow: fetch CompositeSLO Records to be updated
        updateCompositeSLORecords(serviceLevelObjectivesDetailCompositeSLORecordMap,
            objectivesDetailSLIMissingDataTypeMap, sloVersion, runningGoodCount, runningBadCount, compositeSLOId,
            startTime, endTime, sliEvaluationType);
      } else {
        List<CompositeSLORecord> compositeSLORecords = getCompositeSLORecordsFromSLIsDetails(
            serviceLevelObjectivesDetailCompositeSLORecordMap, objectivesDetailSLIMissingDataTypeMap, sloVersion,
            runningGoodCount, runningBadCount, compositeSLOId, sliEvaluationType);
        hPersistence.saveBatch(compositeSLORecords);
      }
      sloHealthIndicatorService.upsert(compositeServiceLevelObjective);
    }
  }

  @Override
  public List<CompositeSLORecord> getSLORecords(String sloId, Instant startTimeStamp, Instant endTimeStamp) {
    return hPersistence.createQuery(CompositeSLORecord.class, excludeAuthorityCount)
        .filter(CompositeSLORecordKeys.sloId, sloId)
        .field(CompositeSLORecordKeys.timestamp)
        .greaterThanOrEq(startTimeStamp)
        .field(CompositeSLORecordKeys.timestamp)
        .lessThan(endTimeStamp)
        .order(Sort.ascending(CompositeSLORecordKeys.timestamp))
        .asList();
  }

  @Override
  public CompositeSLORecord getLatestCompositeSLORecord(String sloId) {
    return hPersistence.createQuery(CompositeSLORecord.class, excludeAuthorityCount)
        .filter(CompositeSLORecordKeys.sloId, sloId)
        .order(Sort.descending(CompositeSLORecordKeys.timestamp))
        .get();
  }

  @Override
  public CompositeSLORecord getLatestCompositeSLORecordWithVersion(
      String sloId, Instant startTimeForCurrentRange, int sloVersion) {
    return hPersistence.createQuery(CompositeSLORecord.class, excludeAuthorityCount)
        .filter(CompositeSLORecordKeys.sloId, sloId)
        .field(CompositeSLORecordKeys.timestamp)
        .greaterThanOrEq(startTimeForCurrentRange)
        .filter(CompositeSLORecordKeys.sloVersion, sloVersion)
        .order(Sort.descending(CompositeSLORecordKeys.timestamp))
        .get();
  }

  public List<CompositeSLORecord> getCompositeSLORecordsFromSLIsDetails(
      Map<ServiceLevelObjectivesDetail, List<SLIRecord>> serviceLevelObjectivesDetailCompositeSLORecordMap,
      Map<ServiceLevelObjectivesDetail, SLIMissingDataType> objectivesDetailSLIMissingDataTypeMap, int sloVersion,
      double runningGoodCount, double runningBadCount, String verificationTaskId, SLIEvaluationType sliEvaluationType) {
    if (sliEvaluationType == SLIEvaluationType.REQUEST) {
      return getRequestCompositeSLORecordsFromSLIsDetails(serviceLevelObjectivesDetailCompositeSLORecordMap,
          objectivesDetailSLIMissingDataTypeMap, sloVersion, runningGoodCount, runningBadCount, verificationTaskId);
    } else {
      return getWindowCompositeSLORecordsFromSLIsDetails(serviceLevelObjectivesDetailCompositeSLORecordMap,
          objectivesDetailSLIMissingDataTypeMap, sloVersion, runningGoodCount, runningBadCount, verificationTaskId);
    }
  }

  private List<CompositeSLORecord> getWindowCompositeSLORecordsFromSLIsDetails(
      Map<ServiceLevelObjectivesDetail, List<SLIRecord>> serviceLevelObjectivesDetailCompositeSLORecordMap,
      Map<ServiceLevelObjectivesDetail, SLIMissingDataType> objectivesDetailSLIMissingDataTypeMap, int sloVersion,
      double runningGoodCount, double runningBadCount, String verificationTaskId) {
    Map<Instant, Double> timeStampToGoodValue = new HashMap<>();
    Map<Instant, Double> timeStampToBadValue = new HashMap<>();
    Map<Instant, Integer> timeStampToTotalValue = new HashMap<>();
    getTimeStampToValueMaps(serviceLevelObjectivesDetailCompositeSLORecordMap, objectivesDetailSLIMissingDataTypeMap,
        timeStampToGoodValue, timeStampToBadValue, timeStampToTotalValue);
    List<CompositeSLORecord> sloRecordList = new ArrayList<>();
    for (Instant instant : ImmutableSortedSet.copyOf(timeStampToTotalValue.keySet())) {
      if (timeStampToTotalValue.get(instant).equals(serviceLevelObjectivesDetailCompositeSLORecordMap.size())) {
        runningGoodCount += timeStampToGoodValue.getOrDefault(instant, 0.0);
        runningBadCount += timeStampToBadValue.getOrDefault(instant, 0.0);
        CompositeSLORecord sloRecord = CompositeSLORecord.builder()
                                           .runningBadCount(runningBadCount)
                                           .runningGoodCount(runningGoodCount)
                                           .sloId(verificationTaskId)
                                           .sloVersion(sloVersion)
                                           .verificationTaskId(verificationTaskId)
                                           .timestamp(instant)
                                           .build();
        sloRecordList.add(sloRecord);
      }
    }
    return sloRecordList;
  }

  private List<CompositeSLORecord> getRequestCompositeSLORecordsFromSLIsDetails(
      Map<ServiceLevelObjectivesDetail, List<SLIRecord>> serviceLevelObjectivesDetailCompositeSLORecordMap,
      Map<ServiceLevelObjectivesDetail, SLIMissingDataType> objectivesDetailSLIMissingDataTypeMap, int sloVersion,
      double runningGoodCount, double runningBadCount, String verificationTaskId) {
    List<CompositeSLORecord> sloRecordList = new ArrayList<>();
    Map<Instant, Map<String, SLIRecord>> timeStampToSLIRecordsMap =
        getTimeStampToSLIRecordMap(serviceLevelObjectivesDetailCompositeSLORecordMap);
    for (Instant instant : timeStampToSLIRecordsMap.keySet()) {
      if (timeStampToSLIRecordsMap.get(instant).size() == serviceLevelObjectivesDetailCompositeSLORecordMap.size()) {
        CompositeSLORecord sloRecord = CompositeSLORecord.builder()
                                           .runningBadCount(runningBadCount)
                                           .runningGoodCount(runningGoodCount)
                                           .sloId(verificationTaskId)
                                           .sloVersion(sloVersion)
                                           .verificationTaskId(verificationTaskId)
                                           .timestamp(instant)
                                           .scopedIdentifierSLIRecordMap(timeStampToSLIRecordsMap.get(instant))
                                           .build();
        sloRecordList.add(sloRecord);
      }
    }
    return sloRecordList;
  }

  private Map<Instant, Map<String, SLIRecord>> getTimeStampToSLIRecordMap(
      Map<ServiceLevelObjectivesDetail, List<SLIRecord>> serviceLevelObjectivesDetailCompositeSLORecordMap) {
    Map<Instant, Map<String, SLIRecord>> timeStampToSLIRecordsMap = new HashMap<>();
    for (ServiceLevelObjectivesDetail objectivesDetail : serviceLevelObjectivesDetailCompositeSLORecordMap.keySet()) {
      for (SLIRecord sliRecord : serviceLevelObjectivesDetailCompositeSLORecordMap.get(objectivesDetail)) {
        Map<String, SLIRecord> serviceLevelObjectivesDetailSLIRecordMap =
            timeStampToSLIRecordsMap.getOrDefault(sliRecord.getTimestamp(), new HashMap<>());
        if (sliRecord.getSliState() != SLIRecord.SLIState.SKIP_DATA) {
          serviceLevelObjectivesDetailSLIRecordMap.put(
              serviceLevelObjectiveV2Service.getScopedIdentifier(objectivesDetail), sliRecord);
        }
        timeStampToSLIRecordsMap.put(sliRecord.getTimestamp(), serviceLevelObjectivesDetailSLIRecordMap);
      }
    }
    return timeStampToSLIRecordsMap;
  }

  @RetryOnException(retryCount = RETRY_COUNT, retryOn = ConcurrentModificationException.class)
  public void updateCompositeSLORecords(
      Map<ServiceLevelObjectivesDetail, List<SLIRecord>> serviceLevelObjectivesDetailCompositeSLORecordMap,
      Map<ServiceLevelObjectivesDetail, SLIMissingDataType> objectivesDetailSLIMissingDataTypeMap, int sloVersion,
      double runningGoodCount, double runningBadCount, String verificationTaskId, Instant startTime, Instant endTime,
      SLIEvaluationType sliEvaluationType) {
    List<CompositeSLORecord> toBeUpdatedSLORecords =
        getSLORecords(verificationTaskId, startTime, endTime.plus(1, ChronoUnit.MINUTES));
    Map<Instant, CompositeSLORecord> sloRecordMap =
        toBeUpdatedSLORecords.stream().collect(Collectors.toMap(CompositeSLORecord::getTimestamp, Function.identity()));
    List<CompositeSLORecord> updateOrCreateSLORecords;
    if (sliEvaluationType == SLIEvaluationType.WINDOW) {
      updateOrCreateSLORecords = updateWindowCompositeSLORecords(serviceLevelObjectivesDetailCompositeSLORecordMap,
          objectivesDetailSLIMissingDataTypeMap, sloVersion, runningGoodCount, runningBadCount, verificationTaskId,
          sloRecordMap);
    } else if (sliEvaluationType == SLIEvaluationType.REQUEST) {
      updateOrCreateSLORecords = updateRequestCompositeSLORecords(serviceLevelObjectivesDetailCompositeSLORecordMap,
          sloVersion, runningGoodCount, runningBadCount, verificationTaskId, sloRecordMap);
    } else {
      throw new InvalidArgumentsException("Invalid Evaluation Type");
    }

    try {
      hPersistence.upsertBatch(CompositeSLORecord.class, updateOrCreateSLORecords, new ArrayList<>());
    } catch (IllegalAccessException exception) {
      log.error("SLO Records update failed through Bulk update {}", exception.getLocalizedMessage());
      hPersistence.save(updateOrCreateSLORecords);
    }
  }

  private List<CompositeSLORecord> updateWindowCompositeSLORecords(
      Map<ServiceLevelObjectivesDetail, List<SLIRecord>> serviceLevelObjectivesDetailCompositeSLORecordMap,
      Map<ServiceLevelObjectivesDetail, SLIMissingDataType> objectivesDetailSLIMissingDataTypeMap, int sloVersion,
      double runningGoodCount, double runningBadCount, String verificationTaskId,
      Map<Instant, CompositeSLORecord> sloRecordMap) {
    List<CompositeSLORecord> updateOrCreateSLORecords = new ArrayList<>();
    Map<Instant, Double> timeStampToGoodValue = new HashMap<>();
    Map<Instant, Double> timeStampToBadValue = new HashMap<>();
    Map<Instant, Integer> timeStampToTotalValue = new HashMap<>();
    getTimeStampToValueMaps(serviceLevelObjectivesDetailCompositeSLORecordMap, objectivesDetailSLIMissingDataTypeMap,
        timeStampToGoodValue, timeStampToBadValue, timeStampToTotalValue);
    CompositeSLORecord sloRecord = null;
    for (Instant instant : ImmutableSortedSet.copyOf(timeStampToTotalValue.keySet())) {
      if (timeStampToTotalValue.get(instant).equals(serviceLevelObjectivesDetailCompositeSLORecordMap.size())) {
        sloRecord = sloRecordMap.get(instant);
        runningGoodCount += timeStampToGoodValue.getOrDefault(instant, 0.0);
        runningBadCount += timeStampToBadValue.getOrDefault(instant, 0.0);
      }
      if (Objects.nonNull(sloRecord)) {
        sloRecord.setRunningGoodCount(runningGoodCount);
        sloRecord.setRunningBadCount(runningBadCount);
        sloRecord.setSloVersion(sloVersion);
      } else {
        sloRecord = CompositeSLORecord.builder()
                        .runningBadCount(runningBadCount)
                        .runningGoodCount(runningGoodCount)
                        .sloId(verificationTaskId)
                        .sloVersion(sloVersion)
                        .verificationTaskId(verificationTaskId)
                        .timestamp(instant)
                        .build();
      }
      updateOrCreateSLORecords.add(sloRecord);
    }
    return updateOrCreateSLORecords;
  }

  private List<CompositeSLORecord> updateRequestCompositeSLORecords(
      Map<ServiceLevelObjectivesDetail, List<SLIRecord>> serviceLevelObjectivesDetailCompositeSLORecordMap,
      int sloVersion, double runningGoodCount, double runningBadCount, String verificationTaskId,
      Map<Instant, CompositeSLORecord> sloRecordMap) {
    List<CompositeSLORecord> updateOrCreateSLORecords = new ArrayList<>();
    Map<Instant, Map<String, SLIRecord>> timeStampToSLIRecordsMap =
        getTimeStampToSLIRecordMap(serviceLevelObjectivesDetailCompositeSLORecordMap);
    CompositeSLORecord sloRecord = null;
    for (Instant instant : timeStampToSLIRecordsMap.keySet()) {
      if (timeStampToSLIRecordsMap.get(instant).size() == serviceLevelObjectivesDetailCompositeSLORecordMap.size()) {
        sloRecord = sloRecordMap.get(instant);
      }
      if (Objects.nonNull(sloRecord)) {
        sloRecord.setRunningGoodCount(runningGoodCount);
        sloRecord.setRunningBadCount(runningBadCount);
        sloRecord.setSloVersion(sloVersion);
        sloRecord.setScopedIdentifierSLIRecordMap(timeStampToSLIRecordsMap.get(instant));
      } else {
        sloRecord = CompositeSLORecord.builder()
                        .runningBadCount(runningBadCount)
                        .runningGoodCount(runningGoodCount)
                        .sloId(verificationTaskId)
                        .sloVersion(sloVersion)
                        .verificationTaskId(verificationTaskId)
                        .timestamp(instant)
                        .scopedIdentifierSLIRecordMap(timeStampToSLIRecordsMap.get(instant))
                        .build();
      }
      updateOrCreateSLORecords.add(sloRecord);
    }
    return updateOrCreateSLORecords;
  }

  private void getTimeStampToValueMaps(
      Map<ServiceLevelObjectivesDetail, List<SLIRecord>> serviceLevelObjectivesDetailCompositeSLORecordMap,
      Map<ServiceLevelObjectivesDetail, SLIMissingDataType> objectivesDetailSLIMissingDataTypeMap,
      Map<Instant, Double> timeStampToGoodValue, Map<Instant, Double> timeStampToBadValue,
      Map<Instant, Integer> timeStampToTotalValue) {
    for (ServiceLevelObjectivesDetail objectivesDetail : serviceLevelObjectivesDetailCompositeSLORecordMap.keySet()) {
      for (SLIRecord sliRecord : serviceLevelObjectivesDetailCompositeSLORecordMap.get(objectivesDetail)) {
        if (SLIRecord.SLIState.GOOD.equals(sliRecord.getSliState())
            || (SLIRecord.SLIState.NO_DATA.equals(sliRecord.getSliState())
                && objectivesDetailSLIMissingDataTypeMap.get(objectivesDetail).equals(SLIMissingDataType.GOOD))) {
          double goodCount = timeStampToGoodValue.getOrDefault(sliRecord.getTimestamp(), 0.0);
          goodCount += objectivesDetail.getWeightagePercentage() / 100;
          timeStampToGoodValue.put(sliRecord.getTimestamp(), goodCount);
          timeStampToTotalValue.put(
              sliRecord.getTimestamp(), timeStampToTotalValue.getOrDefault(sliRecord.getTimestamp(), 0) + 1);
        } else if (SLIRecord.SLIState.BAD.equals(sliRecord.getSliState())
            || (SLIRecord.SLIState.NO_DATA.equals(sliRecord.getSliState())
                && objectivesDetailSLIMissingDataTypeMap.get(objectivesDetail).equals(SLIMissingDataType.BAD))) {
          double badCount = timeStampToBadValue.getOrDefault(sliRecord.getTimestamp(), 0.0);
          badCount += objectivesDetail.getWeightagePercentage() / 100;
          timeStampToBadValue.put(sliRecord.getTimestamp(), badCount);
          timeStampToTotalValue.put(
              sliRecord.getTimestamp(), timeStampToTotalValue.getOrDefault(sliRecord.getTimestamp(), 0) + 1);
        }
      }
    }
  }
  @Override
  public List<CompositeSLORecord> getLatestCountSLORecords(String sloId, int count) {
    return hPersistence.createQuery(CompositeSLORecord.class, excludeAuthorityCount)
        .filter(CompositeSLORecordKeys.sloId, sloId)
        .order(Sort.descending(CompositeSLORecordKeys.timestamp))
        .asList(new FindOptions().limit(count));
  }

  @Override
  public CompositeSLORecord getLastCompositeSLORecord(String sloId, Instant startTimeStamp) {
    return hPersistence.createQuery(CompositeSLORecord.class, excludeAuthorityCount)
        .filter(CompositeSLORecordKeys.sloId, sloId)
        .field(CompositeSLORecordKeys.timestamp)
        .lessThan(startTimeStamp)
        .order(Sort.descending(CompositeSLORecordKeys.timestamp))
        .get();
  }

  @Override
  public CompositeSLORecord getFirstCompositeSLORecord(String sloId, Instant timestampInclusive) {
    return hPersistence.createQuery(CompositeSLORecord.class, excludeAuthorityCount)
        .filter(CompositeSLORecordKeys.sloId, sloId)
        .field(CompositeSLORecordKeys.timestamp)
        .greaterThanOrEq(timestampInclusive)
        .order(Sort.ascending(CompositeSLORecordKeys.timestamp))
        .get();
  }

  @Override
  public List<CompositeSLORecord> getSLORecordsOfMinutes(String sloId, List<Instant> minutes) {
    return hPersistence.createQuery(CompositeSLORecord.class, excludeAuthorityCount)
        .filter(CompositeSLORecordKeys.sloId, sloId)
        .field(CompositeSLORecordKeys.timestamp)
        .in(minutes)
        .order(Sort.ascending(CompositeSLORecordKeys.timestamp))
        .asList();
  }
}
