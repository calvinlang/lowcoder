/**
 * Copyright 2021 Appsmith Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * <p>
 */

// adapted for rest api queries

package org.lowcoder.plugin.restapi;

import static com.google.common.base.MoreObjects.firstNonNull;
import static org.apache.commons.collections4.MapUtils.emptyIfNull;
import static org.apache.commons.lang3.StringUtils.trimToEmpty;
import static org.lowcoder.plugin.restapi.RestApiError.REST_API_EXECUTION_ERROR;
import static org.lowcoder.plugin.restapi.helpers.ContentTypeHelper.isBinary;
import static org.lowcoder.plugin.restapi.helpers.ContentTypeHelper.isJson;
import static org.lowcoder.plugin.restapi.helpers.ContentTypeHelper.isJsonContentType;
import static org.lowcoder.plugin.restapi.helpers.ContentTypeHelper.isPicture;
import static org.lowcoder.plugin.restapi.helpers.ContentTypeHelper.isValidContentType;
import static org.lowcoder.plugin.restapi.helpers.ContentTypeHelper.parseContentType;
import static org.lowcoder.sdk.exception.PluginCommonError.JSON_PARSE_ERROR;
import static org.lowcoder.sdk.exception.PluginCommonError.QUERY_ARGUMENT_ERROR;
import static org.lowcoder.sdk.exception.PluginCommonError.QUERY_EXECUTION_ERROR;
import static org.lowcoder.sdk.plugin.restapi.DataUtils.convertToMultiformFileValue;
import static org.lowcoder.sdk.plugin.restapi.auth.RestApiAuthType.DIGEST_AUTH;
import static org.lowcoder.sdk.plugin.restapi.auth.RestApiAuthType.OAUTH2_INHERIT_FROM_LOGIN;
import static org.lowcoder.sdk.util.ExceptionUtils.propagateError;
import static org.lowcoder.sdk.util.JsonUtils.readTree;
import static org.lowcoder.sdk.util.JsonUtils.toJsonThrows;
import static org.lowcoder.sdk.util.MustacheHelper.renderMustacheJson;
import static org.lowcoder.sdk.util.MustacheHelper.renderMustacheString;
import static org.lowcoder.sdk.util.StreamUtils.collectList;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.lowcoder.plugin.restapi.constants.ResponseDataType;
import org.lowcoder.plugin.restapi.helpers.AuthHelper;
import org.lowcoder.plugin.restapi.helpers.BufferingFilter;
import org.lowcoder.plugin.restapi.model.QueryBody;
import org.lowcoder.plugin.restapi.model.RestApiQueryConfig;
import org.lowcoder.plugin.restapi.model.RestApiQueryExecutionContext;
import org.lowcoder.sdk.config.CommonConfig;
import org.lowcoder.sdk.exception.PluginException;
import org.lowcoder.sdk.models.Property;
import org.lowcoder.sdk.models.QueryExecutionResult;
import org.lowcoder.sdk.models.RestBodyFormFileData;
import org.lowcoder.sdk.plugin.common.QueryExecutor;
import org.lowcoder.sdk.plugin.common.RestApiUriBuilder;
import org.lowcoder.sdk.plugin.restapi.DataUtils;
import org.lowcoder.sdk.plugin.restapi.MultipartFormData;
import org.lowcoder.sdk.plugin.restapi.RestApiDatasourceConfig;
import org.lowcoder.sdk.plugin.restapi.auth.AuthConfig;
import org.lowcoder.sdk.plugin.restapi.auth.BasicAuthConfig;
import org.lowcoder.sdk.plugin.restapi.auth.RestApiAuthType;
import org.lowcoder.sdk.query.QueryVisitorContext;
import org.lowcoder.sdk.webclient.WebClientBuildHelper;
import org.pf4j.Extension;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;

import lombok.Builder;
import lombok.Getter;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@Extension
public class RestApiExecutor implements QueryExecutor<RestApiDatasourceConfig, Object, RestApiQueryExecutionContext> {

    private static final Consumer<HttpHeaders> DEFAULT_HEADERS_CONSUMER = httpHeaders -> {};
    private static final String DEFAULT_REST_ERROR_CODE = "REST_API_EXECUTION_ERROR";
    private static final int MAX_REDIRECTS = 7;
    private final DataUtils dataUtils = DataUtils.getInstance();
    private final CommonConfig commonConfig;

    // Set an unlimited buffer size, because query payload limit will be handled in webFilter
    private final ExchangeStrategies exchangeStrategies = ExchangeStrategies
            .builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(-1))
            .build();

    public RestApiExecutor(CommonConfig commonConfig) {
        this.commonConfig = commonConfig;
    }

    @Override
    public RestApiQueryExecutionContext buildQueryExecutionContext(RestApiDatasourceConfig datasourceConfig,
            Map<String, Object> queryConfigMap,
            Map<String, Object> requestParams, QueryVisitorContext queryVisitorContext) {

        RestApiQueryConfig queryConfig = RestApiQueryConfig.from(queryConfigMap);

        // from datasource config
        String urlDomain = datasourceConfig.getUrl();
        List<Property> datasourceHeaders = datasourceConfig.getHeaders();
        List<Property> datasourceUrlParams = datasourceConfig.getParams();
        List<Property> datasourceBodyFormData = datasourceConfig.getBodyFormData();
        Set<String> forwardCookies = datasourceConfig.getForwardCookies();
        boolean forwardAllCookies = datasourceConfig.isForwardAllCookies();

        // from query config
        HttpMethod httpMethod = queryConfig.getHttpMethod();
        boolean encodeParams = !queryConfig.isDisableEncodingParams();

        String queryBody = trimToEmpty(queryConfig.getBody());
        String queryPath = trimToEmpty(queryConfig.getPath());
        List<Property> queryParams = queryConfig.getParams();
        List<Property> queryHeaders = queryConfig.getHeaders();
        List<Property> queryBodyParams = queryConfig.getBodyFormData();

        String updatedQueryPath = renderMustacheString(queryPath, requestParams);

        List<Property> updatedQueryParams = renderMustacheValueInProperties(queryParams, requestParams);
        List<Property> updatedQueryHeaders = renderMustacheValueInProperties(queryHeaders, requestParams);

        Map<String, String> allHeaders = buildHeaders(datasourceHeaders, updatedQueryHeaders);
        String contentType = parseContentType(allHeaders).toLowerCase();
        if (!isValidContentType(contentType)) {
            throw new PluginException(QUERY_ARGUMENT_ERROR, "INVALID_CONTENT_TYPE", contentType);
        }

        List<Property> updatedQueryBodyParams = renderMustacheValueForQueryBody(queryBodyParams, requestParams, contentType);

        // string | jsonNode
        QueryBody updatedQueryBody;
        Pair<Boolean, Boolean> jsonContentType = isJsonContentType(contentType);
        boolean isJsonContent = jsonContentType.getLeft();
        Boolean isSpecialJsonContent = jsonContentType.getRight();
        if (isJsonContent) {
            updatedQueryBody = new QueryBody(renderMustacheJson(queryBody, requestParams), true, isSpecialJsonContent);
        } else {
            updatedQueryBody = new QueryBody(renderMustacheString(queryBody, requestParams), false, false);
        }

        Map<String, String> urlParams = buildUrlParams(datasourceUrlParams, updatedQueryParams);
        List<Property> bodyParams = mergeBody(datasourceBodyFormData, updatedQueryBodyParams);

        URI uri = RestApiUriBuilder.buildUri(urlDomain, updatedQueryPath, requestParams, urlParams);

        QueryBody mergedQueryBody = mergeBody(updatedQueryBody, datasourceBodyFormData);
        return RestApiQueryExecutionContext.builder()
                .httpMethod(httpMethod)
                .uri(uri)
                .headers(allHeaders)
                .contentType(contentType)
                .urlParams(urlParams)
                .bodyParams(bodyParams)
                .encodeParams(encodeParams)
                .queryBody(mergedQueryBody)
                .forwardCookies(forwardCookies)
                .forwardAllCookies(forwardAllCookies)
                .requestCookies(queryVisitorContext.getCookies())
                .authConfig(datasourceConfig.getAuthConfig())
                .sslConfig(datasourceConfig.getSslConfig())
                .authTokenMono(queryVisitorContext.getAuthTokenMono())
                .timeoutMs(queryConfig.getTimeoutMs())
                .build();
    }

    private List<Property> renderMustacheValueForQueryBody(List<Property> queryBodyParams, Map<String, Object> paramMap,
            String contentType) {
        return queryBodyParams.stream()
                .map(it -> {
                    String renderedKey = renderMustacheString(it.getKey(), paramMap);
                    if (MediaType.MULTIPART_FORM_DATA_VALUE.equals(contentType) && it.isMultipartFileType()) {
                        List<MultipartFormData> multiformFileData = convertToMultiformFileValue(it.getValue(), paramMap);
                        return new RestBodyFormFileData(renderedKey, multiformFileData);
                    }

                    String renderedStringValue = renderMustacheString(it.getValue(), paramMap);
                    return new Property(renderedKey, renderedStringValue, it.getType());
                })
                .toList();
    }


    private QueryBody mergeBody(QueryBody queryBody, List<Property> datasourceBody) {
        if (!queryBody.isJsonContent() || CollectionUtils.isEmpty(datasourceBody)) {
            return queryBody;
        }
        JsonNode jsonNode = queryBody.getJsonValue();
        if (jsonNode instanceof ObjectNode objectNode) {
            for (Property property : datasourceBody) {
                objectNode.put(property.getKey(), property.getValue());
            }
            return queryBody;
        }

        return queryBody;
    }

    @Override
    public Mono<QueryExecutionResult> executeQuery(Object webClientFilter, RestApiQueryExecutionContext context) {

        return Mono.defer(() -> authByOauth2InheritFromLogin(context))
                .then(Mono.defer(() -> {
                    WebClient.Builder webClientBuilder = WebClientBuildHelper.builder()
                            .disallowedHosts(commonConfig.getDisallowedHosts())
                            .sslConfig(context.getSslConfig())
                            .toWebClientBuilder();

                    Map<String, String> allHeaders = context.getHeaders();
                    String contentType = context.getContentType();
                    allHeaders.forEach(webClientBuilder::defaultHeader);

                    //basic auth
                    AuthConfig authConfig = context.getAuthConfig();
                    if (authConfig != null && authConfig.getType() == RestApiAuthType.BASIC_AUTH) {
                        webClientBuilder.defaultHeaders(AuthHelper.basicAuth((BasicAuthConfig) authConfig));
                    }

                    if (MediaType.MULTIPART_FORM_DATA_VALUE.equals(contentType)) {
                        webClientBuilder.filter(new BufferingFilter());
                    }

                    HttpClient httpClient = HttpClient.create()
                    		.responseTimeout(Duration.ofMillis(context.getTimeoutMs()));

                    webClientBuilder.defaultCookies(injectCookies(context));
                    WebClient client = webClientBuilder
                            .exchangeStrategies(exchangeStrategies)
                            .clientConnector(new ReactorClientHttpConnector(httpClient))
                            .build();

                    BodyInserter<?, ? super ClientHttpRequest> bodyInserter = buildBodyInserter(
                            context.getHttpMethod(),
                            context.isEncodeParams(),
                            contentType,
                            context.getQueryBody(),
                            context.getBodyParams());

                    return httpCall(client, context.getHttpMethod(), context.getUri(), bodyInserter, 0, authConfig, DEFAULT_HEADERS_CONSUMER)
                            .map(this::convertToQueryExecutionResult)
                            .onErrorResume(e -> propagateError(REST_API_EXECUTION_ERROR, DEFAULT_REST_ERROR_CODE, e));
                }));
    }

    private Mono<ResponseEntity<byte[]>> httpCall(WebClient webClient, HttpMethod httpMethod,
            URI uri,
            BodyInserter<?, ? super ClientHttpRequest> requestBody,
            int iteration,
            @Nullable AuthConfig authConfig,
            Consumer<HttpHeaders> headersConsumer) {
        if (iteration == MAX_REDIRECTS) {
            return Mono.error(new PluginException(QUERY_EXECUTION_ERROR, "REACH_REDIRECT_LIMIT", MAX_REDIRECTS));
        }

        return webClient
                .method(httpMethod)
                .uri(uri)
                .headers(headersConsumer)
                .body(requestBody)
                .exchangeToMono(response -> {
                    if (response.statusCode().is3xxRedirection()) {
                        String redirectUrl = response.headers().header("Location").get(0);
                        URI redirectUri;
                        try {
                            redirectUri = new URI(redirectUrl);
                        } catch (URISyntaxException e) {
                            return propagateError(REST_API_EXECUTION_ERROR, DEFAULT_REST_ERROR_CODE, e);
                        }
                        return httpCall(webClient, httpMethod, redirectUri, requestBody, iteration + 1, authConfig, headersConsumer);
                    }
                    //digest auth
                    if (authConfig != null && authConfig.getType() == DIGEST_AUTH && AuthHelper.shouldDigestAuth(response)) {
                        try {
                            return httpCall(webClient, httpMethod, uri, requestBody, iteration + 1, authConfig,
                                    headersConsumer.andThen(
                                            AuthHelper.digestAuth((BasicAuthConfig) authConfig, response, httpMethod, uri.getPath())));
                        } catch (ParseException e) {
                            return propagateError(REST_API_EXECUTION_ERROR, DEFAULT_REST_ERROR_CODE, e);
                        }
                    }

                    return response.toEntity(byte[].class);
                });
    }

    private Mono<Void> authByOauth2InheritFromLogin(RestApiQueryExecutionContext context) {
        if (context.getAuthConfig() == null || context.getAuthConfig().getType() != OAUTH2_INHERIT_FROM_LOGIN) {
            return Mono.empty();
        }
        return context.getAuthTokenMono()
                .doOnNext(properties -> {
                    Map<String, List<Property>> propertyMap = properties.stream()
                            .collect(Collectors.groupingBy(Property::getType));

                    List<Property> params = propertyMap.get("param");
                    if (CollectionUtils.isNotEmpty(params)) {
                        Map<String, String> paramMap = new HashMap<>(emptyIfNull(context.getUrlParams()));
                        for (Property param : params) {
                            paramMap.put(param.getKey(), param.getValue());
                        }
                        context.setUrlParams(ImmutableMap.copyOf(paramMap));
                    }

                    List<Property> headers = propertyMap.get("header");
                    if (CollectionUtils.isNotEmpty(headers)) {
                        Map<String, String> headerMap = new HashMap<>(emptyIfNull(context.getHeaders()));
                        for (Property header : headers) {
                            headerMap.put(header.getKey(), header.getValue());
                        }
                        context.setHeaders(ImmutableMap.copyOf(headerMap));
                    }
                })
                .switchIfEmpty(Mono.error(new PluginException(REST_API_EXECUTION_ERROR, DEFAULT_REST_ERROR_CODE,
                        "$ACCESS_TOKEN parameter missing.")))
                .onErrorResume(throwable -> propagateError(REST_API_EXECUTION_ERROR, DEFAULT_REST_ERROR_CODE, throwable))
                .then();
    }

    private Consumer<MultiValueMap<String, String>> injectCookies(RestApiQueryExecutionContext request) {
        return currentCookies -> {
            Set<String> forwardCookies = request.getForwardCookies();
            MultiValueMap<String, HttpCookie> requestCookies = request.getRequestCookies();
            if (requestCookies == null) {
                return;
            }

            if (request.isForwardAllCookies()) {
                requestCookies.forEach((cookieName, httpCookies) -> {
                    if (StringUtils.equals(cookieName, commonConfig.getCookieName())) {
                        return;
                    }
                    currentCookies.addAll(cookieName, collectList(httpCookies, HttpCookie::getValue));
                });
                return;
            }

            requestCookies.entrySet()
                    .stream()
                    .filter(it -> forwardCookies.contains(it.getKey()))
                    .filter(it -> ObjectUtils.notEqual(it.getKey(), commonConfig.getCookieName()))
                    .forEach(entry -> {
                        String cookieName = entry.getKey();
                        List<HttpCookie> httpCookies = entry.getValue();
                        currentCookies.addAll(cookieName, collectList(httpCookies, HttpCookie::getValue));
                    });
        };
    }

    private QueryExecutionResult convertToQueryExecutionResult(ResponseEntity<byte[]> responseEntity) {
        HttpHeaders headers = responseEntity.getHeaders();
        MediaType contentType = firstNonNull(headers.getContentType(), MediaType.TEXT_PLAIN); // text type if null
        byte[] body = responseEntity.getBody();
        HttpStatus statusCode = HttpStatus.resolve(responseEntity.getStatusCode().value());
        JsonNode resultHeaders = parseExecuteResultHeaders(headers);

        if (body == null) {
            return QueryExecutionResult.ofRestApiResult(statusCode, resultHeaders, statusCode.toString());
        }

        ResponseBodyData responseBodyData = parseResponseDataInfo(body, contentType);
        ObjectNode headersObjectNode = (ObjectNode) resultHeaders;
        return QueryExecutionResult.ofRestApiResult(statusCode, headersObjectNode, responseBodyData.getBody());
    }

    @Getter
    @Builder
    private static class ResponseBodyData {
        private ResponseDataType dataType;
        private Object body;

    }

    private ResponseBodyData parseResponseDataInfo(byte[] body, MediaType contentType) {

        if (isJson(contentType)) {
            try {
                return ResponseBodyData.builder()
                        .body(readTree(body))
                        .dataType(ResponseDataType.JSON)
                        .build();

            } catch (IOException e) {
                throw new PluginException(REST_API_EXECUTION_ERROR, "INVALID_JSON_FROM_RESPONSE");
            }
        }

        if (isPicture(contentType)) {
            return ResponseBodyData.builder()
                    .body(Base64.getEncoder().encode(body))
                    .dataType(ResponseDataType.IMAGE)
                    .build();
        }
        if (isBinary(contentType)) {
            return ResponseBodyData.builder()
                    .body(Base64.getEncoder().encode(body))
                    .dataType(ResponseDataType.BINARY)
                    .build();
        }

        return ResponseBodyData.builder()
                .body(new String(body, StandardCharsets.UTF_8).trim())
                .dataType(ResponseDataType.TEXT)
                .build();
    }

    private JsonNode parseExecuteResultHeaders(HttpHeaders headers) {
        // Convert the headers into json tree to store in the results
        String headerInJsonString;
        try {
            headerInJsonString = toJsonThrows(headers);
        } catch (JsonProcessingException e) {
            throw new PluginException(QUERY_EXECUTION_ERROR, DEFAULT_REST_ERROR_CODE, e.getMessage());
        }

        // Set headers in the result now
        try {
            return (readTree(headerInJsonString));
        } catch (IOException e) {
            throw new PluginException(JSON_PARSE_ERROR, "JSON_PARSE_ERROR", headerInJsonString, e.getMessage());
        }
    }

    private List<Property> mergeBody(List<Property> datasourceBodyFormData, List<Property> updatedQueryBodyParams) {
        Set<String> keySet = updatedQueryBodyParams.stream()
                .map(Property::getKey)
                .collect(Collectors.toCollection(HashSet::new));
        List<Property> merge = new ArrayList<>(updatedQueryBodyParams);
        for (Property property : datasourceBodyFormData) {
            if (!keySet.contains(property.getKey())) {
                merge.add(property);
                keySet.add(property.getKey());
            }
        }
        return merge;
    }

    private Map<String, String> buildUrlParams(List<Property> datasourceUrlParams, List<Property> updatedQueryParams) {
        return Stream.concat(datasourceUrlParams.stream(),
                        updatedQueryParams.stream())
                .collect(Collectors.toUnmodifiableMap(Property::getKey,
                        Property::getValue,
                        (oldValue, newValue) -> newValue));
    }

    private Map<String, String> buildHeaders(List<Property> datasourceHeaders, List<Property> updatedQueryHeaders) {
        return Stream.concat(datasourceHeaders.stream(),
                        updatedQueryHeaders.stream())
                .filter(it -> StringUtils.isNotBlank(it.getKey()) && StringUtils.isNotBlank(it.getValue()))
                .collect(Collectors.toUnmodifiableMap(property -> property.getKey().trim(),
                        Property::getValue,
                        (oldValue, newValue) -> newValue));
    }

    private BodyInserter<?, ? super ClientHttpRequest> buildBodyInserter(HttpMethod httpMethod,
            boolean isEncodeParams,
            String requestContentType,
            QueryBody queryBody,
            List<Property> bodyFormData) {

        if (HttpMethod.GET.equals(httpMethod)) {
            return BodyInserters.fromValue(new byte[0]);
        }

        if (isNoneContentType(requestContentType)) {
            return BodyInserters.fromValue(new byte[0]);
        }

        if (queryBody.isSpecialJson()) {
            return BodyInserters.fromValue(queryBody.getJsonValue().toString());
        }

        if (queryBody.isJsonContent()) {
            return BodyInserters.fromValue(queryBody.getJsonValue());
        }

        if (MediaType.APPLICATION_FORM_URLENCODED_VALUE.equals(requestContentType)
                || MediaType.MULTIPART_FORM_DATA_VALUE.equals(requestContentType)) {
            return dataUtils.buildBodyInserter(bodyFormData, requestContentType, isEncodeParams);
        }
        return BodyInserters.fromValue(queryBody.value());
    }

    private boolean isNoneContentType(String requestContentType) {
        return StringUtils.isBlank(requestContentType);
    }

    private List<Property> renderMustacheValueInProperties(List<Property> properties, Map<String, Object> paramMap) {
        return properties.stream()
                .map(it -> {
                    Property newProperty = new Property(renderMustacheString(it.getKey(), paramMap),
                            renderMustacheString(it.getValue(), paramMap));
                    newProperty.setType(it.getType());
                    return newProperty;
                })
                .toList();
    }

}