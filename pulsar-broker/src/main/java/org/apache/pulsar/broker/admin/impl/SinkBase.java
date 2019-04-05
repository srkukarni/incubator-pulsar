/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.admin.impl;

import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.apache.commons.lang.StringUtils;
import org.apache.pulsar.broker.admin.AdminResource;
import org.apache.pulsar.common.io.ConnectorDefinition;
import org.apache.pulsar.common.io.SinkConfig;
import org.apache.pulsar.common.policies.data.SinkStatus;
import org.apache.pulsar.functions.proto.Function.FunctionMetaData;
import org.apache.pulsar.functions.worker.WorkerService;
import org.apache.pulsar.functions.worker.rest.api.SinkImpl;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class SinkBase extends AdminResource implements Supplier<WorkerService> {

    private final SinkImpl sink;

    public SinkBase() {
        this.sink = new SinkImpl(this);
    }

    @Override
    public WorkerService get() {
        return pulsar().getWorkerService();
    }

    @POST
    @ApiOperation(value = "Creates a new Pulsar Sink in cluster mode")
    @ApiResponses(value = {
            @ApiResponse(code = 403, message = "The requester doesn't have admin permissions"),
            @ApiResponse(code = 400, message = "Invalid request (function already exists, etc.)"),
            @ApiResponse(code = 408, message = "Request timeout"),
            @ApiResponse(code = 200, message = "Pulsar Function successfully created")
    })
    @Path("/{tenant}/{namespace}/{sinkName}")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public void registerSink(final @PathParam("tenant") String tenant,
                             final @PathParam("namespace") String namespace,
                             final @PathParam("sinkName") String sinkName,
                             final @FormDataParam("data") InputStream uploadedInputStream,
                             final @FormDataParam("data") FormDataContentDisposition fileDetail,
                             final @FormDataParam("url") String functionPkgUrl,
                             final @FormDataParam("sinkConfig") String sinkConfigJson) {

        sink.registerFunction(tenant, namespace, sinkName, uploadedInputStream, fileDetail,
                functionPkgUrl, sinkConfigJson, clientAppId(), clientAuthData());
    }

    @PUT
    @ApiOperation(value = "Updates a Pulsar Sink currently running in cluster mode")
    @ApiResponses(value = {
            @ApiResponse(code = 403, message = "The requester doesn't have admin permissions"),
            @ApiResponse(code = 400, message = "Invalid request (function doesn't exist, etc.)"),
            @ApiResponse(code = 200, message = "Pulsar Function successfully updated")
    })
    @Path("/{tenant}/{namespace}/{sinkName}")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public void updateSink(final @PathParam("tenant") String tenant,
                           final @PathParam("namespace") String namespace,
                           final @PathParam("sinkName") String sinkName,
                           final @FormDataParam("data") InputStream uploadedInputStream,
                           final @FormDataParam("data") FormDataContentDisposition fileDetail,
                           final @FormDataParam("url") String functionPkgUrl,
                           final @FormDataParam("sinkConfig") String sinkConfigJson) {

         sink.updateFunction(tenant, namespace, sinkName, uploadedInputStream, fileDetail,
                functionPkgUrl, sinkConfigJson, clientAppId(), clientAuthData());

    }


    @DELETE
    @ApiOperation(value = "Deletes a Pulsar Sink currently running in cluster mode")
    @ApiResponses(value = {
            @ApiResponse(code = 403, message = "The requester doesn't have admin permissions"),
            @ApiResponse(code = 400, message = "Invalid request"),
            @ApiResponse(code = 404, message = "The function doesn't exist"),
            @ApiResponse(code = 408, message = "Request timeout"),
            @ApiResponse(code = 200, message = "The function was successfully deleted")
    })
    @Path("/{tenant}/{namespace}/{sinkName}")
    public void deregisterSink(final @PathParam("tenant") String tenant,
                               final @PathParam("namespace") String namespace,
                               final @PathParam("sinkName") String sinkName) {
        sink.deregisterFunction(tenant, namespace, sinkName, clientAppId(), clientAuthData());
    }

    @GET
    @ApiOperation(
            value = "Fetches information about a Pulsar Sink currently running in cluster mode",
            response = FunctionMetaData.class
    )
    @ApiResponses(value = {
            @ApiResponse(code = 403, message = "The requester doesn't have admin permissions"),
            @ApiResponse(code = 400, message = "Invalid request"),
            @ApiResponse(code = 408, message = "Request timeout"),
            @ApiResponse(code = 404, message = "The function doesn't exist")
    })
    @Path("/{tenant}/{namespace}/{sinkName}")
    public SinkConfig getSinkInfo(final @PathParam("tenant") String tenant,
                                  final @PathParam("namespace") String namespace,
                                  final @PathParam("sinkName") String sinkName) throws IOException {
        return sink.getSinkInfo(tenant, namespace, sinkName);
    }

    @GET
    @ApiOperation(
            value = "Displays the status of a Pulsar Sink instance",
            response = SinkStatus.SinkInstanceStatus.SinkInstanceStatusData.class
    )
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Invalid request"),
            @ApiResponse(code = 403, message = "The requester doesn't have admin permissions"),
            @ApiResponse(code = 404, message = "The sink doesn't exist")
    })
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{tenant}/{namespace}/{sinkName}/{instanceId}/status")
    public SinkStatus.SinkInstanceStatus.SinkInstanceStatusData getSinkInstanceStatus(
            final @PathParam("tenant") String tenant,
            final @PathParam("namespace") String namespace,
            final @PathParam("sinkName") String sinkName,
            final @PathParam("instanceId") String instanceId) throws IOException {
        return sink.getSinkInstanceStatus(
            tenant, namespace, sinkName, instanceId, uri.getRequestUri(), clientAppId(), clientAuthData());
    }

    @GET
    @ApiOperation(
            value = "Displays the status of a Pulsar Sink running in cluster mode",
            response = SinkStatus.class
    )
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Invalid request"),
            @ApiResponse(code = 403, message = "The requester doesn't have admin permissions"),
            @ApiResponse(code = 404, message = "The sink doesn't exist")
    })
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{tenant}/{namespace}/{sinkName}/status")
    public SinkStatus getSinkStatus(final @PathParam("tenant") String tenant,
                                    final @PathParam("namespace") String namespace,
                                    final @PathParam("sinkName") String sinkName) throws IOException {
        return sink.getSinkStatus(tenant, namespace, sinkName, uri.getRequestUri(), clientAppId(), clientAuthData());
    }

    @GET
    @ApiOperation(
            value = "Lists all Pulsar Sinks currently deployed in a given namespace",
            response = String.class,
            responseContainer = "Collection"
    )
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Invalid request"),
            @ApiResponse(code = 403, message = "The requester doesn't have admin permissions")
    })
    @Path("/{tenant}/{namespace}")
    public List<String> listSinks(final @PathParam("tenant") String tenant,
                                  final @PathParam("namespace") String namespace) {
        return sink.listFunctions(tenant, namespace, clientAppId(), clientAuthData());
    }

    @POST
    @ApiOperation(value = "Restart sink instance", response = Void.class)
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Invalid request"),
            @ApiResponse(code = 404, message = "The function does not exist"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    @Path("/{tenant}/{namespace}/{sinkName}/{instanceId}/restart")
    @Consumes(MediaType.APPLICATION_JSON)
    public void restartSink(final @PathParam("tenant") String tenant,
                            final @PathParam("namespace") String namespace,
                            final @PathParam("sinkName") String sinkName,
                            final @PathParam("instanceId") String instanceId) {
        sink.restartFunctionInstance(tenant, namespace, sinkName, instanceId, uri.getRequestUri(), clientAppId(), clientAuthData());
    }

    @POST
    @ApiOperation(value = "Restart all sink instances", response = Void.class)
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Invalid request"),
            @ApiResponse(code = 404, message = "The function does not exist"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    @Path("/{tenant}/{namespace}/{sinkName}/restart")
    @Consumes(MediaType.APPLICATION_JSON)
    public void restartSink(final @PathParam("tenant") String tenant,
                            final @PathParam("namespace") String namespace,
                            final @PathParam("sinkName") String sinkName) {
        sink.restartFunctionInstances(tenant, namespace, sinkName, clientAppId(), clientAuthData());
    }

    @POST
    @ApiOperation(value = "Stop sink instance", response = Void.class)
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Invalid request"),
            @ApiResponse(code = 404, message = "The function does not exist"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    @Path("/{tenant}/{namespace}/{sinkName}/{instanceId}/stop")
    @Consumes(MediaType.APPLICATION_JSON)
    public void stopSink(final @PathParam("tenant") String tenant,
                         final @PathParam("namespace") String namespace,
                         final @PathParam("sinkName") String sinkName,
                         final @PathParam("instanceId") String instanceId) {
        sink.stopFunctionInstance(tenant, namespace, sinkName, instanceId, uri.getRequestUri(), clientAppId(), clientAuthData());
    }

    @POST
    @ApiOperation(value = "Stop all sink instances", response = Void.class)
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Invalid request"),
            @ApiResponse(code = 404, message = "The function does not exist"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    @Path("/{tenant}/{namespace}/{sinkName}/stop")
    @Consumes(MediaType.APPLICATION_JSON)
    public void stopSink(final @PathParam("tenant") String tenant,
                         final @PathParam("namespace") String namespace,
                         final @PathParam("sinkName") String sinkName) {
        sink.stopFunctionInstances(tenant, namespace, sinkName, clientAppId(), clientAuthData());
    }

    @POST
    @ApiOperation(value = "Start sink instance", response = Void.class)
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Invalid request"),
            @ApiResponse(code = 404, message = "The function does not exist"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    @Path("/{tenant}/{namespace}/{sinkName}/{instanceId}/start")
    @Consumes(MediaType.APPLICATION_JSON)
    public void startSink(final @PathParam("tenant") String tenant,
                          final @PathParam("namespace") String namespace,
                          final @PathParam("sinkName") String sinkName,
                          final @PathParam("instanceId") String instanceId) {
        sink.startFunctionInstance(tenant, namespace, sinkName, instanceId, uri.getRequestUri(), clientAppId(), clientAuthData());
    }

    @POST
    @ApiOperation(value = "Start all sink instances", response = Void.class)
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Invalid request"),
            @ApiResponse(code = 404, message = "The function does not exist"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    @Path("/{tenant}/{namespace}/{sinkName}/start")
    @Consumes(MediaType.APPLICATION_JSON)
    public void startSink(final @PathParam("tenant") String tenant,
                          final @PathParam("namespace") String namespace,
                          final @PathParam("sinkName") String sinkName) {
        sink.startFunctionInstances(tenant, namespace, sinkName, clientAppId(), clientAuthData());
    }

    @GET
    @ApiOperation(
            value = "Fetches a list of supported Pulsar IO sink connectors currently running in cluster mode",
            response = List.class
    )
    @ApiResponses(value = {
            @ApiResponse(code = 403, message = "The requester doesn't have admin permissions"),
            @ApiResponse(code = 400, message = "Invalid request"),
            @ApiResponse(code = 408, message = "Request timeout")
    })
    @Path("/builtinsinks")
    public List<ConnectorDefinition> getSinkList() {
        List<ConnectorDefinition> connectorDefinitions = sink.getListOfConnectors();
        List<ConnectorDefinition> retval = new ArrayList<>();
        for (ConnectorDefinition connectorDefinition : connectorDefinitions) {
            if (!StringUtils.isEmpty(connectorDefinition.getSinkClass())) {
                retval.add(connectorDefinition);
            }
        }
        return retval;
    }
}
