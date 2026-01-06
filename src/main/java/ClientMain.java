public class ClientMain {
    public static void main(String[] args) throws Exception {
        System.out.println("=== HTTP/3 Bidirectional Streaming Demo ===");
        System.out.println("Client sends fast, server processes slow\n");
        
        DataPacket[] packets = new DataPacket[10];
        for (int i = 0; i < 9; i++) {
            packets[i] = new DataPacket(
                i + 1,
                (i + 1) * 100,
                Math.PI * (i + 1),
                "data-" + (i + 1),
                false
            );
        }
        packets[9] = new DataPacket(10, 1000, Math.E, "final-data", true);
        
        Http3Client client = new Http3Client();
        client.sendData("localhost", 9443, packets);
    }
}
