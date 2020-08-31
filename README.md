# tdaq-operator
Kubernetes Operator for organizing deployments of different runs

## Init before you start the Operator
First apply the webserver to the cluster. The Pods administered by the Operator will talk to this webserver to simulate 
fetching data from the data-storage.
``` bash
kubectl apply -f deployments/deploy-webserver.yaml
```

Apply the CRD to the cluster:
``` bash
kubectl apply -f crd/tdaq-crd.yaml
```

TODO:

Need to remember to apply the CRD, need to define this and write here how to apply it.