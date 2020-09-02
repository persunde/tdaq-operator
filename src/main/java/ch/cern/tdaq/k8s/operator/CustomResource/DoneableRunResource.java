package ch.cern.tdaq.k8s.operator.CustomResource;

import io.fabric8.kubernetes.api.builder.Function;
import io.fabric8.kubernetes.client.CustomResourceDoneable;

public class DoneableRunResource extends CustomResourceDoneable<RunResource> {
    public DoneableRunResource(ch.cern.tdaq.k8s.operator.CustomResource.RunResource resource, Function function) { super(resource, function); }
}
