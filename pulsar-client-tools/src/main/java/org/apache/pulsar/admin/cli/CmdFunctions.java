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
package org.apache.pulsar.admin.cli;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.isNull;
import static org.apache.bookkeeper.common.concurrent.FutureUtils.result;
import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.apache.pulsar.common.naming.TopicName.DEFAULT_NAMESPACE;
import static org.apache.pulsar.common.naming.TopicName.PUBLIC_TENANT;
import static org.apache.pulsar.functions.utils.Utils.fileExists;
import static org.apache.pulsar.functions.worker.Utils.downloadFromHttpUrl;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.converters.StringConverter;
import com.google.common.annotations.VisibleForTesting;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.apache.bookkeeper.api.StorageClient;
import org.apache.bookkeeper.api.kv.Table;
import org.apache.bookkeeper.api.kv.result.KeyValue;
import org.apache.bookkeeper.clients.StorageClientBuilder;
import org.apache.bookkeeper.clients.config.StorageClientSettings;
import org.apache.commons.lang.StringUtils;
import org.apache.pulsar.admin.cli.utils.CmdUtils;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.internal.FunctionsImpl;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.functions.api.Function;
import org.apache.pulsar.functions.instance.AuthenticationConfig;
import org.apache.pulsar.functions.instance.InstanceConfig;
import org.apache.pulsar.functions.proto.Function.FunctionDetails;
import org.apache.pulsar.functions.proto.Function.Resources;
import org.apache.pulsar.functions.proto.Function.SinkSpec;
import org.apache.pulsar.functions.proto.Function.SourceSpec;
import org.apache.pulsar.functions.proto.Function.SubscriptionType;
import org.apache.pulsar.functions.runtime.ProcessRuntimeFactory;
import org.apache.pulsar.functions.runtime.RuntimeSpawner;
import org.apache.pulsar.functions.utils.FunctionConfig;
import org.apache.pulsar.functions.utils.Reflections;
import org.apache.pulsar.functions.utils.Utils;
import org.apache.pulsar.functions.utils.WindowConfig;
import org.apache.pulsar.functions.utils.FunctionConfig.ProcessingGuarantees;
import org.apache.pulsar.functions.utils.validation.ConfigValidation;
import org.apache.pulsar.functions.utils.validation.ValidatorImpls.ImplementsClassesValidator;
import org.apache.pulsar.functions.windowing.WindowFunctionExecutor;
import org.apache.pulsar.functions.windowing.WindowUtils;

@Slf4j
@Parameters(commandDescription = "Interface for managing Pulsar Functions (lightweight, Lambda-style compute processes that work with Pulsar)")
public class CmdFunctions extends CmdBase {
    private static final String DEFAULT_SERVICE_URL = "pulsar://localhost:6650";

    private final LocalRunner localRunner;
    private final CreateFunction creater;
    private final DeleteFunction deleter;
    private final UpdateFunction updater;
    private final GetFunction getter;
    private final GetFunctionStatus statuser;
    private final ListFunctions lister;
    private final StateGetter stateGetter;
    private final TriggerFunction triggerer;
    private final UploadFunction uploader;
    private final DownloadFunction downloader;
    private final GetCluster cluster;

    /**
     * Base command
     */
    @Getter
    abstract class BaseCommand extends CliCommand {
        @Override
        void run() throws Exception {
            processArguments();
            runCmd();
        }

        void processArguments() throws Exception {}

        abstract void runCmd() throws Exception;
    }

    /**
     * Namespace level command
     */
    @Getter
    abstract class NamespaceCommand extends BaseCommand {
        @Parameter(names = "--tenant", description = "The function's tenant")
        protected String tenant;

        @Parameter(names = "--namespace", description = "The function's namespace")
        protected String namespace;

        @Override
        public void processArguments() {
            if (tenant == null) {
                tenant = PUBLIC_TENANT;
            }
            if (namespace == null) {
                namespace = DEFAULT_NAMESPACE;
            }
        }
    }

    /**
     * Function level command
     */
    @Getter
    abstract class FunctionCommand extends BaseCommand {
        @Parameter(names = "--fqfn", description = "The Fully Qualified Function Name (FQFN) for the function")
        protected String fqfn;

        @Parameter(names = "--tenant", description = "The function's tenant")
        protected String tenant;

        @Parameter(names = "--namespace", description = "The function's namespace")
        protected String namespace;

        @Parameter(names = "--name", description = "The function's name")
        protected String functionName;

        @Override
        void processArguments() throws Exception {
            super.processArguments();

            boolean usesSetters = (null != tenant || null != namespace || null != functionName);
            boolean usesFqfn = (null != fqfn);

            // Throw an exception if --fqfn is set alongside any combination of --tenant, --namespace, and --name
            if (usesFqfn && usesSetters) {
                throw new RuntimeException(
                        "You must specify either a Fully Qualified Function Name (FQFN) or tenant, namespace, and function name");
            } else if (usesFqfn) {
                // If the --fqfn flag is used, parse tenant, namespace, and name using that flag
                String[] fqfnParts = fqfn.split("/");
                if (fqfnParts.length != 3) {
                    throw new RuntimeException(
                            "Fully qualified function names (FQFNs) must be of the form tenant/namespace/name");
                }
                tenant = fqfnParts[0];
                namespace = fqfnParts[1];
                functionName = fqfnParts[2];
            } else {
                if (tenant == null) {
                    tenant = PUBLIC_TENANT;
                }
                if (namespace == null) {
                    namespace = DEFAULT_NAMESPACE;
                }
                if (null == functionName) {
                    throw new RuntimeException(
                            "You must specify a name for the function or a Fully Qualified Function Name (FQFN)");
                }
            }
        }
    }

    /**
     * Commands that require a function config
     */
    @Getter
    abstract class FunctionDetailsCommand extends BaseCommand {
        @Parameter(names = "--fqfn", description = "The Fully Qualified Function Name (FQFN) for the function")
        protected String fqfn;
        @Parameter(names = "--tenant", description = "The function's tenant")
        protected String tenant;
        @Parameter(names = "--namespace", description = "The function's namespace")
        protected String namespace;
        @Parameter(names = "--name", description = "The function's name")
        protected String functionName;
        // for backwards compatibility purposes
        @Parameter(names = "--className", description = "The function's class name", hidden = true)
        protected String DEPRECATED_className;
        @Parameter(names = "--classname", description = "The function's class name")
        protected String className;
        @Parameter(names = "--jar", description = "Path to the jar file for the function (if the function is written in Java). It also supports url-path [http/https/file (file protocol assumes that file already exists on worker host)] from which worker can download the package.", listConverter = StringConverter.class)
        protected String jarFile;
        @Parameter(
                names = "--py",
                description = "Path to the main Python file for the function (if the function is written in Python)",
                listConverter = StringConverter.class)
        protected String pyFile;
        @Parameter(names = "--inputs", description = "The function's input topic or topics (multiple topics can be specified as a comma-separated list)")
        protected String inputs;
        // for backwards compatibility purposes
        @Parameter(names = "--topicsPattern", description = "TopicsPattern to consume from list of topics under a namespace that match the pattern. [--input] and [--topic-pattern] are mutually exclusive. Add SerDe class name for a pattern in --custom-serde-inputs (supported for java fun only)", hidden = true)
        protected String DEPRECATED_topicsPattern;
        @Parameter(names = "--topics-pattern", description = "The topic pattern to consume from list of topics under a namespace that match the pattern. [--input] and [--topic-pattern] are mutually exclusive. Add SerDe class name for a pattern in --custom-serde-inputs (supported for java fun only)")
        protected String topicsPattern;
        @Parameter(names = "--output", description = "The function's output topic (use skipOutput flag to skip output topic)")
        protected String output;
        @Parameter(names = "--skip-output", description = "Skip publishing function output to output topic")
        protected boolean skipOutput;
        // for backwards compatibility purposes
        @Parameter(names = "--logTopic", description = "The topic to which the function's logs are produced", hidden = true)
        protected String DEPRECATED_logTopic;
        @Parameter(names = "--log-topic", description = "The topic to which the function's logs are produced")
        protected String logTopic;
        // for backwards compatibility purposes
        @Parameter(names = "--customSerdeInputs", description = "The map of input topics to SerDe class names (as a JSON string)", hidden = true)
        protected String DEPRECATED_customSerdeInputString;
        @Parameter(names = "--custom-serde-inputs", description = "The map of input topics to SerDe class names (as a JSON string)")
        protected String customSerdeInputString;
        // for backwards compatibility purposes
        @Parameter(names = "--outputSerdeClassName", description = "The SerDe class to be used for messages output by the function", hidden = true)
        protected String DEPRECATED_outputSerdeClassName;
        @Parameter(names = "--output-serde-classname", description = "The SerDe class to be used for messages output by the function")
        protected String outputSerdeClassName;
        // for backwards compatibility purposes
        @Parameter(names = "--functionConfigFile", description = "The path to a YAML config file specifying the function's configuration", hidden = true)
        protected String DEPRECATED_fnConfigFile;
        @Parameter(names = "--function-config-file", description = "The path to a YAML config file specifying the function's configuration")
        protected String fnConfigFile;
        // for backwards compatibility purposes
        @Parameter(names = "--processingGuarantees", description = "The processing guarantees (aka delivery semantics) applied to the function", hidden = true)
        protected FunctionConfig.ProcessingGuarantees DEPRECATED_processingGuarantees;
        @Parameter(names = "--processing-guarantees", description = "The processing guarantees (aka delivery semantics) applied to the function")
        protected FunctionConfig.ProcessingGuarantees processingGuarantees;
        // for backwards compatibility purposes
        @Parameter(names = "--userConfig", description = "User-defined config key/values", hidden = true)
        protected String DEPRECATED_userConfigString;
        @Parameter(names = "--user-config", description = "User-defined config key/values")
        protected String userConfigString;
        @Parameter(names = "--retainOrdering", description = "Function consumes and processes messages in order")
        protected boolean retainOrdering;
        @Parameter(names = "--parallelism", description = "The function's parallelism factor (i.e. the number of function instances to run)")
        protected Integer parallelism;
        @Parameter(names = "--cpu", description = "The cpu in cores that need to be allocated per function instance(applicable only to docker runtime)")
        protected Double cpu;
        @Parameter(names = "--ram", description = "The ram in bytes that need to be allocated per function instance(applicable only to process/docker runtime)")
        protected Long ram;
        @Parameter(names = "--disk", description = "The disk in bytes that need to be allocated per function instance(applicable only to docker runtime)")
        protected Long disk;
        // for backwards compatibility purposes
        @Parameter(names = "--windowLengthCount", description = "The number of messages per window", hidden = true)
        protected Integer DEPRECATED_windowLengthCount;
        @Parameter(names = "--window-length-count", description = "The number of messages per window")
        protected Integer windowLengthCount;
        // for backwards compatibility purposes
        @Parameter(names = "--windowLengthDurationMs", description = "The time duration of the window in milliseconds", hidden = true)
        protected Long DEPRECATED_windowLengthDurationMs;
        @Parameter(names = "--window-length-duration-ms", description = "The time duration of the window in milliseconds")
        protected Long windowLengthDurationMs;
        // for backwards compatibility purposes
        @Parameter(names = "--slidingIntervalCount", description = "The number of messages after which the window slides", hidden = true)
        protected Integer DEPRECATED_slidingIntervalCount;
        @Parameter(names = "--sliding-interval-count", description = "The number of messages after which the window slides")
        protected Integer slidingIntervalCount;
        // for backwards compatibility purposes
        @Parameter(names = "--slidingIntervalDurationMs", description = "The time duration after which the window slides", hidden = true)
        protected Long DEPRECATED_slidingIntervalDurationMs;
        @Parameter(names = "--sliding-interval-duration-ms", description = "The time duration after which the window slides")
        protected Long slidingIntervalDurationMs;
        // for backwards compatibility purposes
        @Parameter(names = "--autoAck", description = "Whether or not the framework will automatically acknowleges messages", hidden = true)
        protected Boolean DEPRECATED_autoAck = null;
        @Parameter(names = "--auto-ack", description = "Whether or not the framework will automatically acknowleges messages")
        protected Boolean autoAck ;
        // for backwards compatibility purposes
        @Parameter(names = "--timeoutMs", description = "The message timeout in milliseconds", hidden = true)
        protected Long DEPRECATED_timeoutMs;
        @Parameter(names = "--timeout-ms", description = "The message timeout in milliseconds")
        protected Long timeoutMs;
        protected FunctionConfig functionConfig;
        protected String userCodeFile;


        private void mergeArgs() {
            if (!StringUtils.isBlank(DEPRECATED_className)) className = DEPRECATED_className;
            if (!StringUtils.isBlank(DEPRECATED_topicsPattern)) topicsPattern = DEPRECATED_topicsPattern;
            if (!StringUtils.isBlank(DEPRECATED_logTopic)) logTopic = DEPRECATED_logTopic;
            if (!StringUtils.isBlank(DEPRECATED_outputSerdeClassName)) outputSerdeClassName = DEPRECATED_outputSerdeClassName;
            if (!StringUtils.isBlank(DEPRECATED_fnConfigFile)) fnConfigFile = DEPRECATED_fnConfigFile;
            if (DEPRECATED_processingGuarantees != FunctionConfig.ProcessingGuarantees.ATLEAST_ONCE) processingGuarantees = DEPRECATED_processingGuarantees;
            if (!StringUtils.isBlank(DEPRECATED_userConfigString)) userConfigString = DEPRECATED_userConfigString;
            if (DEPRECATED_windowLengthCount != null) windowLengthCount = DEPRECATED_windowLengthCount;
            if (DEPRECATED_windowLengthDurationMs != null) windowLengthDurationMs = DEPRECATED_windowLengthDurationMs;
            if (DEPRECATED_slidingIntervalCount != null) slidingIntervalCount = DEPRECATED_slidingIntervalCount;
            if (DEPRECATED_slidingIntervalDurationMs != null) slidingIntervalDurationMs = DEPRECATED_slidingIntervalDurationMs;
            if (DEPRECATED_autoAck != null) autoAck = DEPRECATED_autoAck;
            if (DEPRECATED_timeoutMs != null) timeoutMs = DEPRECATED_timeoutMs;
        }

        @Override
        void processArguments() throws Exception {
            super.processArguments();
            // merge deprecated args with new args
            mergeArgs();

            // Initialize config builder either from a supplied YAML config file or from scratch
            if (null != fnConfigFile) {
                functionConfig = CmdUtils.loadConfig(fnConfigFile, FunctionConfig.class);
            } else {
                functionConfig = new FunctionConfig();
            }

            if (null != fqfn) {
                parseFullyQualifiedFunctionName(fqfn, functionConfig);
            } else {
                if (null != tenant) {
                    functionConfig.setTenant(tenant);
                }
                if (null != namespace) {
                    functionConfig.setNamespace(namespace);
                }
                if (null != functionName) {
                    functionConfig.setName(functionName);
                }
            }

            if (null != inputs) {
                List<String> inputTopics = Arrays.asList(inputs.split(","));
                functionConfig.setInputs(inputTopics);
            }
            if (null != customSerdeInputString) {
                Type type = new TypeToken<Map<String, String>>(){}.getType();
                Map<String, String> customSerdeInputMap = new Gson().fromJson(customSerdeInputString, type);
                functionConfig.setCustomSerdeInputs(customSerdeInputMap);
            }
            if (null != topicsPattern) {
                functionConfig.setTopicsPattern(topicsPattern);
            }
            if (null != output) {
                functionConfig.setOutput(output);
            }
            functionConfig.setSkipOutput(skipOutput);
            if (null != logTopic) {
                functionConfig.setLogTopic(logTopic);
            }
            if (null != className) {
                functionConfig.setClassName(className);
            }
            if (null != outputSerdeClassName) {
                functionConfig.setOutputSerdeClassName(outputSerdeClassName);
            }
            if (null != processingGuarantees) {
                functionConfig.setProcessingGuarantees(processingGuarantees);
            }
            
            functionConfig.setRetainOrdering(retainOrdering);
            
            if (null != userConfigString) {
                Type type = new TypeToken<Map<String, String>>(){}.getType();
                Map<String, Object> userConfigMap = new Gson().fromJson(userConfigString, type);
                functionConfig.setUserConfig(userConfigMap);
            }
            if (functionConfig.getInputs() == null) {
                functionConfig.setInputs(new LinkedList<>());
            }
            if (functionConfig.getCustomSerdeInputs() == null) {
                functionConfig.setCustomSerdeInputs(new HashMap<>());
            }
            if (functionConfig.getUserConfig() == null) {
                functionConfig.setUserConfig(new HashMap<>());
            }

            if (parallelism != null) {
                functionConfig.setParallelism(parallelism);
            }

            functionConfig.setResources(new org.apache.pulsar.functions.utils.Resources(cpu, ram, disk));

            if (timeoutMs != null) {
                functionConfig.setTimeoutMs(timeoutMs);
            }

            // window configs
            WindowConfig windowConfig = functionConfig.getWindowConfig();
            if (null != windowLengthCount) {
                if (windowConfig == null) {
                    windowConfig = new WindowConfig();
                }
                windowConfig.setWindowLengthCount(windowLengthCount);
            }
            if (null != windowLengthDurationMs) {
                if (windowConfig == null) {
                    windowConfig = new WindowConfig();
                }
                windowConfig.setWindowLengthDurationMs(windowLengthDurationMs);
            }
            if (null != slidingIntervalCount) {
                if (windowConfig == null) {
                    windowConfig = new WindowConfig();
                }
                windowConfig.setSlidingIntervalCount(slidingIntervalCount);
            }
            if (null != slidingIntervalDurationMs) {
                if (windowConfig == null) {
                    windowConfig = new WindowConfig();
                }
                windowConfig.setSlidingIntervalDurationMs(slidingIntervalDurationMs);
            }

            functionConfig.setWindowConfig(windowConfig);

            if  (null != autoAck) {
                functionConfig.setAutoAck(autoAck);
            } else {
                functionConfig.setAutoAck(true);
            }

            if (null != jarFile) {
                functionConfig.setJar(jarFile);
            }

            if (null != pyFile) {
                functionConfig.setPy(pyFile);
            }

            if (functionConfig.getJar() != null) {
                userCodeFile = functionConfig.getJar();
            } else if (functionConfig.getPy() != null) {
                userCodeFile = functionConfig.getPy();
            }

            // infer default vaues
            inferMissingArguments(functionConfig);
        }

        protected void validateFunctionConfigs(FunctionConfig functionConfig) {
            
            if (isBlank(functionConfig.getOutput()) && !functionConfig.isSkipOutput()) {
                throw new ParameterException(
                        "output topic is not present (pass skipOutput flag to skip publish output on topic)");
            }

            if (isNotBlank(functionConfig.getJar()) && isNotBlank(functionConfig.getPy())) {
                throw new ParameterException("Either a Java jar or a Python file needs to"
                        + " be specified for the function. Cannot specify both.");
            }

            if (isBlank(functionConfig.getJar()) && isBlank(functionConfig.getPy())) {
                throw new ParameterException("Either a Java jar or a Python file needs to"
                        + " be specified for the function. Please specify one.");
            }

            boolean isJarPathUrl = isNotBlank(functionConfig.getJar()) && Utils.isFunctionPackageUrlSupported(functionConfig.getJar());
            String jarFilePath = null;
            if (isJarPathUrl) {
                if (functionConfig.getJar().startsWith(Utils.HTTP)) {
                    // download jar file if url is http or file is downloadable
                    File tempPkgFile = null;
                    try {
                        tempPkgFile = File.createTempFile(functionConfig.getName(), "function");
                        downloadFromHttpUrl(functionConfig.getJar(), new FileOutputStream(tempPkgFile));
                        jarFilePath = tempPkgFile.getAbsolutePath();
                    } catch (Exception e) {
                        if (tempPkgFile != null) {
                            tempPkgFile.deleteOnExit();
                        }
                        throw new ParameterException("Failed to download jar from " + functionConfig.getJar()
                                + ", due to =" + e.getMessage());
                    }
                }
            } else {
                if (!fileExists(userCodeFile)) {
                    throw new ParameterException("File " + userCodeFile + " does not exist");
                }
                jarFilePath = userCodeFile;
            }

            if (functionConfig.getRuntime() == FunctionConfig.Runtime.JAVA) {

                if (jarFilePath != null) {
                    File file = new File(jarFilePath);
                    ClassLoader userJarLoader;
                    try {
                        userJarLoader = Reflections.loadJar(file);
                    } catch (MalformedURLException e) {
                        throw new ParameterException(
                                "Failed to load user jar " + file + " with error " + e.getMessage());
                    }
                    // make sure the function class loader is accessible thread-locally
                    Thread.currentThread().setContextClassLoader(userJarLoader);

                    (new ImplementsClassesValidator(Function.class, java.util.function.Function.class))
                            .validateField("className", functionConfig.getClassName());
                }
            }

            try {
                // Need to load jar and set context class loader before calling
                ConfigValidation.validateConfig(functionConfig, functionConfig.getRuntime().name());
            } catch (Exception e) {
                throw new ParameterException(e.getMessage());
            }
        }

        private void inferMissingArguments(FunctionConfig functionConfig) {
            if (StringUtils.isEmpty(functionConfig.getName())) {
                inferMissingFunctionName(functionConfig);
            }
            if (StringUtils.isEmpty(functionConfig.getTenant())) {
                inferMissingTenant(functionConfig);
            }
            if (StringUtils.isEmpty(functionConfig.getNamespace())) {
                inferMissingNamespace(functionConfig);
            }

            if (functionConfig.getParallelism() == 0) {
                functionConfig.setParallelism(1);
            }

            if (functionConfig.getJar() != null) {
                functionConfig.setRuntime(FunctionConfig.Runtime.JAVA);
            } else if (functionConfig.getPy() != null) {
                functionConfig.setRuntime(FunctionConfig.Runtime.PYTHON);
            }

            WindowConfig windowConfig = functionConfig.getWindowConfig();
            if (windowConfig != null) {
                WindowUtils.inferDefaultConfigs(windowConfig);
                // set auto ack to false since windowing framework is responsible
                // for acking and not the function framework
                if (autoAck != null && autoAck == true) {
                    throw new ParameterException("Cannot enable auto ack when using windowing functionality");
                }
                functionConfig.setAutoAck(false);
            }
        }

        private void inferMissingFunctionName(FunctionConfig functionConfig) {
            if (isNull(functionConfig.getClassName())) {
                throw new ParameterException("You must specify a class name for the function");
            }

            String [] domains = functionConfig.getClassName().split("\\.");
            if (domains.length == 0) {
                functionConfig.setName(functionConfig.getClassName());
            } else {
                functionConfig.setName(domains[domains.length - 1]);
            }
        }

        private void inferMissingTenant(FunctionConfig functionConfig) {
            functionConfig.setTenant(PUBLIC_TENANT);
        }

        private void inferMissingNamespace(FunctionConfig functionConfig) {
            functionConfig.setNamespace(DEFAULT_NAMESPACE);
        }

        private String getUniqueInput(FunctionConfig functionConfig) {
            if (functionConfig.getInputs().size() + functionConfig.getCustomSerdeInputs().size() != 1) {
                throw new IllegalArgumentException();
            }
            if (functionConfig.getInputs().size() == 1) {
                return functionConfig.getInputs().iterator().next();
            } else {
                return functionConfig.getCustomSerdeInputs().keySet().iterator().next();
            }
        }

        protected FunctionDetails convert(FunctionConfig functionConfig)
                throws IOException {

            // check if configs are valid
            validateFunctionConfigs(functionConfig);

            Class<?>[] typeArgs = null;
            if (functionConfig.getRuntime() == FunctionConfig.Runtime.JAVA) {
                if (functionConfig.getJar().startsWith(Utils.FILE)) {
                    // server derives the arg-type by loading a class
                    if (isBlank(functionConfig.getClassName())) {
                        throw new ParameterException("Class-name must be present for jar with file-url");
                    }
                } else {
                    typeArgs = Utils.getFunctionTypes(functionConfig);
                }
            }

            FunctionDetails.Builder functionDetailsBuilder = FunctionDetails.newBuilder();

            // Setup source
            SourceSpec.Builder sourceSpecBuilder = SourceSpec.newBuilder();
            Map<String, String> topicToSerDeClassNameMap = new HashMap<>();
            topicToSerDeClassNameMap.putAll(functionConfig.getCustomSerdeInputs());
            functionConfig.getInputs().forEach(v -> topicToSerDeClassNameMap.put(v, ""));
            sourceSpecBuilder.putAllTopicsToSerDeClassName(topicToSerDeClassNameMap);
            if (StringUtils.isNotBlank(functionConfig.getTopicsPattern())) {
                sourceSpecBuilder.setTopicsPattern(functionConfig.getTopicsPattern());
            }

            // Set subscription type based on ordering and EFFECTIVELY_ONCE semantics
            SubscriptionType subType = (functionConfig.isRetainOrdering()
                    || ProcessingGuarantees.EFFECTIVELY_ONCE.equals(functionConfig.getProcessingGuarantees()))
                            ? SubscriptionType.FAILOVER
                            : SubscriptionType.SHARED;
            sourceSpecBuilder.setSubscriptionType(subType);
            
            if (typeArgs != null) {
                sourceSpecBuilder.setTypeClassName(typeArgs[0].getName());
            }
            if (functionConfig.getTimeoutMs() != null) {
                sourceSpecBuilder.setTimeoutMs(functionConfig.getTimeoutMs());
            }
            functionDetailsBuilder.setSource(sourceSpecBuilder);

            // Setup sink
            SinkSpec.Builder sinkSpecBuilder = SinkSpec.newBuilder();
            if (functionConfig.getOutput() != null) {
                sinkSpecBuilder.setTopic(functionConfig.getOutput());
            }
            if (functionConfig.getOutputSerdeClassName() != null) {
                sinkSpecBuilder.setSerDeClassName(functionConfig.getOutputSerdeClassName());
            }
            if (typeArgs != null) {
                sinkSpecBuilder.setTypeClassName(typeArgs[1].getName());
            }
            functionDetailsBuilder.setSink(sinkSpecBuilder);

            if (functionConfig.getTenant() != null) {
                functionDetailsBuilder.setTenant(functionConfig.getTenant());
            }
            if (functionConfig.getNamespace() != null) {
                functionDetailsBuilder.setNamespace(functionConfig.getNamespace());
            }
            if (functionConfig.getName() != null) {
                functionDetailsBuilder.setName(functionConfig.getName());
            }
            if (functionConfig.getLogTopic() != null) {
                functionDetailsBuilder.setLogTopic(functionConfig.getLogTopic());
            }
            if (functionConfig.getRuntime() != null) {
                functionDetailsBuilder.setRuntime(Utils.convertRuntime(functionConfig.getRuntime()));
            }
            if (functionConfig.getProcessingGuarantees() != null) {
                functionDetailsBuilder.setProcessingGuarantees(
                        Utils.convertProcessingGuarantee(functionConfig.getProcessingGuarantees()));
            }

            Map<String, Object> configs = new HashMap<>();
            configs.putAll(functionConfig.getUserConfig());

            // windowing related
            WindowConfig windowConfig = functionConfig.getWindowConfig();
            if (windowConfig != null) {
                windowConfig.setActualWindowFunctionClassName(functionConfig.getClassName());
                configs.put(WindowConfig.WINDOW_CONFIG_KEY, windowConfig);
                // set class name to window function executor
                functionDetailsBuilder.setClassName(WindowFunctionExecutor.class.getName());

            } else {
                if (functionConfig.getClassName() != null) {
                    functionDetailsBuilder.setClassName(functionConfig.getClassName());
                }
            }
            if (!configs.isEmpty()) {
                functionDetailsBuilder.setUserConfig(new Gson().toJson(configs));
            }

            functionDetailsBuilder.setAutoAck(functionConfig.isAutoAck());
            functionDetailsBuilder.setParallelism(functionConfig.getParallelism());
            if (functionConfig.getResources() != null) {
                Resources.Builder bldr = Resources.newBuilder();
                if (functionConfig.getResources().getCpu() != null) {
                    bldr.setCpu(functionConfig.getResources().getCpu());
                }
                if (functionConfig.getResources().getRam() != null) {
                    bldr.setRam(functionConfig.getResources().getRam());
                }
                if (functionConfig.getResources().getDisk() != null) {
                    bldr.setDisk(functionConfig.getResources().getDisk());
                }
                functionDetailsBuilder.setResources(bldr.build());
            }
            return functionDetailsBuilder.build();
        }

        protected org.apache.pulsar.functions.proto.Function.FunctionDetails convertProto2(FunctionConfig functionConfig)
                throws IOException {
            org.apache.pulsar.functions.proto.Function.FunctionDetails.Builder functionDetailsBuilder = org.apache.pulsar.functions.proto.Function.FunctionDetails.newBuilder();
            Utils.mergeJson(FunctionsImpl.printJson(convert(functionConfig)), functionDetailsBuilder);
            return functionDetailsBuilder.build();
        }
    }

    @Parameters(commandDescription = "Run the Pulsar Function locally (rather than deploying it to the Pulsar cluster)")
    class LocalRunner extends FunctionDetailsCommand {

        // TODO: this should become bookkeeper url and it should be fetched from pulsar client.
        // for backwards compatibility purposes
        @Parameter(names = "--stateStorageServiceUrl", description = "The URL for the state storage service (by default Apache BookKeeper)", hidden = true)
        protected String DEPRECATED_stateStorageServiceUrl;
        @Parameter(names = "--state-storag-service-url", description = "The URL for the state storage service (by default Apache BookKeeper)")
        protected String stateStorageServiceUrl;
        // for backwards compatibility purposes
        @Parameter(names = "--brokerServiceUrl", description = "The URL for the Pulsar broker", hidden = true)
        protected String DEPRECATED_brokerServiceUrl;
        @Parameter(names = "--broker-service-url", description = "The URL for the Pulsar broker")
        protected String brokerServiceUrl;
        // for backwards compatibility purposes
        @Parameter(names = "--clientAuthPlugin", description = "Client authentication plugin using which function-process can connect to broker", hidden = true)
        protected String DEPRECATED_clientAuthPlugin;
        @Parameter(names = "--client-auth-plugin", description = "Client authentication plugin using which function-process can connect to broker")
        protected String clientAuthPlugin;
        // for backwards compatibility purposes
        @Parameter(names = "--clientAuthParams", description = "Client authentication param", hidden = true)
        protected String DEPRECATED_clientAuthParams;
        @Parameter(names = "--client-auth-params", description = "Client authentication param")
        protected String clientAuthParams;
        // for backwards compatibility purposes
        @Parameter(names = "--use_tls", description = "Use tls connection\n", hidden = true)
        protected Boolean DEPRECATED_useTls = null;
        @Parameter(names = "--use-tls", description = "Use tls connection\n")
        protected boolean useTls;
        // for backwards compatibility purposes
        @Parameter(names = "--tls_allow_insecure", description = "Allow insecure tls connection\n", hidden = true)
        protected Boolean DEPRECATED_tlsAllowInsecureConnection = null;
        @Parameter(names = "--tls-allow-insecure", description = "Allow insecure tls connection\n")
        protected boolean tlsAllowInsecureConnection;
        // for backwards compatibility purposes
        @Parameter(names = "--hostname_verification_enabled", description = "Enable hostname verification", hidden = true)
        protected Boolean DEPRECATED_tlsHostNameVerificationEnabled = null;
        @Parameter(names = "--hostname-verification-enabled", description = "Enable hostname verification")
        protected boolean tlsHostNameVerificationEnabled;
        // for backwards compatibility purposes
        @Parameter(names = "--tls_trust_cert_path", description = "tls trust cert file path", hidden = true)
        protected String DEPRECATED_tlsTrustCertFilePath;
        @Parameter(names = "--tls-trust-cert-path", description = "tls trust cert file path")
        protected String tlsTrustCertFilePath;
        // for backwards compatibility purposes
        @Parameter(names = "--instanceIdOffset", description = "Start the instanceIds from this offset", hidden = true)
        protected Integer DEPRECATED_instanceIdOffset = null;
        @Parameter(names = "--instance-id-offset", description = "Start the instanceIds from this offset")
        protected Integer instanceIdOffset = 0;

        private void mergeArgs() {
            if (!StringUtils.isBlank(DEPRECATED_stateStorageServiceUrl)) stateStorageServiceUrl = DEPRECATED_stateStorageServiceUrl;
            if (!StringUtils.isBlank(DEPRECATED_brokerServiceUrl)) brokerServiceUrl = DEPRECATED_brokerServiceUrl;
            if (!StringUtils.isBlank(DEPRECATED_clientAuthPlugin)) clientAuthPlugin = DEPRECATED_clientAuthPlugin;
            if (!StringUtils.isBlank(DEPRECATED_clientAuthParams)) clientAuthParams = DEPRECATED_clientAuthParams;
            if (DEPRECATED_useTls != null) useTls = DEPRECATED_useTls;
            if (DEPRECATED_tlsAllowInsecureConnection != null) tlsAllowInsecureConnection = DEPRECATED_tlsAllowInsecureConnection;
            if (DEPRECATED_tlsHostNameVerificationEnabled != null) tlsHostNameVerificationEnabled = DEPRECATED_tlsHostNameVerificationEnabled;
            if (!StringUtils.isBlank(DEPRECATED_tlsTrustCertFilePath)) tlsTrustCertFilePath = DEPRECATED_tlsTrustCertFilePath;
            if (DEPRECATED_instanceIdOffset != null) instanceIdOffset = DEPRECATED_instanceIdOffset;
        }

        @Override
        void runCmd() throws Exception {
            mergeArgs();
            CmdFunctions.startLocalRun(convertProto2(functionConfig), functionConfig.getParallelism(),
                    instanceIdOffset, brokerServiceUrl, stateStorageServiceUrl,
                    AuthenticationConfig.builder().clientAuthenticationPlugin(clientAuthPlugin)
                            .clientAuthenticationParameters(clientAuthParams).useTls(useTls)
                            .tlsAllowInsecureConnection(tlsAllowInsecureConnection)
                            .tlsHostnameVerificationEnable(tlsHostNameVerificationEnabled)
                            .tlsTrustCertsFilePath(tlsTrustCertFilePath).build(),
                    userCodeFile, admin);
        }
    }

    @Parameters(commandDescription = "Create a Pulsar Function in cluster mode (i.e. deploy it on a Pulsar cluster)")
    class CreateFunction extends FunctionDetailsCommand {
        @Override
        void runCmd() throws Exception {
            if (Utils.isFunctionPackageUrlSupported(functionConfig.getJar())) {
                admin.functions().createFunctionWithUrl(convert(functionConfig), functionConfig.getJar());
            } else {
                admin.functions().createFunction(convert(functionConfig), userCodeFile);
            }

            print("Created successfully");
        }
    }

    @Parameters(commandDescription = "Fetch information about a Pulsar Function")
    class GetFunction extends FunctionCommand {
        @Override
        void runCmd() throws Exception {
            String json = Utils.printJson(admin.functions().getFunction(tenant, namespace, functionName));
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            System.out.println(gson.toJson(new JsonParser().parse(json)));
        }
    }

    @Parameters(commandDescription = "Check the current status of a Pulsar Function")
    class GetFunctionStatus extends FunctionCommand {
        @Override
        void runCmd() throws Exception {
            String json = Utils.printJson(admin.functions().getFunctionStatus(tenant, namespace, functionName));
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            System.out.println(gson.toJson(new JsonParser().parse(json)));
        }
    }

    @Parameters(commandDescription = "Delete a Pulsar Function that's running on a Pulsar cluster")
    class DeleteFunction extends FunctionCommand {
        @Override
        void runCmd() throws Exception {
            admin.functions().deleteFunction(tenant, namespace, functionName);
            print("Deleted successfully");
        }
    }

    @Parameters(commandDescription = "Update a Pulsar Function that's been deployed to a Pulsar cluster")
    class UpdateFunction extends FunctionDetailsCommand {
        @Override
        void runCmd() throws Exception {
            if (Utils.isFunctionPackageUrlSupported(functionConfig.getJar())) {
                admin.functions().updateFunctionWithUrl(convert(functionConfig), functionConfig.getJar());
            } else {
                admin.functions().updateFunction(convert(functionConfig), userCodeFile);
            }
            print("Updated successfully");
        }
    }

    @Parameters(commandDescription = "List all of the Pulsar Functions running under a specific tenant and namespace")
    class ListFunctions extends NamespaceCommand {
        @Override
        void runCmd() throws Exception {
            print(admin.functions().getFunctions(tenant, namespace));
        }
    }

    @Parameters(commandDescription = "Fetch the current state associated with a Pulsar Function running in cluster mode")
    class StateGetter extends FunctionCommand {

        @Parameter(names = { "-k", "--key" }, description = "key")
        private String key = null;

        // TODO: this url should be fetched along with bookkeeper location from pulsar admin
        @Parameter(names = { "-u", "--storage-service-url" }, description = "The URL for the storage service used by the function")
        private String stateStorageServiceUrl = null;

        @Parameter(names = { "-w", "--watch" }, description = "Watch for changes in the value associated with a key for a Pulsar Function")
        private boolean watch = false;

        @Override
        void runCmd() throws Exception {
            checkNotNull(stateStorageServiceUrl, "The state storage service URL is missing");

            String tableNs = String.format(
                "%s_%s",
                tenant,
                namespace).replace('-', '_');

            String tableName = getFunctionName();

            try (StorageClient client = StorageClientBuilder.newBuilder()
                 .withSettings(StorageClientSettings.newBuilder()
                     .serviceUri(stateStorageServiceUrl)
                     .clientName("functions-admin")
                     .build())
                 .withNamespace(tableNs)
                 .build()) {
                try (Table<ByteBuf, ByteBuf> table = result(client.openTable(tableName))) {
                    long lastVersion = -1L;
                    do {
                        try (KeyValue<ByteBuf, ByteBuf> kv = result(table.getKv(Unpooled.wrappedBuffer(key.getBytes(UTF_8))))) {
                            if (null == kv) {
                                System.out.println("key '" + key + "' doesn't exist.");
                            } else {
                                if (kv.version() > lastVersion) {
                                    if (kv.isNumber()) {
                                        System.out.println("value = " + kv.numberValue());
                                    } else {
                                        System.out.println("value = " + new String(ByteBufUtil.getBytes(kv.value()), UTF_8));
                                    }
                                    lastVersion = kv.version();
                                }
                            }
                        }
                        if (watch) {
                            Thread.sleep(1000);
                        }
                    } while (watch);
                }
            }

        }
    }

    @Parameters(commandDescription = "Triggers the specified Pulsar Function with a supplied value")
    class TriggerFunction extends FunctionCommand {
        // for backward compatibility purposes
        @Parameter(names = "--triggerValue", description = "The value with which you want to trigger the function", hidden = true)
        protected String DEPRECATED_triggerValue;
        @Parameter(names = "--trigger-value", description = "The value with which you want to trigger the function")
        protected String triggerValue;
        // for backward compatibility purposes
        @Parameter(names = "--triggerFile", description = "The path to the file that contains the data with which you'd like to trigger the function", hidden = true)
        protected String DEPRECATED_triggerFile;
        @Parameter(names = "--trigger-file", description = "The path to the file that contains the data with which you'd like to trigger the function")
        protected String triggerFile;
        @Parameter(names = "--topic", description = "The specific topic name that the function consumes from that you want to inject the data to")
        protected String topic;

        public void mergeArgs() {
            if (!StringUtils.isBlank(DEPRECATED_triggerValue)) triggerValue = DEPRECATED_triggerValue;
            if (!StringUtils.isBlank(DEPRECATED_triggerFile)) triggerFile = DEPRECATED_triggerFile;
        }

        @Override
        void runCmd() throws Exception {
            mergeArgs();
            if (triggerFile == null && triggerValue == null) {
                throw new ParameterException("Either a trigger value or a trigger filepath needs to be specified");
            }
            String retval = admin.functions().triggerFunction(tenant, namespace, functionName, topic, triggerValue, triggerFile);
            System.out.println(retval);
        }
    }

    @Parameters(commandDescription = "Upload File Data to Pulsar", hidden = true)
    class UploadFunction extends BaseCommand {
        // for backward compatibility purposes
        @Parameter(
                names = "--sourceFile",
                description = "The file whose contents need to be uploaded",
                listConverter = StringConverter.class, hidden = true)
        protected String DEPRECATED_sourceFile;
        @Parameter(
                names = "--source-file",
                description = "The file whose contents need to be uploaded",
                listConverter = StringConverter.class)
        protected String sourceFile;
        @Parameter(
                names = "--path",
                description = "Path where the contents need to be stored",
                listConverter = StringConverter.class, required = true)
        protected String path;

        private void mergeArgs() {
            if (!StringUtils.isBlank(DEPRECATED_sourceFile)) sourceFile = DEPRECATED_sourceFile;
        }

        @Override
        void runCmd() throws Exception {
            mergeArgs();
            if (StringUtils.isBlank(sourceFile)) {
                throw new ParameterException("--source-file needs to be specified");
            }
            admin.functions().uploadFunction(sourceFile, path);
            print("Uploaded successfully");
        }
    }

    @Parameters(commandDescription = "Download File Data from Pulsar", hidden = true)
    class DownloadFunction extends BaseCommand {
        // for backward compatibility purposes
        @Parameter(
                names = "--destinationFile",
                description = "The file where downloaded contents need to be stored",
                listConverter = StringConverter.class, hidden = true)
        protected String DEPRECATED_destinationFile;
        @Parameter(
                names = "--destination-file",
                description = "The file where downloaded contents need to be stored",
                listConverter = StringConverter.class)
        protected String destinationFile;
        @Parameter(
                names = "--path",
                description = "Path where the contents are to be stored",
                listConverter = StringConverter.class, required = true)
        protected String path;

        private void mergeArgs() {
            if (!StringUtils.isBlank(DEPRECATED_destinationFile)) destinationFile = DEPRECATED_destinationFile;
        }

        @Override
        void runCmd() throws Exception {
            mergeArgs();
            if (StringUtils.isBlank(destinationFile)) {
                throw new ParameterException("--destination-file needs to be specified");
            }
            admin.functions().downloadFunction(destinationFile, path);
            print("Downloaded successfully");
        }
    }

    @Parameters(commandDescription = "Get list of workers registered in cluster")
    class GetCluster extends BaseCommand {
        @Override
        void runCmd() throws Exception {
            String json = (new Gson()).toJson(admin.functions().getCluster());
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            System.out.println(gson.toJson(new JsonParser().parse(json)));
        }
    }

    public CmdFunctions(PulsarAdmin admin) throws PulsarClientException {
        super("functions", admin);
        localRunner = new LocalRunner();
        creater = new CreateFunction();
        deleter = new DeleteFunction();
        updater = new UpdateFunction();
        getter = new GetFunction();
        statuser = new GetFunctionStatus();
        lister = new ListFunctions();
        stateGetter = new StateGetter();
        triggerer = new TriggerFunction();
        uploader = new UploadFunction();
        downloader = new DownloadFunction();
        cluster = new GetCluster();
        jcommander.addCommand("localrun", getLocalRunner());
        jcommander.addCommand("create", getCreater());
        jcommander.addCommand("delete", getDeleter());
        jcommander.addCommand("update", getUpdater());
        jcommander.addCommand("get", getGetter());
        jcommander.addCommand("getstatus", getStatuser());
        jcommander.addCommand("list", getLister());
        jcommander.addCommand("querystate", getStateGetter());
        jcommander.addCommand("trigger", getTriggerer());
        jcommander.addCommand("upload", getUploader());
        jcommander.addCommand("download", getDownloader());
        jcommander.addCommand("cluster", cluster);
    }

    @VisibleForTesting
    LocalRunner getLocalRunner() {
        return localRunner;
    }

    @VisibleForTesting
    CreateFunction getCreater() {
        return creater;
    }

    @VisibleForTesting
    DeleteFunction getDeleter() {
        return deleter;
    }

    @VisibleForTesting
    UpdateFunction getUpdater() {
        return updater;
    }

    @VisibleForTesting
    GetFunction getGetter() {
        return getter;
    }

    @VisibleForTesting
    GetFunctionStatus getStatuser() { return statuser; }

    @VisibleForTesting
    ListFunctions getLister() {
        return lister;
    }

    @VisibleForTesting
    StateGetter getStateGetter() {
        return stateGetter;
    }

    @VisibleForTesting
    TriggerFunction getTriggerer() {
        return triggerer;
    }

    @VisibleForTesting
    UploadFunction getUploader() {
        return uploader;
    }

    @VisibleForTesting
    DownloadFunction getDownloader() {
        return downloader;
    }

    private void parseFullyQualifiedFunctionName(String fqfn, FunctionConfig functionConfig) {
        String[] args = fqfn.split("/");
        if (args.length != 3) {
            throw new ParameterException("Fully qualified function names (FQFNs) must be of the form tenant/namespace/name");
        } else {
            functionConfig.setTenant(args[0]);
            functionConfig.setNamespace(args[1]);
            functionConfig.setName(args[2]);
        }
    }

    protected static void startLocalRun(org.apache.pulsar.functions.proto.Function.FunctionDetails functionDetails,
            int parallelism, int instanceIdOffset, String brokerServiceUrl, String stateStorageServiceUrl, AuthenticationConfig authConfig,
            String userCodeFile, PulsarAdmin admin)
            throws Exception {

        String serviceUrl = admin.getServiceUrl();
        if (brokerServiceUrl != null) {
            serviceUrl = brokerServiceUrl;
        }
        if (serviceUrl == null) {
            serviceUrl = DEFAULT_SERVICE_URL;
        }
        try (ProcessRuntimeFactory containerFactory = new ProcessRuntimeFactory(serviceUrl, stateStorageServiceUrl, authConfig, null, null,
                null)) {
            List<RuntimeSpawner> spawners = new LinkedList<>();
            for (int i = 0; i < parallelism; ++i) {
                InstanceConfig instanceConfig = new InstanceConfig();
                instanceConfig.setFunctionDetails(functionDetails);
                // TODO: correctly implement function version and id
                instanceConfig.setFunctionVersion(UUID.randomUUID().toString());
                instanceConfig.setFunctionId(UUID.randomUUID().toString());
                instanceConfig.setInstanceId(Integer.toString(i + instanceIdOffset));
                instanceConfig.setMaxBufferedTuples(1024);
                instanceConfig.setPort(Utils.findAvailablePort());
                RuntimeSpawner runtimeSpawner = new RuntimeSpawner(
                        instanceConfig,
                        userCodeFile,
                        containerFactory,
                        30000);
                spawners.add(runtimeSpawner);
                runtimeSpawner.start();
            }
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    log.info("Shutting down the localrun runtimeSpawner ...");
                    for (RuntimeSpawner spawner : spawners) {
                        spawner.close();
                    }
                }
            });
            Timer statusCheckTimer = new Timer();
            statusCheckTimer.scheduleAtFixedRate(new TimerTask() {
                    @Override
                    public void run() {
                        CompletableFuture<String>[] futures = new CompletableFuture[spawners.size()];
                        int index = 0;
                        for (RuntimeSpawner spawner : spawners) {
                            futures[index++] = spawner.getFunctionStatusAsJson();
                        }
                        try {
                            CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);
                            for (index = 0; index < futures.length; ++index) {
                                String json = futures[index].get();
                                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                                log.info(gson.toJson(new JsonParser().parse(json)));
                            }
                        } catch (Exception ex) {
                            log.error("Could not get status from all local instances");
                        }
                    }
                }, 30000, 30000);
            Runtime.getRuntime().addShutdownHook(new Thread() {
                    public void run() {
                        statusCheckTimer.cancel();
                    }
                });
            for (RuntimeSpawner spawner : spawners) {
                spawner.join();
                log.info("RuntimeSpawner quit because of", spawner.getRuntime().getDeathException());
            }

        }
    }
}
