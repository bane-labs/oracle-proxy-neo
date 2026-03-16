package axlabs.com.oracleproxy;

import io.neow3j.devpack.ByteString;
import io.neow3j.devpack.Hash160;
import io.neow3j.devpack.Storage;
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
import io.neow3j.devpack.List;
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
    private static final int KEY_OWNER = 0x01;
    private static final int KEY_MESSAGE_BRIDGE = 0x02;
    private static final int KEY_NATIVE_BRIDGE = 0x03;
    private static final int KEY_EXECUTION_MANAGER = 0x04;
    private static final int KEY_EVM_ORACLE_PROXY = 0x05;
    private static final int KEY_ORACLE_RESULT = 0x10;
    private static final int KEY_REQUEST_ID = 0x11;

    /**
     * EVM function signature used to compute the on-chain selector.
     * keccak256("onOracleResult(uint256,uint256,string)")[0:4]
     */
    private static final String ON_ORACLE_RESULT_SIG = "onOracleResult(uint256,uint256,string)";

    /**
     * Custom response code indicating the serialized message exceeds the message bridge's
     * maximum allowed size. Not part of the Neo Oracle response codes (0x00–0x1c, 0xff).
     */
    private static final int RESPONSE_CODE_MESSAGE_TOO_LARGE = 0x20;

    private static final StorageMap baseMap = new StorageMap(PREFIX_BASE);
    private static final StorageMap resultMap = new StorageMap(KEY_ORACLE_RESULT);

    // Native Oracle contract - no need to set address manually
    public static final OracleContract oracle = new OracleContract();
    public static final StdLib stdLib = new StdLib();

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
    @EventParameterNames({"RequestId", "MessageNonce"})
    static Event2Args<Integer, Integer> onOracleResponseExecuted;

    @DisplayName("MessageTooLarge")
    @EventParameterNames({"RequestId", "MessageSize", "MaxSize"})
    static Event3Args<Integer, Integer, Integer> onMessageTooLarge;

    /**
     * Struct to store Oracle result
     */
    public static class OracleResult {
        public int requestId;
        public int gasOracleResponseReturn;
        public int code;
        public ByteString result;
    }

    /**
     * Struct passed as userData to Oracle request, returned in the callback.
     */
    @Struct
    public static class OracleUserData {
        public int requestId;
        public int gasOracleResponseReturn;
    }

    /**
     * Deployment data struct
     */
    @Struct
    public static class DeploymentData {
        public Hash160 owner;
        public Hash160 nativeBridge;
        public Hash160 messageBridge;
        public Hash160 executionManager;
        public Hash160 evmOracleProxy;
    }

    /**
     * Makes an Oracle request.
     * This method is called via message bridge from EVM side.
     * 
     * @param url The URL to request data from
     * @param filter JSONPath filter (can be empty string)
     * @param callbackMethod The method name for the callback
     * @param gasForOracle GAS amount for Oracle callback execution
     * @param gasOracleRequestExec GAS to refund the executor of this request transaction
     * @param gasOracleResponseReturn GAS reserved for the response return trip (stored in result, refunded on sendOracleResponse)
     * @param nonce The native bridge withdrawal nonce to claim
     * @param requestId The Oracle request ID assigned by the EVM contract
     * @return The Oracle request ID
     */
    public static int requestOracleData(
            String url,
            String filter,
            String callbackMethod,
            int gasForOracle,
	        int gasOracleRequestExec,
	        int gasOracleResponseReturn,
            int nonce,
            int requestId
    ) {
        onlyExecutionManager();
        // Claim native tokens from the bridge using the provided nonce
        Hash160 bridgeHash = getNativeBridge();

        // TODO, since anyone can claim, this could fail, needs to be fixed when there's a way to verify that a claim is still available.
        NativeBridgeInterface bridge = new NativeBridgeInterface(bridgeHash);
        bridge.claimNative(nonce);

        // Build userData struct so the Oracle callback receives both requestId
        // and the gas amount reserved for the response return trip.
        OracleUserData oracleUserData = new OracleUserData();
        oracleUserData.requestId = requestId;
        oracleUserData.gasOracleResponseReturn = gasOracleResponseReturn;
        ByteString serializedUserData = stdLib.serialize(oracleUserData);

        oracle.request(url, filter, "onOracleResponse", serializedUserData, gasForOracle);

        onOracleRequested.fire(requestId, url);

        // Refund the transaction executor for the cost of running this request
        Signer[] signers = Runtime.currentSigners();
        Hash160 txSender = signers[0].account;
        if (!new GasToken().transfer(getExecutingScriptHash(), txSender, gasOracleRequestExec, null)) {
            abort("Failed to refund Gas");
        }

        return requestId;
    }

    /**
     * Oracle callback method.
     * This is called by the Oracle contract when the request is fulfilled.
     * 
     * @param url The URL that was requested
     * @param userData Serialized OracleUserData struct (contains requestId and gasOracleResponseReturn) N3 Oracle contract can only callback the initiator of the oracle call, therefore we can trust this data.
     * @param responseCode The response code (0 = success)
     * @param response The Oracle response result as ByteString
     */
    public static void onOracleResponse(String url, ByteString userData, int responseCode, ByteString response) {
        if (getCallingScriptHash() != oracle.getHash()) {
            abort("Only Oracle contract can call this");
        }

        onOracleResponseEvent.fire(url, responseCode);

        // Deserialize the userData struct to recover requestId and gasOracleResponseReturn
        OracleUserData userDataStruct = (OracleUserData) stdLib.deserialize(userData);
        int requestId = userDataStruct.requestId;

        OracleResult oracleResult = new OracleResult();
        oracleResult.requestId = requestId;
        oracleResult.gasOracleResponseReturn = userDataStruct.gasOracleResponseReturn;
        oracleResult.code = responseCode;
        oracleResult.result = response;

        ByteString serialized = stdLib.serialize(oracleResult);
        resultMap.put(requestId, serialized);

        onOracleResponseStored.fire(requestId, url, response.toString());
    }

    /**
     * Builds the full AMBTypes.Call-wrapped message for
     * {@code onOracleResult(uint256 requestId, uint256 responseCode, string oracleResult)}
     * on the EVM OracleProxy contract.
     *
     * <h3>ABI encoding layout</h3>
     * <p>Solidity's ABI encodes fixed-size parameters (uint256) inline at their positional
     * slot (32 bytes each), but dynamic types (string, bytes, arrays) use an indirection
     * pattern: the positional slot holds an <em>offset</em> pointing to the location in the
     * tail section where the actual data lives (length-prefixed).</p>
     *
     * <p>For {@code onOracleResult(uint256, uint256, string)} the wire format is:</p>
     * <pre>
     *   [4-byte selector]
     *   slot 0: requestId                         (uint256, inline)
     *   slot 1: responseCode                      (uint256, inline)
     *   slot 2: offset to oracleResult data        (uint256, placeholder — filled by encoder)
     *   --- tail section ---
     *   slot 3: length of oracleResult string      (uint256)
     *   slot 4+: UTF-8 bytes of oracleResult       (padded to 32-byte boundary)
     * </pre>
     *
     * <p>The zero placeholder at index 2 in {@code callParams} reserves the positional slot.
     * {@code encodeFunctionCallWithDynamic} knows (via {@code callDynamicIndices}) that
     * index 2 is dynamic: it replaces the zero with the correct byte offset to the tail
     * section, then appends the length-prefixed string data from {@code callDynamicData}
     * after all fixed slots.</p>
     *
     * <p>Finally, the encoded calldata is wrapped in an {@code AMBTypes.Call} struct so the
     * message bridge's execution manager can route it to the target contract on EVM.</p>
     *
     * @param requestId        The Oracle request ID
     * @param responseCode     The response code (Oracle code, or custom e.g. MESSAGE_TOO_LARGE)
     * @param resultString     The oracle result string (may be empty on error)
     * @param evmOracleProxy 20-byte big-endian EVM address of the OracleProxy contract
     * @return The ABI-encoded AMBTypes.Call message ready to send via the message bridge
     */
    private static ByteString buildOracleResultMessage(
            int requestId, int responseCode, String resultString, Hash160 evmOracleProxy) {

        // Fixed params: two uint256 values inline, plus a placeholder for the dynamic offset
        List<ByteString> callParams = new List<>();
        callParams.add(EvmSerializerLib.encodeUint256(requestId));
        callParams.add(EvmSerializerLib.encodeUint256(responseCode));
        callParams.add(EvmSerializerLib.encodeUint256(0)); // slot 2: replaced with tail offset by encoder

        // Index 2 is the dynamic parameter (the string)
        int[] callDynamicIndices = new int[]{2};

        // The actual dynamic data: the ABI-encoded string (length + padded UTF-8 bytes)
        List<ByteString> callDynamicData = new List<>();
        callDynamicData.add(EvmSerializerLib.encodeString(resultString));

        ByteString evmCalldata = EvmSerializerLib.encodeFunctionCallWithDynamic(new CryptoLib(),
                ON_ORACLE_RESULT_SIG, callParams, callDynamicIndices, callDynamicData);

        return EvmSerializerLib.encodeAmbTypesCall(evmOracleProxy, false, 0, evmCalldata);
    }

    /**
     * Sends a pre-built EVM call back to EVM via the message bridge as an executable message.
     * The call is expected to be an ABI-encoded AMBTypes.Call targeting the EVM OracleProxy's
     * {@code onOracleResult}.
     *
     * @param serializedEvmCall The ABI-encoded EVM call (AMBTypes.Call struct)
     * @param messageBridgeHash The message bridge contract hash
     * @param sendingFee        The fee for sending the message
     * @return The message nonce assigned by the message bridge
     */
    private static int sendResultViaMessageBridge(ByteString serializedEvmCall, Hash160 messageBridgeHash, int sendingFee) {
        MessageBridgeInterface messageBridge = new MessageBridgeInterface(messageBridgeHash);

        // Use the first transaction signer as the feeSponsor.
        // GasToken.transfer(feeSponsor, ...) internally calls checkWitness(feeSponsor),
        // which passes only for transaction signers. Using the tx sender (first signer)
        // ensures the fee is taken from the entity that initiated this transaction.
        Signer[] signers = Runtime.currentSigners();
        Hash160 feeSponsor = signers[0].account;

        return messageBridge.sendExecutableMessage(serializedEvmCall, false, feeSponsor, sendingFee);
    }

    /**
     * Gets the stored Oracle result for a request ID.
     * 
     * @param requestId The Oracle request ID
     * @return The Oracle result
     */
    @Safe
    public static OracleResult getOracleResult(int requestId) {
        ByteString resultBytes = resultMap.get(requestId);
        if (resultBytes == null) {
            abort("No oracle result found for requestId");
        }

        // Deserialize the stored result
        // We know it's an OracleResult since we store it that way
        OracleResult result = (OracleResult) stdLib.deserialize(resultBytes);
        
        assert requestId == result.requestId : "requestId does not match result";
        
        return result;
    }

    /**
     * Executes the Oracle response by building and forwarding the EVM call fully on-chain.
     * Called by bridgeman after it observes the {@code OracleResponseStored} event.
     *
     * <p>The contract reads the stored Oracle result, builds the EVM calldata for
     * {@code onOracleResult(uint256, uint256, string)}, wraps it in an {@code AMBTypes.Call}
     * struct, and forwards it to the message bridge.
     *
     * @param requestId        The Oracle request ID (must have a stored result)
     */
    public static void sendOracleResponse(int requestId, int sendingFee) {
        // Validate that a result exists for this request ID
        OracleResult oracleResult = getOracleResult(requestId);

        Hash160 evmOracleProxy = getEvmOracleProxy();

        // Refund the transaction executor for the cost of sending the response back
        Signer[] signers = Runtime.currentSigners();
        Hash160 txSender = signers[0].account;
        if (!new GasToken().transfer(getExecutingScriptHash(), txSender, oracleResult.gasOracleResponseReturn, null)) {
            abort("Failed to refund Gas");
        }

        assert oracleResult.requestId == requestId : "RequestId mismatch";

        ByteString rawMessage = buildOracleResultMessage(
                oracleResult.requestId, oracleResult.code, oracleResult.result.toString(), evmOracleProxy);

        Hash160 messageBridge = getMessageBridge();

        MessageBridgeInterface messageBridgeInterface = new MessageBridgeInterface(messageBridge);
        int maxSize = messageBridgeInterface.maxMessageSize();
        int messageSize = rawMessage.length();

        
        // If result cannot be sent due to its size, re-encode result with custom response code and empty body
        if (messageSize > maxSize) {
            onMessageTooLarge.fire(oracleResult.requestId, messageSize, maxSize);
            rawMessage = buildOracleResultMessage(
                    oracleResult.requestId, RESPONSE_CODE_MESSAGE_TOO_LARGE, "", evmOracleProxy);
        }

        int messageNonce = sendResultViaMessageBridge(rawMessage, messageBridge, sendingFee);

        onOracleResponseExecuted.fire(requestId, messageNonce);
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
     * Sets the owner, native bridge, message bridge, and execution manager during initial deployment.
     * 
     * @param data Deployment data. Should be a DeploymentData struct with owner, nativeBridge, messageBridge, and executionManager.
     * @param isUpdate Whether this is an update (true) or initial deployment (false)
     */
    @OnDeployment
    public static void deploy(Object data, boolean isUpdate) {
        if (!isUpdate) {
            // On initial deployment, set owner, native bridge, message bridge, and execution manager from deployment data
            if (data == null) {
                abort("Invalid deployment data - DeploymentData struct required");
            }
            DeploymentData deployData = (DeploymentData) data;
            
            // Validate and set owner
            if (deployData.owner == null || !Hash160.isValid(deployData.owner) || deployData.owner.isZero()) {
                abort("Invalid owner");
            }
            baseMap.put(KEY_OWNER, deployData.owner);
            
            // Set native bridge if provided
            if (deployData.nativeBridge != null && Hash160.isValid(deployData.nativeBridge) && !deployData.nativeBridge.isZero()) {
                baseMap.put(KEY_NATIVE_BRIDGE, deployData.nativeBridge);
            }
            
            // Set message bridge if provided
            if (deployData.messageBridge != null && Hash160.isValid(deployData.messageBridge) && !deployData.messageBridge.isZero()) {
                baseMap.put(KEY_MESSAGE_BRIDGE, deployData.messageBridge);
            }
            
            // Set execution manager (required)
            if (deployData.executionManager == null || !Hash160.isValid(deployData.executionManager) || deployData.executionManager.isZero()) {
                abort("Invalid execution manager - execution manager is required");
            }
            baseMap.put(KEY_EXECUTION_MANAGER, deployData.executionManager);

            // Set EVM Oracle Proxy address if provided (20-byte big-endian EVM address)
            if (deployData.evmOracleProxy != null && Hash160.isValid(deployData.evmOracleProxy) && !deployData.evmOracleProxy.isZero()) {
                baseMap.put(KEY_EVM_ORACLE_PROXY, deployData.evmOracleProxy);
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
    public static Hash160 getNativeBridge() {
        Hash160 bridge = baseMap.getHash160(KEY_NATIVE_BRIDGE);
        if (bridge == null || bridge.isZero() || !Hash160.isValid(bridge)) {
            abort("Bridge not set");
        }
        return bridge;
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
    @Safe
    public static Hash160 getMessageBridge() {
        Hash160 messageBridge = baseMap.getHash160(KEY_MESSAGE_BRIDGE);
        if (messageBridge == null || messageBridge.isZero() || !Hash160.isValid(messageBridge)) {
            abort("Message bridge not set");
        }
        return messageBridge;
    }

    /**
     * Sets the execution manager contract address.
     * Only the owner can call this method.
     * 
     * @param executionManagerHash The execution manager contract hash
     */
    public static void setExecutionManager(Hash160 executionManagerHash) {
        onlyOwner();
        if (executionManagerHash == null || !Hash160.isValid(executionManagerHash) || executionManagerHash.isZero()) {
            abort("Invalid execution manager hash");
        }
        baseMap.put(KEY_EXECUTION_MANAGER, executionManagerHash);
    }

    /**
     * Gets the execution manager contract address.
     * 
     * @return The execution manager contract hash
     */
    @Safe
    public static Hash160 getExecutionManager() {
        Hash160 executionManager = baseMap.getHash160(KEY_EXECUTION_MANAGER);
        if (executionManager == null || executionManager.isZero() || !Hash160.isValid(executionManager)) {
            abort("Execution manager not set");
        }
        return executionManager;
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
     * Sets the EVM Oracle Proxy address (20-byte big-endian EVM address).
     * Only the owner can call this method.
     *
     * @param evmOracleProxyAddress 20-byte EVM address of the Oracle Proxy contract
     */
    public static void setEvmOracleProxy(Hash160 evmOracleProxyAddress) {
        onlyOwner();
        if (evmOracleProxyAddress == null || !Hash160.isValid(evmOracleProxyAddress) || evmOracleProxyAddress.isZero()) {
            abort("Invalid EVM Oracle Proxy address (expected 20 bytes)");
        }
        baseMap.put(KEY_EVM_ORACLE_PROXY, evmOracleProxyAddress);
    }

    /**
     * Gets the EVM Oracle Proxy address.
     *
     * @return The 20-byte EVM address of the Oracle Proxy contract
     */
    @Safe
    public static Hash160 getEvmOracleProxy() {
        Hash160 evmOracleProxy = baseMap.getHash160(KEY_EVM_ORACLE_PROXY);
        if (evmOracleProxy == null || evmOracleProxy.isZero() || !Hash160.isValid(evmOracleProxy)) {
            abort("EVM Oracle Proxy address not set");
        }
        return evmOracleProxy;
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
     * Checks that the caller is the execution manager.
     * Aborts if not authorized.
     */
    private static void onlyExecutionManager() {
        Hash160 executionManager = getExecutionManager();
        Hash160 callingScriptHash = getCallingScriptHash();
        if (!callingScriptHash.equals(executionManager)) {
            abort("No authorization - only execution manager");
        }
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

        @CallFlags(io.neow3j.devpack.constants.CallFlags.ReadOnly)
        public native int maxMessageSize();

        @CallFlags(io.neow3j.devpack.constants.CallFlags.All)
        public native int sendExecutableMessage(ByteString rawMessage, boolean storeResult, Hash160 feeSponsor, int maxFee);
    }

    /**
     * Interface for calling Bridge contract methods
     */
    private static class NativeBridgeInterface extends ContractInterface {
        public NativeBridgeInterface(Hash160 contractHash) {
            super(contractHash);
        }

        @CallFlags(io.neow3j.devpack.constants.CallFlags.All)
        public native void claimNative(int nonce);
    }

}
