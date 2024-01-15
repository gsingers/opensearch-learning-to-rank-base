/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.o19s.es.ltr.rest;

import com.o19s.es.ltr.feature.store.index.IndexFeatureStore;
import org.opensearch.client.node.NodeClient;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.action.RestStatusToXContentListener;

import java.util.List;

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableList;
import static org.opensearch.index.query.QueryBuilders.boolQuery;
import static org.opensearch.index.query.QueryBuilders.matchQuery;
import static org.opensearch.index.query.QueryBuilders.termQuery;

public class RestSearchStoreElements extends FeatureStoreBaseRestHandler {
    private final String type;

    public RestSearchStoreElements(String type) {
        this.type = type;
    }

    @Override
    public String getName() {
        return "Search for " + type + " elements in the LTR feature store";
    }

    @Override
    public List<Route> routes() {
        return unmodifiableList(asList(
                new Route(RestRequest.Method.GET, "/_ltr/{store}/_" + type),
                new Route(RestRequest.Method.GET, "/_ltr/_" + type)
        ));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        return search(client, type, indexName(request), request);
    }

    RestChannelConsumer search(NodeClient client, String type, String indexName, RestRequest request) {
        String prefix = request.param("prefix");
        int from = request.paramAsInt("from", 0);
        int size = request.paramAsInt("size", 20);
        BoolQueryBuilder qb = boolQuery().filter(termQuery("type", type));
        if (prefix != null && !prefix.isEmpty()) {
            qb.must(matchQuery("name.prefix", prefix));
        }
        return (channel) -> client.prepareSearch(indexName)
                .setTypes(IndexFeatureStore.ES_TYPE)
                .setQuery(qb)
                .setSize(size)
                .setFrom(from)
                .execute(new RestStatusToXContentListener<>(channel));
    }

}
