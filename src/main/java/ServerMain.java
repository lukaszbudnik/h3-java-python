public class ServerMain {
    public static void main(String[] args) throws Exception {
        Http3BidirectionalStreamServer server = new Http3BidirectionalStreamServer(4);
        server.start(9443);
    }
}
