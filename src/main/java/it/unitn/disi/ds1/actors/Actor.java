package it.unitn.disi.ds1.actors;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import it.unitn.disi.ds1.Config;
import it.unitn.disi.ds1.Logger;
import it.unitn.disi.ds1.messages.*;

import java.util.concurrent.TimeUnit;
import scala.concurrent.duration.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Actor base class
 * Provides the actor base functionality
 */
public abstract class Actor extends AbstractActor {
    /**
     * Peer id
     */
    public final int id;

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
     * Data in transit
     */
    protected Map<Integer, Integer> dataInTransit = new HashMap<>();

    /**
     * Set which considers the token which has been received
     */
    protected Set<ActorRef> tokensReceived = new HashSet<>();

    /**
     * Snapshot identifier
     */
    protected int snapshotId = 0;

    /**
     * Timer
     */
    protected Cancellable timeoutScheduler;

    /**
     * Constructor of the Actor base class
     * @param id identifier of the peer
     */
    public Actor(int id){
        this.id = id;
        this.timeoutScheduler = null;
    }

    /**
     * Multicast method
     * Just multicast one serializable message to a set of nodes
     * @param msg message
     * @param multicastGroup group to whom send the message
     * @return
     */
    protected void multicast(Message msg, List<ActorRef> multicastGroup) {
        for (ActorRef p: multicastGroup) {
            if (!p.equals(getSelf())) {
                p.tell(msg, getSelf());

                // simulate network delays using sleep
                try { Thread.sleep(Config.RANDOM.nextInt(Config.NETWORK_DELAY_MS)); }
                catch (InterruptedException e) { e.printStackTrace(); }
            }
        }
    }

    /**
     * Send the token to all the peers
     * @param peers List of peers
     */
    private void sendTokens(List<ActorRef> peers) {
        Logger.INSTANCE.finer(getSelf().path().name() + " with id " + this.id +" sending tokens");
        TokenMessage t = new TokenMessage(this.snapshotId);
        this.multicast(t, peers);
    }

    /**
     * Tells whether the snapshot has ended
     * @param peers
     */
    private boolean snapshotEnded(List<ActorRef> peers){
        return this.tokensReceived.containsAll(peers);
    }

    /**
     * Capture the current data within the system
     * @param data either the cache or the database
     */
    private void captureState(Map<Integer, Integer> data) {
        // State set to captured
        this.stateCaptured = true;
        // This means that the snapshot is not stored yet.
        this.currentCache = Collections.unmodifiableMap(data);
        // Add itself to the tokens received
        this.tokensReceived.add(getSelf());
    }

    /**
     * OnToken method
     * Define what the actor does when the token message is received
     * @param token
     */
    protected void onToken(TokenMessage token, Map<Integer, Integer> data, List<ActorRef> peers) {
        // When the token as been received
        this.snapshotId = token.snapId;

        // Manage the token reception, the first one should start the
        // snapshot and the last token needs to stop the algorithm

        // Add the sender to the getSender
        this.tokensReceived.add(getSender());

        if(!this.stateCaptured){
            // If I am not in the snapshot I enter it
            this.captureState(data);
            // I send the tokens
            this.sendTokens(peers);
        }

        if(this.snapshotEnded(peers)){
            // Terminates the snapshot
            Logger.INSTANCE.info(getSelf().path().name() + " with id: " + this.id + " snapshotId: "+ this.snapshotId + " state: " + this.currentCache + " messages in transit: " + this.dataInTransit);
            this.terminateSnapshot();
        }
    }

    /**
     * Method used in order to capture the data in transit
     * @param transit data in transit
     * @param sender sender
     */
    protected void capureTransitMessages(Map<Integer, Integer> transit, ActorRef sender){
        if(this.stateCaptured && !this.tokensReceived.contains(sender)){
            // It means that I am in the snapshot, and I am recording not that channel
            this.dataInTransit.putAll(transit);
        }
    }

    abstract protected void onReadMessage(ReadMessage msg);

    abstract protected void onWriteMessage(WriteMessage msg);

    abstract protected void onCriticalReadMessage(CriticalReadMessage msg);

    abstract protected void onCriticalWriteMessage(CriticalWriteMessage msg);

    abstract protected void onTimeoutMessage(TimeoutMessage msg);

    abstract protected void onJoinCachesMessage(JoinCachesMessage msg);

    abstract protected void onResponseMessage(ResponseMessage msg);

    /**
     * Schedule a message after a fixed timer
     * @param msg message to schedule
     * @param timeoutMillis time to wait in milliseconds
     */
    protected void scheduleTimer(Message msg, int timeoutMillis){
        Logger.INSTANCE.info(getSelf().path().name() + " is scheduling a timeout of " + timeoutMillis);
        this.timeoutScheduler = getContext().system().scheduler().scheduleOnce(
                Duration.create(timeoutMillis, TimeUnit.MILLISECONDS),        // how frequently generate them
                getSelf(),                                                    // destination actor reference
                msg,                                                          // Timeout message
                getContext().system().dispatcher(),                           // system dispatcher
                getSelf()                                                     // source of the message (myself)
        );
    }

    /**
     * Cancel the timeout timer
     */
    protected void cancelTimer(){
        Logger.INSTANCE.info(getSelf().path().name() + " is cancelling a timeout");
        if(this.timeoutScheduler != null){
            this.timeoutScheduler.cancel();
        }
    }

    /**
     * OnStartShapshot
     * Starts a snapshot
     * @param msg start snapshot message
     */
    protected void onStartSnapshot(StartSnapshotMessage msg, Map<Integer, Integer> data, List<ActorRef> peers) {
        // we've been asked to initiate a snapshot
        this.snapshotId += 1;
        Logger.INSTANCE.info(getSelf().path().name() + " with id: " + this.id + " snapshotId: " + this.snapshotId + " starting a snapshot");
        this.captureState(data);
        this.sendTokens(peers);
    }

    /**
     * Terminates the snapshot
     */
    private void terminateSnapshot(){
        this.stateCaptured = false;
        this.currentCache = new HashMap<>();
        this.dataInTransit = new HashMap<>();
        this.tokensReceived.clear();
    }

    /**
     * On Recovery method
     * @param msg recovery message
     */
    protected abstract void onRecoveryMessage(RecoveryMessage msg);

    /**
     * Crashed behavior
     * @return builder
     */
    public Receive crashed() {
        return receiveBuilder()
                .match(RecoveryMessage.class, this::onRecoveryMessage)
                .matchAny(msg -> {})
                .build();
    }
}
