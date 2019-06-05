package com.gxchain.client.graphenej.objects;

import com.google.common.primitives.Bytes;
import com.google.gson.*;
import com.gxchain.client.graphenej.Util;
import com.gxchain.client.graphenej.enums.OperationType;
import com.gxchain.client.graphenej.interfaces.ByteSerializable;
import com.gxchain.client.graphenej.interfaces.JsonSerializable;
import com.gxchain.client.graphenej.operations.*;
import com.gxchain.client.util.TxSerializerUtil;
import com.gxchain.common.signature.SignatureUtil;
import com.gxchain.common.signature.utils.Wif;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.DumpedPrivateKey;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Class used to represent a generic Graphene transaction.
 */
@Slf4j
public class Transaction implements ByteSerializable, JsonSerializable {
    private static final Logger LOGGER = LoggerFactory.getLogger(Transaction.class);

    private static final long serialVersionUID = -2169174657812805629L;
    private final Logger logger = LoggerFactory.getLogger(Transaction.class);

    /* Default expiration time */
    public static final int DEFAULT_EXPIRATION_TIME = 120;

    /* Constant field names used for serialization/deserialization purposes */
    public static final String KEY_EXPIRATION = "expiration";
    public static final String KEY_SIGNATURES = "signatures";
    public static final String KEY_OPERATIONS = "operations";
    public static final String KEY_EXTENSIONS = "extensions";
    public static final String KEY_REF_BLOCK_NUM = "ref_block_num";
    public static final String KEY_REF_BLOCK_PREFIX = "ref_block_prefix";

    private ECKey privateKey;
    private BlockData blockData;
    private List<BaseOperation> operations;
    private Extensions extensions;
    private String chainId;

    @Getter
    private String signature;

    private byte[] tx_buffer;

    /**
     * BlockTransaction constructor.
     *
     * @param privateKey    : Instance of a ECKey containing the private key that will be used to sign this transaction.
     * @param blockData     : Block data containing important information used to sign a transaction.
     * @param operationList : List of operations to include in the transaction.
     */
    public Transaction(ECKey privateKey, BlockData blockData, List<BaseOperation> operationList) {
        this.privateKey = privateKey;
        this.blockData = blockData;
        this.operations = operationList;
        this.extensions = new Extensions();
    }

    /**
     * BlockTransaction constructor.
     *
     * @param wif:            The user's private key in the base58 format.
     * @param operation_list: List of operations to include in the transaction.
     */
    public Transaction(String wif, List<BaseOperation> operation_list) {
        this(DumpedPrivateKey.fromBase58(null, wif).getKey(), null, operation_list);
    }

    /**
     * BlockTransaction constructor.
     *
     * @param wif:            The user's private key in the base58 format.
     * @param block_data:     Block data containing important information used to sign a transaction.
     * @param operation_list: List of operations to include in the transaction.
     */
    public Transaction(String wif, BlockData block_data, List<BaseOperation> operation_list) {
        this(DumpedPrivateKey.fromBase58(null, wif).getKey(), block_data, operation_list);
    }

    /**
     * Constructor used to build a BlockTransaction object without a private key. This kind of object
     * is used to represent a transaction data that we don't intend to serialize and sign.
     *
     * @param blockData:     Block data instance, containing information about the location of this transaction in the blockchain.
     * @param operationList: The list of operations included in this transaction.
     */
    public Transaction(BlockData blockData, List<BaseOperation> operationList) {
        this.blockData = blockData;
        this.operations = operationList;
    }

    public Transaction(BlockData blockData, List<BaseOperation> operationList, String signature) {
        this.blockData = blockData;
        this.operations = operationList;
        this.signature = signature;
    }

    /**
     * Updates the block data
     *
     * @param blockData: New block data
     */
    public void setBlockData(BlockData blockData) {
        this.blockData = blockData;
    }

    /**
     * Updates the fees for all operations in this transaction.
     *
     * @param fees: New fees to apply
     */
    public void setFees(List<AssetAmount> fees) {
        for (int i = 0; i < operations.size(); i++)
            operations.get(i).setFee(fees.get(i));
    }

    public void setPrivateKey(String wif){
        this.privateKey = DumpedPrivateKey.fromBase58(null, wif).getKey();
    }

    public ECKey getPrivateKey() {
        return this.privateKey;
    }

    public List<BaseOperation> getOperations() {
        return this.operations;
    }

    /**
     * This method is used to query whether the instance has a private key.
     *
     * @return
     */
    public boolean hasPrivateKey() {
        return this.privateKey != null;
    }

    /**
     * Obtains a signature of this transaction. Please note that due to the current reliance on
     * bitcoinj to generate the signatures, and due to the fact that it uses deterministic
     * ecdsa signatures, we are slightly modifying the expiration time of the transaction while
     * we look for a signature that will be accepted by the graphene network.
     * <p>
     * This should then be called before any other serialization method.
     *
     * @return: A valid signature of the current transaction.
     */
    public byte[] getGrapheneSignature() {
        return SignatureUtil.signature(this.toBytes(), new Wif(privateKey).toString());
    }

    /**
     * Method that creates a serialized byte array with compact information about this transaction
     * that is needed for the creation of a signature.
     *
     * @return: byte array with serialized information about this transaction.
     */
    public byte[] toBytes() {
        // Creating a List of Bytes and adding the first bytes from the chain apiId
        List<Byte> byteArray = new ArrayList<Byte>();
        byteArray.addAll(Bytes.asList(Util.hexToBytes(getChainId())));
        byteArray.addAll(Bytes.asList(TxSerializerUtil.serializeTransaction(this.toJsonObjectNoSign())));
        return Bytes.toArray(byteArray);
    }

    public String calculateTxid() {
        return Util.bytesToHex(Sha256Hash.hash(TxSerializerUtil.serializeTransaction(this.toJsonObjectNoSign()))).substring(0, 40);
    }

    @Override
    public String toJsonString() {
        return toJsonObject().toString();
    }

    @Override
    public JsonObject toJsonObject() {

        // Getting the signature before anything else,
        // since this might change the transaction expiration data slightly
        long l1 = System.currentTimeMillis();
        byte[] signature = getGrapheneSignature();
        logger.info("signature consuming " + (System.currentTimeMillis() - l1) + " ms");
        String sign = Util.bytesToHex(signature);
        this.signature = sign;

        JsonObject obj = toJsonObjectNoSign();
        // Adding signatures
        JsonArray signatureArray = new JsonArray();
        signatureArray.add(sign);
        obj.add(KEY_SIGNATURES, signatureArray);
        return obj;

    }

    public JsonObject toJsonObjectNoSign() {
        JsonObject obj = new JsonObject();
        // Adding block data
        obj.addProperty(KEY_REF_BLOCK_NUM, blockData.getRefBlockNum());
        obj.addProperty(KEY_REF_BLOCK_PREFIX, blockData.getRefBlockPrefix());

        // Formatting expiration time
        Date expirationTime = new Date(blockData.getExpiration() * 1000);
        SimpleDateFormat dateFormat = new SimpleDateFormat(Util.TIME_DATE_FORMAT);
        dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));

        // Adding expiration
        obj.addProperty(KEY_EXPIRATION, dateFormat.format(expirationTime));

        JsonArray operationsArray = new JsonArray();
        for (BaseOperation operation : operations) {
            operationsArray.add(operation.toJsonObject());
        }
        // Adding operations
        obj.add(KEY_OPERATIONS, operationsArray);

        // Adding signatures
        JsonArray signatureArray = new JsonArray();
        obj.add(KEY_SIGNATURES, signatureArray);
        // Adding extensions
        obj.add(KEY_EXTENSIONS, new JsonArray());
        return obj;
    }

    public String getChainId() {
        return chainId;
    }

    public void setChainId(String chainId) {
        this.chainId = chainId;
    }

    /**
     * Class used to encapsulate the procedure to be followed when converting a transaction from a
     * java object to its JSON string format representation.
     */
    public static class TransactionSerializer implements JsonSerializer<Transaction> {

        @Override
        public JsonElement serialize(Transaction transaction, Type type, JsonSerializationContext jsonSerializationContext) {
            return transaction.toJsonObject();
        }
    }


    /**
     * Static inner class used to encapsulate the procedure to be followed when converting a transaction from its
     * JSON string format representation into a java object instance.
     */
    public static class TransactionDeserializer implements JsonDeserializer<Transaction> {

        @Override
        public Transaction deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject jsonObject = json.getAsJsonObject();

            // Parsing block data information
            int refBlockNum = jsonObject.get(KEY_REF_BLOCK_NUM).getAsInt();
            long refBlockPrefix = jsonObject.get(KEY_REF_BLOCK_PREFIX).getAsLong();
            String expiration = jsonObject.get(KEY_EXPIRATION).getAsString();
            SimpleDateFormat dateFormat = new SimpleDateFormat(Util.TIME_DATE_FORMAT);
            dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
            Date expirationDate = dateFormat.parse(expiration, new ParsePosition(0));
            BlockData blockData = new BlockData(refBlockNum, refBlockPrefix, expirationDate.getTime() / 1000);
            // Parsing signatures
            String signature = null;
            try {
                signature = null == jsonObject.get(KEY_SIGNATURES) ? null : jsonObject.get(KEY_SIGNATURES).getAsString();
            } catch (Exception e) {
            }
            // Parsing operation list
            BaseOperation operation = null;
            ArrayList<BaseOperation> operationList = new ArrayList<>();
            try {
                for (JsonElement jsonOperation : jsonObject.get(KEY_OPERATIONS).getAsJsonArray()) {
                    int operationId = jsonOperation.getAsJsonArray().get(0).getAsInt();
                    Class c = getOperationClass(operationId);
                    if (c != null) {
                        operation = context.deserialize(jsonOperation, c);
                    }
                    if (operation != null) {
                        operationList.add(operation);
                    }
                    operation = null;
                }
                return new Transaction(blockData, operationList, signature);
            } catch (Exception e) {
                LOGGER.info("Exception. Msg: " + e.getMessage());
                for (StackTraceElement el : e.getStackTrace()) {
                    LOGGER.info(el.getFileName() + "#" + el.getMethodName() + ":" + el.getLineNumber());
                }
            }
            return new Transaction(blockData, operationList, signature);
        }
    }

    private static Class getOperationClass(int operationId) {
        if (operationId == OperationType.TRANSFER_OPERATION.getCode()) {
            return TransferOperation.class;
        } else if (operationId == OperationType.ACCOUNT_CREATE_OPERATION.getCode()) {
            return AccountCreateOperation.class;
        }
        return null;
    }
}
