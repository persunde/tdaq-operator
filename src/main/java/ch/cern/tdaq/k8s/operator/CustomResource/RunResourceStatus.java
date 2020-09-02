package ch.cern.tdaq.k8s.operator.CustomResource;

import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(
        using = JsonDeserializer.None.class
)
public class RunResourceStatus {
    private boolean isRunFinished;

    public boolean getIsRunFinished() { return isRunFinished; }
    public void setRunFinished(boolean runFinished) { isRunFinished = runFinished; }

    /*
    private ArrayList<String> runningDeployments = new ArrayList<>(); // Unsure of this type works out of the box like this with the K8S interface
    public ArrayList<String> getRunningDeployments() { return runningDeployments; }
    public void addRunningDeployment(String deploymentName) { runningDeployments.add(deploymentName); }
    public void removeRunningDeployment(String deploymentName) { runningDeployments.remove(deploymentName); }
     */
}
