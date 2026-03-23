package axlabs.com.oracleproxy;

import io.neow3j.contract.SmartContract;
import io.neow3j.protocol.Neow3j;
import io.neow3j.protocol.core.response.InvocationResult;
import io.neow3j.protocol.core.stackitem.StackItem;
import io.neow3j.protocol.http.HttpService;
import io.neow3j.types.Hash160;
import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

/**
 * Read-only script that queries all configurable values from the deployed OracleProxy contract
 * and prints them.
 * <p>
 * Usage:
 *   ./gradlew getConfig -PcontractHash=&lt;deployed_contract_hash&gt; [-PrpcUrl=&lt;rpc_url&gt;]
 * <p>
 * Or set in .env:
 *   N3_CONTRACT_HASH=&lt;deployed_contract_hash&gt;
 *   N3_JSON_RPC=&lt;rpc_url&gt;
 */
public class GetConfig {

    private static final Logger logger = LoggerFactory.getLogger(GetConfig.class);
    private static final String DEFAULT_RPC_URL = "http://localhost:40332";
    private static Dotenv dotenv = null;

    private static final String[] GETTER_FUNCTIONS = {
            "owner",
            "getTokenBridge",
            "getMessageBridge",
            "getExecutionManager",
            "getEvmOracleProxy",
            "getOracleContract",
    };

    public static void main(String[] args) throws Throwable {
        try {
            File envFile = new File(".env");
            if (envFile.exists()) {
                dotenv = Dotenv.configure()
                        .directory(".")
                        .filename(".env")
                        .ignoreIfMissing()
                        .load();
            }
        } catch (Exception e) {
            logger.debug("Could not load .env file: {}", e.getMessage());
        }

        String contractHashStr = getConfig("contractHash", "N3_CONTRACT_HASH", true);
        String rpcUrl = getConfig("rpcUrl", "N3_JSON_RPC", false);
        if (rpcUrl == null || rpcUrl.isEmpty()) {
            rpcUrl = DEFAULT_RPC_URL;
        }

        Hash160 contractHash = parseHash160(contractHashStr, "contractHash");
        Neow3j neow3j = Neow3j.build(new HttpService(rpcUrl));
        SmartContract contract = new SmartContract(contractHash, neow3j);

        logger.info("");
        logger.info("=== OracleProxy Configuration ===");
        logger.info("RPC URL:        {}", rpcUrl);
        logger.info("Contract Hash:  {}", contractHash);
        logger.info("Contract Addr:  {}", contractHash.toAddress());
        logger.info("");

        for (String fn : GETTER_FUNCTIONS) {
            String value = invokeRead(contract, fn);
            logger.info("{}: {}", padRight(fn, 24), value);
        }

        logger.info("");
    }

    private static String invokeRead(SmartContract contract, String function) {
        try {
            InvocationResult result = contract.callInvokeFunction(function)
                    .getInvocationResult();

            if (result.hasStateFault()) {
                return "FAULT - " + (result.getException() != null ? result.getException() : "unknown error");
            }

            List<StackItem> stack = result.getStack();
            if (stack.isEmpty()) {
                return "(empty)";
            }

            StackItem item = stack.get(0);
            return formatStackItem(item);
        } catch (Exception e) {
            return "ERROR - " + e.getMessage();
        }
    }

    private static String formatStackItem(StackItem item) {
        switch (item.getType()) {
            case BYTE_STRING:
            case BUFFER:
                try {
                    byte[] bytes = item.getByteArray();
                    if (bytes.length == 20) {
                        Hash160 hash = new Hash160(bytes);
                        return hash + " (" + hash.toAddress() + ")";
                    }
                    String hex = bytesToHex(bytes);
                    String ascii = tryAscii(bytes);
                    return ascii != null ? ascii + " (0x" + hex + ")" : "0x" + hex;
                } catch (Exception e) {
                    return item.getString();
                }
            case INTEGER:
                return item.getInteger().toString();
            case BOOLEAN:
                return String.valueOf(item.getBoolean());
            case ARRAY:
                return "(array with " + item.getList().size() + " elements)";
            default:
                return item.toString();
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String tryAscii(byte[] bytes) {
        for (byte b : bytes) {
            if (b < 0x20 || b > 0x7e) return null;
        }
        return new String(bytes);
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    private static String getConfig(String propertyName, String envName, boolean required) {
        String value = System.getProperty(propertyName);

        if (value == null || value.isEmpty()) {
            value = System.getenv(envName);
        }

        if ((value == null || value.isEmpty()) && dotenv != null) {
            value = dotenv.get(envName);
        }

        if (required && (value == null || value.isEmpty())) {
            throw new IllegalArgumentException("Required parameter missing: " + propertyName +
                    " (property), " + envName + " (environment variable), or in .env file");
        }

        return value;
    }

    private static Hash160 parseHash160(String input, String paramName) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException(paramName + " cannot be null or empty");
        }

        input = input.trim();

        if (input.startsWith("N") || input.startsWith("A")) {
            try {
                return Hash160.fromAddress(input);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid Neo address for " + paramName + ": " + input, e);
            }
        }

        try {
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
