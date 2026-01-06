# HTTP/3 Bidirectional Streaming Project

This project demonstrates HTTP/3 bidirectional streaming using Netty (Java) and aioquic (Python). It features a Java server that processes incoming data packets asynchronously and slowly to demonstrate flow control and bidirectional communication, with both Java and Python client implementations.

## Features
- ✅ **HTTP/3 & QUIC**: Full HTTP/3 bidirectional streaming.
- ✅ **Cross-Language**: Java Server with both Java and Python clients.
- ✅ **Asynchronous Processing**: Server uses a thread pool to process packets without blocking the event loop.
- ✅ **JSON Communication**: Structured data exchange using Jackson (Java) and json (Python).
- ✅ **Flow Control**: Demonstrates handling of rapid packet bursts with slow processing.

## Project Structure
```
.
├── src/main/java/
│   ├── Http3BidirectionalStreamServer.java # Server implementation
│   ├── Http3Client.java                    # Java Client implementation
│   ├── ServerMain.java                     # Server entry point
│   ├── ClientMain.java                     # Java Client entry point
│   ├── DataPacket.java                     # Data model for packets
│   ├── AckResponse.java                    # Response model for acknowledgments
│   └── QuicTest.java                       # QUIC availability test
├── python_client.py                        # Python Client implementation
├── pyproject.toml                          # Python configuration & dependencies (Poetry)
├── build.gradle                            # Gradle build configuration
└── poetry.lock                             # Locked Python dependencies
```

## Getting Started

### Prerequisites
- **Java 21** or higher.
- **Python 3.10** or higher (for the Python client).
- **Poetry** (for Python dependency management).
- **OpenSSL** (to generate certificates).

### 1. Generate SSL Certificates
The HTTP/3 server requires an SSL certificate and private key to function. Run the following command in the project root to generate self-signed certificates:

```bash
openssl req -x509 -newkey rsa:2048 -keyout server.key -out server.crt -days 365 -nodes -subj "/CN=localhost"
```

This will create `server.key` and `server.crt` in your current directory.

### 2. Setup Python Environment
This project uses **Poetry** for Python dependency management (similar to Gradle for Java).

1. **Install Poetry** (if not already installed):
   ```bash
   pip install poetry
   ```

2. **Install Dependencies**:
   ```bash
   poetry install
   ```

### 3. Running the Server
The server starts on port `9443` using the certificates generated in step 1.
```bash
./gradlew run
```

### 4. Running the Clients

#### Java Client
```bash
./gradlew runClient
```

#### Python Client
```bash
poetry run python python_client.py
```

## Implementation Details

### Java Client Fixes
The Java client has been optimized for:
- **Correct Pipeline Setup**: Uses `Http3ClientConnectionHandler` and `Http3.newRequestStream` for proper HTTP/3 frame handling.
- **Strict JSON Serialization**: Uses Jackson annotations to ensure JSON compatibility between Java and Python components.
- **Resilient Deserialization**: Handles variations in JSON field names (e.g., `isFinal` vs `final`) gracefully.

### Server Behavior
The server is designed to be "slow" by introducing a random delay (500ms - 1500ms) for each packet. This highlights the bidirectional nature of the protocol where the client can keep sending packets while waiting for individual ACKs to arrive out-of-order or delayed.

## Dependencies
- **Netty Incubator HTTP/3 & QUIC**: For the core protocol implementation.
- **Jackson Databind**: For JSON processing in Java.
- **aioquic**: For the Python client implementation.
