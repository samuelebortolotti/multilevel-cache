package it.unitn.disi.ds1.actors;

import akka.actor.ActorRef;
import akka.actor.Props;
import it.unitn.disi.ds1.Config;
import it.unitn.disi.ds1.Logger;
import it.unitn.disi.ds1.messages.CriticalUpdateMessage;
import it.unitn.disi.ds1.messages.CriticalUpdateResponseMessage;
import it.unitn.disi.ds1.messages.CriticalUpdateTimeoutMessage;
import it.unitn.disi.ds1.messages.CriticalWriteResponseMessage;
import it.unitn.disi.ds1.messages.JoinCachesMessage;
import it.unitn.disi.ds1.messages.ReadMessage;
import it.unitn.disi.ds1.messages.RecoveryMessage;
import it.unitn.disi.ds1.messages.ResponseMessage;
import it.unitn.disi.ds1.messages.StartSnapshotMessage;
import it.unitn.disi.ds1.messages.TimeoutMessage;
import it.unitn.disi.ds1.messages.TokenMessage;
import it.unitn.disi.ds1.messages.WriteMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Database actor
 * <p>
 * It stores inside a variable a key-value pairs HashSet
 * It communicates with L1 cache servers, handling the following requests:
 * - READ
 * - WRITE
 * - CRITREAD
 * - CRITWRITE
 * <p>
 * We can take for granted this actor doesn't crash
 */
public class Database extends Actor {
    /**
     * List of all L1 caches it communicates with
     */
    private final List<ActorRef> caches;

    /**
     * The database is stored inside this variable as
     * key-value integer pairs
     * <p>
     * We can assume there's infinite space
     */
    private final Map<Integer, Integer> database;

    /**
     * List of values which are currently going to update though CRITWRITES
     */
    private final Map<Integer, Integer> criticalKeyValue;

    /**
     * Map uuid to key of critical writes
     */
    private final Map<UUID, Integer> criticalSessionKey;

    /**
     * List of neighbors from which the acknowledgement should be received
     */
    private final Map<UUID, Set<ActorRef>> receivedAcksForCritWrite;

    /**
     * Database Constructor
     * Initialize variables
     *
     * @param id       database identifier
     * @param database A Map containing the entries of our database
     */
    public Database(int id, Map<Integer, Integer> database) {
        super(id);
        this.database = database;
        this.caches = new ArrayList<>();
        // Initialize the sequence numbers at zero
        this.database.forEach((k, v) -> this.seqnoCache.put(k, 0));
        // Initialize the critical keys
        this.criticalSessionKey = new HashMap<>();
        this.criticalKeyValue = new HashMap<>();
        this.receivedAcksForCritWrite = new HashMap<>();
    }

    /**
     * Database static builder
     *
     * @param id       database identifier
     * @param database database values
     * @return Database instance
     */
    static public Props props(int id, Map<Integer, Integer> database) {
        return Props.create(Database.class, () -> new Database(id, database));
    }

    /**
     * Handler of JoinCachesMessage message.
     * Add all the joined caches as target for queries
     *
     * @param msg message containing information about the joined cache servers
     */
    @Override
    protected void onJoinCachesMessage(JoinCachesMessage msg) {
        this.caches.addAll(msg.caches);
        Logger.DEBUG.info(getSelf().path().name() + ": joining a the distributed cache with " +
                this.caches.size() + " visible peers with ID " + this.id);
    }

    /**
     * Handler of the ReadMessage message.
     * Get the value for the specified key and send back the response to the sender
     * **NOTE**: by assumption of the assignment, all the value requested will concern values which are present in
     * the database
     *
     * @param msg message containing the queried key and the list of the communication hops
     */
    @Override
    protected void onReadMessage(ReadMessage msg) {
        // Generate a new ArrayList from the message hops
        List<ActorRef> newHops = new ArrayList<>(msg.hops);

        // Remove the next hop from the new hops array
        // The hops contains the nodes which have been traveled to reach the database
        newHops.remove(newHops.size() - 1);

        // Return the sequence number
        Integer seqno = this.seqnoCache.get(msg.requestKey);

        Map<Integer, Integer> valueToReturn = Collections.singletonMap(msg.requestKey, this.database.get(msg.requestKey));
        // Value on CRITWRITE
        if (criticalKeyValue.containsKey(msg.requestKey)) {
            // Logger.INSTANCE.severe(getSelf().path().name() + " cannot read a message which is on critical update " + msg.requestKey);
            valueToReturn = null;
        }

        // Generate a response message containing the response and the new hops array
        ResponseMessage responseMessage = new ResponseMessage(
                valueToReturn,
                newHops,
                msg.queryUUID,                  // Encapsulating the query UUID
                msg.isCritical ? Config.RequestType.CRITREAD : Config.RequestType.READ,
                msg.isCritical,
                seqno
        );

        // Network delay
        this.delay();
        // Send the response back to the sender
        getSender().tell(responseMessage, getSelf());

        Logger.logCheck(Level.FINE, this.id, getIdFromName(getSender().path().name()),
                msg.isCritical ? Config.RequestType.CRITREAD : Config.RequestType.READ,
                true, msg.requestKey, valueToReturn == null ? null : this.database.get(msg.requestKey),
                seqno, "Response for key [CRIT: " + msg.isCritical + "]", msg.queryUUID
        );
        Logger.DEBUG.info(getSelf().path().name() + " is answering " + msg.requestKey + " to: " +
                getSender().path().name() + " sequence number: " + seqno + " [CRITICAL] = " + msg.isCritical);
    }

    /**
     * Handler of the WriteMessage
     * The function overrides the element in the database
     * and sends the update to all the cache using multicast
     *
     * @param msg   write message
     */
    @Override
    protected void onWriteMessage(WriteMessage msg) {
        // Value on CRITWRITE
        if (criticalKeyValue.containsKey(msg.requestKey)) {
            Logger.DEBUG.severe(getSelf().path().name() + " cannot write a message which is on critical update " +
                    msg.requestKey);
            // Get the list of hops
            List<ActorRef> newHops = new ArrayList<>(msg.hops);
            // Remove the next hop from the new hops array
            // The hops contains the nodes which have been traveled to reach the database
            newHops.remove(newHops.size() - 1);
            // Send the response back to the sender
            // Return the sequence number
            Integer seqno = this.seqnoCache.get(msg.requestKey);
            // Network delay
            this.delay();
            // Send the message
            getSender().tell(
                    new ResponseMessage(null, newHops, msg.queryUUID, Config.RequestType.WRITE, msg.isCritical, seqno),
                    getSelf()
            );
            Logger.logCheck(Level.INFO, this.id, getIdFromName(getSender().path().name()),
                    msg.isCritical ? Config.RequestType.CRITWRITE : Config.RequestType.WRITE,
                    true, msg.requestKey, null, seqno,
                    "Write request for key [CRIT: " + msg.isCritical + "]", msg.queryUUID
            );
            return;
        }

        // Generate a new ArrayList from the message hops
        // The hops contain the nodes which have been traveled to reach the database
        List<ActorRef> newHops = new ArrayList<>(msg.hops);

        // Remove the next hop from the new hops array
        newHops.remove(newHops.size() - 1);

        // Handle critical write in a different way, using a protocol devised from 2PC
        if (msg.isCritical) {
            this.criticalSessionKey.put(msg.queryUUID, msg.requestKey);
            this.criticalKeyValue.put(msg.requestKey, msg.modifiedValue);
            Logger.DEBUG.info(getSelf().path().name() +
                    " Sending the request for critical write to all the caches, hope to receive all OK! for " +
                    msg.requestKey + " value: " + msg.modifiedValue
            );

            // Send the critical update message to L1 caches - we expect an acknowledgement containing COMMIT/ABORT
            this.multicast(
                    new CriticalUpdateMessage(msg.requestKey, msg.modifiedValue, msg.queryUUID, newHops),
                    this.caches
            );

            // If the database doesn't receive an acknowledgement within a given timeout, abort the write and return error
            this.scheduleTimer(
                    new CriticalUpdateTimeoutMessage(msg.queryUUID, newHops),
                    Config.CRIT_WRITE_TIME_OUT, msg.queryUUID
            );
            return;
        }

        // Override the value in the database
        this.database.remove(msg.requestKey);
        this.database.put(msg.requestKey, msg.modifiedValue);

        // Update the sequence number
        Integer newSeqno = this.seqnoCache.get(msg.requestKey);
        newSeqno++;

        // Override the value in the sequence number cache
        this.seqnoCache.remove(msg.requestKey);
        this.seqnoCache.put(msg.requestKey, newSeqno);

        Logger.DEBUG.info(getSelf().path().name() + ": forwarding the new value for " + msg.requestKey + " to: " +
                getSender().path().name() + " sequence number " + newSeqno);

        // Multicast to the cache the update
        this.multicastAndCheck(
                new ResponseMessage(Collections.singletonMap(msg.requestKey, msg.modifiedValue), newHops, msg.queryUUID,
                        Config.RequestType.WRITE, false, newSeqno), this.caches, Config.RequestType.WRITE,
                msg.requestKey, msg.modifiedValue, newSeqno, false, msg.queryUUID
        );
    }

    /**
     * On timeout abort if the request has not ended
     *
     * @param msg critical update message
     */
    protected void onCriticalUpdateTimeoutMessage(CriticalUpdateTimeoutMessage msg) {
        // Abort
        /**
         * Remember that the timeout is started from the database which is waiting for a response
         *
         * To avoid that onTimeout messages are put in the queue right after the response
         * In this case we have already addressed the queries, therefore, the all the caches have answered
         */
        if (!this.criticalSessionKey.containsKey(msg.queryUUID)) {
            return;
        }
        Integer key = this.criticalSessionKey.get(msg.queryUUID);
        Integer value = this.criticalKeyValue.get(key);
        Logger.DEBUG.info(getSelf().path().name() + " Aborting the critical write for " + key + " value " + value);
        this.multicast(
                new CriticalWriteResponseMessage(Config.ACResponse.ABORT, msg.queryUUID, msg.hops, null),
                this.caches
        );
    }

    /**
     * Collects the acknowledgment from the caches:
     * CriticalUpdateResponseMessage should collect Config.CUResponse.OK
     * If all agree then commit, if someone does not then ABORT
     * The Commit and Abort messages are CriticalWriteResponseMessage
     *
     * @param msg   critical update message
     */
    protected void onCriticalUpdateResponseMessage(CriticalUpdateResponseMessage msg) {
        // If this.criticalSessionKey doesn't contain msg.queryUUID, it means the database
        // already handled this critical write by aborting. In this case we can just return
        if (!this.criticalSessionKey.containsKey(msg.queryUUID)) {
            return;
        }

        // Got an OK -> voted yes
        if (msg.response == Config.CUResponse.OK) {
            // Add the sender to the list of received acknowledgements
            if (!this.receivedAcksForCritWrite.containsKey(msg.queryUUID)) {
                this.receivedAcksForCritWrite.put(msg.queryUUID, new HashSet<>());
            }
            this.receivedAcksForCritWrite.get(msg.queryUUID).add(getSender());

            // If the database has received all acknowledgements, proceed with the protocol's flow
            if (this.receivedAcksForCritWrite.get(msg.queryUUID).containsAll(this.caches)) {
                // Cancel the timer
                this.cancelTimer(msg.queryUUID);

                // Commit by replacing the value with the updated one
                int keyToUpdate = this.criticalSessionKey.get(msg.queryUUID);
                int newValue = this.criticalKeyValue.get(keyToUpdate);

                // Update new value
                this.database.remove(keyToUpdate);
                this.database.put(keyToUpdate, newValue);

                // Update the sequence number
                Integer newSeqno = this.seqnoCache.get(keyToUpdate);
                newSeqno++;

                // Override the value in the sequence number cache
                this.seqnoCache.remove(keyToUpdate);
                this.seqnoCache.put(keyToUpdate, newSeqno);

                // Clear critical writes value
                this.clearCriticalWrite(msg.queryUUID);

                Logger.DEBUG.info(getSelf().path().name() +
                        " Committing since all answers OK! the critical write for " + keyToUpdate + " value " +
                        newValue
                );

                // Send commit to the caches with the new sequence number to be updated
                this.multicastAndCheck(
                        new CriticalWriteResponseMessage(Config.ACResponse.COMMIT, msg.queryUUID, msg.hops, newSeqno),
                        this.caches, Config.RequestType.CRITWRITE, keyToUpdate, newValue, newSeqno,
                        true, msg.queryUUID
                );
            }
        } else {
            // Got NO, I can abort
            Integer key = this.criticalSessionKey.get(msg.queryUUID);
            Integer value = this.criticalKeyValue.get(key);
            Logger.DEBUG.info(getSelf().path().name() + " Aborting, someone answered NO the critical write for " +
                    key + " value " + value);
            this.clearCriticalWrite(msg.queryUUID);
            this.multicastAndCheck(
                    new CriticalWriteResponseMessage(Config.ACResponse.ABORT, msg.queryUUID, msg.hops, null),
                    this.caches, Config.RequestType.CRITWRITE, key, null, -1, true, msg.queryUUID
            );
        }
    }

    /**
     * Clear for critical write
     *
     * @param requestId id of the request
     */
    private void clearCriticalWrite(UUID requestId) {
        Integer oldKey = this.criticalSessionKey.get(requestId);
        // Empty
        this.criticalSessionKey.remove(requestId);
        this.criticalKeyValue.remove(oldKey);
        this.receivedAcksForCritWrite.remove(requestId);
    }

    /**
     * Empty function, this operation is not supported by the Database
     *
     * @param msg on response message
     */
    @Override
    protected void onResponseMessage(ResponseMessage msg) {
    }

    /**
     * Empty function, this operation is not supported by the Database
     *
     * @param msg on timeout message
     */
    @Override
    protected void onTimeoutMessage(TimeoutMessage msg) {
    }

    /**
     * Empty function, this operation is not supported by the Database
     *
     * @param msg recovery message
     */
    @Override
    protected void onRecoveryMessage(RecoveryMessage msg) {
    }

    /**
     * Handler of the messages
     * <p>
     * It handles:
     * {@link JoinCachesMessage join message}
     * {@link TokenMessage token message} for distributed snapshot
     * {@link StartSnapshotMessage start snapshot message} for starting the snapshot, since the only node which
     * is connected with a spanning tree with all the other components
     * {@link ReadMessage join message}
     * {@link WriteMessage join message}
     */
    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(JoinCachesMessage.class, this::onJoinCachesMessage)
                .match(TokenMessage.class, msg -> onToken(msg, this.database, this.seqnoCache, this.caches))
                .match(StartSnapshotMessage.class, msg -> onStartSnapshot(msg, this.database, this.seqnoCache, this.caches))
                .match(CriticalUpdateResponseMessage.class, this::onCriticalUpdateResponseMessage)
                .match(CriticalUpdateTimeoutMessage.class, this::onCriticalUpdateTimeoutMessage)
                .match(ReadMessage.class, this::onReadMessage)
                .match(WriteMessage.class, this::onWriteMessage)
                .build();
    }
}
