package ch.cern.tdaq.k8s.operator.CustomResource;

import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(
        using = JsonDeserializer.None.class
)
public class RunResourceSpec {
    private String name;
    private int runNumber;
    private String runPipe;
    private String label;

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

    public String getLabel() {
        return label;
    }
    public void setLabel(String label) {
        this.label = label;
    }
}

