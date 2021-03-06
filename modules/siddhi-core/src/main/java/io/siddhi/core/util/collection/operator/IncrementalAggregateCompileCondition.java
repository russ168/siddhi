/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.siddhi.core.util.collection.operator;

import io.siddhi.core.aggregation.IncrementalDataAggregator;
import io.siddhi.core.aggregation.IncrementalExecutor;
import io.siddhi.core.aggregation.IncrementalExternalTimestampDataAggregator;
import io.siddhi.core.config.SiddhiQueryContext;
import io.siddhi.core.event.ComplexEvent;
import io.siddhi.core.event.ComplexEventChunk;
import io.siddhi.core.event.state.StateEvent;
import io.siddhi.core.event.stream.MetaStreamEvent;
import io.siddhi.core.event.stream.StreamEvent;
import io.siddhi.core.event.stream.StreamEventCloner;
import io.siddhi.core.event.stream.StreamEventFactory;
import io.siddhi.core.event.stream.populater.ComplexEventPopulater;
import io.siddhi.core.exception.SiddhiAppRuntimeException;
import io.siddhi.core.executor.ExpressionExecutor;
import io.siddhi.core.executor.VariableExpressionExecutor;
import io.siddhi.core.query.selector.GroupByKeyGenerator;
import io.siddhi.core.table.Table;
import io.siddhi.core.util.parser.helper.QueryParserHelper;
import io.siddhi.query.api.aggregation.TimePeriod;
import io.siddhi.query.api.definition.AggregationDefinition;
import io.siddhi.query.api.definition.Attribute;
import io.siddhi.query.api.exception.SiddhiAppValidationException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.siddhi.query.api.expression.Expression.Time.normalizeDuration;

/**
 * Defines the logic to find a matching event from an incremental aggregator (retrieval from incremental aggregator),
 * based on the logical conditions defined herewith.
 */
public class IncrementalAggregateCompileCondition implements CompiledCondition {
    private final StreamEventFactory streamEventFactoryForTableMeta;
    private final StreamEventCloner tableEventCloner;
    private final StreamEventFactory streamEventFactoryForAggregateMeta;
    private final StreamEventCloner aggregateEventCloner;
    private final List<Attribute> additionalAttributes;
    private Map<TimePeriod.Duration, CompiledCondition> withinTableCompiledConditions;
    private CompiledCondition inMemoryStoreCompileCondition;
    private Map<TimePeriod.Duration, CompiledCondition> withinTableLowerGranularityCompileCondition;
    private CompiledCondition onCompiledCondition;
    private MetaStreamEvent tableMetaStreamEvent;
    private ComplexEventPopulater complexEventPopulater;
    private MatchingMetaInfoHolder alteredMatchingMetaInfoHolder;
    private ExpressionExecutor perExpressionExecutor;
    private ExpressionExecutor startTimeEndTimeExpressionExecutor;
    private List<ExpressionExecutor> timestampFilterExecutors;
    private boolean isProcessingOnExternalTime;
    private final boolean isDistributed;
    private final List<TimePeriod.Duration> incrementalDurations;

    private MatchingMetaInfoHolder newMatchingHolderInfo;
    private List<VariableExpressionExecutor> newVariableExpressionExecutors;

    public IncrementalAggregateCompileCondition(
            Map<TimePeriod.Duration, CompiledCondition> withinTableCompiledConditions,
            CompiledCondition inMemoryStoreCompileCondition,
            Map<TimePeriod.Duration, CompiledCondition> withinTableLowerGranularityCompileCondition,
            CompiledCondition onCompiledCondition,
            MetaStreamEvent tableMetaStreamEvent, MetaStreamEvent aggregateMetaSteamEvent,
            List<Attribute> additionalAttributes, MatchingMetaInfoHolder alteredMatchingMetaInfoHolder,
            ExpressionExecutor perExpressionExecutor, ExpressionExecutor startTimeEndTimeExpressionExecutor,
            List<ExpressionExecutor> timestampFilterExecutors, boolean isProcessingOnExternalTime,
            List<TimePeriod.Duration> incrementalDurations, boolean isDistributed,
            MatchingMetaInfoHolder newMatchingHolderInfo,
            List<VariableExpressionExecutor> newVariableExpressionExecutors) {
        this.withinTableCompiledConditions = withinTableCompiledConditions;
        this.inMemoryStoreCompileCondition = inMemoryStoreCompileCondition;
        this.withinTableLowerGranularityCompileCondition = withinTableLowerGranularityCompileCondition;
        this.onCompiledCondition = onCompiledCondition;
        this.tableMetaStreamEvent = tableMetaStreamEvent;

        this.streamEventFactoryForTableMeta = new StreamEventFactory(tableMetaStreamEvent);
        this.tableEventCloner = new StreamEventCloner(tableMetaStreamEvent, streamEventFactoryForTableMeta);

        this.streamEventFactoryForAggregateMeta = new StreamEventFactory(aggregateMetaSteamEvent);
        this.aggregateEventCloner = new StreamEventCloner(aggregateMetaSteamEvent, streamEventFactoryForAggregateMeta);
        this.additionalAttributes = additionalAttributes;
        this.alteredMatchingMetaInfoHolder = alteredMatchingMetaInfoHolder;
        this.perExpressionExecutor = perExpressionExecutor;
        this.startTimeEndTimeExpressionExecutor = startTimeEndTimeExpressionExecutor;
        this.timestampFilterExecutors = timestampFilterExecutors;
        this.isProcessingOnExternalTime = isProcessingOnExternalTime;
        this.incrementalDurations = incrementalDurations;
        this.isDistributed = isDistributed;
        this.newMatchingHolderInfo = newMatchingHolderInfo;
        this.newVariableExpressionExecutors = newVariableExpressionExecutors;
    }

    public void init() {
        QueryParserHelper.updateVariablePosition(newMatchingHolderInfo.getMetaStateEvent(),
                newVariableExpressionExecutors);
    }

    public StreamEvent find(StateEvent matchingEvent, AggregationDefinition aggregationDefinition,
                            Map<TimePeriod.Duration, IncrementalExecutor> incrementalExecutorMap,
                            Map<TimePeriod.Duration, Table> aggregationTables,
                            List<ExpressionExecutor> baseExecutorsForFind,
                            List<ExpressionExecutor> outputExpressionExecutors,
                            SiddhiQueryContext siddhiQueryContext,
                            List<List<ExpressionExecutor>> aggregateProcessingExecutorsListForFind,
                            List<GroupByKeyGenerator> groupbyKeyGeneratorList,
                            ExpressionExecutor shouldUpdateTimestamp) {

        ComplexEventChunk<StreamEvent> complexEventChunkToHoldWithinMatches = new ComplexEventChunk<>(true);

        //Create matching event if it is store Query
        int additionTimestampAttributesSize = this.timestampFilterExecutors.size() + 2;
        Long[] timestampFilters = new Long[additionTimestampAttributesSize];
        if (matchingEvent.getStreamEvent(0) == null) {
            StreamEvent streamEvent = new StreamEvent(0, additionTimestampAttributesSize, 0);
            matchingEvent.addEvent(0, streamEvent);
        }

        Long[] startTimeEndTime = (Long[]) startTimeEndTimeExpressionExecutor.execute(matchingEvent);
        if (startTimeEndTime == null) {
            throw new SiddhiAppRuntimeException("Start and end times for within duration cannot be retrieved");
        }
        timestampFilters[0] = startTimeEndTime[0];
        timestampFilters[1] = startTimeEndTime[1];

        if (isDistributed) {
            for (int i = 0; i < additionTimestampAttributesSize - 2; i++) {
                timestampFilters[i + 2] = ((Long) this.timestampFilterExecutors.get(i).execute(matchingEvent));
            }
        }


        complexEventPopulater.populateComplexEvent(matchingEvent.getStreamEvent(0), timestampFilters);

        // Get all the aggregates within the given duration, from table corresponding to "per" duration
        // Retrieve per value
        String perValueAsString = perExpressionExecutor.execute(matchingEvent).toString();
        TimePeriod.Duration perValue;
        try {
            // Per time function verification
            perValue = normalizeDuration(perValueAsString);
        } catch (SiddhiAppValidationException e) {
            throw new SiddhiAppRuntimeException(
                    "Aggregation Query's per value is expected to be of a valid time function of the " +
                            "following " + TimePeriod.Duration.SECONDS + ", " + TimePeriod.Duration.MINUTES + ", "
                            + TimePeriod.Duration.HOURS + ", " + TimePeriod.Duration.DAYS + ", "
                            + TimePeriod.Duration.MONTHS + ", " + TimePeriod.Duration.YEARS + ".");
        }
        if (!incrementalExecutorMap.keySet().contains(perValue)) {
            throw new SiddhiAppRuntimeException("The aggregate values for " + perValue.toString()
                    + " granularity cannot be provided since aggregation definition " +
                    aggregationDefinition.getId() + " does not contain " + perValue.toString() + " duration");
        }

        Table tableForPerDuration = aggregationTables.get(perValue);

        StreamEvent withinMatchFromPersistedEvents = tableForPerDuration.find(matchingEvent,
                withinTableCompiledConditions.get(perValue));
        complexEventChunkToHoldWithinMatches.add(withinMatchFromPersistedEvents);

        // Optimization step.
        long oldestInMemoryEventTimestamp = getOldestInMemoryEventTimestamp(incrementalExecutorMap,
                incrementalDurations, perValue);

        //If processing on external time, the in-memory data also needs to be queried
        if (isProcessingOnExternalTime || requiresAggregatingInMemoryData(oldestInMemoryEventTimestamp,
                startTimeEndTime)) {
            if (isDistributed) {
                int perValueIndex = this.incrementalDurations.indexOf(perValue);
                if (perValueIndex != 0) {
                    Map<TimePeriod.Duration, CompiledCondition> lowerGranularityLookups = new HashMap<>();
                    for (int i = 0; i < perValueIndex; i++) {
                        TimePeriod.Duration key = this.incrementalDurations.get(i);
                        lowerGranularityLookups.put(key, withinTableLowerGranularityCompileCondition.get(key));
                    }
                    List<StreamEvent> eventChunks = lowerGranularityLookups.entrySet().stream()
                            .map((entry) -> aggregationTables.get(entry.getKey()).find(matchingEvent, entry.getValue()))
                            .collect(Collectors.toList());
                    eventChunks.forEach((eventChunk) -> {
                        if (eventChunk != null) {
                            complexEventChunkToHoldWithinMatches.add(eventChunk);
                        }
                    });
                }
            } else {
                IncrementalDataAggregator incrementalDataAggregator = new IncrementalDataAggregator(
                        incrementalDurations, perValue, oldestInMemoryEventTimestamp, baseExecutorsForFind,
                        tableMetaStreamEvent, shouldUpdateTimestamp, groupbyKeyGeneratorList.get(0) != null);
                ComplexEventChunk<StreamEvent> aggregatedInMemoryEventChunk;
                // Aggregate in-memory data and create an event chunk out of it
                aggregatedInMemoryEventChunk = incrementalDataAggregator.aggregateInMemoryData(incrementalExecutorMap);

                // Get the in-memory aggregate data, which is within given duration
                StreamEvent withinMatchFromInMemory = ((Operator) inMemoryStoreCompileCondition).find(matchingEvent,
                        aggregatedInMemoryEventChunk, tableEventCloner);
                complexEventChunkToHoldWithinMatches.add(withinMatchFromInMemory);
            }
        }

        ComplexEventChunk<StreamEvent> processedEvents;
        if (isDistributed || isProcessingOnExternalTime) {
            int durationIndex = incrementalDurations.indexOf(perValue);
            List<ExpressionExecutor> expressionExecutors = aggregateProcessingExecutorsListForFind.get(durationIndex);
            GroupByKeyGenerator groupByKeyGenerator = groupbyKeyGeneratorList.get(durationIndex);
            IncrementalExternalTimestampDataAggregator incrementalExternalTimestampDataAggregator =
                    new IncrementalExternalTimestampDataAggregator(expressionExecutors, groupByKeyGenerator,
                            tableMetaStreamEvent, siddhiQueryContext,
                            shouldUpdateTimestamp);
            processedEvents = incrementalExternalTimestampDataAggregator
                    .aggregateData(complexEventChunkToHoldWithinMatches);
        } else {
            processedEvents = complexEventChunkToHoldWithinMatches;
        }

        // Get the final event chunk from the data which is within given duration. This event chunk contains the values
        // in the select clause of an aggregate definition.
        ComplexEventChunk<StreamEvent> aggregateSelectionComplexEventChunk = createAggregateSelectionEventChunk(
                processedEvents, outputExpressionExecutors);

        // Execute the on compile condition
        return ((Operator) onCompiledCondition).find(matchingEvent, aggregateSelectionComplexEventChunk,
                aggregateEventCloner);
    }

    private ComplexEventChunk<StreamEvent> createAggregateSelectionEventChunk(
            ComplexEventChunk<StreamEvent> complexEventChunkToHoldMatches,
            List<ExpressionExecutor> outputExpressionExecutors) {
        ComplexEventChunk<StreamEvent> aggregateSelectionComplexEventChunk = new ComplexEventChunk<>(true);
        StreamEvent resetEvent = streamEventFactoryForTableMeta.newInstance();
        resetEvent.setType(ComplexEvent.Type.RESET);

        while (complexEventChunkToHoldMatches.hasNext()) {
            StreamEvent streamEvent = complexEventChunkToHoldMatches.next();
            StreamEvent newStreamEvent = streamEventFactoryForAggregateMeta.newInstance();
            Object outputData[] = new Object[newStreamEvent.getOutputData().length];
            for (int i = 0; i < outputExpressionExecutors.size(); i++) {
                outputData[i] = outputExpressionExecutors.get(i).execute(streamEvent);
            }
            newStreamEvent.setTimestamp(streamEvent.getTimestamp());
            newStreamEvent.setOutputData(outputData);
            aggregateSelectionComplexEventChunk.add(newStreamEvent);
        }

        for (ExpressionExecutor expressionExecutor : outputExpressionExecutors) {
            expressionExecutor.execute(resetEvent);
        }

        return aggregateSelectionComplexEventChunk;
    }

    private boolean requiresAggregatingInMemoryData(long oldestInMemoryEventTimestamp, Long[] startTimeEndTime) {
        if (oldestInMemoryEventTimestamp == -1) {
            return false;
        }
        long endTimeForWithin = startTimeEndTime[1];
        return endTimeForWithin > oldestInMemoryEventTimestamp;
    }

    private long getOldestInMemoryEventTimestamp(Map<TimePeriod.Duration, IncrementalExecutor> incrementalExecutorMap,
                                                 List<TimePeriod.Duration> incrementalDurations,
                                                 TimePeriod.Duration perValue) {
        long oldestEvent;
        TimePeriod.Duration incrementalDuration;
        for (int i = perValue.ordinal(); i >= incrementalDurations.get(0).ordinal(); i--) {
            incrementalDuration = TimePeriod.Duration.values()[i];
            //If the reduced granularity is not configured
            if (incrementalExecutorMap.containsKey(incrementalDuration)) {
                oldestEvent = incrementalExecutorMap.get(incrementalDuration).getAggregationStartTimestamp();
                if (oldestEvent != -1) {
                    return oldestEvent;
                }
            }
        }
        return -1;
    }

    public void setComplexEventPopulater(ComplexEventPopulater complexEventPopulater) {
        this.complexEventPopulater = complexEventPopulater;
    }

    public List<Attribute> getAdditionalAttributes() {
        return this.additionalAttributes;
    }

    public MatchingMetaInfoHolder getAlteredMatchingMetaInfoHolder() {
        return this.alteredMatchingMetaInfoHolder;
    }
}
