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
package org.apache.pulsar.functions.worker.rest.api;

import org.apache.distributedlog.api.namespace.Namespace;
import org.apache.pulsar.client.admin.Namespaces;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.Tenants;
import org.apache.pulsar.common.functions.FunctionConfig;
import org.apache.pulsar.common.policies.data.FunctionStats;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.apache.pulsar.functions.api.Context;
import org.apache.pulsar.functions.instance.InstanceConfig;
import org.apache.pulsar.functions.instance.InstanceUtils;
import org.apache.pulsar.functions.instance.JavaInstanceRunnable;
import org.apache.pulsar.functions.proto.Function;
import org.apache.pulsar.functions.proto.InstanceCommunication;
import org.apache.pulsar.functions.runtime.Runtime;
import org.apache.pulsar.functions.runtime.RuntimeFactory;
import org.apache.pulsar.functions.runtime.RuntimeSpawner;
import org.apache.pulsar.functions.source.TopicSchema;
import org.apache.pulsar.functions.utils.ComponentType;
import org.apache.pulsar.functions.utils.FunctionConfigUtils;
import org.apache.pulsar.functions.worker.FunctionMetaDataManager;
import org.apache.pulsar.functions.worker.FunctionRuntimeInfo;
import org.apache.pulsar.functions.worker.FunctionRuntimeManager;
import org.apache.pulsar.functions.worker.WorkerUtils;
import org.apache.pulsar.functions.worker.WorkerConfig;
import org.apache.pulsar.functions.worker.WorkerService;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.testng.Assert;
import org.testng.IObjectFactory;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.ObjectFactory;
import org.testng.annotations.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.apache.pulsar.functions.utils.ComponentType.FUNCTION;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.doReturn;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

@PrepareForTest({WorkerUtils.class, InstanceUtils.class})
@PowerMockIgnore({ "javax.management.*", "javax.ws.*", "org.apache.logging.log4j.*" })
public class FunctionsImplTest {

    @ObjectFactory
    public IObjectFactory getObjectFactory() {
        return new org.powermock.modules.testng.PowerMockObjectFactory();
    }

    private static final class TestFunction implements org.apache.pulsar.functions.api.Function<String, String> {

        @Override
        public String process(String input, Context context) {
            return input;
        }
    }

    private static final String tenant = "test-tenant";
    private static final String namespace = "test-namespace";
    private static final String function = "test-function";
    private static final String outputTopic = "test-output-topic";
    private static final String outputSerdeClassName = TopicSchema.DEFAULT_SERDE;
    private static final String className = TestFunction.class.getName();
    private Function.SubscriptionType subscriptionType = Function.SubscriptionType.FAILOVER;
    private static final Map<String, String> topicsToSerDeClassName = new HashMap<>();
    static {
        topicsToSerDeClassName.put("persistent://sample/standalone/ns1/test_src", TopicSchema.DEFAULT_SERDE);
    }
    private static final int parallelism = 1;
    private static final String workerId = "worker-0";

    private WorkerService mockedWorkerService;
    private PulsarAdmin mockedPulsarAdmin;
    private Tenants mockedTenants;
    private Namespaces mockedNamespaces;
    private TenantInfo mockedTenantInfo;
    private List<String> namespaceList = new LinkedList<>();
    private FunctionMetaDataManager mockedManager;
    private FunctionRuntimeManager mockedFunctionRunTimeManager;
    private RuntimeFactory mockedRuntimeFactory;
    private Namespace mockedNamespace;
    private FunctionsImpl resource;
    private InputStream mockedInputStream;
    private FormDataContentDisposition mockedFormData;
    private Function.FunctionMetaData mockedFunctionMetadata;

    @BeforeMethod
    public void setup() throws Exception {
        this.mockedManager = mock(FunctionMetaDataManager.class);
        this.mockedFunctionRunTimeManager = mock(FunctionRuntimeManager.class);
        this.mockedTenantInfo = mock(TenantInfo.class);
        this.mockedRuntimeFactory = mock(RuntimeFactory.class);
        this.mockedInputStream = mock(InputStream.class);
        this.mockedNamespace = mock(Namespace.class);
        this.mockedFormData = mock(FormDataContentDisposition.class);
        when(mockedFormData.getFileName()).thenReturn("test");
        this.mockedPulsarAdmin = mock(PulsarAdmin.class);
        this.mockedTenants = mock(Tenants.class);
        this.mockedNamespaces = mock(Namespaces.class);
        this.mockedFunctionMetadata = Function.FunctionMetaData.newBuilder().setFunctionDetails(createDefaultFunctionDetails()).build();
        namespaceList.add(tenant + "/" + namespace);

        this.mockedWorkerService = mock(WorkerService.class);
        when(mockedWorkerService.getFunctionMetaDataManager()).thenReturn(mockedManager);
        when(mockedWorkerService.getFunctionRuntimeManager()).thenReturn(mockedFunctionRunTimeManager);
        when(mockedFunctionRunTimeManager.getRuntimeFactory()).thenReturn(mockedRuntimeFactory);
        when(mockedWorkerService.getDlogNamespace()).thenReturn(mockedNamespace);
        when(mockedWorkerService.isInitialized()).thenReturn(true);
        when(mockedWorkerService.getBrokerAdmin()).thenReturn(mockedPulsarAdmin);
        when(mockedPulsarAdmin.tenants()).thenReturn(mockedTenants);
        when(mockedPulsarAdmin.namespaces()).thenReturn(mockedNamespaces);
        when(mockedTenants.getTenantInfo(any())).thenReturn(mockedTenantInfo);
        when(mockedNamespaces.getNamespaces(any())).thenReturn(namespaceList);
        when(mockedManager.getFunctionMetaData(any(), any(), any())).thenReturn(mockedFunctionMetadata);
        when(mockedManager.containsFunction(tenant, namespace, function)).thenReturn(true);
        when(mockedFunctionRunTimeManager.findFunctionAssignment(eq(tenant), eq(namespace), eq(function), anyInt()))
                .thenReturn(Function.Assignment.newBuilder()
                        .setWorkerId(workerId)
                        .build());

        Function.FunctionDetails.Builder functionDetailsBuilder =  createDefaultFunctionDetails().toBuilder();
        InstanceConfig instanceConfig = new InstanceConfig();
        instanceConfig.setFunctionDetails(functionDetailsBuilder.build());
        instanceConfig.setMaxBufferedTuples(1024);

        JavaInstanceRunnable javaInstanceRunnable = new JavaInstanceRunnable(
                instanceConfig, null, null, null, null, null, null);
        CompletableFuture<InstanceCommunication.MetricsData> metricsDataCompletableFuture = new CompletableFuture<InstanceCommunication.MetricsData>();
        metricsDataCompletableFuture.complete(javaInstanceRunnable.getMetrics());
        Runtime runtime = mock(Runtime.class);
        doReturn(metricsDataCompletableFuture).when(runtime).getMetrics(anyInt());

        CompletableFuture<InstanceCommunication.FunctionStatus> functionStatusCompletableFuture = new CompletableFuture<>();
        functionStatusCompletableFuture.complete(javaInstanceRunnable.getFunctionStatus().build());

        RuntimeSpawner runtimeSpawner = mock(RuntimeSpawner.class);
        when(runtimeSpawner.getFunctionStatus(anyInt())).thenReturn(functionStatusCompletableFuture);
        doReturn(runtime).when(runtimeSpawner).getRuntime();

        FunctionRuntimeInfo functionRuntimeInfo = mock(FunctionRuntimeInfo.class);
        doReturn(runtimeSpawner).when(functionRuntimeInfo).getRuntimeSpawner();

        when(mockedFunctionRunTimeManager.getFunctionRuntimeInfo(any())).thenReturn(functionRuntimeInfo);

        // worker config
        WorkerConfig workerConfig = new WorkerConfig()
                .setWorkerId(workerId)
                .setWorkerPort(8080)
                .setDownloadDirectory("/tmp/pulsar/functions")
                .setFunctionMetadataTopicName("pulsar/functions")
                .setNumFunctionPackageReplicas(3)
                .setPulsarServiceUrl("pulsar://localhost:6650/");
        when(mockedWorkerService.getWorkerConfig()).thenReturn(workerConfig);

        this.resource = spy(new FunctionsImpl(() -> mockedWorkerService));

        mockStatic(InstanceUtils.class);
        PowerMockito.when(InstanceUtils.calculateSubjectType(any())).thenReturn(ComponentType.FUNCTION);    }

    @Test
    public void testStatusEmpty() {
        Assert.assertTrue(this.resource.getFunctionInstanceStatus(tenant, namespace, function, "0", null, null, null) !=null);
    }

    @Test
    public void testMetricsEmpty() {
        Function.FunctionDetails.Builder functionDetailsBuilder =  createDefaultFunctionDetails().toBuilder();
        InstanceConfig instanceConfig = new InstanceConfig();
        instanceConfig.setFunctionDetails(functionDetailsBuilder.build());
        instanceConfig.setMaxBufferedTuples(1024);

        JavaInstanceRunnable javaInstanceRunnable = new JavaInstanceRunnable(
                instanceConfig, null, null, null, null, null, null);
        CompletableFuture<InstanceCommunication.MetricsData> completableFuture = new CompletableFuture<InstanceCommunication.MetricsData>();
        completableFuture.complete(javaInstanceRunnable.getMetrics());
        Runtime runtime = mock(Runtime.class);
        doReturn(completableFuture).when(runtime).getMetrics(anyInt());
        RuntimeSpawner runtimeSpawner = mock(RuntimeSpawner.class);
        doReturn(runtime).when(runtimeSpawner).getRuntime();

        FunctionRuntimeInfo functionRuntimeInfo = mock(FunctionRuntimeInfo.class);
        doReturn(runtimeSpawner).when(functionRuntimeInfo).getRuntimeSpawner();

        FunctionStats.FunctionInstanceStats instanceStats1 = WorkerUtils
                .getFunctionInstanceStats("public/default/test", functionRuntimeInfo, 0);
        FunctionStats.FunctionInstanceStats instanceStats2 = WorkerUtils
                .getFunctionInstanceStats("public/default/test", functionRuntimeInfo, 1);

        FunctionStats functionStats = new FunctionStats();
        functionStats.addInstance(instanceStats1);
        functionStats.addInstance(instanceStats2);

        Assert.assertTrue(functionStats.calculateOverall() != null);
    }

    public static FunctionConfig createDefaultFunctionConfig() {
        FunctionConfig functionConfig = new FunctionConfig();
        functionConfig.setTenant(tenant);
        functionConfig.setNamespace(namespace);
        functionConfig.setName(function);
        functionConfig.setClassName(className);
        functionConfig.setParallelism(parallelism);
        functionConfig.setCustomSerdeInputs(topicsToSerDeClassName);
        functionConfig.setOutput(outputTopic);
        functionConfig.setOutputSerdeClassName(outputSerdeClassName);
        functionConfig.setRuntime(FunctionConfig.Runtime.JAVA);
        return functionConfig;
    }

    public static Function.FunctionDetails createDefaultFunctionDetails() {
        FunctionConfig functionConfig = createDefaultFunctionConfig();
        return FunctionConfigUtils.convert(functionConfig, null);
    }
}
