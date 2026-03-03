package axlabs.com.oracleproxy;

import io.neow3j.devpack.ByteString;
import io.neow3j.devpack.Hash160;
import io.neow3j.devpack.Storage;
import io.neow3j.devpack.StorageContext;
import io.neow3j.devpack.StorageMap;
import io.neow3j.devpack.annotations.CallFlags;
import io.neow3j.devpack.annotations.DisplayName;
import io.neow3j.devpack.annotations.EventParameterNames;
import io.neow3j.devpack.annotations.ManifestExtra;
import io.neow3j.devpack.annotations.OnDeployment;
import io.neow3j.devpack.annotations.OnNEP17Payment;
import io.neow3j.devpack.annotations.Permission;
import io.neow3j.devpack.annotations.Safe;
import io.neow3j.devpack.annotations.Struct;
import io.neow3j.devpack.contracts.ContractInterface;
import io.neow3j.devpack.contracts.CryptoLib;
import io.neow3j.devpack.contracts.GasToken;
import io.neow3j.devpack.contracts.OracleContract;
import io.neow3j.devpack.contracts.StdLib;
import io.neow3j.devpack.constants.NativeContract;
import io.neow3j.devpack.events.Event2Args;
import io.neow3j.devpack.events.Event3Args;
import io.neow3j.devpack.events.Event1Arg;
import io.neow3j.devpack.contracts.ContractManagement;
import axlabs.com.lib.EvmSerializerLib;

import static io.neow3j.devpack.Helper.abort;
import static io.neow3j.devpack.Helper.concat;
import static io.neow3j.devpack.Helper.toByteArray;
import io.neow3j.devpack.Runtime;
import io.neow3j.devpack.Signer;
import io.neow3j.devpack.Transaction;
import static io.neow3j.devpack.Runtime.checkWitness;
import static io.neow3j.devpack.Runtime.getCallingScriptHash;
import static io.neow3j.devpack.Runtime.getExecutingScriptHash;

/**
 * Oracle proxy contract for Oracle calls via message bridge.
 * 
 * This contract:
 * 1. Receives Oracle request calls via message bridge
 * 2. Makes Oracle requests to the native Oracle contract
 * 3. Handles Oracle responses
 * 4. Can send results back via message bridge
 */
@DisplayName("OracleProxy")
@ManifestExtra(key = "author", value = "AxLabs")
@ManifestExtra(key = "description", value = "Oracle proxy contract for Oracle calls via message bridge")
@Permission(contract = "*")
public class OracleProxy {

    private static final byte PREFIX_BASE = 0x0a;
    private static final int KEY_MESSAGE_BRIDGE = 0x01;
    private static final int KEY_NATIVE_BRIDGE = 0x02;
    private static final int KEY_OWNER = 0x03;
    private static final int KEY_ORACLE_RESULT = 0x10;
    private static final int KEY_REQUEST_ID = 0x11;

    /**
     * EVM function signature used to compute the on-chain selector.
     * keccak256("onOracleResult(uint256,bytes)")[0:4] = 0xed5cacbb
     */
    private static final String ON_ORACLE_RESULT_SIG = "onOracleResult(uint256,bytes)";

    private static final StorageContext ctx = Storage.getStorageContext();
    private static final StorageMap baseMap = new StorageMap(ctx, PREFIX_BASE);
    private static final StorageMap resultMap = new StorageMap(ctx, KEY_ORACLE_RESULT);

    // Native Oracle contract - no need to set address manually
    public static final OracleContract oracle = new OracleContract();
    public static final StdLib stdLib = new StdLib();
    public static final CryptoLib cryptoLib = new CryptoLib();

    // Events
    @DisplayName("OracleRequested")
    @EventParameterNames({"RequestId", "Url"})
    static Event2Args<Integer, String> onOracleRequested;

    @DisplayName("OracleResponse")
    @EventParameterNames({"Url", "ResponseCode"})
    static Event2Args<String, Integer> onOracleResponseEvent;

    @DisplayName("OracleResponseStored")
    @EventParameterNames({"RequestId", "Url", "Result"})
    static Event3Args<Integer, String, String> onOracleResponseStored;

    @DisplayName("OracleResponseExecuted")
    @EventParameterNames({"RequestId"})
    static Event1Arg<Integer> onOracleResponseExecuted;

    @DisplayName("MessageBridgeSent")
    @EventParameterNames({"RequestId", "MessageNonce"})
    static Event2Args<Integer, Integer> onMessageBridgeSent;

    /**
     * Struct to store Oracle result
     */
    public static class OracleResult {
        public int requestId;
        public String url;
        public int code;
        public String result;
        public boolean hasResult;
    }

    /**
     * Deployment data struct
     */
    @Struct
    public static class DeploymentData {
        public Hash160 owner;
        public Hash160 nativeBridge;
        public Hash160 messageBridge;
    }

    /**
     * Makes an Oracle request.
     * This method is called via message bridge from EVM side.
     * 
     * @param url The URL to request data from
     * @param filter JSONPath filter (can be empty string)
     * @param callbackContract The contract hash to receive the callback (this contract)
     * @param callbackMethod The method name for the callback
     * @param userData User data to pass to callback
     * @param gasForResponse GAS amount for callback execution
     * @return The Oracle request ID
     */
    public static int requestOracleData(
            String url,
            String filter,
            Hash160 callbackContract,
            String callbackMethod,
            int gasForResponse,
            int nonce,
            int requestId
    ) {
        // Claim native tokens from the bridge using the provided nonce
        Hash160 bridgeHash = baseMap.getHash160(KEY_NATIVE_BRIDGE);
        if (bridgeHash != null && !bridgeHash.isZero()) {
            BridgeInterface bridge = new BridgeInterface(bridgeHash);
            bridge.claimNative(nonce);
        }

        // Make Oracle request using the native Oracle contract
        oracle.request(url, filter, "onOracleResponse", requestId, gasForResponse);

        // Emit event after Oracle request
        onOracleRequested.fire(requestId, url);

        return requestId;
    }

    /**
     * Oracle callback method.
     * This is called by the Oracle contract when the request is fulfilled.
     * 
     * @param url The URL that was requested
     * @param userData The user data passed in the request (should contain requestId)
     * @param responseCode The response code (0 = success)
     * @param response The Oracle response result as ByteString
     */
    public static void onOracleResponse(String url, int userData, int responseCode, ByteString response) {
        // Only Oracle contract can call this
        if (getCallingScriptHash() != oracle.getHash()) {
            abort("Only Oracle contract can call this");
        }

        // Emit event with url and responseCode
        onOracleResponseEvent.fire(url, responseCode);

        // Convert ByteString response to String for storage
        String resultString = response.toString();

        // Extract requestId from userData (now an int, so use it directly)
        int requestId = userData;

        // Store result
        OracleResult oracleResult = new OracleResult();
        oracleResult.requestId = requestId;
        oracleResult.url = url;
        oracleResult.code = responseCode;
        oracleResult.result = resultString;
        oracleResult.hasResult = true;

        // Serialize once
        ByteString resultBytes = stdLib.serialize(oracleResult);
        
        // Store serialized result
        resultMap.put(requestId, resultBytes);

        // Emit event when Oracle response is stored
        onOracleResponseStored.fire(requestId, url, resultString);
    }

    /**
     * Sends a pre-built EVM call back to EVM via the message bridge as an executable message.
     * The call is expected to be an ABI-encoded AMBTypes.Call targeting ExampleBridge.onOracleResult.
     * Fires a {@code MessageBridgeSent} event with the Oracle request ID and the message nonce
     * assigned by the message bridge.
     *
     * @param requestId         The Oracle request ID (emitted in the event for traceability)
     * @param serializedEvmCall The ABI-encoded EVM call (AMBTypes.Call struct)
     * @param messageBridgeHash The message bridge contract hash
     */
    private static void sendResultViaMessageBridge(int requestId, ByteString serializedEvmCall, Hash160 messageBridgeHash) {
        // Call message bridge using ContractInterface
        MessageBridgeInterface messageBridge = new MessageBridgeInterface(messageBridgeHash);

        // Get sending fee
        int sendingFee = messageBridge.sendingFee();

        // Use the first transaction signer as the feeSponsor.
        // GasToken.transfer(feeSponsor, ...) internally calls checkWitness(feeSponsor),
        // which passes only for transaction signers. Using the tx sender (first signer)
        // ensures the fee is taken from the entity that initiated this transaction.
        Signer[] signers = Runtime.currentSigners();
        Hash160 feeSponsor = signers[0].account;

        // Send as executable message so the EVM bridge will call onOracleResult directly.
        // Capture the nonce assigned by the message bridge.
        int messageNonce = messageBridge.sendExecutableMessage(serializedEvmCall, false, feeSponsor, sendingFee);

        // Emit event so watchers (e.g. bridgeman) can link this Oracle request to the
        // outgoing message bridge nonce.
        onMessageBridgeSent.fire(requestId, messageNonce);
    }

    /**
     * Gets the stored Oracle result for a request ID.
     * 
     * @param requestId The Oracle request ID
     * @return The Oracle result
     */
    public static OracleResult getOracleResult(int requestId) {
        ByteString resultBytes = resultMap.get(requestId);
        if (resultBytes == null) {
            OracleResult emptyResult = new OracleResult();
            emptyResult.requestId = requestId;
            emptyResult.url = "";
            emptyResult.code = 0xff;
            emptyResult.hasResult = false;
            return emptyResult;
        }

        // Deserialize the stored result
        // We know it's an OracleResult since we store it that way
        OracleResult result = (OracleResult) stdLib.deserialize(resultBytes);
        
        // Ensure requestId matches (safety check)
        if (result.requestId != requestId) {
            result.requestId = requestId;
        }
        
        return result;
    }

    /**
     * Executes the Oracle response by building and forwarding the EVM call fully on-chain.
     * Called by bridgeman after it observes the {@code OracleResponseStored} event.
     *
     * <p>The contract reads the stored Oracle result, computes the EVM function selector from the
     * hardcoded method signature {@value #ON_ORACLE_RESULT_SIG}, and assembles both the EVM
     * calldata and the {@code AMBTypes.Call} wrapper.
     *
     * <p>The {@code AMBTypes.Call} encoding matches what Go's {@code abi.Pack} produces for a
     * single dynamic struct argument:
     * <pre>
     *   [outerOffset=32]
     *   [target (EVM address, left-padded to 32 bytes)]
     *   [allowFailure=0]
     *   [value=0]
     *   [calldataOffset=128]
     *   [calldataLength]
     *   [calldata right-padded to 32-byte boundary]
     * </pre>
     *
     * @param requestId        The Oracle request ID (must have a stored result)
     * @param evmTargetAddress 20-byte big-endian EVM address of the target contract on NeoX
     */
    public static void executeOracleResponse(int requestId, ByteString evmTargetAddress) {
        // Validate that a result exists for this request ID
        ByteString storedResultBytes = resultMap.get(requestId);
        if (storedResultBytes == null) {
            abort("Oracle result not found");
        }

        if (evmTargetAddress == null || evmTargetAddress.length() != 20) {
            abort("Invalid EVM target address (expected 20 bytes)");
        }

        // Deserialize stored oracle result
        OracleResult oracleResult = (OracleResult) stdLib.deserialize(storedResultBytes);

        // ── Step 1: Compute the 4-byte EVM function selector on-chain ────────────
        // keccak256("onOracleResult(uint256,bytes)")[0:4]
        ByteString selectorBytes = cryptoLib.keccak256(
                new ByteString(toByteArray(ON_ORACLE_RESULT_SIG))
        ).range(0, 4);

        // ── Step 2: Build complete EVM calldata ──────────────────────────────────
        // Layout: [selector(4)] [requestId(32)] [resultOffset(32)] [resultLen(32)] [resultData...]
        ByteString encodedRequestId = EvmSerializerLib.encodeUint256(requestId);
        ByteString callWithRequestId = EvmSerializerLib.appendArgToCall(selectorBytes, encodedRequestId);
        // callWithRequestId = 4 + 32 = 36 bytes → (36-4) % 32 == 0, auto-detect works ✓

        ByteString encodedResult = EvmSerializerLib.encodeString(oracleResult.result);
        ByteString evmCalldata = EvmSerializerLib.appendDynamicArgToCall(callWithRequestId, encodedResult);

        // ── Step 3: Wrap in AMBTypes.Call ABI encoding ───────────────────────────
        // The EVM ExecutionManager decodes rawMessage as abi.decode(raw, (AMBTypes.Call)) where
        //   AMBTypes.Call = (address target, bool allowFailure, uint256 value, bytes callData)

        // Outer offset = 32 (Go abi.Pack wraps a dynamic struct in an outer offset word)
        byte[] outerOffset = EvmSerializerLib.encodeUint256(32).toByteArray();

        // Target: 20-byte big-endian EVM address, left-padded with 12 zero bytes
        byte[] addrPadding = new byte[12];
        byte[] paddedTarget = concat(addrPadding, evmTargetAddress.toByteArray());

        byte[] encodedAllowFailure = EvmSerializerLib.encodeUint256(0).toByteArray(); // false
        byte[] encodedValue = EvmSerializerLib.encodeUint256(0).toByteArray();        // 0 ETH
        byte[] encodedCalldataOffset = EvmSerializerLib.encodeUint256(128).toByteArray(); // 4×32

        // callData tail: [length (32 bytes)][calldata padded to 32-byte boundary]
        byte[] encodedCalldataSection = EvmSerializerLib.encodeBytes(evmCalldata).toByteArray();

        byte[] rawMessageBytes = concat(
                concat(
                        concat(
                                concat(
                                        concat(outerOffset, paddedTarget),
                                        encodedAllowFailure
                                ),
                                encodedValue
                        ),
                        encodedCalldataOffset
                ),
                encodedCalldataSection
        );

        ByteString rawMessage = new ByteString(rawMessageBytes);

        // Forward the AMBTypes.Call-encoded message to the message bridge
        Hash160 messageBridgeHash = baseMap.getHash160(KEY_MESSAGE_BRIDGE);
        if (messageBridgeHash != null && !messageBridgeHash.isZero()) {
            sendResultViaMessageBridge(requestId, rawMessage, messageBridgeHash);
        }

        onOracleResponseExecuted.fire(requestId);
    }


    /**
     * Upgrades the contract.
     * Only the owner can call this method.
     * 
     * @param nef The new NEF file
     * @param manifest The new manifest
     * @param data Optional data for the update
     */
    public static void upgrade(ByteString nef, String manifest, Object data) {
        onlyOwner();
        new ContractManagement().update(nef, manifest, data);
    }

    /**
     * Deploy function called automatically when contract is deployed.
     * Sets the owner, native bridge, and message bridge during initial deployment.
     * 
     * @param data Deployment data. Should be a DeploymentData struct with owner, nativeBridge, and messageBridge.
     * @param isUpdate Whether this is an update (true) or initial deployment (false)
     */
    @OnDeployment
    public static void deploy(Object data, boolean isUpdate) {
        if (!isUpdate) {
            // On initial deployment, set owner, native bridge, and message bridge from deployment data
            if (data == null) {
                abort("Invalid deployment data - DeploymentData struct required");
            }
            DeploymentData deployData = (DeploymentData) data;
            
            // Validate and set owner
            if (deployData.owner == null || !Hash160.isValid(deployData.owner) || deployData.owner.isZero()) {
                abort("Invalid owner");
            }
            baseMap.put(KEY_OWNER, deployData.owner);
            
            // Owner must witness the deployment
            if (!checkWitness(owner())) {
                abort("Owner must witness the deployment");
            }
            
            // Set native bridge if provided
            if (deployData.nativeBridge != null && Hash160.isValid(deployData.nativeBridge) && !deployData.nativeBridge.isZero()) {
                baseMap.put(KEY_NATIVE_BRIDGE, deployData.nativeBridge);
            }
            
            // Set message bridge if provided
            if (deployData.messageBridge != null && Hash160.isValid(deployData.messageBridge) && !deployData.messageBridge.isZero()) {
                baseMap.put(KEY_MESSAGE_BRIDGE, deployData.messageBridge);
            }
        }
    }

    /**
     * Sets the message bridge contract address.
     * Only the owner can call this method.
     * 
     * @param messageBridgeHash The message bridge contract hash
     */
    public static void setMessageBridge(Hash160 messageBridgeHash) {
        onlyOwner();
        if (messageBridgeHash == null || !Hash160.isValid(messageBridgeHash) || messageBridgeHash.isZero()) {
            abort("Invalid message bridge hash");
        }
        baseMap.put(KEY_MESSAGE_BRIDGE, messageBridgeHash);
    }

    /**
     * Sets the bridge contract address.
     * Only the owner can call this method.
     * 
     * @param bridgeHash The bridge contract hash
     */
    public static void setNativeBridge(Hash160 bridgeHash) {
        onlyOwner();
        if (bridgeHash == null || !Hash160.isValid(bridgeHash) || bridgeHash.isZero()) {
            abort("Invalid bridge hash");
        }
        baseMap.put(KEY_NATIVE_BRIDGE, bridgeHash);
    }

    /**
     * Gets the bridge contract address.
     * 
     * @return The bridge contract hash
     */
    @Safe
    public static Hash160 getBridge() {
        return baseMap.getHash160(KEY_NATIVE_BRIDGE);
    }

    /**
     * Sets the owner address.
     * Only the current owner can call this method.
     * 
     * @param newOwner The new owner address
     */
    public static void setOwner(Hash160 newOwner) {
        onlyOwner();
        if (newOwner == null || !Hash160.isValid(newOwner)) {
            abort("Invalid new owner");
        }
        if (!checkWitness(newOwner)) {
            abort("New owner must witness owner change");
        }
        baseMap.put(KEY_OWNER, newOwner);
    }

    /**
     * Gets the owner address.
     * 
     * @return The owner address
     */
    @Safe
    public static Hash160 owner() {
        return baseMap.getHash160(KEY_OWNER);
    }

    /**
     * Checks that the caller is the owner.
     * Aborts if not authorized.
     */
    private static void onlyOwner() {
        if (!checkWitness(owner())) {
            abort("No authorization - only owner");
        }
    }

    /**
     * Gets the message bridge contract address.
     * 
     * @return The message bridge contract hash
     */
    public static Hash160 getMessageBridge() {
        return baseMap.getHash160(KEY_MESSAGE_BRIDGE);
    }

    /**
     * Gets the Oracle contract hash.
     * 
     * @return The Oracle native contract hash
     */
    @Safe
    public static Hash160 getOracleContract() {
        return oracle.getHash();
    }


    /**
     * NEP-17 payment callback.
     * This contract accepts only GAS token payments.
     * 
     * @param from The sender of the payment
     * @param amount The amount of tokens transferred
     * @param data Optional data parameter (not accepted)
     */
    @OnNEP17Payment
    public static void onNep17Payment(Hash160 from, int amount, Object data) {
        // Only accept GAS token payments
        Hash160 callingScriptHash = getCallingScriptHash();
        if (!callingScriptHash.equals(new GasToken().getHash())) {
            abort("Only GAS token payments are accepted");
        }
        
        // Reject payments with data
        if (data != null) {
            abort("No data accepted");
        }
        
        // Reject invalid amounts
        if (amount <= 0) {
            abort("Invalid amount");
        }
        
        // Payment accepted - GAS will be added to contract balance
    }

    /**
     * Interface for calling MessageBridge contract methods
     */
    private static class MessageBridgeInterface extends ContractInterface {
        public MessageBridgeInterface(Hash160 contractHash) {
            super(contractHash);
        }

        @CallFlags(io.neow3j.devpack.constants.CallFlags.ReadOnly)
        public native int sendingFee();

        @CallFlags(io.neow3j.devpack.constants.CallFlags.All)
        public native int sendExecutableMessage(ByteString rawMessage, boolean storeResult, Hash160 feeSponsor, int maxFee);
    }

    /**
     * Interface for calling Bridge contract methods
     */
    private static class BridgeInterface extends ContractInterface {
        public BridgeInterface(Hash160 contractHash) {
            super(contractHash);
        }

        @CallFlags(io.neow3j.devpack.constants.CallFlags.All)
        public native void claimNative(int nonce);
    }

}
