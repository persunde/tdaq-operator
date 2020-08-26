package ch.cern.tdaq.k8s.operator.CustomResource;

public class RunResourceStatus {
    private boolean isRunFinished;

    public boolean getIsRunFinished() { return isRunFinished; }
    public void setRunFinished(boolean runFinished) { isRunFinished = runFinished; }
}
