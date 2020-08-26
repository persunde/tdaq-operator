package ch.cern.tdaq.k8s.operator;

import ch.cern.tdaq.k8s.operator.CustomResource.RunResource;

import com.github.containersolutions.operator.api.Context;
import com.github.containersolutions.operator.api.Controller;
import com.github.containersolutions.operator.api.ResourceController;
import com.github.containersolutions.operator.api.UpdateControl;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Controller(customResourceClass = RunResource.class,
        crdName = "runresource.operator.tdaq.cern.ch")
public class RunController implements ResourceController<RunResource> {
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final KubernetesClient kubernetesClient;
    private final int runNumberPaddingSize = 100000;

    private final String ENVIRONMENT_RUN_NUMBER_NAME = "RUN_NUMBER";
    private final String ENVIRONMENT_RUN_PIPE_NAME = "RUN_PIPE";

    private final String METADATA_LABEL_RUN_NUMBER_KEY = "tdaq.run-number";
    private final String METADATA_LABEL_RUN_PIPE_KEY = "tdaq.run-number";

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
        deleteFinishedDeployments();

        /**
         * TODO: add some notes/log to the status of the resource, to make it descriptive when queried by a human
         */

        return UpdateControl.updateCustomResource(resource);
    }

    /**
     * Finds the largest RunNumber in the cluster and returns it.
     * Finds the number based on metadata labels for each Pod in the "default" namespace.
     * @return The largest RunNumber of any deployment in the clusters "default" namespace
     */
    private int getLatestDeploymentRunNumberDeployed() {
        int largestRunNumber = -1;
        DeploymentList deploymentList = kubernetesClient.apps().deployments().inNamespace("default").list(); /* TODO: fix namespace. get it from ... ? */
        for (Deployment deployment : deploymentList.getItems()) {
            String runNumberAsString = deployment.getMetadata().getLabels().getOrDefault(ENVIRONMENT_RUN_NUMBER_NAME, "-1");
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
    @NotNull
    private void createNewDeployment(int runNumber, String runPipeName) throws IOException {
        String deploymentYamlPath = "run-deployment.yaml"; /* Should we let the users use their own yaml, or yaml that is part of the JAR under /resources ? */
        try (InputStream yamlInputStream = getClass().getResourceAsStream(deploymentYamlPath)) {
            Deployment newRunDeployment = kubernetesClient.apps().deployments().load(yamlInputStream).get();

            /* Set the metadata labels tdaq.run-number and tdaq.run-pipe for this deployment */
            Map<String, String> labels = newRunDeployment.getMetadata().getLabels();
            final String runNumberAsString = Integer.toString(runNumber);
            labels.put(METADATA_LABEL_RUN_NUMBER_KEY, runNumberAsString);
            labels.put(METADATA_LABEL_RUN_PIPE_KEY, runPipeName);
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
                 * */
                List<EnvVar> envVarList = container.getEnv();
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

            Deployment createdDeployment = kubernetesClient.apps().deployments().inNamespace(newRunDeployment.getMetadata().getNamespace()).create(newRunDeployment);

            log.info("Created deployment: {}", deploymentYamlPath);
        } catch (IOException ex) {
            log.error("createOrReplaceDeployment failed", ex);
            throw ex;
        }
    }

    /**
     * Deletes all deployments where all the pods are finished running.
     * NOTE: We assume the Pods terminate and are not rebooted after they exit/are finished. Probably use policty: OnFailure. Read more here:
     *  https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#restart-policy
     *  https://kubernetes.io/docs/concepts/workloads/controllers/deployment/#pod-template
     * @throws IOException If unable to read the yaml file from /resources
     */
    @NotNull
    private void deleteFinishedDeployments() {
        /**
         *  NOTE: If we have too many Pods (more than 10 000(???)) in the same namespace, it could cause a timeout problem when we query the API Server
         *  The SIG-Scalability group should have some answers regarding this. Check out their video from KubeCon December/September 2018 on YouTube.
         *
         *  Instead we could query based on labels? But then we have to be sure we will not miss deployments for a certain run!
         */
        String namespace = "default"; /* TODO: fix namespace. get it from ... ? */
        DeploymentList deploymentList = kubernetesClient.apps().deployments().inNamespace(namespace).list();
        for (Deployment deployment : deploymentList.getItems()) {
            /**
             * TODO: Test which of these replica count values are the one I want to use!?
             */
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
             * TODO: Unsure about how to handle null values. What does "null" mean? That the value is inaccessible, should we delete or not when null?
             */
            if (    currentReplicas != null
                    && currentReplicas == 0
            ) {
                String deploymentName = deployment.getMetadata().getName();
                Boolean isDeleted = kubernetesClient.apps().deployments().inNamespace(namespace).withName(deploymentName).delete();
                if (!isDeleted) {
                    /**
                     * TODO: What to do on failure to delete Deployment? Just accept for now and try again?
                     */
                }
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
     * @param deployment
     * @param currentRunNumber
     * @return False if deployment is the current deployment, True otherwise
     */
    @NotNull
    private boolean isNotCurrentDeployment(Deployment deployment, int currentRunNumber) {
        return !isCurrentDeployment(deployment, currentRunNumber);
    }

    /**
     * Returns true if the deployment given as parameter is the current latest deployed deployment.
     * This is done by matching the RunNumber in Deployment against the "currentRunNumber" value.
     * NOTE: for this to work, runNumberPaddingSize must be the same as used in the actual deployment name!
     * @param deployment
     * @param currentRunNumber
     * @return True if deployment is the current deployment, False otherwise
     */
    @NotNull
    private boolean isCurrentDeployment(Deployment deployment, int currentRunNumber) {
        String deploymentName = deployment.getMetadata().getName();
        String formattedRunNumber = String.format("%0" + runNumberPaddingSize + "d", currentRunNumber);
        return deploymentName.contains(formattedRunNumber);
    }

}
