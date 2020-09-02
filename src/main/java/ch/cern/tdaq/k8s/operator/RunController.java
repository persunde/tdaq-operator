package ch.cern.tdaq.k8s.operator;

import ch.cern.tdaq.k8s.operator.CustomResource.RunResource;
import com.github.containersolutions.operator.api.Context;
import com.github.containersolutions.operator.api.Controller;
import com.github.containersolutions.operator.api.ResourceController;
import com.github.containersolutions.operator.api.UpdateControl;
import com.google.gson.JsonParser;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
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
        crdName = "runresources.operator.tdaq.cern.ch")
public class RunController implements ResourceController<RunResource> {
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final KubernetesClient kubernetesClient;
    private final int runNumberPaddingSize = 4;

    private static final String ENVIRONMENT_RUN_NUMBER_NAME = "RUN_NUMBER";
    private static final String ENVIRONMENT_RUN_PIPE_NAME = "RUN_PIPE";

    private static final String METADATA_LABEL_RUN_NUMBER_KEY = "tdaq.run-number";
    private static final String METADATA_LABEL_RUN_PIPE_KEY = "tdaq.run-pipe";
    private static final String METADATA_LABEL_TDAQ_WORKER_KEY = "tdaq.worker";
    private static final String METADATA_LABEL_TDAQ_WORKER_VALUE = "true";

    @NotNull
    public RunController(KubernetesClient kubernetesClient) {
        this.kubernetesClient = kubernetesClient;
    }

    @Override
    public boolean deleteResource(RunResource runResource, Context<RunResource> context) {
        return false;
    }

    @Override
    public UpdateControl createOrUpdateResource(RunResource resource, Context<RunResource> context) {
        log.info("Execution createOrUpdateResource for: {}", resource.getMetadata().getName());

        int latestRunNumber = resource.getSpec().getRunNumber();
        String latestRunPipe = resource.getSpec().getRunPipe();

        /**
         * TODO: Might want to do something particular if it is the last try.
         */
        boolean lastTry = context.retryInfo().isLastAttempt();

        /* Only run a new deployment, if the CR has a new RunNumber value (aka a higher value than in any current deployment) */
        int latestRunNumberDeployed = getLatestDeploymentRunNumberDeployed();
        if (latestRunNumberDeployed < latestRunNumber) {
            try {
                createNewDeployment(latestRunNumber, latestRunPipe);
            } catch (IOException e) {
                e.printStackTrace();
                log.error("createOrReplaceDeployment failed", e);
                /* Throwing an error should cause a new call to createOrUpdateResource() after a backoff period. Double check! */
                throw new RuntimeException(e);
            }
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
     * @param runNumber The current RunNumber as fetched from the CR
     * @param runPipeName The current RunPipe type as fetched from the CR
     * @throws IOException If unable to read the yaml file from /resources
     */
    private void createNewDeployment(int runNumber, @NotNull String runPipeName) throws IOException {
        String deploymentYamlPath = "deploy-worker.yaml"; /* Should we let the users use their own yaml, or yaml that is part of the JAR under /resources ? */
        try (InputStream yamlInputStream = getClass().getResourceAsStream(deploymentYamlPath)) {
            Deployment newRunDeployment = kubernetesClient.apps().deployments().load(yamlInputStream).get();

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
                container.setEnv(envVarList);
            }

            /**
             * TODO: Check if you need to set the containerList for the workerDeployment as well, or if only setting the Env in the containers is enough
             */

            /* Set the deployment name to include the RunNumber and be unique to not overwrite the other deployments */
            String newRunDeploymentName = generateNewRunDeploymentName(newRunDeployment, runNumber, runPipeName);
            newRunDeployment.getMetadata().setName(newRunDeploymentName);

            String namespace = newRunDeployment.getMetadata().getNamespace();
            if (namespace == null || namespace.isEmpty()) {
                namespace = "default";
                newRunDeployment.getMetadata().setNamespace(namespace);
            }
            Deployment createdDeployment = kubernetesClient.apps().deployments().inNamespace(namespace).create(newRunDeployment);

            log.info("Created deployment: {}", deploymentYamlPath);
        } catch (IOException ex) {
            log.error("createOrReplaceDeployment failed", ex);
            throw ex;
        }
    }


    public static void deleteFinishedDeployments(final KubernetesClient kubernetesClient) throws IOException {
        int latestRunNumber = getLatestRunNumberFromWebserver();
        DeploymentList deploymentList = kubernetesClient.apps().deployments().inAnyNamespace().withLabel(METADATA_LABEL_TDAQ_WORKER_KEY, METADATA_LABEL_TDAQ_WORKER_VALUE).list();
        for (Deployment deployment : deploymentList.getItems()) {
            Map<String, String> labels = deployment.getMetadata().getLabels();
            String deploymentRunNumberString = labels.getOrDefault(METADATA_LABEL_RUN_NUMBER_KEY, null);
            /* First, check if the deployment contains the runNumber label */
            if (deploymentRunNumberString != null) {
                /* Secondly, check if the deployment's runNumber is lower than the latest runNumber, if so then delete the deployment */
                int deploymentRunNumber = Integer.parseInt(deploymentRunNumberString);
                if (deploymentRunNumber < latestRunNumber) {
                    String deploymentName = deployment.getMetadata().getName();
                    String namespace = deployment.getMetadata().getNamespace();
                    Boolean isDeleted = kubernetesClient.apps().deployments().inNamespace(namespace).withName(deploymentName).delete();
                    if (!isDeleted) {
                        /**
                         * TODO: What to do on failure to delete Deployment? Just accept for now and try again later?
                         */
                    }
                }
            }
        }
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

    @NotNull
    private String generateNewRunDeploymentName(Deployment deployment, int currentRunNumber, String runPipeName) {
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
}
