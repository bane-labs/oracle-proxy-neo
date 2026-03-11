package axlabs.com.oracleproxy;

import io.neow3j.contract.SmartContract;
import io.neow3j.protocol.Neow3j;
import io.neow3j.protocol.core.response.NeoApplicationLog;
import io.neow3j.protocol.core.response.NeoSendRawTransaction;
import io.neow3j.protocol.http.HttpService;
import io.neow3j.transaction.AccountSigner;
import io.neow3j.transaction.TransactionBuilder;
import io.neow3j.types.ContractParameter;
import io.neow3j.types.Hash160;
import io.neow3j.types.Hash256;
import io.neow3j.types.NeoVMStateType;
import io.neow3j.wallet.Account;
import io.neow3j.wallet.Wallet;
import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Script to set the execution manager address on the deployed OracleProxy contract.
 * <p>
 * Calls the contract's {@code setExecutionManager(Hash160)} method. The caller must be the current
 * contract owner (enforced by {@code onlyOwner()}).
 * <p>
 * Configuration can be provided in three ways (in order of precedence):
 * 1. System properties (-Pkey=value passed to Gradle)
 * 2. Environment variables
 * 3. .env file in the project root
 * <p>
 * Usage:
 *   ./gradlew setExecutionManager -PcontractHash=<deployed_contract_hash> [-PexecutionManager=<execution_manager_address>] [-PwalletPath=<wallet_path>] [-PwalletPassword=<wallet_password>] [-PrpcUrl=<rpc_url>] [-PdryRun=true]
 * <p>
 * Addresses can be provided in two formats:
 *   - Neo address: N... (e.g., NRozNKnv4aSMEUL3KyD4UyeHoiPdLpi4y6)
 *   - Hex hash: 0x... or without prefix (e.g., 0x1234... or 1234...)
 * <p>
 * Or create a .env file with:
 *   N3_CONTRACT_HASH=<deployed_contract_hash>        # Required (Neo address or hex hash)
 *   N3_EXECUTION_MANAGER=<execution_manager_address>  # Required (Neo address or hex hash)
 *   WALLET_FILEPATH_DEPLOYER=<wallet_path>            # Required
 *   WALLET_PASSWORD_DEPLOYER=<wallet_password>        # Optional
 *   N3_JSON_RPC=<rpc_url>                             # Optional, defaults to http://localhost:40332
 *   DRY_RUN=true                                       # Optional
 * <p>
 * Then run: ./gradlew setExecutionManager
 */
public class SetExecutionManager {

    private static final Logger logger = LoggerFactory.getLogger(SetExecutionManager.class);
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
        String contractHashStr = getConfig("contractHash", "N3_CONTRACT_HASH", true);
        String executionManagerStr = getConfig("executionManager", "N3_EXECUTION_MANAGER", true);
        String walletPath = getConfig("walletPath", "WALLET_FILEPATH_DEPLOYER", true);
        String walletPassword = getConfig("walletPassword", "WALLET_PASSWORD_DEPLOYER", false);
        String rpcUrl = getConfig("rpcUrl", "N3_JSON_RPC", false);
        if (rpcUrl == null || rpcUrl.isEmpty()) {
            rpcUrl = DEFAULT_RPC_URL;
        }
        String dryRunStr = getConfig("dryRun", "DRY_RUN", false);
        boolean dryRun = dryRunStr != null && (dryRunStr.equalsIgnoreCase("true") || dryRunStr.equals("1"));

        if (dryRun) {
            logger.info("=== DRY RUN MODE - Transaction will NOT be submitted ===");
        }
        logger.info("");
        logger.info("=== OracleProxy Set Execution Manager ===");
        logger.info("RPC URL:            {}", rpcUrl);
        logger.info("Contract Hash:     {}", contractHashStr);
        logger.info("Execution Manager: {}", executionManagerStr);
        logger.info("Wallet Path:       {}", walletPath);
        logger.info("Dry Run:           {}", dryRun);

        // Connect to Neo3 network
        Neow3j neow3j = Neow3j.build(new HttpService(rpcUrl));

        // Load NEP-6 wallet and decrypt accounts
        logger.info("");
        logger.info("Loading wallet...");
        Wallet wallet = Wallet.fromNEP6Wallet(new File(walletPath));
        String password = walletPassword != null ? walletPassword : "";
        wallet.decryptAllAccounts(password);
        Account account = wallet.getDefaultAccount();
        logger.info("Owner account:   {} ({})", account.getAddress(), account.getScriptHash());

        // Parse contract hash and execution manager address
        logger.info("");
        logger.info("Parsing addresses...");
        Hash160 contractHash = parseHash160(contractHashStr, "contractHash");
        Hash160 executionManager = parseHash160(executionManagerStr, "executionManager");
        logger.info("Contract hash:        {} ({})", contractHash, contractHash.toAddress());
        logger.info("Execution manager:    {} ({})", executionManager, executionManager.toAddress());

        // Build the setExecutionManager invocation:
        // setExecutionManager(Hash160 executionManagerHash)
        logger.info("");
        logger.info("=== Set Execution Manager Details ===");
        logger.info("Contract:            {} ({})", contractHash, contractHash.toAddress());
        logger.info("Execution Manager:   {} ({})", executionManager, executionManager.toAddress());
        logger.info("");
        logger.info("Building setExecutionManager transaction...");
        
        TransactionBuilder builder = new SmartContract(contractHash, neow3j)
                .invokeFunction("setExecutionManager",
                        ContractParameter.hash160(executionManager))
                .signers(AccountSigner.calledByEntry(account));

        if (dryRun) {
            logger.info("");
            logger.info("=== DRY RUN COMPLETE ===");
            logger.info("Transaction was prepared but NOT submitted to the network.");
            logger.info("Contract Hash:        {}", contractHash);
            logger.info("Contract Address:    {}", contractHash.toAddress());
            logger.info("Execution Manager:    {} ({})", executionManager, executionManager.toAddress());
            logger.info("");
            logger.info("To actually set the execution manager, run without -PdryRun=true or set DRY_RUN=false");
            return;
        }

        // Sign and send transaction
        logger.info("Signing setExecutionManager transaction...");
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
            logger.error("Failed to send setExecutionManager transaction: {}", response.getError().getMessage());
            throw new RuntimeException("Failed to send setExecutionManager transaction: " + response.getError().getMessage());
        }

        logger.info("SetExecutionManager transaction sent successfully: {}", txHash);

        // Wait for transaction to be included in a block.
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
                logger.info("=== SET EXECUTION MANAGER FAILED ===");
                logger.info("TX Hash:    {}", txHash);
                logger.info("VM State:   {}", execution.getState());
                logger.info("Exception:  {}", error);
                throw new RuntimeException("SetExecutionManager failed: " + error);
            }
            logger.info("");
            logger.info("=== SET EXECUTION MANAGER SUCCESSFUL ===");
            logger.info("TX Hash:            {}", txHash);
            logger.info("VM State:           {}", execution.getState());
            long gasConsumedRaw = Long.parseLong(execution.getGasConsumed());
            logger.info("GAS Consumed:       {} GAS ({})", String.format("%.8f", gasConsumedRaw / 1e8), gasConsumedRaw);
            logger.info("Contract Hash:      {}", contractHash);
            logger.info("Contract Address:   {}", contractHash.toAddress());
            logger.info("Execution Manager:   {} ({})", executionManager, executionManager.toAddress());
            logger.info("Notifications:      {}", execution.getNotifications().size());
        } else {
            logger.info("");
            logger.info("=== SET EXECUTION MANAGER SUCCESSFUL ===");
            logger.info("TX Hash:            {}", txHash);
            logger.info("Contract Hash:      {}", contractHash);
            logger.info("Contract Address:   {}", contractHash.toAddress());
            logger.info("Execution Manager:   {} ({})", executionManager, executionManager.toAddress());
            logger.warn("Note: Application log not available - some details may be missing");
        }
        
        logger.info("");
        logger.info("=== Set Execution Manager Summary ===");
        logger.info("Execution manager set successfully!");
        logger.info("Contract Hash:      {}", contractHash);
        logger.info("Contract Address:   {}", contractHash.toAddress());
        logger.info("Execution Manager:  {} ({})", executionManager, executionManager.toAddress());
        logger.info("Transaction:       {}", txHash);
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
