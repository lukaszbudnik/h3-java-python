import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AckResponse {
    @JsonProperty("ack")
    private int acknowledgedSequence;
    
    @JsonProperty("status")
    private String status;
    
    @JsonProperty("isFinal")
    private boolean isFinal;
    
    @JsonProperty("processingTimeMs")
    private long processingTimeMs;
    
    @JsonProperty("timestamp")
    private long timestamp;

    public AckResponse() {}
    
    public AckResponse(int ack, String status, boolean isFinal, long processingTime) {
        this.acknowledgedSequence = ack;
        this.status = status;
        this.isFinal = isFinal;
        this.processingTimeMs = processingTime;
        this.timestamp = System.currentTimeMillis();
    }

    public int getAcknowledgedSequence() { return acknowledgedSequence; }
    public String getStatus() { return status; }
    public boolean isFinal() { return isFinal; }
    public long getProcessingTimeMs() { return processingTimeMs; }
    public long getTimestamp() { return timestamp; }
}
