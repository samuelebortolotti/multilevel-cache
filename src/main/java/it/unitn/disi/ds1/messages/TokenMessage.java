package it.unitn.disi.ds1.messages;

import java.io.Serializable;

/**
 * TokenMessage class
 */
public class TokenMessage implements Serializable {
    /**
     * Snapshot identifier
     */
    public final int snapId;

    /**
     * Token Message constructor
     * @param snapId snapshot identifier
     */
    public TokenMessage(int snapId){ this.snapId = snapId; }
}
