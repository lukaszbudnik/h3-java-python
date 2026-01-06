#!/usr/bin/env python3

import asyncio
import json
import time
import ssl
from aioquic.asyncio.client import connect
from aioquic.asyncio.protocol import QuicConnectionProtocol
from aioquic.quic.configuration import QuicConfiguration
from aioquic.quic.events import QuicEvent
from aioquic.h3.connection import H3Connection
from aioquic.h3.events import HeadersReceived, DataReceived

class DataPacket:
    def __init__(self, seq, int_val, double_val, str_val, is_last):
        self.seq = seq
        self.intValue = int_val
        self.doubleValue = double_val
        self.stringValue = str_val
        self.isLast = is_last
        self.timestamp = int(time.time() * 1000)
    
    def to_json(self):
        return json.dumps({
            "seq": self.seq,
            "intValue": self.intValue,
            "doubleValue": self.doubleValue,
            "stringValue": self.stringValue,
            "isLast": self.isLast,
            "timestamp": self.timestamp
        })

class Http3ClientProtocol(QuicConnectionProtocol):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._events = asyncio.Queue()
        self._h3 = H3Connection(self._quic)
        self.ack_count = 0
        self.sent_times = {}

    def quic_event_received(self, event: QuicEvent) -> None:
        self._events.put_nowait(event)

    async def wait_for_event(self) -> QuicEvent:
        return await self._events.get()

async def receiver_task(client):
    """Reads responses until the stream is finished."""
    print("[Client] Listening for responses...")
    while True:
        try:
            event = await asyncio.wait_for(client.wait_for_event(), timeout=30.0)
        except asyncio.TimeoutError:
            print("[Client] Timeout waiting for response.")
            return

        for h3_event in client._h3.handle_event(event):
            if isinstance(h3_event, HeadersReceived):
                status = dict(h3_event.headers).get(b':status', b'unknown').decode()
                print(f"[Server] Headers Received. Status: {status}")
            
            if isinstance(h3_event, DataReceived):
                data = h3_event.data.decode().strip()
                if data:
                    try:
                        ack = json.loads(data)
                        receive_time = int(time.time() * 1000)
                        sent_time = client.sent_times.get(ack["ack"], 0)
                        rtt = receive_time - sent_time
                        client.ack_count += 1
                        
                        print(f"[ACK RECEIVED] Packet #{ack['ack']}: status={ack['status']}, "
                              f"processing={ack['processingTimeMs']}ms, RTT={rtt}ms "
                              f"(total ACKs: {client.ack_count})")
                        
                        if ack.get("isFinal", False):
                            print("\n=== PYTHON CLIENT: Received final ACK. Stream complete! ===")
                            return
                    except json.JSONDecodeError:
                        print(f"[Server] {data}")
                
                if h3_event.stream_ended:
                    print("[Client] Response stream ended. Exiting.")
                    return

        client.transmit()

async def main():
    print("=== HTTP/3 Bidirectional Streaming Demo (Python Client) ===")
    print("Python client sends fast, Java server processes slow\n")
    
    configuration = QuicConfiguration(
        is_client=True,
        alpn_protocols=["h3"],
    )
    configuration.verify_mode = ssl.CERT_NONE

    print("Connecting to localhost:9443...")

    async with connect(
        "localhost",
        9443,
        configuration=configuration,
        create_protocol=Http3ClientProtocol,
    ) as client:
        print("Connected! Sending packets...")
        
        # Create 10 packets to send
        packets = []
        for i in range(9):
            packets.append(DataPacket(
                i + 1,
                (i + 1) * 100,
                3.14159 * (i + 1),
                f"data-{i + 1}",
                False
            ))
        
        # Last packet marked as final
        packets.append(DataPacket(10, 1000, 2.71828, "final-data", True))
        
        stream_id = client._quic.get_next_available_stream_id()
        
        # Send headers
        client._h3.send_headers(
            stream_id=stream_id,
            headers=[
                (b":method", b"POST"),
                (b":scheme", b"https"),
                (b":authority", b"localhost:9443"),
                (b":path", b"/stream"),
                (b"content-type", b"application/json"),
            ],
            end_stream=False
        )
        
        print("\n=== PYTHON CLIENT: Sending all packets rapidly ===")
        
        # Send all packets
        for i, packet in enumerate(packets):
            send_time = int(time.time() * 1000)
            client.sent_times[packet.seq] = send_time
            
            json_data = packet.to_json()
            is_last = (i == len(packets) - 1)
            
            client._h3.send_data(
                stream_id=stream_id,
                data=json_data.encode(),
                end_stream=is_last
            )
            
            print(f"[SENT] Packet #{packet.seq} at {send_time}")
            await asyncio.sleep(0.05)  # 50ms delay
        
        client.transmit()
        print("=== PYTHON CLIENT: All packets sent! Now waiting for ACKs... ===\n")
        
        # Start receiver task
        recv_task = asyncio.create_task(receiver_task(client))
        await recv_task

if __name__ == "__main__":
    asyncio.run(main())
