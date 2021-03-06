package ch.cern.tdaq.k8s.operator;

import ch.cern.tdaq.k8s.operator.CustomResource.DoneableRunResource;
import ch.cern.tdaq.k8s.operator.CustomResource.RunResource;
import ch.cern.tdaq.k8s.operator.CustomResource.RunResourceList;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.containersolutions.operator.api.Context;
import com.github.containersolutions.operator.api.Controller;
import com.github.containersolutions.operator.api.ResourceController;
import com.github.containersolutions.operator.api.UpdateControl;
import com.google.gson.JsonParser;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller(customResourceClass = RunResource.class,
        crdName = "runresources.operator.tdaq.cern.ch") /* NOTE! crdName needs to be in sync with the crd name used by the TdaqRunController and the actual CRD yaml file */
public class RunController implements ResourceController<RunResource> {
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final KubernetesClient kubernetesClient;
    private final int runNumberPaddingSize = 10;

    private static final String ENVIRONMENT_RUN_NUMBER_NAME = "RUN_NUMBER";
    private static final String ENVIRONMENT_RUN_PIPE_NAME = "RUN_PIPE";

    private static final String METADATA_LABEL_RUN_NUMBER_KEY = "tdaq.run-number";
    private static final String METADATA_LABEL_RUN_PIPE_KEY = "tdaq.run-pipe";
    private static final String METADATA_LABEL_TDAQ_WORKER_KEY = "tdaq.worker";
    private static final String METADATA_LABEL_TDAQ_WORKER_VALUE = "true";

    final private String deploymentYamlPath = "deploy-worker.yaml"; /* Should we let the users use their own yaml, or yaml that is part of the JAR under /resources ? */

    @NotNull
    public RunController(KubernetesClient kubernetesClient) {
        this.kubernetesClient = kubernetesClient;
    }

    /**
     * The function called by the Java-Operator-SDK framework when a CR is DELETED.
     * @param resource
     * @param context
     * @return
     */
    @Override
    public boolean deleteResource(RunResource resource, Context<RunResource> context) {
        log.info("Execution deleteResource for: {}", resource.getMetadata().getName());

        /* TODO: Might want to do something particular if it is the last try? */
        boolean lastTry = context.retryInfo().isLastAttempt();

        String namespace = resource.getMetadata().getNamespace();
        int runNumber = resource.getSpec().getRunNumber();
        String runPipeName = resource.getSpec().getRunPipe();

        try (InputStream yamlInputStream = getClass().getResourceAsStream(deploymentYamlPath)) {
            /* Need to use the deployment yaml template to get the deployment name */
            Deployment runDeployment = kubernetesClient.apps().deployments().load(yamlInputStream).get();

            /* Get the deployment name for the deployment related to this CR */
            String deploymentName = getRunDeploymentName(runDeployment, runNumber, runPipeName);

            /* If true, then the Deployment is deleted. If false, then the Java-Operator-SDK will try again until lastTry is true. These values can be configured in Main */
            boolean customResourceDeleted = deleteDeployments(namespace, deploymentName);
            if (customResourceDeleted) {
                /* Delete the Namespace if it now contains no more Deployments */
                /* NOTE: If it fails to delete the Namespace, it will not try again. On fail, you will have to delete the namespace manually. */
                deleteNamespaceIfEmpty(namespace);
            }
            return customResourceDeleted;
        } catch (IOException e) {
            e.printStackTrace();
            log.error("Exception when trying to to delete the CustomResource", e);
            return false;
        }
    }

    /**
     * The function called by the Java-Operator-SDK framework when a CR is CREATED or UPDATED/CHANGED.
     * @param resource
     * @param context
     * @return
     */
    @Override
    public UpdateControl createOrUpdateResource(RunResource resource, Context<RunResource> context) {
        log.info("Execution createOrUpdateResource for: {}", resource.getMetadata().getName());

        String namespace = resource.getMetadata().getNamespace();
        int runNumber = resource.getSpec().getRunNumber();
        String runPipe = resource.getSpec().getRunPipe();

        /* TODO: Might want to do something particular if it is the last try. */
        boolean lastTry = context.retryInfo().isLastAttempt();

        /* Only run a new deployment, if the CR has a new RunNumber value (aka a higher value than in any current deployment) */
//        int latestRunNumberDeployed = getLatestDeploymentRunNumberDeployed();
        try {
            createNewDeploymentIfNotExist(namespace, runNumber, runPipe);
        } catch (IOException e) {
            e.printStackTrace();
            log.error("createOrReplaceDeployment failed", e);
            /* Throwing an error should cause a new call to createOrUpdateResource() after a backoff period. Double check! */
            throw new RuntimeException(e);
        }

        /* Deletes all deployments where all the Pods in the deployment have finished/exited */
        /**
         * Add back deleteFinishedDeployments() once java-operator-sdk support looping after X time
         */
        /* deleteFinishedDeployments(); */

        /**
         * TODO: add some notes/log to the status of the resource, to make it descriptive when queried by a human
         */

        return UpdateControl.updateCustomResource(resource);
    }

    /**
     * Finds the largest RunNumber in the cluster and returns it.
     * Finds the number based on metadata labels for each Pod that have the tdaq-worker label
     * @return The largest (aka latest) RunNumber of any deployment in the clusters that have the tdaq-worker label
     */
    private int getLatestDeploymentRunNumberDeployed() {
        int largestRunNumber = -1;
        DeploymentList deploymentList = kubernetesClient.apps().deployments().inAnyNamespace().withLabel(METADATA_LABEL_TDAQ_WORKER_KEY, METADATA_LABEL_TDAQ_WORKER_VALUE).list();
        for (Deployment deployment : deploymentList.getItems()) {
            String runNumberAsString = deployment.getMetadata().getLabels().getOrDefault(METADATA_LABEL_RUN_NUMBER_KEY, "-1");
            try {
                int deploymentRunNumber = Integer.parseInt(runNumberAsString);
                if (largestRunNumber < deploymentRunNumber) {
                    largestRunNumber = deploymentRunNumber;
                }
            } catch (NumberFormatException e) {
                e.printStackTrace();
            }
        }

        return largestRunNumber;
    }

    /**
     * Creates the run deployment organized and monitored by this Operator
     * It modifies the deployment yaml file, by changing the name to include the RunNumber, and adds labels for the RunNumber and RunPipe.
     * Inserts Environment variables to all the containers in each Pod, so that they have access to the RUN_NUMBER and RUN_PIPE inside the application.
     * NOTE: You can use parameters/args to inject the RUN_NUMBER and RUN_PIPE values as well. Very easy to do.
     * @param namespace The namespace that will be used for the deployment
     * @param runNumber The current RunNumber as fetched from the CR
     * @param runPipeName The current RunPipe type as fetched from the CR
     * @throws IOException If unable to read the yaml file from the resources directory
     */
    private void createNewDeploymentIfNotExist(@NotNull String namespace, int runNumber, @NotNull String runPipeName) throws IOException {
        try (InputStream yamlInputStream = getClass().getResourceAsStream(deploymentYamlPath)) {
            Deployment newRunDeployment = kubernetesClient.apps().deployments().load(yamlInputStream).get();

            /* Set the deployment name to include the RunNumber and be unique to not overwrite the other deployments */
            String deploymentName = getRunDeploymentName(newRunDeployment, runNumber, runPipeName);

            Deployment oldDeployment = kubernetesClient.apps().deployments().inNamespace(namespace).withName(deploymentName).get();
            /* Checks if the deployment already exists, if so, do nothing */
            if (oldDeployment == null) {
                String serviceNamespace = "default";
                String serviceName = "webserver-service";
                Service service = kubernetesClient.services().inNamespace(serviceNamespace).withName(serviceName).get();

                /* Set the metadata labels tdaq.run-number and tdaq.run-pipe for this deployment */
                Map<String, String> labels = newRunDeployment.getMetadata().getLabels();
                if (labels == null) {
                    labels = new HashMap<>();
                }
                final String runNumberAsString = Integer.toString(runNumber);
                labels.put(METADATA_LABEL_RUN_NUMBER_KEY, runNumberAsString);
                labels.put(METADATA_LABEL_RUN_PIPE_KEY, runPipeName);
                labels.put(METADATA_LABEL_TDAQ_WORKER_KEY, METADATA_LABEL_TDAQ_WORKER_VALUE);
                newRunDeployment.getMetadata().setLabels(labels);

                EnvVar envVarRunNumber = new EnvVar();
                envVarRunNumber.setName(ENVIRONMENT_RUN_NUMBER_NAME);
                envVarRunNumber.setValue(Integer.toString(runNumber));
                EnvVar envVarRunPipe = new EnvVar();
                envVarRunPipe.setName(ENVIRONMENT_RUN_PIPE_NAME);
                envVarRunPipe.setValue(runPipeName);

                /* Add the Service IP:PORT to the Pods, since they are not automatically available since the Pods are in a different namespace than the Service */
                EnvVar envVarRunServiceHost = new EnvVar();
                envVarRunServiceHost.setName("WEBSERVER_SERVICE_SERVICE_HOST");
                envVarRunServiceHost.setValue(service.getSpec().getClusterIP());
                EnvVar envVarRunServicePort = new EnvVar();
                envVarRunServicePort.setName("WEBSERVER_SERVICE_SERVICE_PORT");
                envVarRunServicePort.setValue(String.valueOf(service.getSpec().getPorts().get(0).getPort()));

                List<Container> containerList = newRunDeployment.getSpec().getTemplate().getSpec().getContainers();
                for (Container container : containerList) {
                    /**
                     * Add the RUN_NUMBER and RUN_PIPE environment value to each Container in the Pods.
                     * NOTE: Pods can also take in parameters/args
                     * WARNING: These injected environment variables will override the ones in the container, if they have the same name
                     */
                    List<EnvVar> envVarList = container.getEnv();
                    if (envVarList == null) {
                        envVarList = new ArrayList<>();
                    }
                    envVarList.add(envVarRunNumber);
                    envVarList.add(envVarRunPipe);
                    envVarList.add(envVarRunServiceHost);
                    envVarList.add(envVarRunServicePort);
                    container.setEnv(envVarList);
                }

                newRunDeployment.getMetadata().setName(deploymentName);

                if (namespace == null || namespace.isEmpty()) {
                    namespace = "default";
                }
                newRunDeployment.getMetadata().setNamespace(namespace);
                Deployment createdDeployment = kubernetesClient.apps().deployments().inNamespace(namespace).create(newRunDeployment);

                log.info("Created deployment: {}", deploymentYamlPath);
            }
        } catch (IOException ex) {
            log.error("createOrReplaceDeployment failed", ex);
            throw ex;
        }
    }


    /**
     * Tries to delete the Deployment that matches the namespace and deploymentName
     * @param namespace The namespace of the deployment
     * @param deploymentName The actual name of the deployment
     * @return True on success, False when failed to delete the Deployment from the cluster
     */
    private boolean deleteDeployments(String namespace, String deploymentName) {
        Boolean isDeleted = kubernetesClient.apps().deployments().inNamespace(namespace).withName(deploymentName).delete();
        if (isDeleted == null) {
            isDeleted = false;
        }
        if (isDeleted) {
            log.info("Namespace: {} Deployment: {} is successfully deleted", namespace, deploymentName);
        } else {
            log.warn("Namespace: {} Deployment: {} was NOT deleted", namespace, deploymentName);
        }
        return isDeleted;
    }


    private boolean deleteNamespaceIfEmpty(String namespace) {
        Boolean isDeleted = false;
        DeploymentList aDeploymentList = kubernetesClient.apps().deployments().inNamespace(namespace).list();
        if (aDeploymentList.getItems().isEmpty()) {
            isDeleted = kubernetesClient.namespaces().withName(namespace).delete();
            if (isDeleted == null) {
                isDeleted = false;
            }
            if (isDeleted) {
                log.info("Namespace: {} is successfully deleted", namespace);
            } else {
                log.warn("Namespace: {} was NOT deleted", namespace);
            }
        }

        return isDeleted;
    }

    /**
     * NOTE: This model to delete Deployments and Pods does NOT work with the current K8S versions.
     * K8S does NOT allow pods in a deployment to stop on itself. You must set "restartPolicy: Always", no other
     * options are allowed, setting a different restartPolicy is only allowed for Pods running outside of a
     * Deployment or Replicaset. Check out the github issue to see if there is any changes made regarding this:
     * For a deployment, only the "restartPolicy: Always" is supported, a Pod spec supports [Always, OnFailure, Never]
     * See: https://github.com/kubernetes/kubernetes/issues/24725#issuecomment-214513280
     *
     *
     * Deletes all deployments where all the pods are finished running.
     * NOTE: We assume the Pods terminate and are not rebooted after they exit/are finished. Probably use policy: OnFailure. Read more here:
     *  https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#restart-policy
     *  https://kubernetes.io/docs/concepts/workloads/controllers/deployment/#pod-template
     *
     * TODO: Make this non-static once the java-operator-sdk has support for looping after X time
     *  See: https://github.com/ContainerSolutions/java-operator-sdk/issues/157
     *  Set the function back to private, once it is looping as part of createOrUpdateResource()!
     */
    public static void deleteFinishedDeploymentsOld(final KubernetesClient kubernetesClient) {
        /**
         *  NOTE: If we query too many Pods at once (from 3000(?)-5000(?) and more. Not sure about the exact number),
         *  it could cause a timeout problem when we query the API Server.
         *  The SIG-Scalability group should have some answers regarding this. Check out their video from KubeCon December/September 2018 on YouTube.
         *
         *  Instead we could query based on namespaces or more refined labels? But then we have to be sure we will not miss deployments for a certain run!
         */
        DeploymentList deploymentList = kubernetesClient.apps().deployments().inAnyNamespace().withLabel(METADATA_LABEL_TDAQ_WORKER_KEY, METADATA_LABEL_TDAQ_WORKER_VALUE).list();
        for (Deployment deployment : deploymentList.getItems()) {
            /**
             * TODO: Test which of these replica count values are the one I want to use!?
             */
            try {

                Integer currentReplicas = deployment.getStatus().getReplicas(); /* This should be currently running, bad and good. Probably the one I want, but make sure and test it!! */
                /*
                deployment.getStatus().getAvailableReplicas();
                deployment.getStatus().getReadyReplicas();
                deployment.getStatus().getUnavailableReplicas();
                deployment.getStatus().getUpdatedReplicas();
                DeploymentStatus stat = deployment.getStatus();
                 */

                /*
                deployment.getSpec().getReplicas(); // This probably has the originally deployed number of replicas. Probably not the one we want.
                */

                /**
                 * TODO: Unsure about how to handle null values. What does "null" mean? That the value is inaccessible, unknown or just does not exists in the cluster? Should we delete or not when null?
                 */
                if (currentReplicas != null
                        && currentReplicas.intValue() == 0
                ) {
                    String deploymentName = deployment.getMetadata().getName();
                    String namespace = deployment.getMetadata().getNamespace();
                    Boolean isDeleted = kubernetesClient.apps().deployments().inNamespace(namespace).withName(deploymentName).delete();
                    if (!isDeleted) {
                        /**
                         * TODO: What to do on failure to delete Deployment? Just accept for now and try again later?
                         */
                    }
                }
            } catch (NullPointerException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Used to create a name that makes it easy to identify which run this deployment belongs to.
     * This must be used when CREATING a new deployment and when DELETING the same deployment.
     * The name will be in this format, where N is a number [0-9]:
     *  <template-deployment-name>-<NNNNNNNNNN>-<runType>
     * @param deployment
     * @param currentRunNumber
     * @param runPipeName
     * @return
     */
    @NotNull
    private String getRunDeploymentName(Deployment deployment, int currentRunNumber, String runPipeName) {
        String formattedRunNumber = String.format("%0" + runNumberPaddingSize + "d", currentRunNumber);
        return deployment.getMetadata().getName() + "-" + formattedRunNumber + "-" + runPipeName;
    }

    /**
     * Returns false if the deployment given as parameter is the current latest deployed deployment.
     * This is done by matching the RunNumber in Deployment against the "currentRunNumber" value.
     * NOTE: for this to work, runNumberPaddingSize must be the same as used in the actual deployment name!
     * @param deployment The deployment to check against the RunNumber
     * @param currentRunNumber The latest RunNumber
     * @return False if deployment is the current deployment, True otherwise
     */
    private boolean isNotCurrentDeployment(@NotNull Deployment deployment, int currentRunNumber) {
        return !isCurrentDeployment(deployment, currentRunNumber);
    }

    /**
     * Returns true if the deployment given as parameter is the current latest deployed deployment.
     * This is done by matching the RunNumber in Deployment against the "currentRunNumber" value.
     * NOTE: for this to work, runNumberPaddingSize must be the same as used in the actual deployment name!
     * @param deployment The deployment to check against the RunNumber
     * @param currentRunNumber The latest RunNumber
     * @return True if deployment is the current deployment, False otherwise
     */
    private boolean isCurrentDeployment(@NotNull Deployment deployment, int currentRunNumber) {
        String deploymentName = deployment.getMetadata().getName();
        String formattedRunNumber = String.format("%0" + runNumberPaddingSize + "d", currentRunNumber);
        return deploymentName.contains(formattedRunNumber);
    }

    private static int getLatestRunNumberFromWebserver() throws IOException {
        String json = webserverGetRequest(-1);
        JsonParser parser = new JsonParser();
        return parser.parse(json).getAsJsonObject().get("latestRunNumber").getAsInt();
    }

    private static String webserverGetRequest(int runNumber) throws IOException {
        String service_host_ip = System.getenv("WEBSERVER_SERVICE_SERVICE_HOST");
        String service_host_post = System.getenv("WEBSERVER_SERVICE_SERVICE_PORT");
        String urlString = "http://" + service_host_ip + ":" + service_host_post + "/?run=" + Integer.toString(runNumber);
        URL url = new URL(urlString);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");

        /* int status = con.getResponseCode(); */
        BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
        String inputLine;
        StringBuffer content = new StringBuffer();
        while ((inputLine = in.readLine()) != null) {
            content.append(inputLine);
        }
        in.close();

        return content.toString();
    }

    final static String RUN_NUMBER_MAP_KEY = "runNumber";
    final static String RUN_CONTROLLER_CR_NAME = "runcontroller-cr";
    private static void updateRuncontrollerCR(final KubernetesClient kubernetesClient) throws IOException {
        final String crdName = "runresources.operator.tdaq.cern.ch";

        CustomResourceDefinition runControllerCrd = kubernetesClient.customResourceDefinitions().withName(crdName).get();
        CustomResourceDefinitionContext context = CustomResourceDefinitionContext.fromCrd(runControllerCrd);

        MixedOperation<RunResource, RunResourceList, DoneableRunResource, Resource<RunResource, DoneableRunResource>> crClient = kubernetesClient
                .customResources(context, RunResource.class, RunResourceList.class, DoneableRunResource.class);

        /**
         * Note: CR's can be cluster wide or in a given namespace. If we want to use more than one namespace, we need to get the correct namespace here
         */
        /**
         * This did not work for some reason...??? I have to use the "Typeless API" instead of the "Typed API"
         */
//        RunResource customResource = crClient.inNamespace("default").withName("runcontroller-cr").get(); /* TODO: fix how to get a generic runcontroller CR, name can change. Use label or something */
//        RunResourceSpec spec = customResource.getSpec();
//        int nextRunNumber = spec.getRunNumber() + 1;
//        spec.setRunNumber(nextRunNumber);

        /* TODO: set some status, like "LastUpdate" in the RunControllerCustomResource's status*/
        /* Update the CR with the new data aka new RunNumber */
        /* customResource = crClient.inNamespace("default").updateStatus(customResource); */

        //crClient.createOrReplace(customResource);
        //crClient.updateStatus(customResource);

        Map<String, Object> runcontrollerCR = kubernetesClient.customResource(context).get("default", RUN_CONTROLLER_CR_NAME);


        int newRunNumber = 1 + (int) ((HashMap<String, Object>) runcontrollerCR.get("spec")).getOrDefault(RUN_NUMBER_MAP_KEY, -1); /* Not yet tested */
        ((HashMap<String, Object>)runcontrollerCR.get("spec")).put(RUN_NUMBER_MAP_KEY, newRunNumber);
        runcontrollerCR = kubernetesClient.customResource(context).edit("default", RUN_CONTROLLER_CR_NAME, new ObjectMapper().writeValueAsString(runcontrollerCR));
    }
}
