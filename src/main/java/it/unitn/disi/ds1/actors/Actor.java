package it.unitn.disi.ds1.actors;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import it.unitn.disi.ds1.Config;
import it.unitn.disi.ds1.Logger;
import it.unitn.disi.ds1.messages.JoinCachesMessage;
import it.unitn.disi.ds1.messages.Message;
import it.unitn.disi.ds1.messages.ReadMessage;
import it.unitn.disi.ds1.messages.RecoveryMessage;
import it.unitn.disi.ds1.messages.ResponseMessage;
import it.unitn.disi.ds1.messages.StartSnapshotMessage;
import it.unitn.disi.ds1.messages.TimeoutMessage;
import it.unitn.disi.ds1.messages.TokenMessage;
import it.unitn.disi.ds1.messages.WriteMessage;
import scala.concurrent.duration.Duration;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Actor base class, extends {@link AbstractActor actor}
 * Provides the actor base functionalities
 */
public abstract class Actor extends AbstractActor {
    /**
     * Peer id
     */
    public final int id;
    /**
     * Sequence number for monotonic reads
     */
    protected final Map<Integer, Integer> seqnoCache;
    /**
     * Whether the node is in the snapshot yet, namely if the
     * state has been captured.
     * If this variable is true, it means that the snapshot is in progress
     */
    protected boolean stateCaptured = false;
    /**
     * Current cache/database
     */
    protected Map<Integer, Integer> currentCache = new HashMap<>();
    /**
     * Current sequence numbers
     */
    protected Map<Integer, Integer> currentSeqNo = new HashMap<>();
    /**
     * Data in transit for the distributed snapshot
     */
    protected Map<Integer, Integer> dataInTransit = new HashMap<>();
    /**
     * Sequence numbers in transit for the distributed snapshot
     */
    protected Map<Integer, Integer> seqNoInTransit = new HashMap<>();
    /**
     * Set which considers the token which has been received
     */
    protected Set<ActorRef> tokensReceived = new HashSet<>();
    /**
     * Snapshot identifier
     */
    protected int snapshotId = 0;
    /**
     * Timer associated to each request
     */
    protected Map<UUID, Cancellable> timeoutScheduler;

    /**
     * Constructor of the Actor base class
     *
     * @param id identifier of the peer
     */
    public Actor(int id) {
        this.id = id;
        this.timeoutScheduler = new HashMap<>();
        this.seqnoCache = new HashMap<>();
    }

    /**
     * Multicast method logging the event for future consistency checks
     * Just multicast one serializable message to a set of nodes
     * The messages sent in multicast simulate a network delay which is configurable in the Config file
     *
     * @param msg            message to be sent
     * @param multicastGroup group to whom send the message
     * @param key            key linked with the given message
     * @param value          value linked with the given message
     * @param seqno          sequence number linked with the given message
     * @param isCritical     is the message critical?
     * @param requestType    request type
     * @param queryID        query identifier UUID
     */
    protected void multicastAndCheck(
            Message msg,
            List<ActorRef> multicastGroup,
            Config.RequestType requestType,
            Integer key,
            Integer value,
            Integer seqno,
            boolean isCritical,
            UUID queryID
    ) {
        for (ActorRef p : multicastGroup) {
            if (!p.equals(getSelf())) {
                Logger.logCheck(Level.FINE, this.id, this.getIdFromName(p.path().name()), requestType, true,
                        key, value, seqno, "Multicast for key [CRIT: " + isCritical + "]", queryID);

                p.tell(msg, getSelf());

                // simulate network delays using sleep
                try {
                    Thread.sleep(Config.RANDOM.nextInt(Config.NETWORK_DELAY_MS));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Basic Multicast method
     * Just multicast one serializable message to a set of nodes
     * The messages sent in multicast simulate a network delay which is configurable in the Config file
     *
     * @param msg            message to be sent
     * @param multicastGroup group to whom send the message
     */
    protected void multicast(Message msg, List<ActorRef> multicastGroup) {
        for (ActorRef p : multicastGroup) {
            if (!p.equals(getSelf())) {
                p.tell(msg, getSelf());

                // simulate network delays using sleep
                try {
                    Thread.sleep(Config.RANDOM.nextInt(Config.NETWORK_DELAY_MS));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Send the token to all the peers
     *
     * @param peers List of peers
     */
    private void sendTokens(List<ActorRef> peers) {
        Logger.DEBUG.finer(getSelf().path().name() + " with id " + this.id + " sending tokens");
        TokenMessage t = new TokenMessage(this.snapshotId);
        this.multicast(t, peers);
    }

    /**
     * Tells whether the snapshot has ended
     *
     * @param peers
     */
    private boolean snapshotEnded(List<ActorRef> peers) {
        return this.tokensReceived.containsAll(peers);
    }

    /**
     * Capture the current data within the system
     *
     * @param data  either the cache or the database
     * @param seqno sequence number cache
     */
    private void captureState(Map<Integer, Integer> data, Map<Integer, Integer> seqno) {
        // State set to captured
        this.stateCaptured = true;
        // This means that the snapshot is not stored yet.
        this.currentCache = Collections.unmodifiableMap(data);
        // Caputure the sequence numbers
        this.currentSeqNo = Collections.unmodifiableMap(seqno);
        // Add itself to the tokens received
        this.tokensReceived.add(getSelf());
    }

    /**
     * Define what the actor does when the token message is received
     *
     * @param token token message
     * @param data  data to be stored
     * @param seqno sequence numbers
     * @param peers list of peers to whom send the message
     */
    protected void onToken(
            TokenMessage token,
            Map<Integer, Integer> data,
            Map<Integer, Integer> seqno,
            List<ActorRef> peers
    ) {
        // When the token as been received
        this.snapshotId = token.snapId;

        // Manage the token reception, the first one should start the
        // snapshot and the last token needs to stop the algorithm

        // Add the sender to the getSender
        this.tokensReceived.add(getSender());

        if (!this.stateCaptured) {
            // If I am not in the snapshot I enter it
            this.captureState(data, seqno);
            // I send the tokens
            this.sendTokens(peers);
        }

        if (this.snapshotEnded(peers)) {
            // Terminates the snapshot
            Logger.DEBUG.info(getSelf().path().name() + " with id: " + this.id + " snapshotId: " +
                    this.snapshotId + " state: " + this.currentCache + " sequence numbers: " + this.currentSeqNo +
                    " messages in transit: " + this.dataInTransit + " sequence numbers in transit:" +
                    this.seqNoInTransit);
            this.terminateSnapshot();
        }
    }

    /**
     * Method used in order to capture the data in transit
     *
     * @param transit      data in transit
     * @param seqnoTransit data in transit
     * @param sender       sender
     */
    protected void capureTransitMessages(
            Map<Integer, Integer> transit,
            Map<Integer, Integer> seqnoTransit,
            ActorRef sender
    ) {
        if (this.stateCaptured && !this.tokensReceived.contains(sender)) {
            // It means that I am in the snapshot, and I am recording not that channel
            this.dataInTransit.putAll(transit);
            // Put also the sequence numbers
            this.seqNoInTransit.putAll(seqnoTransit);
        }
    }

    /**
     * on read message handler
     *
     * @param msg read message
     */
    abstract protected void onReadMessage(ReadMessage msg);

    /**
     * on write message
     *
     * @param msg write message
     */
    abstract protected void onWriteMessage(WriteMessage msg);

    /**
     * on timeout message
     *
     * @param msg timeout message
     */
    abstract protected void onTimeoutMessage(TimeoutMessage msg);

    /**
     * on join caches
     *
     * @param msg join cache message
     */
    abstract protected void onJoinCachesMessage(JoinCachesMessage msg);

    /**
     * on response message
     *
     * @param msg on response message
     */
    abstract protected void onResponseMessage(ResponseMessage msg);

    /**
     * Schedule a message after a fixed timer
     *
     * @param msg           message to schedule
     * @param timeoutMillis time to wait in milliseconds
     * @param timerRequest  request associated with that timer
     */
    protected void scheduleTimer(Message msg, int timeoutMillis, UUID timerRequest) {
        Logger.DEBUG.info(getSelf().path().name() + " is scheduling a cancellable timeout of " + timeoutMillis);
        this.timeoutScheduler.put(timerRequest,                               // timer associated with the request UUID
                // how frequently generate them
                getContext().system().scheduler().scheduleOnce(Duration.create(timeoutMillis, TimeUnit.MILLISECONDS),
                        getSelf(),                                                    // destination actor reference
                        msg,                                                          // Timeout message
                        getContext().system().dispatcher(),                           // system dispatcher
                        getSelf()                                                     // source of the message (myself)
                ));
    }

    /**
     * Schedule a message after a fixed timer which cannot be cancelled
     *
     * @param msg           message to schedule
     * @param timeoutMillis time to wait in milliseconds
     */
    protected void scheduleDetatchedTimer(Message msg, int timeoutMillis) {
        Logger.DEBUG.info(getSelf().path().name() + " is scheduling a NON cancellable timeout of " + timeoutMillis);
        // how frequently generate them
        getContext().system().scheduler().scheduleOnce(Duration.create(timeoutMillis, TimeUnit.MILLISECONDS),
                getSelf(),                                                    // destination actor reference
                msg,                                                          // Timeout message
                getContext().system().dispatcher(),                           // system dispatcher
                getSelf()                                                     // source of the message (myself)
        );
    }

    /**
     * Cancel the timeout {@link Cancellable timer}
     *
     * @param timerRequest request associated with that timer
     */
    protected void cancelTimer(UUID timerRequest) {
        Logger.DEBUG.info(getSelf().path().name() + " is cancelling a timeout");

        // Cancel the timer
        Cancellable timer = this.timeoutScheduler.get(timerRequest);
        if (timer != null) timer.cancel();

        // Remove the timer from the HashMap
        this.timeoutScheduler.remove(timerRequest);
    }

    /**
     * Starts a snapshot
     *
     * @param msg   start snapshot message
     * @param data  data to be stored
     * @param seqno sequence numbers
     * @param peers to whom send the message
     */
    protected void onStartSnapshot(
            StartSnapshotMessage msg,
            Map<Integer, Integer> data,
            Map<Integer, Integer> seqno,
            List<ActorRef> peers
    ) {
        // we've been asked to initiate a snapshot
        this.snapshotId += 1;
        Logger.DEBUG.info(getSelf().path().name() + " with id: " + this.id + " snapshotId: " +
                this.snapshotId + " starting a snapshot");
        this.captureState(data, seqno);
        this.sendTokens(peers);
    }

    /**
     * Terminates the snapshot
     */
    private void terminateSnapshot() {
        this.stateCaptured = false;
        this.currentCache = new HashMap<>();
        this.currentSeqNo = new HashMap<>();
        this.dataInTransit = new HashMap<>();
        this.seqNoInTransit = new HashMap<>();
        this.tokensReceived.clear();
    }

    /**
     * On Recovery method
     *
     * @param msg recovery message
     */
    protected abstract void onRecoveryMessage(RecoveryMessage msg);

    /**
     * Method used in order to simulate network delays
     */
    protected void delay() {
        // simulate network delays using sleep
        try {
            Thread.sleep(Config.RANDOM.nextInt(Config.NETWORK_DELAY_MS));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Crashed behavior
     *
     * @return {@link akka.actor.AbstractActor.Receive receive builder}
     */
    public Receive crashed() {
        return receiveBuilder().match(RecoveryMessage.class, this::onRecoveryMessage).matchAny(msg -> {
        }).build();
    }

    /**
     * Get Id from Name
     *
     * @param name name of the actor
     * @return Integer
     */
    public Integer getIdFromName(String name) {
        return Integer.valueOf(name.substring(name.lastIndexOf("-") + 1));
    }
}
