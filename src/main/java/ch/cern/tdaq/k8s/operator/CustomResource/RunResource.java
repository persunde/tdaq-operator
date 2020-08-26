package ch.cern.tdaq.k8s.operator.CustomResource;

import io.fabric8.kubernetes.client.CustomResource;

public class RunResource extends CustomResource {
    private RunResourceSpec spec;
    private RunResourceStatus status;

    public RunResourceSpec getSpec() {
        return spec;
    }
    public void setSpec(RunResourceSpec spec) {
        this.spec = spec;
    }

    public RunResourceStatus getStatus() { return status; }
    public void setStatus(RunResourceStatus status) {
        this.status = status;
    }
}
