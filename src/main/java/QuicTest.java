import io.netty.incubator.codec.quic.Quic;

public class QuicTest {
    public static void main(String[] args) {
        try {
            System.out.println("Testing QUIC availability...");
            boolean available = Quic.isAvailable();
            System.out.println("QUIC available: " + available);
            if (!available) {
                System.out.println("QUIC unavailability cause: " + Quic.unavailabilityCause());
            }
        } catch (Exception e) {
            System.err.println("Error testing QUIC: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
