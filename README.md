# DCC IO Daemon

A standalone Java daemon that provides a simple, clean API for controlling DCC command stations by leveraging JMRI's rich controller support without the complexity of JMRI's full object model and data structures.

## License

This project is licensed under the GNU General Public License version 2 (GPL v2). See [LICENSE.txt](LICENSE.txt) for details.

This license is compatible with JMRI (which also uses GPL v2), allowing dcc-io-daemon to link with JMRI's libraries.

## Acknowledgments

DCC IO Daemon uses [JMRI](https://www.jmri.org/) (Java Model Railroad Interface) for controller communication. JMRI is free software licensed under GPL v2, developed by the JMRI community. See [LICENSE.txt](LICENSE.txt) for full acknowledgment details.

## Philosophy

JMRI provides excellent support for a wide variety of DCC command stations through its `jmrix` layer, but using JMRI directly means dealing with its complex object model, data structures, and dependencies. This project bypasses that complexity by:

- **Using JMRI's jmrix layer for transport and message building** - We use each system's traffic controller and message classes (e.g. `XNetMessage.getSpeedAndDirectionMsg`, `getTurnoutCommandMsg`) to build and send packets, and we skip the higher-level bean layer (TurnoutManager, ThrottleManager) where it is unreliable. See [docs/JMRI-INTEGRATION.md](docs/JMRI-INTEGRATION.md) for the full pattern and how to add new controllers.
- **Providing simple, DCC-spec-focused APIs** - Clean interfaces for throttles and accessories that follow the DCC specification, not JMRI's object hierarchy.
- **Minimal dependencies** - Only what's needed for controller communication, not the full JMRI ecosystem.
- **RESTful API** - Language-agnostic HTTP/JSON interface for integration with other systems.

Perfect for headless deployments on Raspberry Pi or other embedded systems where you want DCC control without the overhead of a full JMRI installation.

## Features

- **Auto-Discovery**: Automatically detects and connects to USB/serial controllers using vendor/product IDs
- **Controller Role Assignment**: Assign controllers to handle throttles and/or accessories via web UI
- **Web-Based Configuration**: Modern web interface for managing connections and testing controllers
- **RESTful API**: Clean JSON API for integration with other applications
- **Real-Time Console**: Live message monitoring with decoded and raw hex output
- **Multi-Controller Support**: Handle multiple controllers with automatic role assignment
- **Platform Agnostic**: Works on Linux, Windows, and macOS

## Supported Controllers

- **Hornby Elite / XpressNet** (`xnet-elite`) - Serial/USB - Tested
  - See [XNET_ELITE_IMPLEMENTATION.md](XNET_ELITE_IMPLEMENTATION.md) for Elite-specific details.
- For how we interface with JMRI and how to add new controllers, see [docs/JMRI-INTEGRATION.md](docs/JMRI-INTEGRATION.md).
- **NCE PowerCab Serial** (`nce-serial`) - Serial/USB - Untested
- **NCE PowerCab USB** (`nce-usb`) - USB - Untested
- **DCC++ Ethernet** (`dccpp-ethernet`) - Network connection - Untested

## Prerequisites

- Java 11 or higher
- Maven 3.6+
- JMRI 5.13.6+ (either pre-built JAR or source)

## Building

### Option 1: Using Pre-built JMRI JAR (Recommended)

If you have a pre-built `jmri.jar`:

```bash
cd dcc-io-daemon
mvn clean package
```

The `pom.xml` is configured to use the pre-built JAR via system scope.

### Option 2: Building JMRI from Source

If you only have JMRI source code:

```bash
cd ../JMRI-5.13.6
ant
jar -cf jmri.jar -C java/build/classes .
mvn install:install-file -Dfile=jmri.jar -DgroupId=org.jmri -DartifactId=jmri -Dversion=5.13.6 -Dpackaging=jar
cd ../dcc-io-daemon
mvn clean package
```

### Using Build Scripts

The provided build scripts automatically detect and use pre-built JAR or build from source:

- **Linux/Mac**: `./build.sh`
- **Windows**: `build.bat`

## Running

### Basic Usage

```bash
java -jar target/dcc-io-daemon-0.1.0-SNAPSHOT-jar-with-dependencies.jar [port]
```

- If `port` is omitted, the daemon listens on port `9000` by default
- The web UI will be available at `http://localhost:9000/`

### With System-Scope JMRI

If using system scope for JMRI, you may need to add JMRI to the classpath:

```bash
java -cp "target/dcc-io-daemon-0.1.0-SNAPSHOT-jar-with-dependencies.jar:../JMRI/jmri.jar:../JMRI/lib/*" cc.panelsd.connect.daemon.DccIoDaemon [port]
```

## Web UI

The daemon includes a comprehensive web-based interface accessible at `http://localhost:9000/`.

### Features

- **Auto-Discovery**: Scan for connected controllers automatically
- **Connection Management**: Create and manage controller connections
- **Controller Role Assignment**: Assign controllers to handle throttles and/or accessories
- **Throttle Control**: Test throttle functionality (speed, direction, functions F0-F12)
- **Accessory Control**: Test turnout/accessory control
- **Live Console**: Monitor all messages with decoded and raw hex output
- **Command Station Info**: View version, model, and power status

### Connection Configuration

1. Click "🔍 Auto-Discover Controllers" to scan for connected devices
2. Select a detected device or manually configure:
   - Connection ID (e.g., `elite1`)
   - Controller Type (e.g., `Hornby Elite / XpressNet`)
   - Port (for serial/USB) or Host/Port (for network)
   - Baud Rate and Flow Control (for serial connections)
3. Click "Create Connection"

### Controller Roles

Controllers can be assigned to one or both roles:

- **Throttles**: Handles locomotive control (speed, direction, functions)
- **Accessories**: Handles turnout/accessory control

If only one controller is connected, it's automatically assigned both roles. If multiple controllers are present, you can assign roles via checkboxes in the connections list.

## API Endpoints

### Health & Status

- `GET /health` - Health check (`{"status":"ok"}`)
- `GET /connections` - List all active connections with status and roles

### Connection Management

- `POST /connections/create?id={id}&systemType={type}&portName={port}&...` - Create a new connection
  - Query parameters:
    - `id` (required): Connection identifier
    - `systemType` (required): Controller type (e.g., `xnet-elite`, `nce-usb`)
    - `portName` (serial): Serial/USB port (e.g., `/dev/ttyACM0`, `COM3`)
    - `host` (network): Hostname/IP for network connections
    - `port` (network): Port number for network connections
    - `baudRate` (serial, optional): Baud rate (default: 9600, Elite: 19200)
    - `flowControl` (serial, optional): `none` or `rtscts`
    - `userName` (optional): User name for the connection
    - `systemPrefix` (optional): System prefix

- `POST /connections/requestVersion?id={id}` - Request version info from command station
- `POST /connections/setRole?connectionId={id}&role={role}&enabled={true|false}` - Assign/unassign controller role
  - `role`: `throttles` or `accessories`

### System Information

- `GET /api/systems` - List supported controller types
- `GET /api/ports` - List available serial/USB ports
- `GET /api/discover` - Discover connected controllers (auto-detection)

### Real-Time Events

- `GET /api/events` - Server-Sent Events (SSE) stream for real-time updates
  - Event types: `MESSAGE_RECEIVED`, `MESSAGE_SENT`, `THROTTLE_UPDATED`, `POWER_CHANGED`, `CONNECTION_STATE_CHANGED`

## WebSocket JSON API

The WebSocket endpoint listens on `ws://<host>:<port+1>/json` (port is HTTP port + 1). Messages are JSON envelopes:

- `type`: resource name (e.g., `throttle`, `throttles`, `accessories`)
- `method`: optional, defaults to `get` (supported: `get`, `post`, `put` where a handler allows)
- `data`: optional object payload
- `list`: optional alternative to list all items of a type (delegates to handler `list`)
- `id`: optional request identifier (string) - if provided, will be echoed back in the response for request/response correlation

Error responses use:

```json
{ "type": "error", "data": { "code": 400, "message": "reason" }, "id": "req-1" }
```

If a request includes an `id`, all responses (including errors) will include the same `id` to enable request/response correlation.

### Broadcast events

The server broadcasts JSON to all connected clients when changes occur:

- `status` with `method: "patch"` – delta of changed connections. Only includes connections that changed (connect/disconnect, power status, or roles). Always includes `id`, `connected`, `powerStatus`, and `roles` for changed connections. `systemType` and `commandStation` are only included for new connections.
- `throttle` with `method: "patch"` – only the changed throttle fields plus identifiers (throttle id, address, longAddress). Functions are sent as an object with numeric string keys (e.g., `{ "functions": { "0": true, "1": false } }`). When speed is included, direction is always included.
- `accessories` with `method: "patch"` – array of changed accessories with `name` and `state`

### Status (WebSocket)

Returns server status plus active connections (mirrors the REST `/connections` route).

**Get status**

```json
{ "id": "req-1", "type": "status" }
```

Response:

```json
{
  "id": "req-1",
  "type": "status",
  "data": {
    "status": "ok",
    "connections": [
      {
        "id": "elite1",
        "systemType": "xnet-elite",
        "connected": true,
        "commandStation": { "version": "1.0" },
        "powerStatus": "ON",
        "roles": ["throttles", "accessories"]
      }
    ]
  }
}
```

**Set track power (POST):**

Control track power for a specific connection:

```json
{
  "id": "req-2",
  "type": "status",
  "method": "post",
  "data": {
    "connectionId": "elite1",
    "power": "OFF"
  }
}
```

Valid power values: `"ON"`, `"OFF"`, or `"IDLE"`.

Response:

```json
{
  "id": "req-2",
  "type": "status",
  "data": {
    "status": "ok",
    "connections": [
      {
        "id": "elite1",
        "connected": true,
        "powerStatus": "OFF",
        "roles": ["throttles", "accessories"]
      }
    ]
  }
}
```

The server will automatically broadcast a status patch to all connected clients when power changes.

**Status patch broadcasts:**

The server automatically broadcasts status patches (`type: "status", method: "patch"`) when:
- A controller connects or disconnects
- Power status changes (e.g., emergency stop button pressed, power turned off/on)
- Controller roles change

The patch format is a delta - only changed connections are included. Each changed connection always includes:
- `id` - connection identifier
- `connected` - connection state
- `powerStatus` - current power status
- `roles` - array of roles (e.g., `["throttles", "accessories"]`)

For new connections only, the patch also includes:
- `systemType` - system type identifier
- `commandStation` - command station information object

Example patch for a power status change:

```json
{
  "type": "status",
  "method": "patch",
  "data": {
    "status": "ok",
    "connections": [
      {
        "id": "elite1",
        "connected": true,
        "powerStatus": "OFF",
        "roles": ["throttles", "accessories"]
      }
    ]
  }
}
```

### Accessories (WebSocket)

POST runs commands and stores accessory name/state pairs; GET returns stored states. PUT is not supported.

**POST multiple accessories and commands**

```json
{
  "id": "req-1",
  "type": "accessories",
  "method": "post",
  "data": {
    "accessories": [
      { "name": "signal1", "state": "green" },
      { "name": "point1", "state": "thrown" }
    ],
    "commands": [
      { "address": 12, "state": "closed" },
      { "address": 1, "state": "thrown" }
    ]
  }
}
```

**Successful response**

```json
{
  "id": "req-1",
  "type": "accessories",
  "data": {
    "accessories": [
      { "name": "signal1", "state": "green" },
      { "name": "point1", "state": "thrown" }
    ],
    "commands": [
      { "address": 12, "closed": true },
      { "address": 1, "closed": false }
    ]
  }
}
```

Notes:
- `state` for commands accepts `closed` or `thrown`. Anything else yields 400.
- Commands execute immediately through the accessory controller; command results are not stored.
- Accessory `name`/`state` pairs are stored in memory for later GET/list retrieval.

**GET single accessory**

```json
{ "id": "req-2", "type": "accessories", "data": { "name": "signal1" } }
```

Response:

```json
{ "id": "req-2", "type": "accessories", "data": { "name": "signal1", "state": "green" } }
```

If unknown: `{ "id": "req-2", "type": "error", "data": { "code": 404, "message": "..." } }`.

**List all accessories (GET/list)**

```json
{ "id": "req-3", "list": "accessories" }
```
or
```json
{ "id": "req-3", "type": "accessories" }
```

Response:

```json
{
  "id": "req-3",
  "type": "accessories",
  "data": [
    { "name": "signal1", "state": "green" },
    { "name": "point1", "state": "thrown" }
  ]
}
```

### Throttles (WebSocket)

Clients POST directly to a DCC address - throttles are created/retrieved automatically. Only one client can control speed/direction per address at a time (2 second timeout). Functions can be controlled by any client concurrently.

**Get throttle status by address (GET):**

```json
{
  "id": "req-1",
  "type": "throttle",
  "data": {
    "address": 3
  }
}
```

Response:

```json
{
  "id": "req-1",
  "type": "throttle",
  "data": {
    "throttle": "conn1:3:false",
    "connectionId": "conn1",
    "address": 3,
    "longAddress": false,
    "speed": 0.6,
    "forward": true,
    "functions": {
      "0": false,
      "1": true,
      "2": false,
      "3": false,
      "4": false,
      "5": false,
      "6": false,
      "7": false,
      "8": false,
      "9": false,
      "10": false,
      "11": false,
      "12": false,
      "13": false,
      "14": false,
      "15": false,
      "16": false,
      "17": false,
      "18": false,
      "19": false,
      "20": false,
      "21": false,
      "22": false,
      "23": false,
      "24": false,
      "25": false,
      "26": false,
      "27": false,
      "28": false
    }
  }
}
```

If no throttle exists for that address: `type: "error", code: 404`.

**Control throttle by address (POST):**

```json
{
  "id": "req-1",
  "type": "throttle",
  "method": "post",
  "data": {
    "address": 3,
    "speed": 0.6,
    "forward": true,
    "functions": {
      "0": true,
      "1": true,
      "2": false
    }
  }
}
```

**Response:**

```json
{
  "id": "req-1",
  "type": "throttle",
  "data": {
    "throttle": "conn1:3:false",
    "connectionId": "conn1",
    "address": 3,
    "longAddress": false,
    "speed": 0.6,
    "forward": true,
    "functions": {
      "0": false,
      "1": true,
      "2": false,
      "3": false,
      "4": false,
      "5": false,
      "6": false,
      "7": false,
      "8": false,
      "9": false,
      "10": false,
      "11": false,
      "12": false,
      "13": false,
      "14": false,
      "15": false,
      "16": false,
      "17": false,
      "18": false,
      "19": false,
      "20": false,
      "21": false,
      "22": false,
      "23": false,
      "24": false,
      "25": false,
      "26": false,
      "27": false,
      "28": false
    },
    "updated": true
  }
}
```

**If another client has control (409 Conflict):**

```json
{
  "id": "req-1",
  "type": "error",
  "data": {
    "code": 409,
    "message": "Throttle busy: another client is controlling speed/direction for address 3"
  }
}
```

**Notes:**
- `address` (required): DCC address (1-9999)
- `longAddress` (optional): true for long addresses (default: false)
- `speed` (optional): 0.0 to 1.0
- `forward` (optional): true for forward, false for reverse
- `functions` (optional): Object with function numbers as string keys and boolean values (e.g., `{ "0": true, "1": false, "2": true }` for F0 on, F1 off, F2 on)
- Speed/direction: Exclusive lock per address (2 second timeout after last update)
- Functions: No locking - any client can control

**List all throttles:**

```json
{ "id": "req-2", "list": "throttles" }
```

Throttle responses return `type` of `throttle` or `throttles` with a `data` object/array. Errors use the standard error envelope.

## Configuration

### Device Discovery

Device auto-discovery is configured via `src/main/resources/device-discovery-config.json`. This file defines:

- Vendor ID and Product ID for USB device detection
- System type mapping
- Default configuration (baud rate, flow control, etc.)
- Description patterns for device matching

Example entry:

```json
{
  "name": "Hornby Elite",
  "vendorId": "0x04d8",
  "productId": "0x000a",
  "systemType": "xnet-elite",
  "config": {
    "baudRate": "19200",
    "flowControl": "none"
  },
  "descriptionPatterns": ["Hornby Elite", "XpressNet"]
}
```

### Auto-Connection

The daemon automatically:
- Scans for devices on startup
- Connects to detected controllers
- Assigns roles automatically (single controller = both roles, multiple = first gets both)
- Continuously monitors for new devices (every 5 seconds)

## Architecture

### Design Philosophy

This daemon is designed to be a thin abstraction layer over JMRI's `jmrix` implementations. We:

1. **Reuse JMRI's controller implementations** - All the protocol-specific code (XpressNet, DCC++, NCE, etc.) comes from JMRI
2. **Wrap with simple interfaces** - Our `CommandStationConnection`, `ThrottleSession`, and `AccessoryController` interfaces hide JMRI's complexity
3. **Avoid JMRI's object model** - We don't use JMRI's `InstanceManager`, `NamedBean` hierarchies, or other complex data structures
4. **Focus on DCC operations** - The API is centered on DCC specification operations (throttle control, accessory control) rather than JMRI concepts

### Core Components

- **`DccIoService`**: Main service interface for managing connections and throttles
- **`CommandStationConnection`**: Simple interface for controller connections (wraps JMRI's `SystemConnectionMemo` and adapters)
- **`ThrottleSession`**: Clean interface for locomotive control (wraps JMRI's `DccThrottle`)
- **`AccessoryController`**: Simple interface for turnout/accessory control (wraps JMRI's `TurnoutManager`)
- **`DeviceDiscoveryService`**: Platform-agnostic USB/serial device detection

### Connection Implementations

Each connection implementation:
- Wraps JMRI's `SystemConnectionMemo` and protocol-specific adapters
- Provides a simple `CommandStationConnection` interface
- Handles protocol-specific details internally (you don't need to know about XNet messages, NCE commands, etc.)
- Exposes only DCC-standard operations (throttle speed/direction/functions, accessory states)

- `XNetEliteConnection`: Hornby Elite / XpressNet (wraps JMRI's `XNetSystemConnectionMemo`)
- `NceSerialConnection`: NCE PowerCab Serial (wraps JMRI's `NceSystemConnectionMemo`)
- `NceUsbConnection`: NCE PowerCab USB (wraps JMRI's `NceSystemConnectionMemo`)
- `DccppConnection`: DCC++ Ethernet (wraps JMRI's `DCCppSystemConnectionMemo`)

### Web Server

Uses JDK's built-in `HttpServer` for minimal dependencies. Web UI is served from resources with separate HTML, CSS, and JavaScript files.

## Development

### Project Structure

```
dcc-io-daemon/
├── src/main/java/org/dccio/
│   ├── core/              # Core interfaces and services
│   │   ├── impl/          # Implementation classes
│   │   └── events/        # Event system
│   └── daemon/            # HTTP server and web UI
├── src/main/resources/
│   ├── web/               # Web UI files
│   │   ├── index.html
│   │   └── static/
│   │       ├── style.css
│   │       └── app.js
│   └── device-discovery-config.json
└── pom.xml
```

### Adding New Controllers

1. Create a new connection class implementing `CommandStationConnection`
2. Extend `BaseCommandStationConnection` for common functionality
3. Implement protocol-specific methods
4. Register in `DccIoServiceImpl.createConnection()`
5. Add device discovery entry to `device-discovery-config.json`

## Troubleshooting

### Controller Not Detected

- Check USB/serial port permissions (Linux: add user to `dialout` group)
- Verify vendor/product IDs in `device-discovery-config.json`
- Check device description patterns match actual device name

### Connection Fails

- Verify port name is correct (`/dev/ttyACM0`, `COM3`, etc.)
- Check baud rate matches controller requirements (Elite: 19200)
- Ensure controller is powered on and connected

### No Throttle/Accessory Controller Available

- Assign controller roles via web UI checkboxes
- Ensure at least one controller is connected and assigned the required role

## License

This project wraps JMRI components. Please refer to JMRI's license for usage terms.

## Contributing

Contributions welcome! Please ensure:
- Code follows existing style
- New controllers are properly tested
- Documentation is updated