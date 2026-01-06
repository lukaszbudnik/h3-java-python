import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http.*;
import io.netty.incubator.codec.http3.*;
import io.netty.incubator.codec.quic.*;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Http3Client {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    
    public void sendData(String host, int port, DataPacket[] packets) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup();
        
        try {
            QuicSslContext sslContext = QuicSslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocols("h3")
                .build();
            
            ChannelHandler codec = Http3.newQuicClientCodecBuilder()
                .sslContext(sslContext)
                .maxIdleTimeout(30000, TimeUnit.MILLISECONDS)
                .initialMaxData(100_000_000)
                .initialMaxStreamDataBidirectionalLocal(10_000_000)
                .initialMaxStreamDataBidirectionalRemote(10_000_000)
                .build();
            
            Bootstrap bs = new Bootstrap();
            Channel channel = bs.group(group)
                .channel(NioDatagramChannel.class)
                .handler(codec)
                .bind(0).sync().channel();
            
            QuicChannel quicChannel = QuicChannel.newBootstrap(channel)
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel ch) {
                        System.out.println("QUIC channel initialized: " + ch);
                        ch.pipeline().addLast(new Http3ClientConnectionHandler());
                    }
                })
                .remoteAddress(new InetSocketAddress(host, port))
                .connect()
                .get();
            
            System.out.println("QUIC connection established: " + quicChannel);
            
            try {
                System.out.println("Attempting to create bidirectional stream...");
                QuicStreamChannel streamChannel = (QuicStreamChannel) Http3.newRequestStream(
                    quicChannel,
                    new ClientHandler(packets)
                ).sync().getNow();
                
                System.out.println("Stream created successfully: " + streamChannel);
                
                // Wait for completion with timeout
                boolean completed = streamChannel.closeFuture().await(90, TimeUnit.SECONDS);
                if (!completed) {
                    System.err.println("Timeout waiting for stream to close!");
                }
            } catch (Exception e) {
                System.err.println("Failed to create/complete stream: " + e.getMessage());
                e.printStackTrace();
            }
            
            quicChannel.close().sync();
            
        } finally {
            group.shutdownGracefully();
        }
    }
    
    static class ClientHandler extends ChannelInboundHandlerAdapter {
        private final DataPacket[] packets;
        private final ConcurrentHashMap<Integer, Long> sentTimes = new ConcurrentHashMap<>();
        private final AtomicInteger ackCount = new AtomicInteger(0);
        private final AtomicInteger sentCount = new AtomicInteger(0);
        private volatile boolean allPacketsSent = false;
        
        public ClientHandler(DataPacket[] packets) {
            this.packets = packets;
        }
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            System.out.println("Stream channel active: " + ctx.channel());
            
            // Create HTTP/3 headers using Http3Headers (not HttpRequest)
            Http3Headers headers = new DefaultHttp3Headers();
            headers.method("POST");
            headers.path("/stream");
            headers.scheme("https");
            headers.authority("localhost:9443");  // or host:port
            headers.add("content-type", "application/json");
            
            System.out.println("Sending HTTP/3 headers: " + headers);
            
            // Send as Http3HeadersFrame directly (bypass codec for outbound)
            Http3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame(headers);
            ctx.writeAndFlush(headersFrame);
            
            // Start sending packets after a small delay
            ctx.executor().schedule(() -> {
                System.out.println("\n=== CLIENT: Sending all packets rapidly ===");
                sendNextPacket(ctx);
            }, 100, TimeUnit.MILLISECONDS);
        }
        
        private void sendNextPacket(ChannelHandlerContext ctx) {
            int index = sentCount.getAndIncrement();
            
            if (index < packets.length) {
                try {
                    DataPacket packet = packets[index];
                    long sendTime = System.currentTimeMillis();
                    sentTimes.put(packet.getSequenceNumber(), sendTime);
                    
                    String json = JSON_MAPPER.writeValueAsString(packet);
                    ByteBuf buffer = Unpooled.copiedBuffer(json, StandardCharsets.UTF_8);
                    
                    // Send as Http3DataFrame directly
                    Http3DataFrame dataFrame = new DefaultHttp3DataFrame(buffer);
                    ctx.writeAndFlush(dataFrame);
                    
                    System.out.printf("[SENT] Packet #%d at %d%n", 
                        packet.getSequenceNumber(), sendTime);
                    
                    // Schedule next packet after 50ms (non-blocking)
                    ctx.executor().schedule(() -> {
                        sendNextPacket(ctx);
                    }, 50, TimeUnit.MILLISECONDS);
                    
                } catch (Exception e) {
                    System.err.println("Error sending packet: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                // All packets sent - send empty data frame with end-of-stream flag
                System.out.println("=== CLIENT: All packets sent! Sending end marker... ===\n");
                Http3DataFrame endFrame = new DefaultHttp3DataFrame(Unpooled.EMPTY_BUFFER);
                ctx.writeAndFlush(endFrame);
                allPacketsSent = true;
                
                // Schedule timeout check
                ctx.executor().schedule(() -> {
                    if (ackCount.get() < packets.length) {
                        System.out.println("Timeout waiting for all ACKs! Received: " + ackCount.get() + "/" + packets.length);
                        ctx.close();
                    }
                }, 60, TimeUnit.SECONDS);
            }
        }
        
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            System.out.println("Client received message: " + msg.getClass().getSimpleName());
            
            // Work with raw HTTP/3 frames (no codec)
            if (msg instanceof Http3HeadersFrame) {
                Http3HeadersFrame headersFrame = (Http3HeadersFrame) msg;
                CharSequence status = headersFrame.headers().status();
                System.out.println("Received HTTP/3 headers. Status: " + status);
            }
            else if (msg instanceof Http3DataFrame) {
                Http3DataFrame dataFrame = (Http3DataFrame) msg;
                ByteBuf data = dataFrame.content();
                
                if (data.readableBytes() > 0) {
                    String ackJson = data.toString(StandardCharsets.UTF_8);
                    long receiveTime = System.currentTimeMillis();
                    
                    System.out.println("Received ACK JSON: " + ackJson);
                    
                    try {
                        AckResponse ack = JSON_MAPPER.readValue(ackJson, AckResponse.class);
                        Long sentTime = sentTimes.get(ack.getAcknowledgedSequence());
                        long roundTripTime = sentTime != null ? receiveTime - sentTime : -1;
                        int acks = ackCount.incrementAndGet();
                        
                        System.out.printf("[ACK RECEIVED] Packet #%d: status=%s, processing=%dms, RTT=%dms (total ACKs: %d)%n",
                            ack.getAcknowledgedSequence(),
                            ack.getStatus(),
                            ack.getProcessingTimeMs(),
                            roundTripTime,
                            acks);
                        
                        if (ack.isFinal()) {
                            System.out.println("\n=== CLIENT: Received final ACK. Stream complete! ===");
                            ctx.close();
                        }
                    } catch (Exception e) {
                        System.err.println("Error parsing ACK: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
            else {
                System.out.println("Unexpected message type: " + msg);
            }
        }
        
        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
            ctx.flush();
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("Client exception: " + cause.getMessage());
            cause.printStackTrace();
            ctx.close();
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            System.out.println("Client channel inactive");
        }
    }
}