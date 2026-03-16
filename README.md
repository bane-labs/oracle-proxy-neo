# Oracle Proxy - Neo3 Contracts

Oracle proxy contract for Neo3 that facilitates Oracle calls via message bridge. Built with Gradle and neow3j.

## Project Structure

```
oracle-proxy-neo/
├── src/
│   └── main/
│       └── java/
│           └── axlabs/
│               └── com/
│                   ├── oracleproxy/
│                   │   └── OracleProxy.java
│                   └── lib/
│                       └── EvmSerializerLib.java
├── wallets/                    # Wallet files (gitignored)
├── build.gradle                # Gradle build configuration
├── settings.gradle             # Gradle settings
├── gradle.properties           # Gradle properties
└── LICENSE                     # Apache 2.0 License
```

## Prerequisites

- Java 8 or higher
- Gradle (or use the included Gradle wrapper)

## Setup

1. Clone the repository:
```bash
   git clone <repository-url>
   cd oracle-proxy-neo
```

2. Build the project:
```bash
./gradlew build
```

Or on Windows:
```bash
gradlew.bat build
```

## Compilation

Compile the contract to NEF format using the neow3j Gradle plugin:

```bash
./gradlew neow3jCompile
```

Or specify the class name explicitly:
```bash
./gradlew neow3jCompile -PclassName=axlabs.com.oracleproxy.OracleProxy
```

Compiled artifacts will be generated in `build/neow3j/`:
- `OracleProxy.nef` - The compiled contract bytecode
- `OracleProxy.manifest.json` - The contract manifest
- `OracleProxy.nefdbgnfo` - Debug information

## Contract Overview

### OracleProxy.java

The Oracle proxy contract provides the following functionality:

- **Receives Oracle requests** via message bridge from EVM side
- **Makes Oracle requests** to the native Neo3 Oracle contract
- **Handles Oracle callbacks** and stores results
- **Sends results back** to EVM via message bridge

#### Key Functions

- `requestOracleData()` - Makes an Oracle request (called via message bridge)
- `onOracleResponse()` - Oracle callback handler (called by native Oracle contract)
- `getOracleResult()` - Retrieves stored Oracle result for a request ID
- `executeOracleResponse()` - Executes Oracle response by building and forwarding EVM call
- `setMessageBridge()` - Sets the message bridge contract address (owner only)
- `setTokenBridge()` - Sets the token bridge contract address (owner only)
- `setOwner()` - Sets the contract owner (owner only)
- `owner()` - Gets the contract owner address
- `upgrade()` - Upgrades the contract (owner only)

#### Events

- `OracleRequested` - Emitted when an Oracle request is made
- `OracleResponse` - Emitted when Oracle responds
- `OracleResponseStored` - Emitted when Oracle response is stored
- `OracleResponseExecuted` - Emitted when Oracle response is executed
- `MessageBridgeSent` - Emitted when a message is sent via message bridge

### EvmSerializerLib.java

Library for encoding data in EVM ABI format. Provides functions to serialize data for EVM contracts, including:
- Function selector encoding
- Parameter encoding (uint256, address, bytes, arrays)
- Full function call encoding

## Configuration

The `build.gradle` file configures:
- neow3j compiler plugin (version 3.24.0)
- Main contract class: `axlabs.com.oracleproxy.OracleProxy`
- Java source compatibility: 1.8
- Group: `axlabs.com.oracleproxy`

To compile a different contract, set the `className` property:
```bash
./gradlew neow3jCompile -PclassName=axlabs.com.oracleproxy.YourContract
```

## Deployment

### Prerequisites

1. **Neo3 Network Access** - Access to a Neo3 network (mainnet, testnet, or local)
2. **Wallet** - A Neo3 wallet with GAS for deployment
3. **RPC Endpoint** - Neo3 JSON-RPC endpoint URL

### Environment Variables

Set the following environment variables for deployment:

- `N3_JSON_RPC` - Neo3 JSON-RPC endpoint URL
- `WALLET_FILEPATH_DEPLOYER` - Path to deployer wallet file
- `WALLET_PASSWORD_DEPLOYER` - Password for deployer wallet (optional, empty string if no password)
- `N3_OWNER_ADDRESS` - Owner address for the contract (Neo3 address format)
- `N3_MESSAGE_BRIDGE_HASH` - Message bridge contract hash (optional)
- `N3_TOKEN_BRIDGE_HASH` - Token bridge contract hash (optional)
- `N3_HASH_FILE` - Optional path to save contract hash

### Manual Deployment

1. **Compile the contract:**
   ```bash
   ./gradlew neow3jCompile
   ```

2. **Deploy using neow3j tools or Neo3 CLI:**
   
   The contract requires deployment data in the format:
   ```java
   DeploymentData {
       Hash160 owner;
       Hash160 tokenBridge;  // Optional, can be zero
       Hash160 messageBridge;  // Optional, can be zero
   }
   ```

   Example using neow3j (requires deployment script):
   ```bash
   # Set environment variables
   export N3_JSON_RPC="http://localhost:40332"
   export WALLET_FILEPATH_DEPLOYER="wallets/deployer.json"
   export WALLET_PASSWORD_DEPLOYER=""
   export N3_OWNER_ADDRESS="NRozNKnv4aSMEUL3KyD4UyeHoiPdLpi4y6"
   
   # Run deployment (if deployment script exists)
```

### Post-Deployment Configuration

After deployment, configure the contract:

1. **Set Message Bridge Address:**
```bash
   # Call setMessageBridge() method with the message bridge contract hash
```

2. **Set Token Bridge Address:**
```bash
   # Call setTokenBridge() method with the token bridge contract hash
```

3. **Verify Configuration:**
```bash
   # Call getMessageBridge() to verify message bridge is set
   # Call getTokenBridge() to verify token bridge is set
   # Call owner() to verify owner is set
   ```

## Dependencies

- `io.neow3j:devpack:3.24.0` - Neo3 smart contract development pack
- `io.neow3j:compiler:3.24.0` - Neo3 compiler (for deployment tools)
- `io.neow3j:contract:3.24.0` - Neo3 contract utilities (for deployment tools)

## Testing

Run tests (if test files exist):
```bash
./gradlew test
```

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.

## Contributing

Contributions are welcome! Please ensure all code follows the existing style and includes appropriate tests.
