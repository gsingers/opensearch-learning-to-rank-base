/*
 * Copyright [2017] Wikimedia Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.o19s.es.ltr.query;

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableMap;
import static java.util.stream.Collectors.joining;
import static org.hamcrest.CoreMatchers.instanceOf;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.junit.runner.RunWith;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.ltr.stats.LTRStat;
import org.opensearch.ltr.stats.LTRStats;
import org.opensearch.ltr.stats.StatName;
import org.opensearch.ltr.stats.suppliers.CounterSupplier;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.AbstractQueryTestCase;
import org.opensearch.test.TestGeoShapeFieldMapperPlugin;

import com.carrotsearch.randomizedtesting.RandomizedRunner;
import com.o19s.es.ltr.LtrQueryParserPlugin;
import com.o19s.es.ltr.feature.FeatureValidation;
import com.o19s.es.ltr.feature.store.StorableElement;
import com.o19s.es.ltr.feature.store.StoredFeature;
import com.o19s.es.ltr.feature.store.StoredFeatureNormalizers;
import com.o19s.es.ltr.feature.store.StoredFeatureSet;
import com.o19s.es.ltr.feature.store.StoredLtrModel;
import com.o19s.es.ltr.ranker.parser.LinearRankerParser;
import com.o19s.es.ltr.ranker.parser.LtrRankerParserFactory;

@RunWith(RandomizedRunner.class)
public class ValidatingLtrQueryBuilderTests extends AbstractQueryTestCase<ValidatingLtrQueryBuilder> {
    private final LtrRankerParserFactory factory = new LtrRankerParserFactory.Builder()
        .register(LinearRankerParser.TYPE, LinearRankerParser::new)
        .build();

    private LTRStats ltrStats = new LTRStats(unmodifiableMap(new HashMap<String, LTRStat<?>>() {
        {
            put(StatName.LTR_REQUEST_TOTAL_COUNT.getName(), new LTRStat<>(false, new CounterSupplier()));
            put(StatName.LTR_REQUEST_ERROR_COUNT.getName(), new LTRStat<>(false, new CounterSupplier()));
        }
    }));

    // TODO: Remove the TestGeoShapeFieldMapperPlugin once upstream has completed the migration.
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return asList(LtrQueryParserPlugin.class, TestGeoShapeFieldMapperPlugin.class);
    }

    @Override
    protected Set<String> getObjectsHoldingArbitraryContent() {
        return new HashSet<>(asList(FeatureValidation.PARAMS.getPreferredName(), StoredFeature.TEMPLATE.getPreferredName()));
    }

    /**
     * Create the query that is being tested
     */
    @Override
    protected ValidatingLtrQueryBuilder doCreateTestQueryBuilder() {
        StorableElement element;
        Function<String, StoredFeature> buildFeature = (n) -> new StoredFeature(
            n,
            Collections.singletonList("query_string"),
            "mustache",
            QueryBuilders.matchQuery("test", "{{query_string}}").toString()
        );
        BiFunction<Integer, String, StoredFeatureSet> buildFeatureSet = (i, name) -> new StoredFeatureSet(
            name,
            IntStream.range(0, i).mapToObj((idx) -> buildFeature.apply("feature" + idx)).collect(Collectors.toList())
        );
        Function<String, StoredLtrModel> buildModel = (name) -> new StoredLtrModel(
            name,
            buildFeatureSet.apply(5, "the_feature_set"),
            "model/linear",
            IntStream.range(0, 5).mapToObj((i) -> "\"feature" + i + "\": " + random().nextFloat()).collect(joining(",", "{", "}")),
            true,
            new StoredFeatureNormalizers()
        );

        int type = randomInt(2);
        switch (type) {
            case 0:
                element = buildFeature.apply("feature");
                break;
            case 1:
                element = buildFeatureSet.apply(randomInt(19) + 1, "featureset");
                break;
            case 2:
                element = buildModel.apply("model");
                break;
            default:
                throw new UnsupportedOperationException("Invalid type " + type);
        }
        Map<String, Object> params = new HashMap<>();
        params.put("query_string", "hello world");
        FeatureValidation val = new FeatureValidation("test_index", params);
        return new ValidatingLtrQueryBuilder(element, val, factory, ltrStats);
    }

    @Override
    protected boolean builderGeneratesCacheableQueries() {
        return false;
    }

    @Override
    protected boolean supportsBoost() {
        return false;
    }

    @Override
    protected boolean supportsQueryName() {
        return false;
    }

    @Override
    protected void doAssertLuceneQuery(ValidatingLtrQueryBuilder queryBuilder, Query query, QueryShardContext context) throws IOException {
        if (StoredFeature.TYPE.equals(queryBuilder.getElement().type())) {
            assertThat(query, instanceOf(MatchNoDocsQuery.class));
        } else if (StoredFeatureSet.TYPE.equals(queryBuilder.getElement().type())) {
            assertThat(query, instanceOf(RankerQuery.class));
            RankerQuery q = (RankerQuery) query;
            assertEquals(queryBuilder.getElement().name(), q.featureSet().name());
        } else if (StoredLtrModel.TYPE.equals(queryBuilder.getElement().type())) {
            assertThat(query, instanceOf(RankerQuery.class));
            RankerQuery q = (RankerQuery) query;
            assertEquals(((StoredLtrModel) queryBuilder.getElement()).featureSet().name(), q.featureSet().name());
        } else {
            throw new AssertionError("Invalid storable element type : " + queryBuilder.getElement().type());
        }
    }
}
