/*
   Copyright (c) 2015 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.linkedin.restli.server.multiplexer;


import com.linkedin.common.callback.Callback;
import com.linkedin.data.DataMap;
import com.linkedin.data.template.DataTemplateUtil;
import com.linkedin.parseq.Engine;
import com.linkedin.parseq.Task;
import com.linkedin.parseq.Tasks;
import com.linkedin.r2.message.RequestContext;
import com.linkedin.r2.message.rest.RestRequest;
import com.linkedin.r2.message.rest.RestResponse;
import com.linkedin.r2.message.rest.RestResponseBuilder;
import com.linkedin.r2.transport.common.RestRequestHandler;
import com.linkedin.restli.common.HttpMethod;
import com.linkedin.restli.common.HttpStatus;
import com.linkedin.restli.common.RestConstants;
import com.linkedin.restli.common.multiplexer.IndividualRequest;
import com.linkedin.restli.common.multiplexer.IndividualRequestMap;
import com.linkedin.restli.common.multiplexer.IndividualResponseMap;
import com.linkedin.restli.common.multiplexer.MultiplexedRequestContent;
import com.linkedin.restli.common.multiplexer.MultiplexedResponseContent;
import com.linkedin.restli.internal.common.ContentTypeUtil;
import com.linkedin.restli.internal.common.ContentTypeUtil.ContentType;
import com.linkedin.restli.internal.common.CookieUtil;
import com.linkedin.restli.internal.server.util.DataMapUtils;
import com.linkedin.restli.server.RestLiServiceException;

import java.net.HttpCookie;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.activation.MimeTypeParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Multiplexer implementation.
 *
 * @author Dmitriy Yefremov
 */
public class MultiplexedRequestHandlerImpl implements MultiplexedRequestHandler
{
  private static final String MUX_URI_PATH = "/mux";

  private final Logger _log = LoggerFactory.getLogger(MultiplexedRequestHandlerImpl.class);
  private final RestRequestHandler _requestHandler;
  private final Engine _engine;
  private final int _maximumRequestsNumber;
  private final MultiplexerSingletonFilter _multiplexerSingletonFilter;
  private final Set<String> _individualRequestHeaderWhitelist;

  /**
   * @param requestHandler        the handler that will take care of individual requests
   * @param engine                ParSeq engine to run request handling on
   * @param maximumRequestsNumber the maximum number of individual requests allowed in a multiplexed request
   * @param individualRequestHeaderWhitelist a set of request header names to allow if specified in the individual request
   * @param multiplexerSingletonFilter the singleton filter that is used by multiplexer to pre-process individual request and
   *                                   post-process individual response. Pass in null if no pre-processing or post-processing are required.
   */
  public MultiplexedRequestHandlerImpl(RestRequestHandler requestHandler,
                                       Engine engine,
                                       int maximumRequestsNumber,
                                       Set<String> individualRequestHeaderWhitelist,
                                       MultiplexerSingletonFilter multiplexerSingletonFilter)
  {
    _requestHandler = requestHandler;
    _engine = engine;
    _maximumRequestsNumber = maximumRequestsNumber;
    _individualRequestHeaderWhitelist = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
    if (individualRequestHeaderWhitelist != null)
    {
      _individualRequestHeaderWhitelist.addAll(individualRequestHeaderWhitelist);
    }
    _multiplexerSingletonFilter = multiplexerSingletonFilter;
  }

  @Override
  public boolean isMultiplexedRequest(RestRequest request)
  {
    // we don't check the method here because we want to return 405 if it is anything but POST
    return MUX_URI_PATH.equals(request.getURI().getPath());
  }

  @Override
  public void handleRequest(RestRequest request, RequestContext requestContext, final Callback<RestResponse> callback)
  {
    if (HttpMethod.POST != HttpMethod.valueOf(request.getMethod()))
    {
      _log.error("POST is expected, but " + request.getMethod() + " received");
      callback.onError(new RestLiServiceException(HttpStatus.S_405_METHOD_NOT_ALLOWED));
      return;
    }
    IndividualRequestMap individualRequests;
    try
    {
      individualRequests = extractIndividualRequests(request);
    }
    catch (RestLiServiceException e)
    {
      _log.error("Invalid multiplexed request", e);
      callback.onError(e);
      return;
    }
    catch (Exception e)
    {
      _log.error("Invalid multiplexed request", e);
      callback.onError(new RestLiServiceException(HttpStatus.S_400_BAD_REQUEST));
      return;
    }
    // prepare the map of individual responses to be collected
    final IndividualResponseMap individualResponses = new IndividualResponseMap(individualRequests.size());
    final Map<String, HttpCookie> responseCookies = new HashMap<String, HttpCookie>();
    // all tasks are Void and side effect based, that will be useful when we add streaming
    Task<Void> requestProcessingTask = createParallelRequestsTask(request, requestContext, individualRequests, individualResponses, responseCookies);
    Task<Void> responseAggregationTask = Tasks.action("send aggregated response", new Runnable()
    {
      @Override
      public void run()
      {
        RestResponse aggregatedResponse = aggregateResponses(individualResponses, responseCookies);
        callback.onSuccess(aggregatedResponse);
      }
    });
    _engine.run(Tasks.seq(requestProcessingTask, responseAggregationTask));
  }

  /**
   * Extracts individual requests from the given REST request.
   *
   * @return a non-empty map of individual requests
   */
  private IndividualRequestMap extractIndividualRequests(RestRequest restRequest)
  {
    validateHeaders(restRequest);
    DataMap data = DataMapUtils.readMap(restRequest);
    MultiplexedRequestContent multiplexedRequestContent = DataTemplateUtil.wrap(data, MultiplexedRequestContent.class);
    IndividualRequestMap individualRequests = multiplexedRequestContent.getRequests();
    int totalCount = totalRequestCount(individualRequests);
    if (totalCount == 0)
    {
      throw new IllegalArgumentException("No individual requests to process");
    }
    if (totalCount > _maximumRequestsNumber)
    {
      throw new IllegalArgumentException("The server is configured to serve up to " + _maximumRequestsNumber +
                                         " requests, but received " + totalCount);
    }
    return individualRequests;
  }

  private int totalRequestCount(IndividualRequestMap individualRequests)
  {
    int count = individualRequests.size();
    for (IndividualRequest individualRequest : individualRequests.values())
    {
      count += totalRequestCount(individualRequest.getDependentRequests());
    }
    return count;
  }

  private static void validateHeaders(RestRequest request)
  {
    boolean supported;
    try
    {
      supported = (ContentType.JSON == ContentTypeUtil.getContentType(request.getHeader(RestConstants.HEADER_CONTENT_TYPE)));
    }
    catch (MimeTypeParseException e)
    {
      throw new RestLiServiceException(HttpStatus.S_400_BAD_REQUEST, "Invalid content type");
    }

    if (!supported)
    {
      throw new RestLiServiceException(HttpStatus.S_415_UNSUPPORTED_MEDIA_TYPE, "Unsupported content type");
    }
  }

  private Task<Void> createParallelRequestsTask(RestRequest envelopeRequest,
                                                RequestContext requestContext,
                                                IndividualRequestMap individualRequests,
                                                IndividualResponseMap individualResponses,
                                                Map<String, HttpCookie> responseCookies)
  {
    List<Task<Void>> tasks = new ArrayList<Task<Void>>(individualRequests.size());
    for (IndividualRequestMap.Entry<String, IndividualRequest> individualRequestMapEntry : individualRequests.entrySet())
    {
      String id = individualRequestMapEntry.getKey();
      IndividualRequest individualRequest = individualRequestMapEntry.getValue();
      // create a task for the current request
      Task<Void> individualRequestTask = createRequestHandlingTask(id, envelopeRequest, requestContext, individualRequest, individualResponses, responseCookies);
      IndividualRequestMap dependentRequests = individualRequest.getDependentRequests();
      if (dependentRequests.isEmpty())
      {
        tasks.add(individualRequestTask);
      }
      else
      {
        // recursively process dependent requests
        Task<Void> dependentRequestsTask = createParallelRequestsTask(envelopeRequest, requestContext, dependentRequests, individualResponses, responseCookies);
        // tasks for dependant requests are executed after the current request's task
        tasks.add(Tasks.seq(individualRequestTask, dependentRequestsTask));
      }
    }
    return toVoid(Tasks.par(tasks));
  }

  private Task<Void> createRequestHandlingTask(final String id,
                                               final RestRequest envelopeRequest,
                                               final RequestContext requestContext,
                                               final IndividualRequest individualRequest,
                                               final IndividualResponseMap individualResponses,
                                               final Map<String, HttpCookie> responseCookies)
  {
    final RequestSanitizationTask requestSanitizationTask = new RequestSanitizationTask(individualRequest, _individualRequestHeaderWhitelist);
    final InheritEnvelopeRequestTask inheritEnvelopeRequestTask = new InheritEnvelopeRequestTask(envelopeRequest, requestSanitizationTask);
    final RequestFilterTask requestFilterTask = new RequestFilterTask(_multiplexerSingletonFilter, inheritEnvelopeRequestTask);
    final SyntheticRequestCreationTask syntheticRequestCreationTask = new SyntheticRequestCreationTask(id, envelopeRequest, requestFilterTask);
    final RequestHandlingTask requestHandlingTask = new RequestHandlingTask(_requestHandler, syntheticRequestCreationTask, requestContext);
    final IndividualResponseConversionTask toIndividualResponseTask = new IndividualResponseConversionTask(id, requestHandlingTask);
    final ResponseFilterTask responseFilterTask = new ResponseFilterTask(_multiplexerSingletonFilter, toIndividualResponseTask);
    final Task<Void> addResponseTask = Tasks.action("add response", new Runnable()
    {
      @Override
      public void run()
      {
        IndividualResponseWithCookies individualResponseWithCookies = responseFilterTask.get();
        individualResponses.put(id, individualResponseWithCookies.getIndividualResponse());
        addResponseCookies(responseCookies, individualResponseWithCookies.getCookies());
      }
    });
    return Tasks.seq(
      requestSanitizationTask,
      inheritEnvelopeRequestTask,
      requestFilterTask,
      syntheticRequestCreationTask,
      requestHandlingTask,
      toIndividualResponseTask,
      responseFilterTask,
      addResponseTask);
  }

  private static void addResponseCookies(Map<String, HttpCookie> responseCookies, List<String> setCookieHeaders)
  {
    List<HttpCookie> newCookies = CookieUtil.decodeSetCookies(setCookieHeaders);

    for (HttpCookie newCookie: newCookies)
    {
      // Two cookies are the same if its name, path, and domain are identical.
      String key = newCookie.getName() + ";"
                   + ((newCookie.getDomain() != null) ? newCookie.getDomain().toLowerCase() : "") + ";"
                   + ((newCookie.getPath() != null) ? newCookie.getPath() : "");
      responseCookies.put(key, newCookie);
    }
  }

  private static RestResponse aggregateResponses(IndividualResponseMap responses, Map<String, HttpCookie> responseCookies)
  {
    MultiplexedResponseContent aggregatedResponseContent = new MultiplexedResponseContent();
    aggregatedResponseContent.setResponses(responses);
    byte[] aggregatedResponseData = DataMapUtils.mapToBytes(aggregatedResponseContent.data());
    return new RestResponseBuilder()
        .setStatus(HttpStatus.S_200_OK.getCode())
        .setEntity(aggregatedResponseData)
        .setCookies(CookieUtil.encodeSetCookies(new ArrayList(responseCookies.values())))
      .build();
  }

  /**
   * Converts a Task<List<Void>> into a Task<Void>. That is a hack to make the type system happy.
   * This method adds an unneeded empty task to the execution plan.
   */
  private static Task<Void> toVoid(Task<List<Void>> task)
  {
    Task<Void> doNothingTask = Tasks.action("do nothing", new Runnable()
    {
      @Override
      public void run()
      {
        // seriously, nothing
      }
    });
    return Tasks.seq(task, doNothingTask);
  }
}