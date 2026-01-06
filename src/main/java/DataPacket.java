import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;

@JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE, isGetterVisibility = Visibility.NONE)
public class DataPacket {
    @JsonProperty("seq")
    private int sequenceNumber;
    
    @JsonProperty("intValue")
    private int intValue;
    
    @JsonProperty("doubleValue")
    private double doubleValue;
    
    @JsonProperty("stringValue")
    private String stringValue;
    
    @JsonProperty("isLast")
    private boolean isLast;
    
    @JsonProperty("timestamp")
    private long timestamp;

    public DataPacket() {}
    
    public DataPacket(int seq, int intVal, double doubleVal, String strVal, boolean isLast) {
        this.sequenceNumber = seq;
        this.intValue = intVal;
        this.doubleValue = doubleVal;
        this.stringValue = strVal;
        this.isLast = isLast;
        this.timestamp = System.currentTimeMillis();
    }

    public int getSequenceNumber() { return sequenceNumber; }
    public int getIntValue() { return intValue; }
    public double getDoubleValue() { return doubleValue; }
    public String getStringValue() { return stringValue; }
    public boolean isLast() { return isLast; }
    public long getTimestamp() { return timestamp; }
}
