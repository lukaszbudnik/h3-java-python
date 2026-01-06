import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http.*;
import io.netty.incubator.codec.http3.*;
import io.netty.incubator.codec.quic.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Http3BidirectionalStreamServer {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private final ExecutorService processingExecutor;
    private static final File privateKeyFile = new File("server.key");
    private static final File certificatesFile = new File("server.crt");
    
    public Http3BidirectionalStreamServer(int processingThreads) {
        this.processingExecutor = Executors.newFixedThreadPool(processingThreads);
    }
    
    public void start(int port) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup();
        
        try {
            QuicSslContext sslContext = QuicSslContextBuilder.forServer(
                privateKeyFile, null, certificatesFile)
                .applicationProtocols("h3")
                .build();
            
            ChannelHandler codec = Http3.newQuicServerCodecBuilder()
                .sslContext(sslContext)
                .maxIdleTimeout(30000, TimeUnit.MILLISECONDS)
                .initialMaxData(100_000_000)
                .initialMaxStreamDataBidirectionalLocal(10_000_000)
                .initialMaxStreamDataBidirectionalRemote(10_000_000)
                .initialMaxStreamsBidirectional(100)
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel ch) {
                        System.out.println("New QUIC connection established: " + ch.remoteAddress());
                        System.out.println("Adding HTTP/3 server connection handler...");
                        
                        // Create HTTP/3 server connection handler with proper stream initializer
                        Http3ServerConnectionHandler http3Handler = new Http3ServerConnectionHandler(
                            new ChannelInitializer<QuicStreamChannel>() {
                                @Override
                                protected void initChannel(QuicStreamChannel streamCh) {
                                    System.out.println("=== NEW HTTP/3 STREAM CREATED: " + streamCh + " ===");
                                    
                                    // Add our custom handler first to see raw frames
                                    streamCh.pipeline().addLast("debug", new ChannelInboundHandlerAdapter() {
                                        @Override
                                        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                            System.out.println("Raw stream message: " + msg.getClass().getSimpleName());
                                            super.channelRead(ctx, msg);
                                        }
                                        
                                        @Override
                                        public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                            System.out.println("Stream channel active: " + ctx.channel());
                                            super.channelActive(ctx);
                                        }
                                    });
                                    
                                    // Add HTTP/3 frame codec
                                    streamCh.pipeline().addLast("http3-codec", new Http3FrameToHttpObjectCodec(true));
                                    
                                    // Add debug handler after codec to see converted messages
                                    streamCh.pipeline().addLast("debug-after-codec", new ChannelInboundHandlerAdapter() {
                                        @Override
                                        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                            System.out.println("After codec message: " + msg.getClass().getSimpleName());
                                            super.channelRead(ctx, msg);
                                        }
                                    });
                                    
                                    // Add our processing handler
                                    streamCh.pipeline().addLast("processor", new SlowProcessingStreamHandler(processingExecutor));
                                }
                            }
                        );
                        
                        ch.pipeline().addLast("http3-server", http3Handler);
                        // Add exception handler for the QUIC channel
                        ch.pipeline().addLast("quic-debug", new ChannelInboundHandlerAdapter() {
                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                System.err.println("QUIC connection exception: " + cause.getMessage());
                                cause.printStackTrace();
                            }
                            
                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) {
                                System.out.println("QUIC connection closed: " + ctx.channel());
                            }
                            
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                System.out.println("QUIC channel received: " + msg.getClass().getSimpleName() + " - " + msg);
                                super.channelRead(ctx, msg);
                            }
                        });
                    }
                })
                .build();
            
            Bootstrap bs = new Bootstrap();
            Channel channel = bs.group(group)
                .channel(NioDatagramChannel.class)
                .handler(codec)
                .bind(port).sync().channel();
            
            System.out.println("HTTP/3 Server started on port " + port);
            System.out.println("Server will process packets slowly to demonstrate async behavior");
            channel.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
            processingExecutor.shutdown();
        }
    }
    
    static class SlowProcessingStreamHandler extends ChannelInboundHandlerAdapter {
        private final ExecutorService processingExecutor;
        private final ConcurrentHashMap<Integer, Long> receivedTimes = new ConcurrentHashMap<>();
        private final AtomicInteger receivedCount = new AtomicInteger(0);
        private final AtomicInteger processedCount = new AtomicInteger(0);
        private boolean headersSent = false;
        private volatile int totalPacketsExpected = 0;
        private volatile boolean lastPacketReceived = false;
        
        public SlowProcessingStreamHandler(ExecutorService executor) {
            this.processingExecutor = executor;
        }
        
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            System.out.println("SlowProcessingStreamHandler received: " + msg.getClass().getSimpleName());
            
            if (msg instanceof HttpRequest) {
                HttpRequest request = (HttpRequest) msg;
                System.out.println("Received HTTP request: " + request.method() + " " + request.uri());
                System.out.println("Headers: " + request.headers());
                
                if (!headersSent) {
                    System.out.println("Sending response headers...");
                    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                    response.headers().set("content-type", "application/json");
                    ctx.write(response);
                    ctx.flush();
                    headersSent = true;
                    System.out.println("Response headers sent.");
                }
            }
            else if (msg instanceof HttpContent) {
                HttpContent content = (HttpContent) msg;
                ByteBuf data = content.content();
                
                if (data.readableBytes() > 0) {
                    String jsonData = data.toString(StandardCharsets.UTF_8);
                    System.out.println("Received data: " + jsonData);
                    
                    try {
                        DataPacket packet = JSON_MAPPER.readValue(jsonData, DataPacket.class);
                        long receiveTime = System.currentTimeMillis();
                        int count = receivedCount.incrementAndGet();
                        receivedTimes.put(packet.getSequenceNumber(), receiveTime);
                        
                        if (packet.isLast()) {
                            totalPacketsExpected = count;
                            lastPacketReceived = true;
                        }
                        
                        System.out.printf("[RECEIVED] Packet #%d (total received: %d, total processed: %d)%n",
                            packet.getSequenceNumber(), count, processedCount.get());
                        
                        processingExecutor.submit(() -> {
                            processPacketSlowly(ctx, packet, receiveTime);
                        });
                        
                    } catch (Exception e) {
                        System.err.println("Error parsing data: " + e.getMessage());
                        e.printStackTrace();
                        sendError(ctx, "Invalid JSON format");
                    }
                }
                
                if (msg instanceof LastHttpContent) {
                    System.out.println("Received last HTTP content - stream ended");
                }
            }
            else {
                System.out.println("Unexpected message type: " + msg);
                super.channelRead(ctx, msg);
            }
        }
        
        private void processPacketSlowly(ChannelHandlerContext ctx, DataPacket packet, long receiveTime) {
            try {
                long processingDelay = 500 + (long)(Math.random() * 1000);
                Thread.sleep(processingDelay);
                
                long startTime = receivedTimes.get(packet.getSequenceNumber());
                long totalTime = System.currentTimeMillis() - startTime;
                int processed = processedCount.incrementAndGet();
                
                System.out.printf("[PROCESSED] Packet #%d after %dms (total processed: %d)%n",
                    packet.getSequenceNumber(), totalTime, processed);
                
                // Determine if this will be the final ACK (when all packets are processed)
                boolean isFinalAck = (lastPacketReceived && processed == totalPacketsExpected);
                
                AckResponse ack = new AckResponse(
                    packet.getSequenceNumber(),
                    isFinalAck ? "complete" : "processed",
                    isFinalAck,
                    totalTime
                );
                
                String ackJson = JSON_MAPPER.writeValueAsString(ack);
                ByteBuf ackBuffer = Unpooled.copiedBuffer(ackJson, StandardCharsets.UTF_8);
                
                ctx.channel().eventLoop().execute(() -> {
                    ctx.writeAndFlush(new DefaultHttpContent(ackBuffer));
                    
                    // Check if ALL packets have been processed
                    if (lastPacketReceived && processed == totalPacketsExpected) {
                        System.out.println("[COMPLETE] All packets processed. Closing stream.");
                        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                        System.out.printf("[SUMMARY] Stream completed. Total packets: %d%n", processed);
                    }
                });
                
                if (packet.isLast()) {
                    System.out.println("[COMPLETE] Received last packet. Final ACK sent.");
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Processing interrupted: " + e.getMessage());
            } catch (Exception e) {
                System.err.println("Error processing packet: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        private void sendError(ChannelHandlerContext ctx, String message) {
            AckResponse errorAck = new AckResponse(-1, "error: " + message, true, 0);
            try {
                String errorJson = JSON_MAPPER.writeValueAsString(errorAck);
                ByteBuf errorBuffer = Unpooled.copiedBuffer(errorJson, StandardCharsets.UTF_8);
                ctx.write(new DefaultHttpContent(errorBuffer));
                
                ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("Server exception: " + cause.getMessage());
            cause.printStackTrace();
            ctx.close();
        }
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            System.out.println("Server stream channel active: " + ctx.channel());
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            System.out.println("Server stream channel inactive: " + ctx.channel());
        }
    }
}
