package it.unitn.disi.ds1.messages;

import akka.actor.ActorRef;
import it.unitn.disi.ds1.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Message which represent the response given to the client by the caches.
 * The message will contain the value of the message either retrieved or written if the operation went well
 * Otherwise the message will contain null, in this case the operation has failed.
 */
public class ResponseMessage extends Message {
    /**
     * Map of value passed
     * <p>
     * **NOTE**
     * Integers in Java are immutable, otherwise we would have used
     * other message passing methods
     */
    public final Map<Integer, Integer> values;

    /**
     * Hops the message needs to visit
     */
    public final List<ActorRef> hops;

    /**
     * Unique identifier of the request
     * Query UUID
     */
    public final UUID queryUUID;

    /**
     * Is the ReadMessage a critical one?
     */
    public final boolean isCritical;

    /**
     * Request type
     */
    public final Config.RequestType requestType;

    /**
     * Sequence number for monotonic read
     */
    public final int seqno;

    /**
     * Constructor of the response message
     *
     * @param values      values in the reply
     * @param hops        hops which needs to be traversed to deliver the message
     * @param uuid        unique identifier of the request
     * @param requestType type of request
     * @param seqno       sequence number
     * @param isCritical is critical
     */
    public ResponseMessage(Map<Integer, Integer> values, List<ActorRef> hops, UUID uuid, Config.RequestType requestType, boolean isCritical, int seqno) {
        this.isCritical = isCritical;

        /**
         * Sequence number
         */
        this.seqno = seqno;

        /**
         * **NOTE:** the response message contains null if there were failures
         */
        if (values != null)
            this.values = Collections.unmodifiableMap(new HashMap<>(values));
        else
            this.values = null;
        /**
         * Copy of the UUID
         */
        if (uuid != null)
            this.queryUUID = new UUID(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
        else
            this.queryUUID = null;
        this.hops = Collections.unmodifiableList(new ArrayList<>(hops));
        this.requestType = requestType;
    }
}
