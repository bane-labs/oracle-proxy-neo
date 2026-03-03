package axlabs.com.lib;

import io.neow3j.devpack.ByteString;
import io.neow3j.devpack.Hash160;
import io.neow3j.devpack.List;
import io.neow3j.devpack.contracts.CryptoLib;

import static io.neow3j.devpack.Helper.abort;
import static io.neow3j.devpack.Helper.concat;
import static io.neow3j.devpack.Helper.reverse;
import static io.neow3j.devpack.Helper.toByteArray;

/**
 * Library for encoding data in EVM ABI format.
 * 
 * This library provides functions to serialize data for EVM contracts, including:
 * - Function selector encoding (keccak256 hash of function signature)
 * - Parameter encoding (uint256, address, bytes, arrays)
 * - Full function call encoding
 * 
 * EVM ABI encoding uses:
 * - Big-endian byte order (opposite of Neo's little-endian)
 * - 32-byte padding for fixed-size types
 * - Length-prefixed encoding for dynamic types
 * 
 * Usage examples:
 * 
 * 1. Encode a simple function call with static parameters:
 *    CryptoLib cryptoLib = new CryptoLib();
 *    ByteString selector = EvmSerializerLib.encodeFunctionSelector(cryptoLib, "transfer(address,uint256)");
 *    ByteString encodedTo = EvmSerializerLib.encodeAddress(recipient);
 *    ByteString encodedAmount = EvmSerializerLib.encodeUint256(1000);
 *    List<ByteString> params = new List<>();
 *    params.add(encodedTo);
 *    params.add(encodedAmount);
 *    ByteString callData = EvmSerializerLib.encodeFunctionCall(cryptoLib, "transfer(address,uint256)", params);
 * 
 * 2. Encode a function call with dynamic parameters:
 *    ByteString encodedTo = EvmSerializerLib.encodeAddress(recipient);
 *    ByteString encodedData = EvmSerializerLib.encodeBytes(data);
 *    List<ByteString> staticParams = new List<>();
 *    staticParams.add(encodedTo);
 *    staticParams.add(EvmSerializerLib.encodeUint256(0)); // Placeholder for dynamic param
 *    List<ByteString> dynamicParams = new List<>();
 *    dynamicParams.add(encodedData);
 *    ByteString callData = EvmSerializerLib.encodeFunctionCallWithDynamic(
 *        cryptoLib, "transferWithData(address,bytes)", staticParams, dynamicParams);
 */
public class EvmSerializerLib {

    // EVM ABI constants
    private static final int WORD_SIZE = 32; // 32 bytes per word in EVM
    private static final int FUNCTION_SELECTOR_SIZE = 4; // First 4 bytes of keccak256 hash
    private static final int ADDRESS_SIZE = 20; // EVM address size

    /**
     * Encodes a function selector from a function signature.
     * The function selector is the first 4 bytes of keccak256(functionSignature).
     * 
     * @param cryptoLib The CryptoLib instance for hashing
     * @param functionSignature The function signature (e.g., "transfer(address,uint256)")
     * @return The 4-byte function selector
     */
    public static ByteString encodeFunctionSelector(CryptoLib cryptoLib, String functionSignature) {
        // Avoid String.getBytes() which relies on Charset and is not supported by the Neo compiler.
        byte[] signatureBytes = toByteArray(functionSignature);
        ByteString hash = cryptoLib.keccak256(new ByteString(signatureBytes));
        byte[] hashBytes = hash.toByteArray();
        
        // Extract first 4 bytes (function selector)
        byte[] selector = new byte[FUNCTION_SELECTOR_SIZE];
        for (int i = 0; i < FUNCTION_SELECTOR_SIZE; i++) {
            selector[i] = hashBytes[i];
        }
        return new ByteString(selector);
    }

    /**
     * Encodes a uint256 value in EVM ABI format.
     * Values are padded to 32 bytes and encoded in big-endian format.
     * 
     * @param value The integer value to encode
     * @return The ABI-encoded bytes (32 bytes, big-endian)
     */
    public static ByteString encodeUint256(int value) {
        byte[] valueBytes = toByteArray(value);
        // Reverse to convert from little-endian (Neo) to big-endian (EVM)
        reverse(valueBytes);
        // Pad to 32 bytes (left-pad with zeros)
        byte[] padded = padLeft(valueBytes, WORD_SIZE);
        return new ByteString(padded);
    }

    /**
     * Encodes an address (Hash160) in EVM ABI format.
     * Addresses are 20 bytes, padded to 32 bytes (left-padded with zeros).
     * 
     * @param address The Hash160 address to encode
     * @return The ABI-encoded address (32 bytes)
     */
    public static ByteString encodeAddress(Hash160 address) {
        byte[] addressBytes = address.toByteArray();
        // Reverse to convert from little-endian (Neo) to big-endian (EVM)
        reverse(addressBytes);
        // Pad to 32 bytes (left-pad with zeros)
        byte[] padded = padLeft(addressBytes, WORD_SIZE);
        return new ByteString(padded);
    }

    /**
     * Encodes a bytes32 value in EVM ABI format.
     * The input must be 32 bytes or less. If less, it's left-padded with zeros.
     * 
     * @param data The byte data to encode (max 32 bytes)
     * @return The ABI-encoded bytes32 (32 bytes)
     */
    public static ByteString encodeBytes32(ByteString data) {
        byte[] dataBytes = data.toByteArray();
        if (dataBytes.length > WORD_SIZE) {
            abort("Bytes32 data too long");
        }
        // bytes32 is LEFT-aligned, right-padded with zeros.
        // Unlike uint/address, byte arrays are NOT endian-reversed.
        // Example: bytes3(0x010203) → 0x0102030000...00 (29 zeros on the right)
        int paddingSize = WORD_SIZE - dataBytes.length;
        byte[] padding = new byte[paddingSize];
        return new ByteString(concat(dataBytes, padding));
    }

    /**
     * Encodes a boolean value in EVM ABI format.
     * Booleans are encoded as uint256 (0 for false, 1 for true).
     * 
     * @param value The boolean value to encode
     * @return The ABI-encoded boolean (32 bytes)
     */
    public static ByteString encodeBool(boolean value) {
        int intValue = value ? 1 : 0;
        return encodeUint256(intValue);
    }

    /**
     * Encodes a dynamic bytes array in EVM ABI format.
     * Format: [length (32 bytes)] [data (padded to 32-byte boundary)]
     * Note: When used in function calls, an offset (32 bytes) must be prepended by the caller.
     * 
     * @param data The byte data to encode
     * @return The ABI-encoded bytes (length + data, without offset)
     */
    public static ByteString encodeBytes(ByteString data) {
        byte[] dataBytes = data.toByteArray();
        int dataLength = dataBytes.length;
        
        // Calculate padding: data must be padded to 32-byte boundary
        int padding = (WORD_SIZE - (dataLength % WORD_SIZE)) % WORD_SIZE;
        int paddedLength = dataLength + padding;
        
        // Encode length (32 bytes, big-endian)
        byte[] lengthBytes = toByteArray(dataLength);
        reverse(lengthBytes);
        byte[] paddedLengthBytes = padLeft(lengthBytes, WORD_SIZE);
        
        // bytes/string: data is LEFT-aligned, right-padded. No byte reversal.
        // Only integer types (uint, int, address, bool) are endian-reversed.
        // Example: bytes("Hello") → [0x48, 0x65, 0x6c, 0x6c, 0x6f, 0x00, ..., 0x00]
        byte[] paddedData = new byte[paddedLength];
        for (int i = 0; i < dataLength; i++) {
            paddedData[i] = dataBytes[i];
        }
        // Padding bytes are already zero from allocation
        
        return new ByteString(concat(paddedLengthBytes, paddedData));
    }

    /**
     * Encodes a string in EVM ABI format.
     * Strings are encoded as dynamic bytes (UTF-8 encoded).
     * 
     * @param value The string to encode
     * @return The ABI-encoded string
     */
    public static ByteString encodeString(String value) {
        // Avoid String.getBytes() which relies on Charset and is not supported by the Neo compiler.
        byte[] stringBytes = toByteArray(value);
        return encodeBytes(new ByteString(stringBytes));
    }

    /**
     * Encodes an array of uint256 values in EVM ABI format.
     * Format: [offset (32 bytes)] [length (32 bytes)] [values... (each 32 bytes)]
     * 
     * @param values The array of integers to encode
     * @return The ABI-encoded array
     */
    public static ByteString encodeUint256Array(List<Integer> values) {
        int length = values.size();
        
        // Encode length (32 bytes)
        byte[] lengthBytes = toByteArray(length);
        reverse(lengthBytes);
        byte[] paddedLengthBytes = padLeft(lengthBytes, WORD_SIZE);
        
        // Encode each value (32 bytes each)
        byte[] encodedValues = new byte[0];
        for (int i = 0; i < length; i++) {
            ByteString encodedValue = encodeUint256(values.get(i));
            encodedValues = concat(encodedValues, encodedValue.toByteArray());
        }
        
        return new ByteString(concat(paddedLengthBytes, encodedValues));
    }

    /**
     * Encodes an array of addresses in EVM ABI format.
     * Format: [offset (32 bytes)] [length (32 bytes)] [addresses... (each 32 bytes)]
     * 
     * @param addresses The array of addresses to encode
     * @return The ABI-encoded array
     */
    public static ByteString encodeAddressArray(List<Hash160> addresses) {
        int length = addresses.size();
        
        // Encode length (32 bytes)
        byte[] lengthBytes = toByteArray(length);
        reverse(lengthBytes);
        byte[] paddedLengthBytes = padLeft(lengthBytes, WORD_SIZE);
        
        // Encode each address (32 bytes each)
        byte[] encodedAddresses = new byte[0];
        for (int i = 0; i < length; i++) {
            ByteString encodedAddress = encodeAddress(addresses.get(i));
            encodedAddresses = concat(encodedAddresses, encodedAddress.toByteArray());
        }
        
        return new ByteString(concat(paddedLengthBytes, encodedAddresses));
    }

    /**
     * Encodes a complete EVM function call.
     * Format: [function selector (4 bytes)] [encoded parameters...]
     * 
     * @param cryptoLib The CryptoLib instance for hashing
     * @param functionSignature The function signature (e.g., "transfer(address,uint256)")
     * @param encodedParams Array of already-encoded parameters
     * @return The complete ABI-encoded function call
     */
    public static ByteString encodeFunctionCall(CryptoLib cryptoLib, String functionSignature, 
                                                List<ByteString> encodedParams) {
        // Encode function selector
        ByteString selector = encodeFunctionSelector(cryptoLib, functionSignature);
        
        // Concatenate encoded parameters
        byte[] paramsBytes = new byte[0];
        for (int i = 0; i < encodedParams.size(); i++) {
            paramsBytes = concat(paramsBytes, encodedParams.get(i).toByteArray());
        }
        
        // Combine selector and parameters
        return new ByteString(concat(selector.toByteArray(), paramsBytes));
    }

    /**
     * Encodes a complete EVM function call with mixed static and dynamic parameters.
     * This handles the offset calculation for dynamic types correctly.
     * 
     * The parameters list should contain:
     * - For static types: the encoded parameter directly
     * - For dynamic types: use a placeholder (e.g., encodeUint256(0)) and pass the actual
     *   dynamic data in dynamicParams in the same order
     * 
     * @param cryptoLib The CryptoLib instance for hashing
     * @param functionSignature The function signature
     * @param params Array of encoded parameters (static types directly, dynamic types as placeholders)
     * @param dynamicParams Array of dynamic (variable-size) encoded parameters, in order
     * @return The complete ABI-encoded function call
     */
    public static ByteString encodeFunctionCallWithDynamic(CryptoLib cryptoLib, String functionSignature,
                                                           List<ByteString> params,
                                                           List<ByteString> dynamicParams) {
        // Encode function selector
        ByteString selector = encodeFunctionSelector(cryptoLib, functionSignature);
        
        // Calculate total size of all parameter slots (each param takes 32 bytes for offset/value)
        int paramSlotsSize = params.size() * WORD_SIZE;
        
        // EVM ABI offsets are relative to the START of the params area (i.e., right after selector).
        // First dynamic param's data begins right after all param slots.
        int offset = paramSlotsSize;
        
        // Build the encoded call
        byte[] result = selector.toByteArray();
        
        // Track current offset for dynamic parameters
        int currentOffset = offset;
        int dynamicIndex = 0;
        
        // Process all parameters: static directly, dynamic as offsets
        for (int i = 0; i < params.size(); i++) {
            ByteString param = params.get(i);
            byte[] paramBytes = param.toByteArray();
            
            // Check if this is a placeholder (all zeros, 32 bytes) - indicates dynamic param
            boolean isPlaceholder = paramBytes.length == WORD_SIZE;
            if (isPlaceholder) {
                // Check if all bytes are zero
                boolean allZero = true;
                for (int j = 0; j < WORD_SIZE; j++) {
                    if (paramBytes[j] != 0) {
                        allZero = false;
                        break;
                    }
                }
                
                if (allZero && dynamicIndex < dynamicParams.size()) {
                    // This is a dynamic parameter placeholder - replace with offset
                    byte[] offsetBytes = toByteArray(currentOffset);
                    reverse(offsetBytes);
                    byte[] paddedOffset = padLeft(offsetBytes, WORD_SIZE);
                    result = concat(result, paddedOffset);
                    
                    // Update offset for next dynamic parameter
                    ByteString dynamicParam = dynamicParams.get(dynamicIndex);
                    int dynamicParamSize = dynamicParam.toByteArray().length;
                    currentOffset += dynamicParamSize;
                    dynamicIndex++;
                } else {
                    // Not a placeholder, add as-is
                    result = concat(result, paramBytes);
                }
            } else {
                // Static parameter, add as-is
                result = concat(result, paramBytes);
            }
        }
        
        // Add dynamic parameter data at the end
        for (int i = 0; i < dynamicParams.size(); i++) {
            result = concat(result, dynamicParams.get(i).toByteArray());
        }
        
        return new ByteString(result);
    }
    

    // =========================================================================
    //                     APPEND ARG (GAS-EFFICIENT PATTERN)
    // =========================================================================

    /**
     * Appends a serialized static argument to an existing serialized function call.
     * 
     * This is a gas-efficient pattern: serialize the call off-chain (free), then on-chain
     * only append additional arguments. This avoids paying gas for the full serialization.
     * 
     * For EVM ABI encoding, static parameters are simply 32-byte chunks appended at the end.
     * This function appends the new parameter after the existing parameters.
     * 
     * Format of serializedCall: [function selector (4 bytes)] [param1 (32 bytes)] [param2 (32 bytes)] ...
     * Result: [function selector (4 bytes)] [param1 (32 bytes)] [param2 (32 bytes)] ... [newParam (32 bytes)]
     * 
     * @param serializedCall The existing serialized function call (output of encodeFunctionCall)
     * @param serializedArg The new static argument to append (must be 32 bytes, e.g. from encodeUint256, encodeAddress, etc.)
     * @return The modified serialized call with the new argument appended
     * 
     * @dev Constraints:
     *      - serializedArg must be exactly 32 bytes (static types only)
     *      - For dynamic types, use appendDynamicArgToCall instead
     * 
     * Example:
     *      // Off-chain: serialize the call with known args (free)
     *      ByteString base = encodeFunctionCall(cryptoLib, "transfer(address,uint256)", [encodedTo, encodedAmount]);
     *      // On-chain: only pay gas to append the nonce
     *      ByteString withNonce = appendArgToCall(base, encodeUint256(nonce));
     */
    public static ByteString appendArgToCall(ByteString serializedCall, ByteString serializedArg) {
        byte[] argBytes = serializedArg.toByteArray();
        
        // Validate: static args must be exactly 32 bytes
        if (argBytes.length != WORD_SIZE) {
            abort("Static argument must be exactly 32 bytes");
        }
        
        // Simply concatenate: existing call + new arg
        byte[] callBytes = serializedCall.toByteArray();
        return new ByteString(concat(callBytes, argBytes));
    }

    /**
     * Appends a dynamic argument (bytes, string, array) to an existing serialized function call.
     * 
     * This handles dynamic types correctly by:
     * 1. Adding an offset (32 bytes) in the parameter area pointing to where the dynamic data will be
     * 2. Appending the dynamic data at the end
     * 
     * Format of serializedCall: [selector (4)] [static params (32 each)] [dynamic offsets (32 each)] [dynamic data...]
     * Result: [selector (4)] [static params] [dynamic offsets] [new offset (32)] [dynamic data...] [new dynamic data]
     * 
     * @param serializedCall The existing serialized function call
     * @param serializedDynamicArg The new dynamic argument (from encodeBytes, encodeString, encodeUint256Array, etc.)
     * @return The modified serialized call with the new dynamic argument appended
     * 
     * @dev IMPORTANT: This function assumes the call was created with encodeFunctionCall (only static params).
     *      It calculates the parameter area end by checking if the call length is: 4 + (N * 32) for some N.
     *      If the call already contains dynamic parameters, use appendDynamicArgToCall with explicit paramSlotOffset.
     * 
     *      The serializedDynamicArg should be the output of encodeBytes, encodeString, or array encoding functions.
     *      These already include the length prefix, so we just need to add the offset and append the data.
     * 
     * Example:
     *      ByteString base = encodeFunctionCall(cryptoLib, "transfer(address,uint256)", [encodedTo, encodedAmount]);
     *      // base length = 4 + (2 * 32) = 68 bytes (all static params)
     *      ByteString data = encodeBytes(someData);
     *      ByteString withData = appendDynamicArgToCall(base, data);
     */
    public static ByteString appendDynamicArgToCall(ByteString serializedCall, ByteString serializedDynamicArg) {
        byte[] callBytes = serializedCall.toByteArray();
        byte[] dynamicArgBytes = serializedDynamicArg.toByteArray();
        
        if (callBytes.length < FUNCTION_SELECTOR_SIZE) {
            abort("Call data too short");
        }
        
        // For calls created with encodeFunctionCall (only static params):
        // Length = selector (4) + (numParams * 32)
        // We can detect this: (length - 4) % 32 == 0
        int paramAreaSize = callBytes.length - FUNCTION_SELECTOR_SIZE;
        if (paramAreaSize % WORD_SIZE != 0) {
            // Call might already have dynamic params - can't auto-detect parameter area
            abort("Cannot auto-detect parameter area. Use appendDynamicArgToCall with explicit paramSlotOffset");
        }
        
        // Parameter area ends after selector + all static param slots
        // Calculate offset: EVM ABI offsets are relative to start of params area (after selector).
        // Dynamic data starts at: callBytes.length (base) + WORD_SIZE (new offset slot) bytes from calldata start.
        // Relative to params area start (byte FUNCTION_SELECTOR_SIZE):
        //   offset = (callBytes.length + WORD_SIZE) - FUNCTION_SELECTOR_SIZE
        int offset = callBytes.length + WORD_SIZE - FUNCTION_SELECTOR_SIZE;
        
        // Encode offset (32 bytes, big-endian)
        byte[] offsetBytes = toByteArray(offset);
        reverse(offsetBytes);
        byte[] paddedOffset = padLeft(offsetBytes, WORD_SIZE);
        
        // Build result: existing call + offset slot + dynamic data
        byte[] result = concat(concat(callBytes, paddedOffset), dynamicArgBytes);
        return new ByteString(result);
    }
    
    /**
     * Appends a dynamic argument to a function call, with explicit parameter information.
     * 
     * This version requires you to specify where to insert the offset slot, making it work
     * correctly even when the call already has dynamic parameters.
     * 
     * @param serializedCall The existing serialized function call
     * @param paramSlotOffset The byte offset where to insert the new offset slot (after selector + all param slots)
     * @param serializedDynamicArg The new dynamic argument (from encodeBytes, encodeString, etc.)
     * @return The modified serialized call with the new dynamic argument appended
     * 
     * @dev paramSlotOffset should be: selector size (4) + (number of all parameters * 32)
     *      This includes both static parameters and existing dynamic offset slots.
     * 
     * Example:
     *      // Call has 2 static params, so paramSlotOffset = 4 + (2 * 32) = 68
     *      ByteString base = encodeFunctionCall(cryptoLib, "transfer(address,uint256)", [encodedTo, encodedAmount]);
     *      ByteString data = encodeBytes(someData);
     *      ByteString withData = appendDynamicArgToCall(base, 68, data);
     */
    public static ByteString appendDynamicArgToCall(ByteString serializedCall, int paramSlotOffset, 
                                                    ByteString serializedDynamicArg) {
        byte[] callBytes = serializedCall.toByteArray();
        byte[] dynamicArgBytes = serializedDynamicArg.toByteArray();
        
        if (callBytes.length < FUNCTION_SELECTOR_SIZE) {
            abort("Call data too short");
        }
        
        if (paramSlotOffset < FUNCTION_SELECTOR_SIZE || paramSlotOffset > callBytes.length) {
            abort("Invalid parameter slot offset");
        }
        
        // Calculate offset: EVM ABI offsets are relative to start of params area (after selector).
        // After inserting the new offset slot at paramSlotOffset, dynamic data starts at:
        //   callBytes.length + WORD_SIZE bytes from calldata start.
        // Relative to params area start (byte FUNCTION_SELECTOR_SIZE):
        //   offset = (callBytes.length + WORD_SIZE) - FUNCTION_SELECTOR_SIZE
        int offset = callBytes.length + WORD_SIZE - FUNCTION_SELECTOR_SIZE;
        
        // Encode offset (32 bytes, big-endian)
        byte[] offsetBytes = toByteArray(offset);
        reverse(offsetBytes);
        byte[] paddedOffset = padLeft(offsetBytes, WORD_SIZE);
        
        // Split call at paramSlotOffset: prefix (selector + params) + suffix (dynamic data if any)
        byte[] prefix = new byte[paramSlotOffset];
        byte[] suffix = new byte[callBytes.length - paramSlotOffset];
        for (int i = 0; i < paramSlotOffset; i++) {
            prefix[i] = callBytes[i];
        }
        for (int i = 0; i < suffix.length; i++) {
            suffix[i] = callBytes[paramSlotOffset + i];
        }
        
        // Build result: prefix + new offset slot + suffix + new dynamic data
        byte[] result = concat(concat(concat(prefix, paddedOffset), suffix), dynamicArgBytes);
        return new ByteString(result);
    }

    /**
     * Appends a serialized static argument to an existing serialized function call (optimized version).
     * 
     * This is the most gas-efficient version when you know the structure of the call.
     * The caller provides the offset where to append, eliminating any parsing overhead.
     * 
     * @param serializedCall The existing serialized function call
     * @param appendOffset The byte offset where to append (typically: selector size + existing params size)
     * @param serializedArg The new static argument to append (must be 32 bytes)
     * @return The modified serialized call with the new argument appended
     * 
     * @dev Maximum gas efficiency: no parsing at all. Just memcpy + append.
     *      The caller provides the pre-computed offset, eliminating all overhead.
     * 
     *      To find appendOffset off-chain:
     *        appendOffset = 4 (selector) + (number of existing params * 32)
     * 
     * Example:
     *      // Off-chain: serialize with known args
     *      ByteString base = encodeFunctionCall(cryptoLib, "transfer(address,uint256)", [encodedTo, encodedAmount]);
     *      // appendOffset = 4 + (2 * 32) = 68
     *      int constant OFFSET = 68;
     *      // On-chain: append nonce
     *      ByteString full = appendArgToCall(base, OFFSET, encodeUint256(nonce));
     */
    public static ByteString appendArgToCall(ByteString serializedCall, int appendOffset, ByteString serializedArg) {
        byte[] callBytes = serializedCall.toByteArray();
        byte[] argBytes = serializedArg.toByteArray();
        
        // Validate: static args must be exactly 32 bytes
        if (argBytes.length != WORD_SIZE) {
            abort("Static argument must be exactly 32 bytes");
        }
        
        // Validate offset
        if (appendOffset < FUNCTION_SELECTOR_SIZE || appendOffset > callBytes.length) {
            abort("Invalid append offset");
        }
        
        // Split call at offset: prefix + suffix
        byte[] prefix = new byte[appendOffset];
        byte[] suffix = new byte[callBytes.length - appendOffset];
        for (int i = 0; i < appendOffset; i++) {
            prefix[i] = callBytes[i];
        }
        for (int i = 0; i < suffix.length; i++) {
            suffix[i] = callBytes[appendOffset + i];
        }
        
        // Build result: prefix + new arg + suffix
        return new ByteString(concat(concat(prefix, argBytes), suffix));
    }

    /**
     * Left-pads a byte array to the specified size with zeros.
     * 
     * @param data The data to pad
     * @param targetSize The target size in bytes
     * @return The padded byte array
     */
    private static byte[] padLeft(byte[] data, int targetSize) {
        int dataSize = data.length;
        if (dataSize > targetSize) {
            abort("Data too long for padding");
        }
        
        int paddingSize = targetSize - dataSize;
        byte[] padding = new byte[paddingSize];
        // Padding is already zero from allocation
        
        return concat(padding, data);
    }
}
