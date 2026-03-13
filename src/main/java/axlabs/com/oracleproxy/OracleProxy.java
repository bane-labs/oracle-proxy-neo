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
    private static final int KEY_MESSAGE_BRIDGE = 0x01;
    private static final int KEY_NATIVE_BRIDGE = 0x02;
    private static final int KEY_OWNER = 0x03;
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

    @DisplayName("MessageTooLarge")
    @EventParameterNames({"RequestId", "MessageSize", "MaxSize"})
    static Event3Args<Integer, Integer, Integer> onMessageTooLarge;

    @DisplayName("MessageBridgeSent")
    @EventParameterNames({"RequestId", "MessageNonce"})
    static Event2Args<Integer, Integer> onMessageBridgeSent;

    /**
     * Struct to store Oracle result
     */
    public static class OracleResult {
        public int requestId;
        public int code;
        public ByteString result;
        public int gasOracleResponseReturn;
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
        public ByteString evmOracleProxy;
    }

    /**
     * Makes an Oracle request.
     * This method is called via message bridge from EVM side.
     * 
     * @param url The URL to request data from
     * @param filter JSONPath filter (can be empty string)
     * @param callbackContract The contract hash to receive the callback (this contract)
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
            Hash160 callbackContract,
            String callbackMethod,
            int gasForOracle,
	        int gasOracleRequestExec,
	        int gasOracleResponseReturn,
            int nonce,
            int requestId
    ) {
        onlyExecutionManager();
        // Claim native tokens from the bridge using the provided nonce
        Hash160 bridgeHash = baseMap.getHash160(KEY_NATIVE_BRIDGE);
        if (bridgeHash == null || bridgeHash.isZero()) {
            abort("NativeBridge hash not configured");
        }

        BridgeInterface bridge = new BridgeInterface(bridgeHash);
        bridge.claimNative(nonce);

        // Build userData struct so the Oracle callback receives both requestId
        // and the gas amount reserved for the response return trip.
        OracleUserData oracleUserData = new OracleUserData();
        oracleUserData.requestId = requestId;
        oracleUserData.gasOracleResponseReturn = gasOracleResponseReturn;
        ByteString serializedUserData = stdLib.serialize(oracleUserData);

        oracle.request(url, filter, "onOracleResponse", serializedUserData, gasForOracle);

        // Refund the transaction executor for the cost of running this request
        Signer[] signers = Runtime.currentSigners();
        Hash160 txSender = signers[0].account;
        new GasToken().transfer(getExecutingScriptHash(), txSender, gasOracleRequestExec, null);

        onOracleRequested.fire(requestId, url);

        return requestId;
    }

    /**
     * Oracle callback method.
     * This is called by the Oracle contract when the request is fulfilled.
     * 
     * @param url The URL that was requested
     * @param userData Serialized OracleUserData struct (contains requestId and gasOracleResponseReturn)
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
        oracleResult.code = responseCode;
        oracleResult.result = response;
        oracleResult.gasOracleResponseReturn = userDataStruct.gasOracleResponseReturn;

        ByteString serialized = stdLib.serialize(oracleResult);
        resultMap.put(requestId, serialized);

        onOracleResponseStored.fire(requestId, url, response.toString());
    }

    /**
     * Builds the full AMBTypes.Call-wrapped message for {@code onOracleResult(uint256, uint256, string)}.
     *
     * @param requestId        The Oracle request ID
     * @param responseCode     The response code (Oracle code or custom)
     * @param resultString     The oracle result string (may be empty)
     * @param evmTargetAddress 20-byte big-endian EVM target address
     * @return The ABI-encoded AMBTypes.Call message ready to send via the message bridge
     */
    private static ByteString buildOracleResultMessage(
            int requestId, int responseCode, String resultString, ByteString evmTargetAddress) {
        List<ByteString> callParams = new List<>();
        callParams.add(EvmSerializerLib.encodeUint256(requestId));      // _requestId
        callParams.add(EvmSerializerLib.encodeUint256(responseCode));   // responseCode
        callParams.add(EvmSerializerLib.encodeUint256(0));              // _oracleResult (dynamic placeholder)

        int[] callDynamicIndices = new int[]{2};

        List<ByteString> callDynamicData = new List<>();
        callDynamicData.add(EvmSerializerLib.encodeString(resultString));

        ByteString evmCalldata = EvmSerializerLib.encodeFunctionCallWithDynamic(
                cryptoLib, ON_ORACLE_RESULT_SIG, callParams, callDynamicIndices, callDynamicData);

        return EvmSerializerLib.encodeAmbTypesCall(evmTargetAddress, false, 0, evmCalldata);
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
            emptyResult.code = 0xff;
            emptyResult.result = new ByteString("");
            emptyResult.gasOracleResponseReturn = 0;
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
     * <p>The contract reads the stored Oracle result, builds the EVM calldata for
     * {@code onOracleResult(uint256, uint256, string)}, wraps it in an {@code AMBTypes.Call}
     * struct, and forwards it to the message bridge.
     *
     * @param requestId        The Oracle request ID (must have a stored result)
     */
    public static void sendOracleResponse(int requestId) {
        // Validate that a result exists for this request ID
        ByteString storedResultBytes = resultMap.get(requestId);
        if (storedResultBytes == null) {
            abort("Oracle result not found");
        }

        ByteString evmTargetAddress = baseMap.get(KEY_EVM_ORACLE_PROXY);
        if (evmTargetAddress == null || evmTargetAddress.length() != 20) {
            abort("Invalid EVM target address (expected 20 bytes)");
        }

        OracleResult oracleResult = (OracleResult) stdLib.deserialize(storedResultBytes);

        // Refund the transaction executor for the cost of sending the response back
        Signer[] signers = Runtime.currentSigners();
        Hash160 txSender = signers[0].account;
        new GasToken().transfer(getExecutingScriptHash(), txSender, oracleResult.gasOracleResponseReturn, null);

        if (oracleResult.requestId != requestId) {
            abort("RequestId mismatch");
        }

        ByteString rawMessage = buildOracleResultMessage(
                oracleResult.requestId, oracleResult.code, oracleResult.result.toString(), evmTargetAddress);

        Hash160 messageBridgeHash = baseMap.getHash160(KEY_MESSAGE_BRIDGE);
        if (messageBridgeHash == null || messageBridgeHash.isZero()) {
            abort("Message bridge hash not set");
        }

        MessageBridgeInterface messageBridge = new MessageBridgeInterface(messageBridgeHash);
        int maxSize = messageBridge.maxMessageSize();
        int messageSize = rawMessage.length();

        if (maxSize > 0 && messageSize > maxSize) {
            // Message exceeds bridge limit — re-encode with custom response code and empty result
            onMessageTooLarge.fire(oracleResult.requestId, messageSize, maxSize);
            rawMessage = buildOracleResultMessage(
                    oracleResult.requestId, RESPONSE_CODE_MESSAGE_TOO_LARGE, "", evmTargetAddress);
        }

        sendResultViaMessageBridge(requestId, rawMessage, messageBridgeHash);

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
            
            // Set execution manager (required)
            if (deployData.executionManager == null || !Hash160.isValid(deployData.executionManager) || deployData.executionManager.isZero()) {
                abort("Invalid execution manager - execution manager is required");
            }
            baseMap.put(KEY_EXECUTION_MANAGER, deployData.executionManager);

            // Set EVM Oracle Proxy address if provided (20-byte big-endian EVM address)
            if (deployData.evmOracleProxy != null && deployData.evmOracleProxy.length() == 20) {
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
    @Safe
    public static Hash160 getMessageBridge() {
        return baseMap.getHash160(KEY_MESSAGE_BRIDGE);
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
        return baseMap.getHash160(KEY_EXECUTION_MANAGER);
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
    public static void setEvmOracleProxy(ByteString evmOracleProxyAddress) {
        onlyOwner();
        if (evmOracleProxyAddress == null || evmOracleProxyAddress.length() != 20) {
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
    public static ByteString getEvmOracleProxy() {
        return baseMap.get(KEY_EVM_ORACLE_PROXY);
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
        Hash160 executionManager = baseMap.getHash160(KEY_EXECUTION_MANAGER);
        if (executionManager == null || executionManager.isZero()) {
            abort("Execution manager not set");
        }
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
    private static class BridgeInterface extends ContractInterface {
        public BridgeInterface(Hash160 contractHash) {
            super(contractHash);
        }

        @CallFlags(io.neow3j.devpack.constants.CallFlags.All)
        public native void claimNative(int nonce);
    }

}
