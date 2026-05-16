package com.newspaper.chap;

public class ChapiemSession {

    enum State {
        UNAUTHENTICATED,
        LOGIN_PHASE,
        ACTIVE,
        RECOVERY
    }

    private final byte[] preSharedKey;
    private byte[] currentId;
    private State state;
    private final String expectedUsername;

    public ChapiemSession(byte[] preSharedKey, String expectedUsername) {
        this.preSharedKey = preSharedKey;
        this.expectedUsername = expectedUsername;
        this.state = State.UNAUTHENTICATED;
        this.currentId = null;
    }

    public byte[] getPreSharedKey() {
        return preSharedKey;
    }

    public byte[] getCurrentId() {
        return currentId;
    }

    public State getState() {
        return state;
    }

    public String getExpectedUsername() {
        return expectedUsername;
    }

    public byte[] getCurrentKey() {
        if (state == State.ACTIVE || state == State.RECOVERY) {
            return currentId;
        }
        return preSharedKey;
    }

    public boolean isAuthenticated() {
        return state == State.ACTIVE || state == State.RECOVERY;
    }

    public void completeLogin(byte[] newId) {
        this.currentId = newId;
        this.state = State.ACTIVE;
    }

    public void updateId(byte[] newId) {
        this.currentId = newId;
    }

    public void enterRecovery() {
        this.state = State.RECOVERY;
    }

    public void exitRecovery() {
        this.state = State.ACTIVE;
    }

    public void reset() {
        this.state = State.UNAUTHENTICATED;
        this.currentId = null;
    }
}