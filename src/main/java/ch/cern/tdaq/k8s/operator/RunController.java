package ch.cern.tdaq.k8s.operator;

import ch.cern.tdaq.k8s.operator.CustomResource.RunResource;
import com.github.containersolutions.operator.api.Context;
import com.github.containersolutions.operator.api.Controller;
import com.github.containersolutions.operator.api.ResourceController;
import com.github.containersolutions.operator.api.UpdateControl;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

@Controller(customResourceClass = RunResource.class,
        crdName = "runresource.operator.tdaq.cern.ch")
public class RunController implements ResourceController<RunResource> {
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final KubernetesClient kubernetesClient;
    private final int runNumberPaddingSize = 100000;

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

        int runNumber = resource.getSpec().getRunNumber();
        String runPipe = resource.getSpec().getRunPipe();

        try {
            createNewDeployment(runNumber, runPipe);
        } catch (IOException e) {
            e.printStackTrace();
            log.error("createOrReplaceDeployment failed", e);
            /* Throwing an error should cause a new call to createOrUpdateResource() after a backoff period. Double check! */
            throw new RuntimeException(e);
        }

        return UpdateControl.updateCustomResource(resource);
    }


    /**
     * Creates the worker deployment organized and monitored by this Operator
     * @throws IOException
     */
    @NotNull
    private void createNewDeployment(int runNumber, String runPipe) throws IOException {
        /**
         * TODO: insert the runNumber into the deployment, because the runNumber needs to be accessible as an enviroment variable for all containers in the Pod
         * TODO: make sure you add the runNumberPaddingSize to the deployment name!
         */
        String deploymentYamlPath = "worker-deployment.yaml"; /* Should we let the users use their own yaml, or yaml that is part of the JAR under /resources ? */
        try (InputStream yamlInputStream = getClass().getResourceAsStream(deploymentYamlPath)) {
            Deployment workerDeployment = kubernetesClient.apps().deployments().load(yamlInputStream).get();
            String nameSpace = workerDeployment.getMetadata().getNamespace();
            String name = workerDeployment.getMetadata().getName();
            Deployment currentDeploy = kubernetesClient.apps().deployments().inNamespace(nameSpace).withName(name).get();
            if (currentDeploy == null) {
                Deployment createdDeployment = kubernetesClient.apps().deployments().inNamespace(workerDeployment.getMetadata().getNamespace()).createOrReplace(workerDeployment);
                log.info("Created deployment: {}", deploymentYamlPath);
            }
        } catch (IOException ex) {
            log.error("createOrReplaceDeployment failed", ex);
            throw ex;
        }
    }

    /**
     * Deletes deployment running Pods with an old RunNumber if all the pods are completed
     * @throws IOException
     */
    @NotNull
    private void deleteOldDeploymentIfFinished(int currentRunNumber) throws IOException {
        DeploymentList deploymentList = kubernetesClient.apps().deployments().inNamespace("default").list();
        for (Deployment deployment : deploymentList.getItems()) {
            /**
             * TODO: Test which of these replica count values are the one I want to use!?
             */
            Integer currentReplicas = deployment.getStatus().getReplicas(); /* This should be currently running, bad and good. Probably the one I want, test!! */
            /*
            deployment.getStatus().getAvailableReplicas();
            deployment.getStatus().getReadyReplicas();
            deployment.getStatus().getUnavailableReplicas();
            deployment.getStatus().getUpdatedReplicas();
            DeploymentStatus stat = deployment.getStatus();
             */

            /*
            deployment.getSpec().getReplicas(); // This probably has the wanted number of replicas
            */

            String deploymentName = deployment.getMetadata().getName();
            /**
             * TODO: Unsure about how to handle null values. What does "null" mean? That the value is inaccessible, should we delete or not when null?
             */
            if (currentReplicas != null && currentReplicas == 0 && !isCurrentDeployment(deploymentName, currentRunNumber)) {
                /**
                 * TODO: delete the deployment
                 */
            }
        }
    }

    private boolean isCurrentDeployment(String deploymentName, int currentRunNumber) {
        /**
         * TODO: for this to work, runNumberPaddingSize must be the same as used in the actual deployment name!
         */
        String formattedRunNumber = String.format("%0" + runNumberPaddingSize + "d", currentRunNumber);
        return deploymentName.contains(formattedRunNumber);
    }

}
