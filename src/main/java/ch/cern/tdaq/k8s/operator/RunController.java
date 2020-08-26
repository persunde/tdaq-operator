package ch.cern.tdaq.k8s.operator;

import ch.cern.tdaq.k8s.operator.CustomResource.RunResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.containersolutions.operator.api.Context;
import com.github.containersolutions.operator.api.Controller;
import com.github.containersolutions.operator.api.ResourceController;
import com.github.containersolutions.operator.api.UpdateControl;
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

@Controller(customResourceClass = RunResource.class,
        crdName = "runresource.operator.tdaq.cern.ch")
public class RunController implements ResourceController<RunResource> {
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final KubernetesClient kubernetesClient;

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
            createOrReplaceDeployment(runNumber, runPipe);
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
    private void createOrReplaceDeployment(int runNumber, String runPipe) throws IOException {
        /**
         * TODO: insert the runNumber into the deployment, because the runNumber needs to be accessible as an enviroment variable for all containers in the Pod
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
}
