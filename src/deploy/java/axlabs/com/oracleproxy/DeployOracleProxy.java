package axlabs.com.oracleproxy;

import io.neow3j.contract.ContractManagement;
import io.neow3j.contract.NefFile;
import io.neow3j.contract.SmartContract;
import io.neow3j.protocol.Neow3j;
import io.neow3j.protocol.core.response.NeoApplicationLog;
import io.neow3j.protocol.core.response.NeoSendRawTransaction;
import io.neow3j.protocol.core.response.ContractManifest;
import io.neow3j.protocol.http.HttpService;
import io.neow3j.transaction.AccountSigner;
import io.neow3j.transaction.TransactionBuilder;
import io.neow3j.types.Hash160;
import io.neow3j.types.Hash256;
import io.neow3j.types.NeoVMStateType;
import io.neow3j.wallet.Account;
import io.neow3j.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.cdimascio.dotenv.Dotenv;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Deployment script for OracleProxy contract.
 *
 * Configuration can be provided in three ways (in order of precedence):
 * 1. System properties (-Pkey=value)
 * 2. Environment variables
 * 3. .env file in the project root
 *
 * Usage:
 *   ./gradlew deploy -Powner=<owner_address> -PexecutionManager=<execution_manager_address> [-PmessageBridge=<message_bridge_address>] [-PnativeBridge=<native_bridge_address>] [-PevmOracleProxy=<evm_oracle_proxy_20_byte_hex>] [-PwalletPath=<wallet_path>] [-PwalletPassword=<wallet_password>] [-PrpcUrl=<rpc_url>] [-PdryRun=true]
 *
 * Addresses can be provided in two formats:
 *   - Neo address: N... (e.g., NRozNKnv4aSMEUL3KyD4UyeHoiPdLpi4y6)
 *   - Hex hash: 0x... or without prefix (e.g., 0x1234... or 1234...)
 *
 * Or create a .env file with:
 *   N3_OWNER_ADDRESS=<owner_address>  # Required (Neo address or hex hash)
 *   N3_EXECUTION_MANAGER=<execution_manager_address>  # Required (Neo address or hex hash)
 *   N3_MESSAGE_BRIDGE=<message_bridge_address>  # Optional (Neo address or hex hash)
 *   N3_NATIVE_BRIDGE=<native_bridge_address>  # Optional (Neo address or hex hash)
 *   EVM_ORACLE_PROXY_ADDRESS=<evm_oracle_proxy_20_byte_hex>  # Optional (0x + 40 hex chars, can be set later via setEvmOracleProxy())
 *   WALLET_FILEPATH_DEPLOYER=<wallet_path>
 *   WALLET_PASSWORD_DEPLOYER=<wallet_password>  # Optional
 *   N3_JSON_RPC=<rpc_url>  # Optional, defaults to http://localhost:40332
 *   N3_HASH_FILE=<hash_file_path>  # Optional
 *   DRY_RUN=true  # Optional, if set to true, transaction will not be submitted
 *
 * Then run: ./gradlew deploy
 */
public class DeployOracleProxy {

    private static final Logger logger = LoggerFactory.getLogger(DeployOracleProxy.class);
    private static final String DEFAULT_RPC_URL = "http://localhost:40332";
    private static Dotenv dotenv = null;

    public static void main(String[] args) throws Throwable {
        // Load .env file if it exists (silently ignore if it doesn't)
        try {
            File envFile = new File(".env");
            if (envFile.exists()) {
                dotenv = Dotenv.configure()
                        .directory(".")
                        .filename(".env")
                        .ignoreIfMissing()
                        .load();
                logger.info("Loaded configuration from .env file");
            }
        } catch (Exception e) {
            logger.debug("Could not load .env file: {}", e.getMessage());
        }

        // Get configuration from system properties, environment variables, or .env file
        String ownerAddress = getConfig("owner", "N3_OWNER_ADDRESS", true);
        String messageBridge = getConfig("messageBridge", "N3_MESSAGE_BRIDGE", false);
        String nativeBridge = getConfig("nativeBridge", "N3_NATIVE_BRIDGE", false);
        String executionManager = getConfig("executionManager", "N3_EXECUTION_MANAGER", true);
        String evmOracleProxy = getConfig("evmOracleProxy", "EVM_ORACLE_PROXY_ADDRESS", false);
        String walletPath = getConfig("walletPath", "WALLET_FILEPATH_DEPLOYER", true);
        String walletPassword = getConfig("walletPassword", "WALLET_PASSWORD_DEPLOYER", false);
        String rpcUrl = getConfig("rpcUrl", "N3_JSON_RPC", false);
        if (rpcUrl == null || rpcUrl.isEmpty()) {
            rpcUrl = DEFAULT_RPC_URL;
        }
        String hashFile = getConfig("hashFile", "N3_HASH_FILE", false);
        String dryRunStr = getConfig("dryRun", "DRY_RUN", false);
        boolean dryRun = dryRunStr != null && (dryRunStr.equalsIgnoreCase("true") || dryRunStr.equals("1"));

        if (dryRun) {
            logger.info("=== DRY RUN MODE - Transaction will NOT be submitted ===");
        }
        logger.info("Deploying OracleProxy contract...");
        logger.info("RPC URL: {}", rpcUrl);
        logger.info("Owner: {}", ownerAddress);
        logger.info("Execution Manager: {}", executionManager);
        if (messageBridge != null && !messageBridge.isEmpty()) {
            logger.info("Message Bridge: {}", messageBridge);
        } else {
            logger.info("Message Bridge: (not set - can be set later via setMessageBridge())");
        }
        if (nativeBridge != null && !nativeBridge.isEmpty()) {
            logger.info("Native Bridge: {}", nativeBridge);
        } else {
            logger.info("Native Bridge: (not set - can be set later via setNativeBridge())");
        }
        if (evmOracleProxy != null && !evmOracleProxy.isEmpty()) {
            logger.info("EVM Oracle Proxy: {}", evmOracleProxy);
        } else {
            logger.info("EVM Oracle Proxy: (not set - can be set later via setEvmOracleProxy())");
        }

        // Connect to Neo3 network
        Neow3j neow3j = Neow3j.build(new HttpService(rpcUrl));

        // Load NEP-6 wallet and decrypt accounts
        Wallet wallet = Wallet.fromNEP6Wallet(new File(walletPath));
        String password = walletPassword != null ? walletPassword : "";
        wallet.decryptAllAccounts(password);
        Account account = wallet.getDefaultAccount();
        logger.info("Deployer account: {}", account.getAddress());

        // Load compiled contract files
        String buildDir = "build/neow3j";
        File nefFile = new File(buildDir, "OracleProxy.nef");
        File manifestFile = new File(buildDir, "OracleProxy.manifest.json");

        if (!nefFile.exists() || !manifestFile.exists()) {
            throw new IOException("Contract files not found. Please run './gradlew neow3jCompile' first.");
        }

        NefFile nef = NefFile.readFromFile(nefFile);
        logger.info("NEF file loaded: {} bytes", nef.toArray().length);
        logger.info("NEF checksum: {}", nef.getCheckSumAsInteger());
        
        // Parse manifest using Jackson ObjectMapper from Wallet
        String manifestJson = new String(Files.readAllBytes(manifestFile.toPath()));
        ContractManifest manifest = Wallet.OBJECT_MAPPER.readValue(manifestJson, ContractManifest.class);
        logger.info("Manifest loaded: {}", manifest.getName());
        logger.info("Manifest groups: {}", manifest.getGroups().size());
        logger.info("Manifest permissions: {}", manifest.getPermissions().size());

        // Parse addresses - support both Neo addresses (N...) and hex hashes (0x...)
        logger.info("Parsing addresses...");
        Hash160 owner = parseHash160(ownerAddress, "owner");
        logger.info("Owner hash: {} ({})", owner, owner.toAddress());
        
        Hash160 executionManagerHash = parseHash160(executionManager, "executionManager");
        logger.info("Execution manager hash: {} ({})", executionManagerHash, executionManagerHash.toAddress());
        
        Hash160 messageBridgeHash = null;
        if (messageBridge != null && !messageBridge.isEmpty()) {
            messageBridgeHash = parseHash160(messageBridge, "messageBridge");
            logger.info("Message bridge hash: {} ({})", messageBridgeHash, messageBridgeHash.toAddress());
        } else {
            logger.info("Message bridge: not set (will use zero hash)");
        }
        
        Hash160 nativeBridgeHash = null;
        if (nativeBridge != null && !nativeBridge.isEmpty()) {
            nativeBridgeHash = parseHash160(nativeBridge, "nativeBridge");
            logger.info("Native bridge hash: {} ({})", nativeBridgeHash, nativeBridgeHash.toAddress());
        } else {
            logger.info("Native bridge: not set (will use zero hash)");
        }

        // Create deployment data struct
        // DeploymentData: owner, nativeBridge, messageBridge, executionManager, evmOracleProxy (ByteString 20 bytes)
        // Use zero hash for optional bridges if not provided; use 20 zero bytes for evmOracleProxy if not provided
        Hash160 finalNativeBridge = nativeBridgeHash != null ? nativeBridgeHash : Hash160.ZERO;
        Hash160 finalMessageBridge = messageBridgeHash != null ? messageBridgeHash : Hash160.ZERO;
        Hash160 evmOracleProxyBytes = new Hash160(evmOracleProxy);

        logger.info("");
        logger.info("=== Deployment Data ===");
        logger.info("Owner:             {} ({})", owner, owner.toAddress());
        logger.info("Native Bridge:     {} ({})", finalNativeBridge, finalNativeBridge.toAddress());
        logger.info("Message Bridge:    {} ({})", finalMessageBridge, finalMessageBridge.toAddress());
        logger.info("Execution Manager: {} ({})", executionManagerHash, executionManagerHash.toAddress());
        logger.info("EVM Oracle Proxy: {} (20 bytes)", evmOracleProxy != null && !evmOracleProxy.isEmpty() ? evmOracleProxy : "(zeros - set later via setEvmOracleProxy())");
        logger.info("");

        io.neow3j.types.ContractParameter deploymentData = io.neow3j.types.ContractParameter.array(
                io.neow3j.types.ContractParameter.hash160(owner),
                io.neow3j.types.ContractParameter.hash160(finalNativeBridge),
                io.neow3j.types.ContractParameter.hash160(finalMessageBridge),
                io.neow3j.types.ContractParameter.hash160(executionManagerHash),
                io.neow3j.types.ContractParameter.hash160(evmOracleProxyBytes)
        );

        // Build deployment transaction using ContractManagement.
        // AccountSigner.global is required because checkWitness() is called inside the
        // @OnDeployment callback, which is invoked by ContractManagement (two call levels
        // deep from the entry script). calledByEntry would not cover that depth.
        logger.info("Building deployment transaction...");
        TransactionBuilder builder = new ContractManagement(neow3j)
                .deploy(nef, manifest, deploymentData)
                .signers(AccountSigner.global(account));

        // Calculate contract hash from sender, nef checksum, and manifest name
        Hash160 contractHash = SmartContract.calcContractHash(
                account.getScriptHash(),
                nef.getCheckSumAsInteger(),
                manifest.getName()
        );

        logger.info("Contract Hash (calculated): {}", contractHash);
        logger.info("Contract Address (calculated): {}", contractHash.toAddress());

        if (dryRun) {
            logger.info("");
            logger.info("=== DRY RUN COMPLETE ===");
            logger.info("Transaction was prepared but NOT submitted to the network.");
            logger.info("Contract Hash: {}", contractHash);
            logger.info("Contract Address: {}", contractHash.toAddress());
            logger.info("");
            logger.info("To actually deploy, run without -PdryRun=true or set DRY_RUN=false");

            // Save contract hash to file if specified (even in dry run)
            if (hashFile != null && !hashFile.isEmpty()) {
                Files.write(Paths.get(hashFile), contractHash.toString().getBytes());
                logger.info("Contract hash saved to: {}", hashFile);
            }
            return;
        }

        // Sign and send transaction
        logger.info("Signing deployment transaction...");
        io.neow3j.transaction.Transaction tx = builder.sign();
        
        // Log transaction details before sending
        Hash256 txHash = tx.getTxId();
        long networkFee = tx.getNetworkFee();
        long systemFee = tx.getSystemFee();
        long totalFee = networkFee + systemFee;
        logger.info("Transaction hash: {}", txHash);
        logger.info("Network fee:      {} GAS ({})", String.format("%.8f", networkFee / 1e8), networkFee);
        logger.info("System fee:        {} GAS ({})", String.format("%.8f", systemFee / 1e8), systemFee);
        logger.info("Total fee:         {} GAS ({})", String.format("%.8f", totalFee / 1e8), totalFee);
        logger.info("Sending transaction to network...");
        
        NeoSendRawTransaction response = tx.send();
        if (response.hasError()) {
            logger.error("Failed to send deployment transaction: {}", response.getError().getMessage());
            throw new RuntimeException("Failed to send deployment transaction: " + response.getError().getMessage());
        }

        logger.info("Deployment transaction sent successfully: {}", txHash);

        // Wait for transaction to be included in a block.
        // getTransaction returns the TX from the mempool before it is mined, but
        // getApplicationLog only works once the TX is in a confirmed block.
        // We check getBlockHash() != null to ensure it has been mined.
        logger.info("Waiting for transaction confirmation (max 60 seconds)...");
        io.neow3j.protocol.core.response.Transaction confirmedTx = null;
        int maxAttempts = 60;
        for (int i = 0; i < maxAttempts; i++) {
            Thread.sleep(1000);
            io.neow3j.protocol.core.response.NeoGetTransaction txResponse =
                    neow3j.getTransaction(txHash).send();
            if (!txResponse.hasError() && txResponse.getTransaction() != null
                    && txResponse.getTransaction().getBlockHash() != null) {
                confirmedTx = txResponse.getTransaction();
                logger.info("Transaction confirmed in block: {}", confirmedTx.getBlockHash());
                break;
            }
            if ((i + 1) % 5 == 0) {
                logger.info("Still waiting... ({} seconds elapsed)", i + 1);
            }
        }

        if (confirmedTx == null) {
            logger.error("Transaction not confirmed after {} seconds", maxAttempts);
            throw new RuntimeException("Transaction not confirmed after waiting");
        }

        logger.info("Fetching application log...");
        // Get application log to check result and log details
        NeoApplicationLog appLog = neow3j.getApplicationLog(txHash).send().getApplicationLog();
        if (appLog != null && !appLog.getExecutions().isEmpty()) {
            NeoApplicationLog.Execution execution = appLog.getFirstExecution();
            if (execution.getState() == NeoVMStateType.FAULT) {
                String error = execution.getException() != null ? execution.getException() : "Unknown error";
                logger.info("");
                logger.info("=== DEPLOYMENT FAILED ===");
                logger.info("TX Hash:    {}", txHash);
                logger.info("VM State:   {}", execution.getState());
                logger.info("Exception:  {}", error);
                throw new RuntimeException("Deployment failed: " + error);
            }
            logger.info("");
            logger.info("=== DEPLOYMENT SUCCESSFUL ===");
            logger.info("TX Hash:         {}", txHash);
            logger.info("VM State:        {}", execution.getState());
            long gasConsumedRaw = Long.parseLong(execution.getGasConsumed());
            logger.info("GAS Consumed:    {} GAS ({})", String.format("%.8f", gasConsumedRaw / 1e8), gasConsumedRaw);
            logger.info("Contract Hash:   {}", contractHash);
            logger.info("Contract Address:{}", contractHash.toAddress());
            logger.info("Notifications:   {}", execution.getNotifications().size());
        } else {
            logger.info("");
            logger.info("=== DEPLOYMENT SUCCESSFUL ===");
            logger.info("TX Hash:         {}", txHash);
            logger.info("Contract Hash:   {}", contractHash);
            logger.info("Contract Address:{}", contractHash.toAddress());
            logger.warn("Note: Application log not available - some details may be missing");
        }

        // Save contract hash to file if specified
        if (hashFile != null && !hashFile.isEmpty()) {
            Files.write(Paths.get(hashFile), contractHash.toString().getBytes());
            logger.info("");
            logger.info("Contract hash saved to: {}", hashFile);
        }
        
        logger.info("");
        logger.info("=== Deployment Summary ===");
        logger.info("Contract deployed successfully!");
        logger.info("Contract Hash:   {}", contractHash);
        logger.info("Contract Address:{}", contractHash.toAddress());
        logger.info("Transaction:     {}", txHash);
        logger.info("");
    }

    /**
     * Get configuration value from system properties, environment variables, or .env file.
     * Priority: System property > Environment variable > .env file
     */
    private static String getConfig(String propertyName, String envName, boolean required) {
        // First try system property
        String value = System.getProperty(propertyName);

        // Then try environment variable
        if (value == null || value.isEmpty()) {
            value = System.getenv(envName);
        }

        // Finally try .env file
        if ((value == null || value.isEmpty()) && dotenv != null) {
            value = dotenv.get(envName);
        }

        if (required && (value == null || value.isEmpty())) {
            throw new IllegalArgumentException("Required parameter missing: " + propertyName +
                    " (property), " + envName + " (environment variable), or in .env file");
        }

        return value;
    }

    /**
     * Parse a Hash160 from either a Neo address (N...) or a hex hash (0x... or without prefix).
     * Supports both formats for flexibility.
     */
    private static Hash160 parseHash160(String input, String paramName) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException(paramName + " cannot be null or empty");
        }

        input = input.trim();

        // Check if it's a Neo address (starts with 'N' or 'A')
        if (input.startsWith("N") || input.startsWith("A")) {
            try {
                return Hash160.fromAddress(input);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid Neo address for " + paramName + ": " + input, e);
            }
        }

        // Otherwise, treat as hex hash
        try {
            // Add 0x prefix if missing
            if (!input.startsWith("0x") && !input.startsWith("0X")) {
                input = "0x" + input;
            }
            return new Hash160(input);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid hash format for " + paramName + ": " + input +
                    " (expected Neo address starting with N/A or hex hash)", e);
        }
    }
}
