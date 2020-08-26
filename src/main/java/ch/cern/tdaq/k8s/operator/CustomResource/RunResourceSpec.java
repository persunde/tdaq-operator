package ch.cern.tdaq.k8s.operator.CustomResource;

public class RunResourceSpec {
    private String name;
    private int runNumber;
    private String runPipe;

    public int getRunNumber() { return runNumber; }
    public void setRunNumber(int runNumber) { this.runNumber = runNumber; }

    public String getRunPipe() { return runPipe; }
    public void setRunPipe(String runPipe) { this.runPipe = runPipe; }

    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }
}

